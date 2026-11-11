package com.yiiai.app.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.yiiai.app.settings.CameraFacing
import java.net.URLEncoder

/**
 * MOSS-Live 1.0 实时交互协议（/v1/realtime）的客户端事件构造。
 * 字段说明见 docs/moss-live-protocol.md。
 */
object LiveProtocol {
    const val ENDPOINT = "wss://api.mosi.cn/v1/realtime"
    const val MODEL = "moss-live-1.0"

    /** tagged-v1 上行二进制前缀。 */
    const val TAG_AUDIO: Byte = 0x00
    const val TAG_CAMERA: Byte = 0x01
    const val TAG_SCREEN: Byte = 0x02

    /** 上行音频固定 16 kHz 单声道 PCM16。 */
    const val INPUT_SAMPLE_RATE = 16_000

    fun url(userId: String?): String = buildString {
        append(ENDPOINT).append("?model=").append(MODEL)
        if (!userId.isNullOrBlank()) append("&user=").append(URLEncoder.encode(userId, "UTF-8"))
    }

    fun attachSession(sessionId: String?, memoryUser: Boolean, clientDelegation: Boolean = false) =
        event("attach_session") {
            put("session_id", sessionId?.let { JsonPrimitive(it) } ?: JsonNull)
            if (memoryUser) put("memory_scope", "user")
            if (clientDelegation) putJsonObject("delegation") { put("type", "client") }
        }

    fun changeVoice(voiceId: String) = event("change_voice") { put("voice_name", voiceId) }

    /**
     * 人设 / 指令 / 工具。工具必须下发，所以即使三样人设字段都为空也要发。
     */
    fun sessionUpdate(
        personaName: String,
        personaPrompt: String,
        instructions: String,
        visionEnabled: Boolean = false,
        cameraFacing: CameraFacing = CameraFacing.Back,
    ): String {
        val name = personaName.trim()
        val prompt = personaPrompt.trim()
        val visionPolicy = if (visionEnabled) {
            "看手机屏幕始终可用：用户说「你看这个」「这个怎么样」或讨论屏幕、当前应用里的内容时，调用 look_at_screen。" +
                "摄像头视觉也已由用户开启：只有用户明确要你看现实中的周围环境、实物、用户本人或提到摄像头时，才调用 look_at_camera 获取最多 3 帧；" +
                "摄像头工具结束会自动关闭镜头，不要持续请求，屏幕画面不受影响。" +
                "需要切换时 facing 用 front（前摄）或 back（后摄）；省略时默认使用${cameraFacing.label}。"
        } else {
            "看手机屏幕始终可用：用户说「你看这个」「这个怎么样」或讨论屏幕、当前应用里的内容时，照常调用 look_at_screen。" +
                "摄像头视觉当前关闭（这只影响摄像头，不影响看屏幕）：不要调用 look_at_camera，也不要尝试开启摄像头；" +
                "用户要你看现实中的东西时，告诉用户可在设置中开启「开启视觉」。"
        }
        val baseInstructions = instructions.trim()
        val instr = (baseInstructions.take((MAX_INSTRUCTION_CHARS - visionPolicy.length - 2).coerceAtLeast(0)) +
            if (baseInstructions.isBlank()) visionPolicy else "\n\n$visionPolicy")
        return event("session.update") {
            putJsonObject("session") {
                if (name.isNotEmpty() || prompt.isNotEmpty()) {
                    putJsonObject("persona") {
                        if (name.isNotEmpty()) put("name", name)
                        if (prompt.isNotEmpty()) put("prompt", prompt.take(MAX_PERSONA_CHARS))
                    }
                }
                if (instr.isNotEmpty()) put("instructions", instr.take(MAX_INSTRUCTION_CHARS))
                // 工具整体替换；列表必须每个会话逐字一致，动态信息放在结果里。
                put("tools", Tools.all)
            }
        }
    }

    /** 工具结果回传。失败也要回。 */
    fun functionOutput(callId: String, output: String) = event("conversation.item.create") {
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output.take(MAX_TOOL_OUTPUT_CHARS))
        }
    }

    fun responseCreate(callId: String) = event("response.create") {
        putJsonObject("response") { put("call_ids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(callId)))) }
    }

    fun mediaConfigure(outputSampleRate: Int) = event("media.configure") {
        putJsonObject("output_audio") {
            put("format", "pcm16")
            put("sample_rate", outputSampleRate)
        }
        putJsonObject("input_audio") {
            put("format", "pcm16")
        }
        // 屏幕与摄像头画面：长边 1024、JPEG 质量 0.8。
        // 注意服务端要求 jpeg_quality 在 0.1..1 之间，传 80 会被整条 media.configure 拒绝。
        putJsonObject("video") {
            put("max_long_side", 1024)
            put("jpeg_quality", 0.8)
            put("interval_ms", 1000)
        }
        put("playback", "client")
    }

    fun mediaMode() = event("media.mode") { put("binary_protocol", "tagged-v1") }

    /** 打开 / 关闭某一路画面。发单帧屏幕截图前先发 enabled=true。 */
    fun videoState(source: String, enabled: Boolean) = event("video.state") {
        put("source", source)
        put("enabled", enabled)
    }

    fun videoStreamFocus(source: String) = event("video.stream_focus") { put("source", source) }

    fun clientContext(timeZone: String) = event("client_context.update") {
        putJsonObject("context") { put("time_zone", timeZone) }
    }

    fun micState(enabled: Boolean) = event("mic_state") { put("enabled", enabled) }

    fun chunkCompleted(runId: String, turnId: String, chunkId: String) = event("playback.chunk.completed") {
        put("run_id", runId)
        put("turn_id", turnId)
        put("chunk_id", chunkId)
    }

    fun playbackCompleted(runId: String, turnId: String) = event("playback.completed") {
        put("run_id", runId)
        put("turn_id", turnId)
    }

    fun playbackStopped(runId: String, turnId: String, playedAudioMs: Long) = event("playback.stopped") {
        put("run_id", runId)
        put("turn_id", turnId)
        put("played_audio_ms", playedAudioMs)
    }

    const val MAX_PERSONA_CHARS = 8000
    const val MAX_INSTRUCTION_CHARS = 16000

    /** 单次工具结果最多回传多少字符。 */
    const val MAX_TOOL_OUTPUT_CHARS = 4000

    private inline fun event(action: String, block: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("action", action)
            block()
        }.toString()
}

/** 服务端事件：{"action": "...", "data": {...}} */
class ServerEvent private constructor(val action: String, val data: JsonObject) {

    fun str(key: String): String? = data.str(key)
    fun obj(key: String): JsonObject? = data[key] as? JsonObject

    /** run_id 必须存在；turn_id 缺省时用空串。 */
    fun runTurn(): Pair<String, String>? {
        val run = str("run_id") ?: return null
        return run to (str("turn_id") ?: "")
    }

    fun preview(max: Int = 160): String {
        val s = data.toString()
        return if (s.length > max) s.take(max) + "…" else s
    }

    companion object {
        fun parse(text: String): ServerEvent? {
            return try {
                val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
                val action = root.str("action") ?: return null
                ServerEvent(action, root["data"] as? JsonObject ?: JsonObject(emptyMap()))
            } catch (e: Exception) {
                null
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p.content
}

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
