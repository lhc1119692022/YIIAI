package com.yiiai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.CallState
import com.yiiai.app.live.ChatMessage
import com.yiiai.app.live.Role
import com.yiiai.app.ui.theme.LocalYiiPalette
import com.yiiai.app.ui.theme.YiiPalette
import kotlin.math.max
import kotlin.math.sin

/**
 * 小鹿灵动岛。
 *
 * 收起时是一颗贴着屏幕顶部的「柔光胶囊」：小鹿的脸 + 状态 + 音量条。
 * 展开后是同一颗胶囊长出的面板：最近两句字幕、静音 / 挂断 / 打开。
 * 视觉上不是「通知卡片」，而是小鹿从胶囊里探出头来。
 */
@Composable
fun YiiIsland(
    name: String,
    state: CallState,
    messages: List<ChatMessage>,
    level: Float,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMute: () -> Unit,
    onHangup: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** 停止看屏幕 / 关闭摄像头；为 null 时不显示「停止」。 */
    onStopLooking: (() -> Unit)? = null,
) {
    val palette = LocalYiiPalette.current
    val status = islandStatus(state, palette)
    val glow = status.color
    val shape = RoundedCornerShape(if (expanded) 28.dp else 26.dp)
    val surface = if (palette.isDark) Color(0xFF0D120C) else palette.surface
    val lastUser = messages.lastOrNull { it.role == Role.User && it.text.isNotBlank() }
    val lastAi = messages.lastOrNull { it.role == Role.Assistant && it.text.isNotBlank() }

    Column(
        modifier
            .clip(shape)
            .background(surface)
            .border(1.dp, glow.copy(alpha = if (state.phase == CallPhase.Live) 0.24f else 0.12f), shape)
            .clickable(enabled = !expanded, onClick = onToggle)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .padding(horizontal = if (expanded) 16.dp else 10.dp, vertical = if (expanded) 14.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeerMark(
                modifier = Modifier
                    .size(if (expanded) 46.dp else 36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape),
                glow = glow,
                withTile = true,
                eyes = deerEyesFor(state),
            )
            Spacer(Modifier.width(if (expanded) 12.dp else 9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        color = palette.text,
                        fontSize = if (expanded) 16.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (state.muted) {
                        Spacer(Modifier.width(6.dp))
                        Text("静音", color = palette.textDim, fontSize = 10.sp)
                    }
                }
                Text(
                    status.text,
                    color = status.color,
                    fontSize = if (expanded) 12.sp else 10.sp,
                    maxLines = 1,
                )
            }
            if (expanded) {
                Text(
                    "收起",
                    color = palette.textDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                LiveBars(
                    level = level,
                    color = glow,
                    active = state.phase == CallPhase.Live,
                    modifier = Modifier
                        .width(34.dp)
                        .height(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "⌄",
                    color = palette.textDim,
                    fontSize = 14.sp,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.surfaceRaised.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    lastUser?.let {
                        Text(
                            "你 · ${it.text}",
                            color = palette.textDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    lastAi?.let {
                        Text(
                            it.text,
                            color = palette.text,
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (lastUser == null && lastAi == null) {
                        Text("开口就好", color = palette.textDim, fontSize = 12.sp)
                    }
                }
                // 正在看屏幕或开着摄像头：说清楚在看什么、还剩多久，并且能当场停掉。
                if (state.watchingScreen || state.cameraActive) {
                    val camera = state.cameraActive
                    val left = secondsLeft(if (camera) state.cameraEndsAt else state.watchEndsAt)
                    Row(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background((if (camera) palette.danger else palette.orbUser).copy(alpha = 0.14f))
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                camera -> "${state.cameraFacing.ifBlank { "摄像头" }}开着 · 剩 ${clockText(left)}"
                                state.watchPaused -> "看屏幕暂停中 · 剩 ${clockText(left)}"
                                else -> "正在看屏幕 · 剩 ${clockText(left)}"
                            },
                            color = if (camera) palette.danger else palette.orbUser,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 9.dp),
                        )
                        if (onStopLooking != null) {
                            Text(
                                "停止",
                                color = palette.danger,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onStopLooking)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IslandButton(
                        label = if (state.muted) "取消静音" else "静音",
                        background = palette.surfaceRaised,
                        tint = palette.text,
                        modifier = Modifier.weight(1f),
                        onClick = onMute,
                    )
                    IslandButton(
                        label = "挂断",
                        background = palette.danger,
                        tint = Color.White,
                        modifier = Modifier.weight(1f),
                        onClick = onHangup,
                    )
                    IslandButton(
                        label = "打开",
                        background = glow,
                        tint = if (palette.isDark) Color(0xFF10140E) else Color.White,
                        modifier = Modifier.weight(1f),
                        onClick = onOpen,
                    )
                }
            }
        }
    }
}

private data class IslandStatus(val text: String, val color: Color)

private fun islandStatus(state: CallState, palette: YiiPalette): IslandStatus = when {
    state.phase == CallPhase.Failed -> IslandStatus("连接失败了，点开看看", palette.danger)
    state.phase == CallPhase.Reconnecting -> IslandStatus("正在接回对话…", palette.accent)
    state.phase == CallPhase.Connecting || state.phase == CallPhase.Configuring -> IslandStatus("正在点亮…", palette.orbHalo)
    state.muted -> IslandStatus("已静音", palette.textDim)
    state.assistantSpeaking -> IslandStatus("正在说", palette.orbAssistant)
    state.userSpeaking -> IslandStatus("在听你说", palette.orbUser)
    state.phase == CallPhase.Live -> IslandStatus("在听", palette.orbHalo)
    else -> IslandStatus("轻点展开", palette.textDim)
}

@Composable
private fun IslandButton(
    label: String,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LiveBars(level: Float, color: Color, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "island-bars")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "island-bars-clock",
    )
    Canvas(modifier) {
        val bars = 5
        val gap = size.width * 0.10f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val amplitude = if (active) max(0.10f, level.coerceIn(0f, 1f)) else 0.06f
        repeat(bars) { i ->
            val wave = 0.55f + 0.45f * sin(clock + i * 1.1f)
            val h = size.height * (0.18f + amplitude * 1.5f * wave).coerceIn(0.12f, 1f)
            drawRoundRect(
                color = color.copy(alpha = if (active) 0.90f else 0.35f),
                topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}


