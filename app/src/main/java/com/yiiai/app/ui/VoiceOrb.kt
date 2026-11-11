package com.yiiai.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

enum class OrbMode {
    /** 未开始：小鹿收着光，慢慢呼吸。 */
    Idle,

    /** 连接/配置中：耳朵轻轻动，像在找信号。 */
    Connecting,

    /** 正在聆听：抬眼看你，耳朵也抬起来。 */
    Listening,

    /** 用户说话时：眼睛跟着声音微微睁大。 */
    UserSpeaking,

    /** AI 正在说：眼睛弯成月牙，耳朵放松。 */
    AssistantSpeaking,

    /** 静音中：闭上眼睛，耳朵向后垂。 */
    Muted,

    /** 出错：眼睛变成 ×，耳朵压低。 */
    Error,
}

/**
 * 中心的小鹿「YIIAI」。它同时是这通电话唯一的控制面：
 * 轻点开始 / 静音 / 取消静音，长按挂断。没有下方按钮条。
 */
@Composable
fun VoiceOrb(
    mode: OrbMode,
    level: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 280,
) {
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)

    Box(
        modifier
            .size(sizeDp.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap() }, onLongPress = { longPress() })
            },
    ) {
        DeerMascot(
            mode = mode,
            level = level,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
