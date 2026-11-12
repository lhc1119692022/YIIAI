package com.yiiai.app.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.LiveSession
import com.yiiai.app.settings.SettingsStore
import com.yiiai.app.ui.DeerMark
import com.yiiai.app.ui.deerEyesFor
import com.yiiai.app.ui.YiiIsland
import com.yiiai.app.ui.theme.LocalYiiPalette
import com.yiiai.app.ui.theme.YiiTheme
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 悬浮球：和桌面图标同一只小鹿，异形、没有底板也没有描边。
 *
 * 没人说话时偶尔张望或抖耳朵，动作之间保持安静；有人说话时小动作立刻收起，身后显示声波。
 * 正在看屏幕或开着摄像头时，旁边亮一颗小灯。
 * 轻点展开面板，长按挂断，拖动后自动贴边并转头朝屏幕里面看。
 */
@Composable
fun OverlayBubbleHost(
    live: LiveSession,
    settingsStore: SettingsStore,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onLongPress: () -> Unit,
) {
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    YiiTheme(mode = com.yiiai.app.ui.theme.ThemeMode.of(settings.themeModeKey)) {
        val palette = LocalYiiPalette.current
        val state by live.state.collectAsStateWithLifecycle()
        val levels by live.levels.collectAsStateWithLifecycle()
        val dockedLeft by OverlayService.dockedLeft.collectAsStateWithLifecycle()

        val level = when {
            state.assistantSpeaking -> levels.assistant
            state.userSpeaking -> levels.user
            else -> 0f
        }
        val smooth by animateFloatAsState(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
            label = "bubble-level",
        )
        val connecting = state.phase == CallPhase.Connecting || state.phase == CallPhase.Configuring ||
            state.phase == CallPhase.Reconnecting
        val failed = state.phase == CallPhase.Failed
        val color: Color = when {
            failed -> palette.danger
            state.muted -> palette.orbIdle
            state.assistantSpeaking -> palette.orbAssistant
            state.userSpeaking -> palette.orbUser
            connecting -> palette.accent
            else -> palette.orbHalo
        }
        val animatedColor by androidx.compose.animation.animateColorAsState(color, label = "bubble-color")

        var pressed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var dragLean by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        // 出现时从小弹到正常大小。
        val appear = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0.3f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            appear.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
        }
        val press by animateFloatAsState(
            targetValue = if (pressed || dragging) 0.86f else 1f,
            animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
            label = "bubble-press",
        )
        // 松手后目标回到 0，弹簧欠阻尼，所以会左右晃两下才停。
        val lean by animateFloatAsState(
            targetValue = if (dragging) dragLean else 0f,
            animationSpec = spring(dampingRatio = 0.28f, stiffness = Spring.StiffnessLow),
            label = "bubble-lean",
        )
        val eyes = when {
            failed || state.muted -> deerEyesFor(state)
            dragging -> com.yiiai.app.ui.DeerEyes.Open
            state.assistantSpeaking || state.userSpeaking -> deerEyesFor(state)
            // 正在看屏幕或摄像头：睁着眼。
            state.watchingScreen || state.cameraActive -> com.yiiai.app.ui.DeerEyes.Open
            else -> deerEyesFor(state)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            dragLean = 0f
                            onDragEnd()
                        },
                        onDragCancel = {
                            dragging = false
                            dragLean = 0f
                            onDragEnd()
                        },
                    ) { change, drag ->
                        change.consume()
                        // 顺着拖动方向歪头，做一点平滑免得抖。
                        dragLean = (dragLean * 0.75f + drag.x * 0.9f).coerceIn(-22f, 22f)
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            com.yiiai.app.ui.DeerFloat(
                eyes = eyes,
                glow = animatedColor,
                level = smooth,
                energy = if (state.muted || failed) 0f else 1f,
                earMood = when {
                    failed -> -16f
                    state.muted -> -12f
                    dragging -> 12f
                    state.userSpeaking || state.watchingScreen || state.cameraActive -> 9f
                    else -> 0f
                },
                faceRight = dockedLeft,
                lean = lean,
                scale = appear.value * press,
                // 通话中、没人说话、也没人碰它：这时才做自己的小动作。
                idle = state.phase == CallPhase.Live && !state.muted && !state.assistantSpeaking &&
                    !state.userSpeaking && !dragging && !pressed,
                stateBubble = when {
                    failed -> "!"
                    state.phase == CallPhase.Reconnecting -> "重连"
                    connecting -> "…"
                    else -> null
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 正在看：角上一颗会呼吸的小灯。摄像头用红色，屏幕用聆听色。
            if (state.cameraActive || state.watchingScreen) {
                val lamp = if (state.cameraActive) palette.danger else palette.orbUser
                val glowAlpha by rememberInfiniteTransition(label = "bubble-lamp").animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "bubble-lamp-alpha",
                )
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension * 0.055f
                    // 靠屏幕边缘的那一侧、鹿角旁边；气泡在另一侧，不会挡住。
                    val at = Offset(
                        if (dockedLeft) size.width * 0.17f else size.width * 0.83f,
                        size.height * 0.40f,
                    )
                    drawCircle(Color.White, radius = r * 1.45f, center = at)
                    drawCircle(lamp.copy(alpha = if (state.watchPaused && !state.cameraActive) 0.35f else glowAlpha), radius = r, center = at)
                }
            }
        }
    }
}
/** 收起的灵动岛：顶部正中的一颗黑色胶囊，头像 + 名字 + 状态 + 音量条，点一下展开面板。 */
@Composable
fun OverlayIslandHost(
    live: LiveSession,
    settingsStore: SettingsStore,
    onTap: () -> Unit,
) {
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    // 灵动岛永远是黑色胶囊，和系统的岛一致，不跟随浅色主题。
    YiiTheme(mode = com.yiiai.app.ui.theme.ThemeMode.DARK) {
        val state by live.state.collectAsStateWithLifecycle()
        val messages by live.messages.collectAsStateWithLifecycle()
        val levels by live.levels.collectAsStateWithLifecycle()
        val level = when {
            state.assistantSpeaking -> levels.assistant
            state.userSpeaking -> levels.user
            else -> 0f
        }
        YiiIsland(
            name = settings.personaName.ifBlank { "YIIAI" },
            state = state,
            messages = messages,
            level = level,
            expanded = false,
            onToggle = onTap,
            onMute = { live.toggleMute() },
            onHangup = {},
            onOpen = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 展开后的灵动岛：贴在屏幕顶部，小鹿从胶囊里探出头。 */
@Composable
fun OverlayPanelHost(
    live: LiveSession,
    settingsStore: SettingsStore,
    onCollapse: () -> Unit,
    onHangup: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    YiiTheme(mode = com.yiiai.app.ui.theme.ThemeMode.of(settings.themeModeKey)) {
        val state by live.state.collectAsStateWithLifecycle()
        val messages by live.messages.collectAsStateWithLifecycle()
        val levels by live.levels.collectAsStateWithLifecycle()
        val name = settings.personaName.ifBlank { "YIIAI" }
        val level = when {
            state.assistantSpeaking -> levels.assistant
            state.userSpeaking -> levels.user
            else -> 0f
        }
        YiiIsland(
            name = name,
            state = state,
            messages = messages,
            level = level,
            expanded = true,
            onToggle = onCollapse,
            onMute = { live.toggleMute() },
            onHangup = onHangup,
            onOpen = onOpenApp,
            modifier = Modifier.fillMaxWidth(),
            onStopLooking = {
                if (state.cameraActive) live.stopCameraByUser() else live.stopWatchingByUser()
            },
        )
    }
}

