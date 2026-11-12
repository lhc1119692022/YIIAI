package com.yiiai.app.live

import android.content.Context
import android.util.Log
import com.yiiai.app.audio.AudioRouting
import com.yiiai.app.audio.MicStreamer
import com.yiiai.app.audio.VoicePlayer
import com.yiiai.app.settings.AppSettings
import com.yiiai.app.settings.QualityPresets
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

    private fun tagged(tag: Byte, bytes: ByteArray): ByteString {
        val frame = ByteArray(bytes.size + 1)
        frame[0] = tag
        System.arraycopy(bytes, 0, frame, 1, bytes.size)
        return frame.toByteString()
    }

    /**
     * 发一帧画面。返回 true 已发出、false 连接不在了、null 这一帧因为上行拥塞被跳过。
     *
     * 画面和语音共用一条连接、先进先出。一帧画面有几十到几百 KB，网络慢时会把语音堵在后面，
     * 所以只有发送队列基本排空时才放下一帧进去：网速不够时自动降帧率，语音始终优先。
     */
    private fun sendVideo(tag: Byte, bytes: ByteArray): Boolean? {
        val ws = liveSocket ?: return false
        if (ws.queueSize() > VIDEO_QUEUE_GATE_BYTES) {
            skippedVideoFrames.incrementAndGet()
            return null
        }
        return ws.send(tagged(tag, bytes))
    }

    private val skippedVideoFrames = AtomicLong()

    /**
     * 摄像头画面流：模型调用 look_at_camera 后打开，连续发一段时间，到点自己关。
     * 同时给首页提供一路预览，让用户看到镜头正在拍什么。
     */
    private val cameraStreamer = com.yiiai.app.phone.CameraStreamer(
        context = appContext,
        // tagged-v1：摄像头帧前缀 0x01。
        sendFrame = { bytes -> sendVideo(LiveProtocol.TAG_CAMERA, bytes) },
        onLog = { log(it) },
        onChanged = { publishVision() },
        onEnded = { expired, problem, frames -> inbox.trySend(Msg.CameraEnded(generation, expired, problem, frames)) },
    )

    /** 首页用的摄像头预览帧；镜头没开时为 null。 */
    val cameraPreview: StateFlow<android.graphics.Bitmap?> get() = cameraStreamer.preview

    /** 工具执行器：截图 / 点击 / 输入 / 看屏幕 / 看摄像头，跑在独立协程里，不阻塞 actor。 */
    private val toolRunner = com.yiiai.app.phone.PhoneToolRunner(
        context = appContext,
        // tagged-v1：屏幕帧前缀 0x02，直接走实时连接。
        sendScreenFrame = { bytes -> liveSocket?.send(tagged(LiveProtocol.TAG_SCREEN, bytes)) ?: false },
        sendEvent = { json -> liveSocket?.send(json) ?: false },
        watchScreen = { seconds -> watchScreen(seconds) },
        stopScreen = { endWatching(WatchEnd.Model) },
        isWatching = { screenStreamer.running },
        screenQuality = { settings.current.screenVideo },
        startCamera = { facing, seconds -> cameraStreamer.start(facing, settings.current.cameraVideo, seconds) },
        stopCamera = { cameraStreamer.stop() },
        visionEnabled = { settings.current.visionEnabled },
        defaultCameraFacing = { settings.current.cameraFacing },
        defaultCameraSeconds = { settings.current.cameraLookSeconds },
    )

    /**
     * 屏幕实时画面。模型主要靠它「看见」用户在做什么，控件树只用来精确点击。
     * 由工具 look_at_screen / watch_screen 开启，到时间自动关闭。
     */
    internal val screenStreamer = com.yiiai.app.phone.ScreenStreamer(
        context = appContext,
        sendEvent = { json -> liveSocket?.send(json) ?: false },
        sendFrame = { bytes -> sendVideo(LiveProtocol.TAG_SCREEN, bytes) },
        onLog = { log(it) },
    )

    // ---- 看屏幕的计时。工具协程、界面线程和 actor 都会碰，统一用 watchLock 保护。
    private val watchLock = Any()
    /** 屏幕画面到什么时候自动关；0 表示没在看。 */
    @Volatile private var watchUntil = 0L
    /** 自动续期最多续到什么时候（从最近一次明确请求算起）。 */
    private var watchAutoLimit = 0L

    /** YIIAI 在前台时已经为它停表多久。 */
    private var watchPausedMs = 0L
    private var watchTimer: kotlinx.coroutines.Job? = null

    // ---- 安静提醒
    /** 最近一次有人说话的时间。 */
    @Volatile private var lastActivityAt = 0L
    /** 用户沉默期间已经搭过几次话；用户一说话就清零。 */
    private var nudges = 0
    /** 这一次要安静多久才搭话（在陪伴模式的范围内随机取）；0 表示还没取。 */
    private var nudgeAfterMs = 0L
    private var nudgeMode = com.yiiai.app.settings.CompanionMode.Off
    private var proactiveJob: kotlinx.coroutines.Job? = null
    private val eventSeq = AtomicLong()

    /** 已经完整播完的回复 run_id；服务端事后补的超时通知据此忽略。只留最近一批。 */
    private val playedRuns = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_MESSAGES
    }

    /** 已执行过的 call_id，避免重复调用同一工具。只留最近一批，长通话也不会一直涨。 */
    private val handledCalls = LinkedHashSet<String>()

    /** 已回传结果、等待 conversation.item.created 确认的 call_id。 */
    private val awaitingItemCreated = HashSet<String>()

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val routing = AudioRouting(appContext)
    private val player = VoicePlayer(PlayerCallbacks())

    // ---- 以下字段只在 actor 协程中读写
    @Volatile private var generation = 0
    private var socket: WebSocket? = null
    private var mic: MicStreamer? = null
    @Volatile private var step = SetupStep.None
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
    fun onVisionSettingsChanged() { inbox.trySend(Msg.VisionSettingsChanged) }
    /** 存储页用：清空对话记录。 */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    /** 存储页用：清空运行日志。 */
    fun clearLog() {
        _log.value = emptyList()
    }

    /** 设置里改了视觉、画质或主动开口：通话中立即生效的部分在这里重新下发。 */
    fun onSettingsChanged() = onVisionSettingsChanged()

    /** 界面用：再看 [seconds] 秒屏幕（没在看就开始看）。 */
    fun extendWatching(seconds: Int = com.yiiai.app.phone.PhoneToolRunner.LOOK_SECONDS): Boolean =
        watchScreen(seconds, additive = true)

    /** 界面用：用户手动停止看屏幕。 */
    fun stopWatchingByUser() { endWatching(WatchEnd.User) }

    /** 界面用：用户手动关掉摄像头。 */
    fun stopCameraByUser() {
        if (!cameraStreamer.stop()) return
        liveSocket?.let { ws ->
            ws.send(LiveProtocol.videoState("camera", false))
            ws.send(LiveProtocol.videoStreamFocus(if (screenStreamer.running) "screen" else "auto"))
        }
        tellModel("用户手动关掉了摄像头，你现在看不到现实画面了。")
    }

    /** 首页显示摄像头预览期间置为 true；没人看时不解码预览帧。 */
    fun setCameraPreviewWanted(wanted: Boolean) { cameraStreamer.previewWanted = wanted }

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
            Msg.VisionSettingsChanged -> {
                if (!settings.current.visionEnabled && cameraStreamer.stop() && step == SetupStep.Done) {
                    send(LiveProtocol.videoState("camera", false))
                    send(LiveProtocol.videoStreamFocus(if (screenStreamer.running) "screen" else "auto"))
                }
                if (step == SetupStep.Done) {
                    sendVisionInstructions()
                    // 画质档位可能变了：重新声明画面规格。音频采样率通话中不能换，沿用接通时的。
                    call?.let { send(LiveProtocol.mediaConfigure(it.outputSampleRate, videoEnvelope())) }
                }
            }
            is Msg.CameraEnded -> if (msg.gen == generation) onCameraEnded(msg.expired, msg.problem, msg.frames)
            is Msg.ProactiveTick -> if (msg.gen == generation) onProactiveTick(msg.force)
            is Msg.ProactiveRunPlayed -> if (msg.gen == generation) setServerPlayback(false)
            is Msg.Opened -> if (msg.gen == generation) onOpened()
            is Msg.Text -> if (msg.gen == generation) onText(msg.text)
            is Msg.Binary -> if (msg.gen == generation) onBinary(msg.bytes)
            is Msg.Closed -> if (msg.gen == generation) onClosed(msg.code, msg.reason)
            is Msg.Failure -> if (msg.gen == generation) {
                // 握手被拒（401/403）：这把 Key 失效了，设置页据此提示重新连接。
                if (msg.keyRejected) settings.markApiKeyRejected(true)
                onSocketFailure(msg.message)
            }
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
        serverPlayback = false
        closingNote = null
        pendingAudio = null
        lastRun = null
        userDraftId = null
        resuming = !freshSession && resumeSessionId != null
        if (freshSession) {
            playedRuns.clear()
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
        // 能创建会话，说明 Key 是好的。
        settings.markApiKeyRejected(false)
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
        // 模型正在动手操作：事情没做完，屏幕画面不能在这时候到期。
        if (name in Tools.actionTools) keepWatching(WATCH_KEEP_ON_ACTION_MS)
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
        if (call == null) return
        step = SetupStep.Persona
        sendVisionInstructions()
    }

    private fun sendVisionInstructions() {
        val s = call ?: settings.current
        send(
            LiveProtocol.sessionUpdate(
                s.personaName,
                s.personaPrompt,
                s.instructions,
                visionEnabled = settings.current.visionEnabled,
                cameraFacing = settings.current.cameraFacing,
                cameraSeconds = settings.current.cameraLookSeconds,
                companion = settings.current.companion != com.yiiai.app.settings.CompanionMode.Off,
            ),
        )
    }

    private fun onSessionUpdated(ev: ServerEvent) {
        if (step != SetupStep.Persona) return
        val err = ev.str("error").orEmpty()
        if (err.isNotEmpty()) fail("人设配置未生效：$err") else configureMedia()
    }

    private fun configureMedia() {
        val s = call ?: return
        step = SetupStep.Media
        send(LiveProtocol.mediaConfigure(s.outputSampleRate, videoEnvelope()))
    }

    /** media.configure 里屏幕和摄像头共用一份画面声明，取两路档位的外包络。 */
    private fun videoEnvelope() = QualityPresets.envelope(settings.current.screenVideo, settings.current.cameraVideo)

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
        // 断线前正在看屏幕、时间还没到：接着看。
        if (recovered) resumeWatchingIfNeeded()
        startProactiveLoop(gen)
    }

    // ---------------------------------------------------------------- 看屏幕

    /**
     * 开启或续期屏幕画面，[seconds] 秒后自动关闭。只会延长，不会把剩余时间改短。
     * 返回是否真的在推流（无障碍没连上时为 false）。
     */
    internal fun watchScreen(seconds: Int, additive: Boolean = false): Boolean = synchronized(watchLock) {
        if (step != SetupStep.Done) return false
        val now = System.currentTimeMillis()
        if (!screenStreamer.running) screenStreamer.start(scope) { settings.current.screenVideo }
        if (!screenStreamer.running) {
            watchUntil = 0L
            publishVision()
            return false
        }
        watchUntil = if (additive) {
            // 界面上的「再看 3 分钟」：在剩余时间上往后加，但总共不超过一次观看的上限。
            (maxOf(watchUntil, now) + seconds * 1000L)
                .coerceAtMost(now + com.yiiai.app.phone.PhoneToolRunner.WATCH_MAX_SECONDS * 1000L)
        } else {
            maxOf(watchUntil, now + seconds * 1000L)
        }
        watchAutoLimit = watchUntil + WATCH_AUTO_EXTEND_MAX_MS
        watchPausedMs = 0L
        if (watchTimer?.isActive != true) watchTimer = scope.launch { watchLoop() }
        publishVision()
        true
    }

    /**
     * 模型正在操作手机：保证屏幕画面至少还剩 [ms]，让它看得到这一步操作的结果。
     * 这不是「操作完再多等一段」——剩余时间本来就够的话什么都不变，只在快到期时补到 [ms]。
     * 自动续期有总上限，超过后必须由模型或用户明确续期，避免忘了关一直推流。
     */
    private fun keepWatching(ms: Long) = synchronized(watchLock) {
        if (watchUntil == 0L || !screenStreamer.running) return
        val target = minOf(System.currentTimeMillis() + ms, watchAutoLimit)
        if (target > watchUntil) {
            watchUntil = target
            publishVision()
        }
    }

    /** 每秒检查一次：停表、到期关闭、画面流意外中断。 */
    private suspend fun watchLoop() {
        var last = System.currentTimeMillis()
        while (true) {
            delay(1000)
            var end: WatchEnd? = null
            synchronized(watchLock) {
                val now = System.currentTimeMillis()
                val dt = now - last
                last = now
                if (watchUntil == 0L) return
                when {
                    !screenStreamer.running -> end = WatchEnd.Broken
                    // YIIAI 自己在前台时没有画面可发，先停表；停太久就照常计时，避免无限期挂着。
                    screenStreamer.paused && watchPausedMs < WATCH_PAUSE_BUDGET_MS -> {
                        watchUntil += dt
                        watchAutoLimit += dt
                        watchPausedMs += dt
                    }
                    now >= watchUntil -> end = WatchEnd.Expired
                }
            }
            publishVision()
            // 刚好在这一瞬间被续期的话 endWatching 会放弃关闭，继续计时。
            end?.let { if (endWatching(it)) return }
        }
    }

    /** 结束看屏幕并把原因告诉模型和用户。返回之前是否在看。 */
    private fun endWatching(why: WatchEnd): Boolean {
        val was = synchronized(watchLock) {
            val running = watchUntil != 0L || screenStreamer.running
            when (why) {
                // 这两种由计时循环自己发起：先确认条件还成立（可能刚被续期或重新开启）。
                WatchEnd.Expired -> if (System.currentTimeMillis() < watchUntil) return false
                WatchEnd.Broken -> if (screenStreamer.running) return false
                else -> watchTimer?.cancel()
            }
            watchUntil = 0L
            watchTimer = null
            screenStreamer.stop()
            running
        }
        publishVision()
        if (!was) return false
        log("屏幕画面已关闭（${why.label}，发送 ${screenStreamer.framesSent} 帧，画面没变省掉 ${screenStreamer.framesSkipped} 帧）")
        when (why) {
            // 到时间自然结束：用户不需要知道，只让模型知道自己看不到了，免得它拿旧画面回答。
            WatchEnd.Expired ->
                tellModel("屏幕画面已经到时间自动关闭，你现在看不到屏幕了。之后需要看屏幕时，重新调用 look_at_screen。")
            WatchEnd.Broken -> {
                tellModel("屏幕画面中断了：无障碍服务被系统关闭，你现在看不到屏幕，也点不了。请告诉用户到设置里重新打开「屏幕与应用控制」。")
                _state.update { it.copy(notice = "屏幕画面中断：无障碍服务被系统关掉了") }
            }
            WatchEnd.User -> tellModel("用户手动关掉了屏幕画面，你现在看不到屏幕了。")
            WatchEnd.Model, WatchEnd.Silent -> Unit
        }
        return true
    }

    /** 断线时：停掉推流但记住还剩多久，重连后接着看。 */
    private fun suspendWatching() = synchronized(watchLock) {
        watchTimer?.cancel()
        watchTimer = null
        screenStreamer.stop()
        publishVision()
    }

    private fun resumeWatchingIfNeeded() {
        val remaining = synchronized(watchLock) {
            val left = watchUntil - System.currentTimeMillis()
            watchUntil = 0L
            left
        }
        if (remaining > 5_000L && watchScreen((remaining / 1000L).toInt())) {
            log("重连后继续看屏幕，还剩 ${remaining / 1000} 秒")
        }
    }

    /** 把屏幕 / 摄像头的观看状态同步到界面。 */
    private fun publishVision() {
        val watching = screenStreamer.running
        _state.update {
            it.copy(
                watchingScreen = watching,
                watchEndsAt = if (watching) watchUntil else 0L,
                watchPaused = watching && screenStreamer.paused,
                cameraActive = cameraStreamer.running,
                cameraEndsAt = cameraStreamer.endsAt,
                cameraFacing = cameraStreamer.facing?.label.orEmpty(),
            )
        }
    }

    /**
     * 静默告诉模型一条事实（画面关了、镜头关了）。模型不会因此开口，
     * 但之后不会再假装自己还能看到。任何线程都可以调用。
     */
    private fun tellModel(text: String) {
        val ws = liveSocket ?: return
        log("→ session.thinking.append $text")
        ws.send(LiveProtocol.thinkingAppend(text, nextEventId()))
    }

    private fun nextEventId() = EVENT_ID_PREFIX + eventSeq.incrementAndGet()

    private fun onCameraEnded(expired: Boolean, problem: String?, frames: Int) {
        send(LiveProtocol.videoState("camera", false))
        // 摄像头只是临时看一段，结束后把主画面还回去，否则之后的屏幕帧服务端不看。
        send(LiveProtocol.videoStreamFocus(if (screenStreamer.running) "screen" else "auto"))
        if (problem != null) {
            tellModel("摄像头画面中断：$problem。镜头已经关闭，你现在看不到现实画面了。")
            _state.update { it.copy(notice = "摄像头中断：$problem") }
        } else if (expired) {
            tellModel("摄像头已经到时间自动关闭（共发送 $frames 帧），你现在看不到现实画面了。还需要看就再调用 look_at_camera。")
        }
    }

    // ---------------------------------------------------------------- 陪伴模式

    private fun startProactiveLoop(gen: Int) {
        proactiveJob?.cancel()
        lastActivityAt = System.currentTimeMillis()
        nudges = 0
        nudgeAfterMs = 0L
        proactiveJob = scope.launch {
            while (true) {
                delay(PROACTIVE_TICK_MS)
                inbox.send(Msg.ProactiveTick(gen))
            }
        }
    }

    /**
     * 陪伴模式：用户安静够久了，就让角色主动搭一次话。
     * 「够久」每次都在档位的范围里随机取（标准陪伴 30 秒–2 分钟，热情陪伴 10–45 秒）。
     * 连续几次没人回应后间隔逐次加倍，免得对着没人的房间一直说；用户一开口就恢复。
     */
    private fun onProactiveTick(force: Boolean = false) {
        val mode = settings.current.companion
        if (step != SetupStep.Done) return
        if (mode == com.yiiai.app.settings.CompanionMode.Off && !force) return
        val now = System.currentTimeMillis()
        val state = _state.value
        if (state.assistantSpeaking) {
            lastActivityAt = now
            return
        }
        if (!force) {
            if (state.muted || state.userSpeaking) return
            if (nudgeAfterMs == 0L || nudgeMode != mode) {
                nudgeMode = mode
                nudgeAfterMs = pickNudgeDelay(mode)
            }
            if (now - lastActivityAt < nudgeAfterMs) return
        }
        val quiet = now - lastActivityAt
        val warm = mode == com.yiiai.app.settings.CompanionMode.Warm
        nudges++
        lastActivityAt = now
        nudgeAfterMs = pickNudgeDelay(mode)
        val span = if (quiet >= 60_000L) "${quiet / 60_000L} 分钟" else "${(quiet / 1000L).coerceAtLeast(1)} 秒"
        log(
            "💬 已安静 $span，让角色搭话（第 $nudges 次" +
                (if (mode == com.yiiai.app.settings.CompanionMode.Off) "" else "，下次约 ${nudgeAfterMs / 1000} 秒后") + "）",
        )
        val prompt = buildString {
            append("【陪伴提示，不要念出来，也不要提到它】用户已经有 $span 没说话了。")
            append("请用你自己的人设和说话方式，自然地找个话头跟用户搭话：")
            append("可以接着刚才聊的事往下说，可以说说你这会儿想到的，也可以关心一下对方在忙什么。")
            append(
                if (warm) {
                    "语气热络、主动一些，像是忍不住想找对方说话。"
                } else {
                    "语气轻松随意，点到为止。"
                },
            )
            append("一两句就好，说完等对方回应。是搭话，不是催对方开口：不要问「你怎么不说话」「还在吗」。")
            if (state.watchingScreen) append("你现在能看到用户的屏幕，也可以就屏幕上的内容说两句。")
            if (nudges > 1) append("你上一次搭话对方没有回应，这次换个话题，不要重复。")
        }
        // 主动开口的回复不带 turn_id / chunk_id，没法逐块回执；先切到服务端计时，说完再切回来。
        setServerPlayback(true)
        send(LiveProtocol.commentaryAppend(prompt, nextEventId()))
    }

    /** 当前是否处于「服务端按实时速度推进播放」的模式。 */
    private var serverPlayback = false

    private fun setServerPlayback(on: Boolean) {
        if (serverPlayback == on || step != SetupStep.Done) return
        val s = call ?: return
        serverPlayback = on
        send(LiveProtocol.mediaConfigure(s.outputSampleRate, videoEnvelope(), serverPlayback = on))
    }

    private fun pickNudgeDelay(mode: com.yiiai.app.settings.CompanionMode): Long {
        if (mode == com.yiiai.app.settings.CompanionMode.Off) return Long.MAX_VALUE / 4
        val base = kotlin.random.Random.nextLong(mode.minSeconds * 1000L, mode.maxSeconds * 1000L + 1)
        val unanswered = (nudges - NUDGES_BEFORE_BACKOFF + 1).coerceIn(0, 6)
        return (base shl unanswered).coerceAtMost(maxOf(NUDGE_BACKOFF_MAX_MS, base))
    }

    /** 用户确实说了话：安静计时从头算，搭话的间隔也恢复正常。 */
    private fun onUserSpoke() {
        lastActivityAt = System.currentTimeMillis()
        if (nudges != 0) {
            nudges = 0
            nudgeAfterMs = 0L
        }
    }

    // ---------------------------------------------------------------- 调试入口（仅 debug 构建调用）

    /** 直接执行一个工具，和模型调用走同一条路径，结果只写日志。 */
    internal fun debugRunTool(name: String, argsJson: String) {
        scope.launch {
            val args = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
            if (name in Tools.actionTools) keepWatching(WATCH_KEEP_ON_ACTION_MS)
            val result = runCatching { toolRunner.run(name, args) }.getOrElse { "工具执行出错：${it.message}" }
            log("🧪 $name($argsJson) → $result")
        }
    }

    /** 立刻触发一次陪伴搭话，不等安静时间。 */
    internal fun debugNudge() { inbox.trySend(Msg.ProactiveTick(generation, force = true)) }

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
        if (turn.isEmpty()) {
            setServerPlayback(false)
            return
        }
        send(LiveProtocol.playbackStopped(run, turn, playedMs))
    }

    private fun onInterrupted(ev: ServerEvent) {
        val run = ev.str("run_id")
        val text = ev.str("text")
        // playback_timeout 是服务端自己按回执估算的播放超时：用户真插话时 reason 是 barge-in，
        // 所以这种一律不是「被打断」。我们已经完整播完的、或正在播的，都不该被它掐掉
        // （角色主动开口的回复没有 turn_id / chunk_id，服务端等不到逐块回执，必然误判）。
        if (run != null && text.isNullOrBlank() && ev.str("reason") == "playback_timeout") {
            val finished = playedRuns.containsKey(run)
            log("（忽略服务端的播放超时：这段${if (finished) "已经播完" else "由本地播放控制"}）")
            return
        }
        if (run == null) {
            player.openRuns().forEach { (r, t) -> stopPlayback(r, t) }
            return
        }
        aiMsgByRun[run]?.let { id ->
            updateMessage(id) { m ->
                m.copy(text = text?.takeIf { it.isNotBlank() } ?: m.text, final = true, interrupted = true)
            }
            _messages.value.firstOrNull { it.id == id }?.let { log("🗣（被打断）${it.text.take(200)}") }
        }
        stopPlayback(run, ev.str("turn_id").orEmpty())
    }

    private fun onResponseEnded(ev: ServerEvent) {
        val run = ev.str("run_id") ?: return
        val id = aiMsgByRun[run] ?: return
        val status = ev.str("status")
        val msg = _messages.value.firstOrNull { it.id == id } ?: return
        if (status != "completed" && msg.text.isBlank()) removeMessage(id) else updateMessage(id) { it.copy(final = true) }
        // 角色主动开口的回复没有 turn_id；把它说了什么记进日志，方便核对陪伴模式的效果。
        if (ev.str("turn_id").isNullOrEmpty() && msg.text.isNotBlank()) log("🗣（主动）${msg.text.take(200)}")
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
        if (text.isNotBlank()) {
            if (final) onUserSpoke() else lastActivityAt = System.currentTimeMillis()
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
        // 我们自己发的静默通知 / 安静提醒被拒：只记日志，不打扰用户。
        if (err?.str("client_event_id")?.startsWith(EVENT_ID_PREFIX) == true) {
            log("⚠ 服务端没有接受通知：$code $message")
            return
        }
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
        suspendWatching()
        cameraStreamer.stop()
        proactiveJob?.cancel()
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
        endWatching(WatchEnd.Silent)
        cameraStreamer.stop()
        proactiveJob?.cancel()
        proactiveJob = null
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
        val skipped = skippedVideoFrames.getAndSet(0)
        if (skipped > 0) log("上行忙时跳过了 $skipped 帧画面（语音优先）")
        _messages.update { list -> list.map { if (it.final) it else it.copy(final = true) } }
        _levels.value = AudioLevels()
        _state.update { it.copy(userSpeaking = false, assistantSpeaking = false, muted = false) }
    }

    private fun send(text: String) {
        val ws = socket ?: return
        val action = ServerEventPeek.action(text)
        log("→ $action")
        raw("→", text)
        ws.send(text)
    }

    /** 仅 debug 构建：把收发的原始 JSON 打到 logcat（标签 YIIAI.Raw），排查协议问题用。不进应用内日志。 */
    private fun raw(direction: String, json: String) {
        if (com.yiiai.app.BuildConfig.DEBUG) Log.v("YIIAI.Raw", "$direction ${json.take(700)}")
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
            raw("←", text)
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
            val rejected = response?.code == 401 || response?.code == 403
            inbox.trySend(Msg.Failure(gen, describeFailure(t, response), keyRejected = rejected))
        }
    }

    private inner class PlayerCallbacks : VoicePlayer.Listener {
        override fun onChunkPlayed(runId: String, turnId: String, chunkId: String) {
            // 没有编号的片段（角色主动开口）回执会被服务端拒绝，不发。
            if (chunkId.isEmpty() || turnId.isEmpty()) return
            log("→ playback.chunk.completed $chunkId")
            liveSocket?.send(LiveProtocol.chunkCompleted(runId, turnId, chunkId).also { raw("→", it) })
        }

        override fun onRunPlayed(runId: String, turnId: String) {
            log("→ playback.completed ${runId.takeLast(8)}｜下行 ${audioFrames} 帧/${audioBytes / 1024}KB｜${player.stats()}")
            playedRuns[runId] = true
            if (turnId.isEmpty()) {
                inbox.trySend(Msg.ProactiveRunPlayed(generation))
                return
            }
            liveSocket?.send(LiveProtocol.playbackCompleted(runId, turnId).also { raw("→", it) })
        }

        override fun onPlayingChanged(playing: Boolean) {
            lastActivityAt = System.currentTimeMillis()
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
        /**
         * 上行积压超过这么多就丢语音帧，避免 OkHttp 16 MB 上限触发断线。
         * 队列里可能排着一帧画面（清晰档摄像头一帧可达三四百 KB），所以阈值要比一帧画面大。
         */
        const val MAX_SEND_QUEUE_BYTES = 1024L * 1024
        const val MAX_LOG_LINES = 400
        /** 会话内保留的消息条数上限。 */
        const val MAX_MESSAGES = 200
        /** 记住多少个已执行的工具调用（去重用）。 */
        const val MAX_HANDLED_CALLS = 64

        /** 模型每操作一步，屏幕画面至少还剩这么久，够它看到结果并决定下一步。 */
        const val WATCH_KEEP_ON_ACTION_MS = 30_000L
        /** 自动续期最多比明确要求的时间多看这么久，之后必须明确续期。 */
        const val WATCH_AUTO_EXTEND_MAX_MS = 10 * 60_000L
        /** YIIAI 在前台时最多为它停表这么久。 */
        const val WATCH_PAUSE_BUDGET_MS = 3 * 60_000L

        const val PROACTIVE_TICK_MS = 2_000L
        /** 连续搭话这么多次都没人回应后，间隔开始逐次加倍。 */
        const val NUDGES_BEFORE_BACKOFF = 3
        const val NUDGE_BACKOFF_MAX_MS = 30 * 60_000L

        /** 发送队列超过这么多字节就不再放画面进去，让语音先走。 */
        const val VIDEO_QUEUE_GATE_BYTES = 32L * 1024

        /** 我们自己发的 append 事件 ID 前缀，用来认出对应的错误回执。 */
        const val EVENT_ID_PREFIX = "yii-"
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

/** 屏幕画面为什么关。 */
private enum class WatchEnd(val label: String) {
    Expired("到时间"),
    Broken("无障碍中断"),
    User("用户关闭"),
    Model("模型关闭"),
    /** 通话结束时顺手关掉，不用通知谁。 */
    Silent("通话结束"),
}

private class AudioContext(val runId: String, val turnId: String, val chunkId: String?)

private sealed interface Msg {
    data object Start : Msg
    data object Hangup : Msg
    data object ToggleMute : Msg
    data object ToggleSpeaker : Msg
    data object VisionSettingsChanged : Msg
    class Opened(val gen: Int) : Msg
    class Text(val gen: Int, val text: String) : Msg
    class Binary(val gen: Int, val bytes: ByteArray) : Msg
    class Closed(val gen: Int, val code: Int, val reason: String) : Msg
    class Failure(val gen: Int, val message: String, val keyRejected: Boolean = false) : Msg
    class CameraEnded(val gen: Int, val expired: Boolean, val problem: String?, val frames: Int) : Msg
    class ProactiveTick(val gen: Int, val force: Boolean = false) : Msg
    class ProactiveRunPlayed(val gen: Int) : Msg
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

