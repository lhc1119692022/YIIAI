package com.yiiai.app.live

import android.content.Context
import android.util.Log
import com.yiiai.app.audio.AudioRouting
import com.yiiai.app.audio.MicStreamer
import com.yiiai.app.audio.VoicePlayer
import com.yiiai.app.settings.AppSettings
import com.yiiai.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * 一路 MOSS-Live 实时通话：WebSocket、会话配置、麦克风上行、语音下行、字幕与回执。
 *
 * 线程模型：所有状态变更都在一个 actor 协程里串行处理（[inbox]）。
 * 麦克风帧与播放回执是高频路径，直接通过 [liveSocket] 发送（OkHttp 的 send 线程安全）。
 *
 * M1 阶段由 Activity 驱动；M2 会把它挂到前台服务上，实现后台保活。
 */
class LiveSession(context: Context, private val settings: SettingsStore) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        CallState(
            speakerOn = settings.current.speakerOn,
            route = AudioRouting.idleLabel(settings.current.speakerOn),
        ),
    )
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** 工具执行器：截图 / 点击 / 输入，跑在独立协程里，不阻塞 actor。 */
    private val toolRunner = com.yiiai.app.phone.PhoneToolRunner(
        context = appContext,
        sendScreenFrame = { bytes ->
            // tagged-v1：屏幕帧前缀 0x02，直接走实时连接。
            val frame = ByteArray(bytes.size + 1)
            frame[0] = LiveProtocol.TAG_SCREEN
            System.arraycopy(bytes, 0, frame, 1, bytes.size)
            liveSocket?.send(frame.toByteString()) ?: false
        },
        sendEvent = { json ->
            val ws = liveSocket
            if (ws == null) false else ws.send(json)
        },
        startWatching = { seconds -> watchScreen(seconds) },
        isWatching = { screenStreamer.running },
    )

    /**
     * 屏幕实时画面。模型主要靠它「看见」用户在做什么，控件树只用来精确点击。
     * 由工具 look_at_screen / watch_screen 开启，闲置一段时间自动关闭。
     */
    internal val screenStreamer = com.yiiai.app.phone.ScreenStreamer(
        context = appContext,
        sendEvent = { json -> liveSocket?.send(json) ?: false },
        sendFrame = { bytes ->
            val frame = ByteArray(bytes.size + 1)
            frame[0] = LiveProtocol.TAG_SCREEN
            System.arraycopy(bytes, 0, frame, 1, bytes.size)
            liveSocket?.send(frame.toByteString()) ?: false
        },
        onLog = { log(it) },
    )

    /** 屏幕画面闲置多久后自动关掉。 */
    private var watchUntil = 0L
    private var watchTimer: kotlinx.coroutines.Job? = null

    /** 已执行过的 call_id，避免重复调用同一工具。只留最近一批，长通话也不会一直涨。 */
    private val handledCalls = LinkedHashSet<String>()

    /** 已回传结果、等待 conversation.item.created 确认的 call_id。 */
    private val awaitingItemCreated = HashSet<String>()

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val routing = AudioRouting(appContext)
    private val player = VoicePlayer(PlayerCallbacks())

    // ---- 以下字段只在 actor 协程中读写
    private var generation = 0
    private var socket: WebSocket? = null
    private var mic: MicStreamer? = null
    private var step = SetupStep.None
    private var call: AppSettings? = null
    private var closingNote: String? = null
    private var pendingAudio: AudioContext? = null
    private var lastRun: Pair<String, String>? = null
    private var userDraftId: String? = null
    // 这两个表把 turn/run 映射到消息 id，长通话里会一直涨，超过上限就丢掉最早的。
    private val userMsgByTurn = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_MESSAGES
    }
    private val aiMsgByRun = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_MESSAGES
    }
    private var seq = 0L
    private var audioFrames = 0
    private var audioBytes = 0

    /** 已建立会话的 ID，断线后用它恢复，保住对话上下文。 */
    private var resumeSessionId: String? = null
    /** 连续重连次数，成功进入 Live 后清零。 */
    private var reconnectAttempt = 0
    /** 用户主动挂断，避免把正常结束当成断线去重连。 */
    private var userHangup = false
    /** 本次连接是否为恢复会话（恢复跳过多余的重复配置）。 */
    private var resuming = false

    /** 会话就绪后才非空；麦克风线程和播放线程直接用它发送。 */
    @Volatile private var liveSocket: WebSocket? = null
    private val droppedFrames = AtomicLong()

    init {
        scope.launch {
            for (msg in inbox) {
                try {
                    handle(msg)
                } catch (t: Throwable) {
                    Log.e(TAG, "handle failed: $msg", t)
                    log("内部错误：${t.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 对外接口

    fun start() { inbox.trySend(Msg.Start) }
    fun hangup() { inbox.trySend(Msg.Hangup) }
    fun toggleMute() { inbox.trySend(Msg.ToggleMute) }
    fun toggleSpeaker() { inbox.trySend(Msg.ToggleSpeaker) }
    /** 存储页用：清空对话记录。 */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    /** 存储页用：清空运行日志。 */
    fun clearLog() {
        _log.value = emptyList()
    }

    /** 界面用：手动开关屏幕画面。 */
    fun setWatching(on: Boolean) {
        if (on) watchScreen(300) else stopWatching()
    }

    val isWatching: Boolean get() = screenStreamer.running

    fun dismissError() { _state.update { it.copy(error = null, phase = if (it.phase == CallPhase.Failed) CallPhase.Idle else it.phase) } }

    // ---------------------------------------------------------------- actor

    private fun handle(msg: Msg) {
        when (msg) {
            Msg.Start -> startCall()
            Msg.Hangup -> {
                userHangup = true
                endCall(null)
            }
            Msg.ToggleMute -> toggleMuteInternal()
            Msg.ToggleSpeaker -> toggleSpeakerInternal()
            is Msg.Opened -> if (msg.gen == generation) onOpened()
            is Msg.Text -> if (msg.gen == generation) onText(msg.text)
            is Msg.Binary -> if (msg.gen == generation) onBinary(msg.bytes)
            is Msg.Closed -> if (msg.gen == generation) onClosed(msg.code, msg.reason)
            is Msg.Failure -> if (msg.gen == generation) onSocketFailure(msg.message)
            is Msg.MicError -> if (msg.gen == generation) fail(msg.message)
            is Msg.Reconnect -> if (msg.gen == generation && socket == null && !userHangup) attemptReconnect()
            is Msg.ToolDone -> if (msg.gen == generation) send(msg.payload)
            is Msg.SetupTimeout -> if (msg.gen == generation && socket != null && step != SetupStep.Done) {
                fail("会话准备超时（卡在「${step.label}」），请检查网络后重试")
            }
        }
    }

    private fun startCall() {
        if (socket != null) return
        val s = settings.current
        if (s.apiKey.isBlank()) {
            _state.update { it.copy(phase = CallPhase.Failed, error = "请先在设置里填写 MOSS API Key") }
            return
        }
        userHangup = false
        reconnectAttempt = 0
        resumeSessionId = null
        resuming = false
        beginConnect(freshSession = true)
    }

    /** 建立连接。freshSession=true 表示新通话，false 表示恢复既有会话。 */
    private fun beginConnect(freshSession: Boolean) {
        val s = settings.current
        if (s.apiKey.isBlank()) {
            _state.update { it.copy(phase = CallPhase.Failed, error = "请先在设置里填写 MOSS API Key") }
            return
        }
        generation++
        val gen = generation
        call = s
        step = SetupStep.Attach
        closingNote = null
        pendingAudio = null
        lastRun = null
        userDraftId = null
        resuming = !freshSession && resumeSessionId != null
        if (freshSession) {
            userMsgByTurn.clear()
            aiMsgByRun.clear()
            droppedFrames.set(0)
            handledCalls.clear()
            awaitingItemCreated.clear()
            audioFrames = 0
            audioBytes = 0
            if (_messages.value.isNotEmpty()) {
                addMessage(ChatMessage(nextId("s"), Role.System, "新的通话 · ${clock(short = true)}", final = true))
            }
        }
        _state.update {
            it.copy(
                phase = if (resuming) CallPhase.Reconnecting else CallPhase.Connecting,
                error = null,
                notice = null,
                muted = false,
                userSpeaking = false,
                assistantSpeaking = false,
                sessionId = if (resuming) resumeSessionId else null,
            )
        }
        // 恢复会话时音频链路已经在跑，不要重开路由与播放器。
        if (freshSession) {
            val route = routing.start(s.speakerOn)
            _state.update { it.copy(route = route) }
            player.start(s.outputSampleRate)
            log(
                "连接 ${LiveProtocol.ENDPOINT}（model=${LiveProtocol.MODEL}，记忆 user=${s.memoryUser().takeLast(8)}）",
            )
        } else {
            log("重连中（第 $reconnectAttempt 次），恢复会话 ${resumeSessionId?.take(8)}")
        }
        val request = Request.Builder()
            .url(LiveProtocol.url(s.memoryUser()))
            .header("Authorization", "Bearer ${s.apiKey.trim()}")
            .build()
        socket = http.newWebSocket(request, SocketListener(gen))
        scope.launch {
            delay(SETUP_TIMEOUT_MS)
            inbox.send(Msg.SetupTimeout(gen))
        }
    }

    /** 断线后的自动重连：指数退避，超过上限才真正失败。 */
    private fun scheduleReconnect(reason: String) {
        if (userHangup) return
        val s = call ?: settings.current
        if (s.apiKey.isBlank()) return
        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT) {
            fail("$reason，已重试 $MAX_RECONNECT 次仍未恢复")
            return
        }
        val delayMs = (BASE_RECONNECT_DELAY_MS shl (reconnectAttempt - 1).coerceAtMost(4))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        _state.update {
            it.copy(phase = CallPhase.Reconnecting, assistantSpeaking = false, userSpeaking = false)
        }
        log("⚠ $reason，${delayMs}ms 后重连（第 $reconnectAttempt/$MAX_RECONNECT 次）")
        val gen = generation
        scope.launch {
            delay(delayMs)
            inbox.send(Msg.Reconnect(gen))
        }
    }

    private fun attemptReconnect() {
        if (socket != null || userHangup) return
        // 上一轮的 socket / 麦克风 / 播放器要清掉，但保留会话 ID、字幕和音频路由。
        liveSocket = null
        mic?.stop()
        mic = null
        player.stop()
        beginConnect(freshSession = false)
    }

    private fun onOpened() {
        _state.update { it.copy(phase = CallPhase.Configuring) }
        // 恢复会话时带原 session_id，服务端据此接回上下文；新通话传 null。
        send(LiveProtocol.attachSession(sessionId = if (resuming) resumeSessionId else null, memoryUser = true))
    }

    private fun onText(text: String) {
        val ev = ServerEvent.parse(text)
        if (ev == null) {
            log("← 无法解析：${text.take(120)}")
            return
        }
        if (ev.action !in QUIET_EVENTS) log("← ${ev.action} ${ev.preview()}")
        when (ev.action) {
            "session_attached" -> onAttached(ev)
            "voice_changed" -> if (step == SetupStep.Voice) configurePersona()
            "session.updated" -> onSessionUpdated(ev)
            "media.configured" -> onMediaConfigured(ev)

            "input.speech.started" -> _state.update { it.copy(userSpeaking = true) }
            "input.speech.stopped" -> _state.update { it.copy(userSpeaking = false) }
            "input.transcript.updated" -> userTranscript(ev.str("text").orEmpty(), null, final = false)
            "input.transcript.done" -> userTranscript(ev.str("text").orEmpty(), ev.str("turn"), final = true)
            "input.transcript.revised" -> reviseUser(ev.str("turn"), ev.str("text").orEmpty())
            "output.transcript.updated" -> aiTranscript(ev.str("run_id"), ev.str("text").orEmpty(), final = false)
            "output.transcript.done" -> aiTranscript(ev.str("run_id"), ev.str("text").orEmpty(), final = true)

            "response.audio.started" -> ev.runTurn()?.let { (run, turn) ->
                lastRun = run to turn
                player.openRun(run, turn)
            }
            "response.audio.context" -> pendingAudio = ev.runTurn()?.let { (run, turn) ->
                lastRun = run to turn
                AudioContext(run, turn, ev.str("chunk_id"))
            }
            "response.audio.done" -> ev.runTurn()?.let { (run, turn) -> player.markRunDone(run, turn) }
            "response.audio.stop" -> ev.runTurn()?.let { (run, turn) -> stopPlayback(run, turn) }
            "response.interrupted" -> onInterrupted(ev)
            "response.ended" -> onResponseEnded(ev)

            "response.function_call_arguments.done" -> onFunctionCall(ev)
            // 协议要求：item.create → 收到 item.created → 再发 response.create。
            "conversation.item.created" -> ev.obj("item")?.str("call_id")?.let { callId ->
                if (awaitingItemCreated.remove(callId)) send(LiveProtocol.responseCreate(callId))
            }
            "session.closing" -> closingNote = ev.str("reason")
            "error" -> onServerError(ev)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 会话配置链

    private fun onAttached(ev: ServerEvent) {
        if (step != SetupStep.Attach) return
        val sid = ev.str("session_id")
        _state.update { it.copy(sessionId = sid) }
        if (sid != null) resumeSessionId = sid
        // 恢复会话：音色/人设由服务端会话保存，不必重复下发；但媒体配置重连后必须重发。
        if (resuming) {
            log("会话已恢复 ${sid?.take(8)}，重新配置音频")
            configureMedia()
            return
        }
        val voice = call?.voiceId?.trim().orEmpty()
        if (voice.isNotEmpty()) {
            step = SetupStep.Voice
            send(LiveProtocol.changeVoice(voice))
        } else {
            configurePersona()
        }
    }

    /**
     * 工具调用：执行完回传结果，再请求续答。
     * 整个流程在独立协程里跑（截图和滑动都有等待），结果通过 inbox 回到 actor 串行发送。
     */
    private fun onFunctionCall(ev: ServerEvent) {
        val callId = ev.str("call_id") ?: return
        if (!handledCalls.add(callId)) return
        if (handledCalls.size > MAX_HANDLED_CALLS) {
            handledCalls.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }
        val name = ev.str("name").orEmpty()
        val raw = ev.str("arguments").orEmpty()
        val gen = generation
        log("⚙ 工具 $name(${raw.take(120)})")
        scope.launch {
            val args = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val result = runCatching { toolRunner.run(name, args) }
                .getOrElse { "工具执行出错：${it.message}" }
            log("⚙ $name → ${result.take(160)}")
            awaitingItemCreated.add(callId)
            inbox.send(Msg.ToolDone(gen, LiveProtocol.functionOutput(callId, result)))
            // 万一服务端没回 conversation.item.created，也不能让这一轮卡住。
            kotlinx.coroutines.delay(1500)
            if (awaitingItemCreated.remove(callId)) {
                log("⚠ 没等到 item.created，直接请求续答")
                inbox.send(Msg.ToolDone(gen, LiveProtocol.responseCreate(callId)))
            }
        }
    }

    private fun configurePersona() {
        val s = call ?: return
        step = SetupStep.Persona
        send(LiveProtocol.sessionUpdate(s.personaName, s.personaPrompt, s.instructions))
    }

    private fun onSessionUpdated(ev: ServerEvent) {
        if (step != SetupStep.Persona) return
        val err = ev.str("error").orEmpty()
        if (err.isNotEmpty()) fail("人设配置未生效：$err") else configureMedia()
    }

    private fun configureMedia() {
        val s = call ?: return
        step = SetupStep.Media
        send(LiveProtocol.mediaConfigure(s.outputSampleRate))
    }

    private fun onMediaConfigured(ev: ServerEvent) {
        if (step != SetupStep.Media) return
        val err = ev.str("error").orEmpty()
        if (err.isNotEmpty()) log("音频格式设置未生效，沿用服务端当前设置：$err")
        val out = ev.obj("output_audio")
        val format = out?.str("format") ?: "pcm16"
        val rate = out?.int("sample_rate") ?: 48_000
        if (format != "pcm16") {
            fail("服务端下发了暂不支持的音频格式：$format")
            return
        }
        player.setSampleRate(rate)
        goLive(rate)
    }

    private fun goLive(outputRate: Int) {
        val gen = generation
        step = SetupStep.Done
        send(LiveProtocol.clientContext(ZoneId.systemDefault().id))
        send(LiveProtocol.mediaMode())
        send(LiveProtocol.micState(true))

        val streamer = MicStreamer(
            onFrame = ::sendAudioFrame,
            onLevel = { lv -> _levels.update { it.copy(user = lv) } },
            onError = { e -> inbox.trySend(Msg.MicError(gen, e)) },
            onDiag = { d -> log(d) },
        )
        val err = streamer.start()
        if (err != null) {
            fail(err)
            return
        }
        mic = streamer
        liveSocket = socket
        val recovered = resuming
        reconnectAttempt = 0
        resuming = false
        log(
            (if (recovered) "会话已恢复；" else "") +
                "就绪：上行 16 kHz PCM，下行 ${outputRate / 1000} kHz PCM；系统回声消除" +
                (if (streamer.echoCancelerActive) "已启用" else "不可用（外放时可能自我打断，建议戴耳机）") +
                "，降噪" + (if (streamer.noiseSuppressorActive) "已启用" else "不可用"),
        )
        _state.update {
            it.copy(
                phase = CallPhase.Live,
                notice = if (recovered) "网络中断过一次，已接回原来的对话" else it.notice,
            )
        }
    }

    /**
     * 开启/续期屏幕画面。[seconds] 秒内没有新的请求就自动关闭，避免一直推流。
     */
    internal fun watchScreen(seconds: Int = 120) {
        if (step != SetupStep.Done) return
        watchUntil = System.currentTimeMillis() + seconds * 1000L
        if (!screenStreamer.running) {
            screenStreamer.start(scope)
        }
        watchTimer?.cancel()
        watchTimer = scope.launch {
            while (System.currentTimeMillis() < watchUntil) {
                kotlinx.coroutines.delay(1000)
            }
            if (screenStreamer.running) {
                screenStreamer.stop("屏幕画面临时关闭（${seconds} 秒没有新的请求）")
            }
        }
    }

    internal fun stopWatching() {
        watchTimer?.cancel()
        watchTimer = null
        screenStreamer.stop()
    }

    // ---------------------------------------------------------------- 音频

    /** 麦克风线程调用。 */
    private fun sendAudioFrame(frame: ByteArray) {
        val ws = liveSocket ?: return
        if (ws.queueSize() > MAX_SEND_QUEUE_BYTES) {
            droppedFrames.incrementAndGet()
            return
        }
        ws.send(frame.toByteString())
    }

    private fun onBinary(bytes: ByteArray) {
        val ctx = pendingAudio
        pendingAudio = null
        audioFrames++
        audioBytes += bytes.size
        if (ctx != null) {
            player.enqueue(ctx.runId, ctx.turnId, ctx.chunkId, bytes)
        } else {
            // 没有配对的 context（理论上 PCM 模式不会出现）：归到最近一次回复，不发回执。
            log("⚠ 收到未配对的音频帧 ${bytes.size}B")
            lastRun?.let { (run, turn) -> player.enqueue(run, turn, null, bytes) }
        }
    }

    private fun stopPlayback(run: String, turn: String) {
        val playedMs = player.stopRun(run) ?: return
        send(LiveProtocol.playbackStopped(run, turn, playedMs))
    }

    private fun onInterrupted(ev: ServerEvent) {
        val run = ev.str("run_id")
        val text = ev.str("text")
        if (run == null) {
            player.openRuns().forEach { (r, t) -> stopPlayback(r, t) }
            return
        }
        aiMsgByRun[run]?.let { id ->
            updateMessage(id) { m ->
                m.copy(text = text?.takeIf { it.isNotBlank() } ?: m.text, final = true, interrupted = true)
            }
        }
        stopPlayback(run, ev.str("turn_id").orEmpty())
    }

    private fun onResponseEnded(ev: ServerEvent) {
        val run = ev.str("run_id") ?: return
        val id = aiMsgByRun[run] ?: return
        val status = ev.str("status")
        val msg = _messages.value.firstOrNull { it.id == id } ?: return
        if (status != "completed" && msg.text.isBlank()) removeMessage(id) else updateMessage(id) { it.copy(final = true) }
    }

    // ---------------------------------------------------------------- 字幕

    private fun userTranscript(text: String, turn: String?, final: Boolean) {
        var id = userDraftId
        if (id == null) {
            if (text.isBlank()) return
            id = nextId("u")
            addMessage(ChatMessage(id, Role.User, text, final = final))
        } else {
            updateMessage(id) { it.copy(text = text, final = final) }
        }
        if (final) {
            userDraftId = null
            if (turn != null) userMsgByTurn[turn] = id
            if (text.isBlank()) removeMessage(id)
        } else {
            userDraftId = id
        }
    }

    private fun reviseUser(turn: String?, text: String) {
        val id = turn?.let { userMsgByTurn[it] } ?: return
        updateMessage(id) { it.copy(text = text) }
    }

    private fun aiTranscript(run: String?, text: String, final: Boolean) {
        val key = run ?: lastRun?.first ?: "unknown"
        val existing = aiMsgByRun[key]
        if (existing == null) {
            if (text.isBlank()) return
            val id = nextId("a")
            aiMsgByRun[key] = id
            addMessage(ChatMessage(id, Role.Assistant, text, final = final))
        } else {
            updateMessage(existing) { if (it.interrupted) it else it.copy(text = text, final = final || it.final) }
        }
    }

    private fun nextId(prefix: String) = prefix + (++seq)
    private fun addMessage(m: ChatMessage) =
        // 只留最近 MAX_MESSAGES 条：长时间聊天不会一直涨内存。
        _messages.update { (it + m).takeLast(MAX_MESSAGES) }
    private fun updateMessage(id: String, f: (ChatMessage) -> ChatMessage) =
        _messages.update { list -> list.map { if (it.id == id) f(it) else it } }
    private fun removeMessage(id: String) = _messages.update { list -> list.filterNot { it.id == id } }

    // ---------------------------------------------------------------- 控制

    private fun toggleMuteInternal() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        mic?.muted = muted
        if (step == SetupStep.Done) send(LiveProtocol.micState(!muted))
    }

    private fun toggleSpeakerInternal() {
        val on = !_state.value.speakerOn
        settings.update { it.copy(speakerOn = on) }
        val route = routing.setSpeaker(on)
        _state.update { it.copy(speakerOn = on, route = route) }
    }

    // ---------------------------------------------------------------- 结束与错误

    private fun onServerError(ev: ServerEvent) {
        val err = ev.obj("error")
        val code = err?.str("code") ?: "unknown"
        val message = err?.str("message").orEmpty()
        if (step != SetupStep.Done) {
            fail("服务端拒绝：$code $message".trim())
        } else {
            _state.update { it.copy(notice = "服务端提示：$code $message".trim()) }
        }
    }

    private fun onClosed(code: Int, reason: String) {
        val why = reason.ifBlank { closingNote.orEmpty() }
        when {
            userHangup -> endCall(null)
            code == 1000 && step == SetupStep.Done -> endCall("通话已由服务端结束${if (why.isNotEmpty()) "：$why" else ""}")
            // 参数级错误重连也没用，直接失败。
            code == 1008 -> fail("会话参数错误：$why")
            // 并发已满 / 维护中：断开本轮，交给退避重连接管。
            code == 1013 -> dropAndRetry("服务繁忙（并发已满或正在维护）")
            else -> dropAndRetry("连接已断开（$code${if (why.isNotEmpty()) " $why" else ""}）")
        }
    }

    /** 握手失败或传输中断。已经进入对话的，优先尝试恢复会话。 */
    private fun onSocketFailure(message: String) {
        val established = resumeSessionId != null && step == SetupStep.Done
        if (established && !userHangup) dropAndRetry(message) else fail(message)
    }

    /** 连接层断开：清掉本轮连接但保留会话，交给重连流程。 */
    private fun dropAndRetry(reason: String) {
        val hadSession = resumeSessionId != null && step == SetupStep.Done
        releaseConnection()
        if (hadSession) {
            scheduleReconnect(reason)
        } else {
            // 还没真正进入对话就断了，重连也接不回上下文，直接失败更诚实。
            fail(reason)
        }
    }

    /** 只释放本次连接的资源，保留会话 ID、字幕与音频路由。 */
    private fun releaseConnection() {
        stopWatching()
        generation++
        liveSocket = null
        mic?.stop()
        mic = null
        player.stop()
        socket?.close(1000, "retry")
        socket = null
        step = SetupStep.None
        pendingAudio = null
        _state.update { it.copy(assistantSpeaking = false, userSpeaking = false) }
    }

    private fun endCall(note: String?) {
        if (socket == null) return
        userHangup = true
        teardown()
        _state.update { it.copy(phase = CallPhase.Idle, notice = note) }
        log("通话结束")
    }

    private fun fail(message: String) {
        log("✖ $message")
        teardown()
        _state.update { it.copy(phase = CallPhase.Failed, error = message) }
    }

    private fun teardown() {
        stopWatching()
        generation++
        liveSocket = null
        mic?.stop()
        mic = null
        player.stop()
        socket?.close(1000, "bye")
        socket = null
        routing.stop()
        step = SetupStep.None
        pendingAudio = null
        resumeSessionId = null
        reconnectAttempt = 0
        resuming = false
        val dropped = droppedFrames.getAndSet(0)
        if (dropped > 0) log("网络拥塞丢弃了 $dropped 帧上行音频（约 ${dropped * 20} ms）")
        _messages.update { list -> list.map { if (it.final) it else it.copy(final = true) } }
        _levels.value = AudioLevels()
        _state.update { it.copy(userSpeaking = false, assistantSpeaking = false, muted = false) }
    }

    private fun send(text: String) {
        val ws = socket ?: return
        val action = ServerEventPeek.action(text)
        log("→ $action")
        ws.send(text)
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        _log.update { (it + "${clock()} $line").takeLast(MAX_LOG_LINES) }
    }

    private fun describeFailure(t: Throwable, response: Response?): String {
        if (response != null) {
            val body = runCatching { response.body?.string() }.getOrNull()?.trim().orEmpty()
            // MOSS 的错误体形如 {"error":{"code":"102111001","message":"iam api key auth failed"}}
            val detail = runCatching {
                val obj = kotlinx.serialization.json.Json
                    .parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                val err = obj?.get("error") as? kotlinx.serialization.json.JsonObject
                listOfNotNull(err?.str("code"), err?.str("message")).joinToString(" ").ifBlank { null }
            }.getOrNull()
            val hint = when (response.code) {
                401, 403 -> if (response.code == 403 && detail == null) "没有调用权限" else "API Key 无效或已失效"
                404 -> "接口地址或模型不存在"
                429 -> "请求太频繁或额度不足"
                in 500..599 -> "服务端暂时不可用"
                else -> "握手失败"
            }
            return "$hint（HTTP ${response.code}）" + if (detail != null) "：$detail" else ""
        }
        return when (t) {
            is UnknownHostException -> "无法解析服务器地址，请检查网络"
            is SocketTimeoutException -> "连接超时，请检查网络"
            is ConnectException -> "无法连接服务器：${t.message}"
            is SSLException -> "安全连接失败：${t.message}"
            else -> "连接中断：${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---------------------------------------------------------------- 回调

    private inner class SocketListener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            inbox.trySend(Msg.Opened(gen))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inbox.trySend(Msg.Text(gen, text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            inbox.trySend(Msg.Binary(gen, bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            inbox.trySend(Msg.Closed(gen, code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inbox.trySend(Msg.Closed(gen, code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            inbox.trySend(Msg.Failure(gen, describeFailure(t, response)))
        }
    }

    private inner class PlayerCallbacks : VoicePlayer.Listener {
        override fun onChunkPlayed(runId: String, turnId: String, chunkId: String) {
            log("→ playback.chunk.completed $chunkId")
            liveSocket?.send(LiveProtocol.chunkCompleted(runId, turnId, chunkId))
        }

        override fun onRunPlayed(runId: String, turnId: String) {
            log("→ playback.completed ${runId.takeLast(8)}｜下行 ${audioFrames} 帧/${audioBytes / 1024}KB｜${player.stats()}")
            liveSocket?.send(LiveProtocol.playbackCompleted(runId, turnId))
        }

        override fun onPlayingChanged(playing: Boolean) {
            _state.update { it.copy(assistantSpeaking = playing) }
        }

        override fun onLevel(level: Float) {
            _levels.update { it.copy(assistant = level) }
        }
    }

    private companion object {
        const val TAG = "YIIAI.Live"
        const val SETUP_TIMEOUT_MS = 20_000L
        /** 自动重连上限。 */
        const val MAX_RECONNECT = 5
        const val BASE_RECONNECT_DELAY_MS = 500L
        const val MAX_RECONNECT_DELAY_MS = 8_000L
        /** 上行积压超过约 8 秒音频就丢帧，避免 OkHttp 16 MB 上限触发断线。 */
        const val MAX_SEND_QUEUE_BYTES = 256L * 1024
        const val MAX_LOG_LINES = 400
        /** 会话内保留的消息条数上限。 */
        const val MAX_MESSAGES = 200
        /** 记住多少个已执行的工具调用（去重用）。 */
        const val MAX_HANDLED_CALLS = 64
        val QUIET_EVENTS = setOf(
            "input.transcript.updated",
            "output.transcript.updated",
            "response.audio.context",
            "pong",
        )
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val CLOCK_SHORT = DateTimeFormatter.ofPattern("HH:mm")
        fun clock(short: Boolean = false): String = LocalTime.now().format(if (short) CLOCK_SHORT else CLOCK)
    }
}

private enum class SetupStep(val label: String) {
    None("未开始"),
    Attach("创建会话"),
    Voice("切换音色"),
    Persona("配置人设"),
    Media("配置音频"),
    Done("就绪"),
}

private class AudioContext(val runId: String, val turnId: String, val chunkId: String?)

private sealed interface Msg {
    data object Start : Msg
    data object Hangup : Msg
    data object ToggleMute : Msg
    data object ToggleSpeaker : Msg
    class Opened(val gen: Int) : Msg
    class Text(val gen: Int, val text: String) : Msg
    class Binary(val gen: Int, val bytes: ByteArray) : Msg
    class Closed(val gen: Int, val code: Int, val reason: String) : Msg
    class Failure(val gen: Int, val message: String) : Msg
    class MicError(val gen: Int, val message: String) : Msg
    class Reconnect(val gen: Int) : Msg
    class ToolDone(val gen: Int, val payload: String) : Msg
    class SetupTimeout(val gen: Int) : Msg
}

/** 只为日志取出客户端事件的 action，不做完整解析。 */
private object ServerEventPeek {
    private val ACTION = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"")
    fun action(json: String): String = ACTION.find(json)?.groupValues?.get(1) ?: "?"
}

