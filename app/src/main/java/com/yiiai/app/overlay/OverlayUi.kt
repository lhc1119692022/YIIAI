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
 * 悬浮球：固定不动的YIIAI头像，外面一圈状态色轮廓。
 * 说话时轮廓像参考项目的 logo 一样不停变形（花瓣状的波动），音量越大波动越大；
 * 安静时收回成正圆，不再上下浮动。
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

        val speaking = state.assistantSpeaking || state.userSpeaking
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
        // 变形幅度：说话时最大，连接时中等，安静聆听时轻微，静音/出错回到正圆。
        val amplitude by animateFloatAsState(
            targetValue = when {
                state.muted || state.phase == CallPhase.Failed -> 0f
                speaking -> 0.055f + smooth * 0.07f
                connecting -> 0.045f
                state.phase == CallPhase.Live -> 0.018f
                else -> 0f
            },
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
            label = "bubble-amp",
        )
        val transition = rememberInfiniteTransition(label = "bubble")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 6.2832f,
            animationSpec = infiniteRepeatable(
                tween(if (state.assistantSpeaking) 1600 else 3200, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "bubble-phase",
        )

        val color: Color = when {
            state.phase == CallPhase.Failed -> palette.danger
            state.muted -> palette.orbIdle
            state.assistantSpeaking -> palette.orbAssistant
            state.userSpeaking -> palette.orbUser
            connecting -> palette.accent
            else -> palette.orbHalo
        }
        val shape = BlobShape(amplitude, phase)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    ) { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                // 外圈柔光
                val glowR = size.minDimension * 0.50f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.45f + smooth * 0.25f), Color.Transparent),
                        center = c,
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = c,
                )
                // 状态色轮廓：和头像同一个变形轮廓，略大一圈
                val ring = blobPath(size.minDimension * 0.42f, c, amplitude, phase)
                drawPath(ring, color, style = Stroke(width = 3.dp.toPx()))
            }
            Box(
                Modifier
                    .fillMaxSize(0.76f)
                    .clip(shape),
            ) {
                DeerMark(
                    modifier = Modifier.fillMaxSize(),
                    glow = color,
                    withTile = true,
                    eyes = deerEyesFor(state),
                )
            }
        }
    }
}

/** 花瓣状波动的圆：r(θ) = R · (1 - a · (0.5 + 0.5·sin(7θ + φ)))。a = 0 时就是正圆。 */
private fun blobPath(radius: Float, center: Offset, amplitude: Float, phase: Float): Path {
    val path = Path()
    val steps = 96
    for (i in 0..steps) {
        val t = i / steps.toFloat() * 6.2832f
        val wobble = 0.5f + 0.5f * sin(7f * t + phase) * 0.7f + 0.3f * sin(3f * t - phase * 2f) * 0.5f
        val r = radius * (1f - amplitude * wobble)
        val x = center.x + r * cos(t)
        val y = center.y + r * sin(t)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private class BlobShape(private val amplitude: Float, private val phase: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(blobPath(size.minDimension / 2f, Offset(size.width / 2f, size.height / 2f), amplitude, phase))
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
        )
    }
}

