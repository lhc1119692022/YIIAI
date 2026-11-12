package com.yiiai.app.live

enum class CallPhase { Idle, Connecting, Configuring, Live, Reconnecting, Failed }

val CallPhase.isActive: Boolean
    get() = this == CallPhase.Connecting || this == CallPhase.Configuring ||
        this == CallPhase.Live || this == CallPhase.Reconnecting

enum class Role { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val final: Boolean = false,
    val interrupted: Boolean = false,
)

data class CallState(
    val phase: CallPhase = CallPhase.Idle,
    val userSpeaking: Boolean = false,
    val assistantSpeaking: Boolean = false,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    /** 当前音频路由的显示名：外放 / 听筒 / 耳机 / 蓝牙。 */
    val route: String = "",
    val sessionId: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 正在持续看屏幕。 */
    val watchingScreen: Boolean = false,
    /** 看屏幕到什么时候自动停（System.currentTimeMillis）。 */
    val watchEndsAt: Long = 0L,
    /** YIIAI 自己在前台，画面暂停发送，倒计时也停住。 */
    val watchPaused: Boolean = false,
    /** 摄像头正开着。 */
    val cameraActive: Boolean = false,
    val cameraEndsAt: Long = 0L,
    /** 正在用的镜头：前摄 / 后摄。 */
    val cameraFacing: String = "",
)

data class AudioLevels(val user: Float = 0f, val assistant: Float = 0f)
