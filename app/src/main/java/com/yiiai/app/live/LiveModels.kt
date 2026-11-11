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
)

data class AudioLevels(val user: Float = 0f, val assistant: Float = 0f)
