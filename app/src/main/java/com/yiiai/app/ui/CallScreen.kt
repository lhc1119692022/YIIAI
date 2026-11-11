package com.yiiai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.Animatable
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.CallState
import com.yiiai.app.live.ChatMessage
import com.yiiai.app.live.LiveSession
import com.yiiai.app.live.Role
import com.yiiai.app.live.isActive
import com.yiiai.app.settings.SettingsStore
import com.yiiai.app.ui.theme.LocalYiiPalette

@Composable
fun CallScreen(
    live: LiveSession,
    settingsStore: SettingsStore,
    onStartCall: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleOverlay: () -> Unit = {},
    onEditPersona: () -> Unit = {},
    onAddPersona: () -> Unit = {},
    onOpenPhoneUse: () -> Unit = {},
) {
    val palette = LocalYiiPalette.current
    val state by live.state.collectAsStateWithLifecycle()
    val messages by live.messages.collectAsStateWithLifecycle()
    val levels by live.levels.collectAsStateWithLifecycle()
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    val overlayMode by com.yiiai.app.overlay.OverlayService.mode.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // 曾经开通过、现在被系统关掉了。
    var a11yTick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { a11yTick++; onPauseOrDispose { } }
    val ctx = LocalContext.current
    val a11yLost = remember(a11yTick, settings.phoneUseEverReady) {
        com.yiiai.app.phone.PhoneUse.wasDisabled(ctx, settings.phoneUseEverReady)
    }
    val inCall = state.phase.isActive
    val assistantName = settings.personaName.ifBlank { "YIIAI" }

    val orbMode = when {
        state.phase == CallPhase.Failed -> OrbMode.Error
        state.phase == CallPhase.Reconnecting -> OrbMode.Connecting
        state.phase == CallPhase.Connecting || state.phase == CallPhase.Configuring -> OrbMode.Connecting
        state.phase == CallPhase.Live && state.muted -> OrbMode.Muted
        state.phase == CallPhase.Live && state.assistantSpeaking -> OrbMode.AssistantSpeaking
        state.phase == CallPhase.Live && state.userSpeaking -> OrbMode.UserSpeaking
        state.phase == CallPhase.Live -> OrbMode.Listening
        else -> OrbMode.Idle
    }
    val orbLevel = if (state.assistantSpeaking) levels.assistant else levels.user

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(72.dp))
            Spacer(Modifier.weight(1f))

            VoiceOrb(
                mode = orbMode,
                level = orbLevel,
                sizeDp = 300,
                onTap = {
                    when {
                        !inCall -> onStartCall()
                        state.phase == CallPhase.Failed -> live.dismissError()
                        state.phase == CallPhase.Live -> live.toggleMute()
                        else -> Unit
                    }
                },
                onLongPress = { if (inCall) live.hangup() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusChip(orbMode, hintFor(state, assistantName))
                state.notice?.let {
                    Text(
                        it,
                        color = palette.textDim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = messages.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LastLines(messages, assistantName, onOpen = { showHistory = true })
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp, start = 20.dp, end = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (inCall) {
                    Text(
                        "轻点小鹿静音 · 长按挂断",
                        color = palette.textDim.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                } else if (settings.apiKey.isBlank()) {
                    Text(
                        "还没有填写 MOSS API Key，点这里去设置",
                        color = palette.accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 无障碍被系统关掉时，在顶部提醒一次（不挡操作，点一下直接去开）。
        if (a11yLost) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.danger.copy(alpha = 0.14f))
                    .clickable(onClick = onOpenPhoneUse)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "无障碍已关闭 · YIIAI看不到屏幕了，点这里重新开启",
                    color = palette.danger,
                    fontSize = 12.sp,
                )
            }
        }

        // 左上：当前角色胶囊，点开切换 / 添加 / 编辑。
        PersonaCapsule(
            settings = settings,
            inCall = inCall,
            eyes = orbMode.toDeerEyes(),
            onSelect = { settingsStore.setActivePersona(it) },
            onAdd = onAddPersona,
            onEdit = onEditPersona,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 12.dp),
        )

        // 右上：设置与悬浮，竖排两个按钮。悬浮只在通话中出现。
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SideButton(
                label = "设置",
                tint = palette.text,
                onClick = onOpenSettings,
            ) { tint ->
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = tint, modifier = Modifier.size(28.dp))
            }
            AnimatedVisibility(
                visible = inCall,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                val floating = overlayMode == com.yiiai.app.overlay.OverlayMode.Bubble
                SideButton(
                    label = if (floating) "悬浮中" else "悬浮",
                    tint = if (floating) palette.orbHalo else palette.text,
                    onClick = onToggleOverlay,
                    active = floating,
                ) { tint ->
                    Icon(
                        androidx.compose.ui.res.painterResource(
                            if (floating) com.yiiai.app.R.drawable.ic_float_window_on else com.yiiai.app.R.drawable.ic_float_window,
                        ),
                        contentDescription = if (floating) "关闭悬浮球" else "开启悬浮球",
                        tint = tint,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
    if (showHistory) {
        HistorySheet(messages, assistantName, onClose = { showHistory = false })
    }
}

/** 以图标为主体、下面一行小字说明的按钮，没有底板。激活时图标下多一个状态点。 */
@Composable
private fun SideButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    active: Boolean = false,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
    val animatedTint by androidx.compose.animation.animateColorAsState(tint, label = "side-tint")
    Column(
        Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(animatedTint)
        Text(
            label,
            color = animatedTint.copy(alpha = if (active) 1f else 0.7f),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
/** 当前角色胶囊：头像 + 名字 + ▾，点开是角色菜单。 */
@Composable
private fun PersonaCapsule(
    settings: com.yiiai.app.settings.AppSettings,
    inCall: Boolean,
    eyes: DeerEyes,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYiiPalette.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.surfaceRaised)
                .clickable { open = true }
                .padding(start = 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeerMark(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                withTile = true,
                eyes = eyes,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    settings.personaName.ifBlank { "YIIAI" },
                    color = palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Text(
                    if (settings.personas.size > 1) "${settings.personas.size} 个角色 · 点按切换" else "点按切换或添加角色",
                    color = palette.textDim,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("▾", color = palette.textDim, fontSize = 14.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = palette.surface,
        ) {
            if (inCall) {
                Text(
                    "切换后，下次接通时生效",
                    color = palette.textDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            settings.personas.forEach { p ->
                val selected = p.id == settings.activePersonaId
                DropdownMenuItem(
                    text = {
                        Text(
                            p.name.ifBlank { "未命名" },
                            color = if (selected) palette.accent else palette.text,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = { if (selected) Text("✓", color = palette.accent) },
                    onClick = {
                        onSelect(p.id)
                        open = false
                    },
                )
            }
            HorizontalDivider(color = palette.divider, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("＋ 添加角色", color = palette.accent) },
                onClick = {
                    open = false
                    onAdd()
                },
            )
            DropdownMenuItem(
                text = { Text("编辑当前角色", color = palette.text) },
                onClick = {
                    open = false
                    onEdit()
                },
            )
        }
    }
}

/** 首页状态：一颗会呼吸的状态点 + 文字，整块用状态色着色，开始之后一眼能看出在干什么。 */
@Composable
private fun StatusChip(mode: OrbMode, text: String) {
    val palette = LocalYiiPalette.current
    val color = when (mode) {
        OrbMode.Idle -> palette.textDim
        OrbMode.Connecting -> palette.orbHalo
        OrbMode.Listening, OrbMode.UserSpeaking -> palette.orbUser
        OrbMode.AssistantSpeaking -> palette.orbAssistant
        OrbMode.Muted -> palette.textDim
        OrbMode.Error -> palette.danger
    }
    val animated by androidx.compose.animation.animateColorAsState(color, label = "status-color")
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "status")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(if (mode == OrbMode.Connecting) 500 else 900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "status-pulse",
        )
    val live = mode != OrbMode.Idle && mode != OrbMode.Muted
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(animated.copy(alpha = if (mode == OrbMode.Idle) 0.08f else 0.16f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animated.copy(alpha = if (live) pulse else 0.6f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = if (mode == OrbMode.Idle) palette.textDim else animated,
            fontSize = 14.sp,
            fontWeight = if (live) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/** 最近一句用户话 + 最近一句 AI 话，两行封顶。 */
@Composable
private fun LastLines(messages: List<ChatMessage>, assistantName: String, onOpen: () -> Unit) {
    val palette = LocalYiiPalette.current
    val lastUser = messages.lastOrNull { it.role == Role.User }
    val lastAi = messages.lastOrNull { it.role == Role.Assistant }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lastUser?.takeIf { it.text.isNotBlank() }?.let {
            Text(
                it.text,
                color = palette.textDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        lastAi?.takeIf { it.text.isNotBlank() }?.let {
            Text(
                it.text,
                color = palette.text,
                fontSize = 15.sp,
                fontStyle = if (it.final) FontStyle.Normal else FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun hintFor(state: CallState, name: String): String = when (state.phase) {
    CallPhase.Idle -> "轻点小鹿，和 $name 说话"
    CallPhase.Connecting -> "正在连接…"
    CallPhase.Configuring -> "正在准备会话…"
    CallPhase.Reconnecting -> "网络断了，正在接回去…"
    CallPhase.Failed -> "没有连上，轻点重试"
    CallPhase.Live -> when {
        state.muted -> "已静音，轻点取消"
        state.assistantSpeaking -> "$name 正在说，直接开口就能打断"
        state.userSpeaking -> "在听"
        else -> "在听你说"
    }
}

@Composable
private fun LogPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val palette = LocalYiiPalette.current
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(10.dp),
    ) {
        items(lines.asReversed()) { line ->
            Text(
                line,
                color = palette.textDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun HistorySheet(messages: List<ChatMessage>, assistantName: String, onClose: () -> Unit) {
    val palette = LocalYiiPalette.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background.copy(alpha = 0.97f))
            .statusBarsPadding()
            .clickable(onClick = onClose),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("本次对话", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "收起",
                    color = palette.accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m ->
                    if (m.role == Role.System) {
                        Text(
                            m.text,
                            color = palette.textDim,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val isUser = m.role == Role.User
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        ) {
                            Column(
                                Modifier
                                    .widthIn(max = 290.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isUser) palette.userBubble else palette.aiBubble)
                                    .padding(horizontal = 13.dp, vertical = 9.dp),
                            ) {
                                if (!isUser) {
                                    Text(
                                        assistantName,
                                        color = palette.textDim,
                                        fontSize = 10.sp,
                                    )
                                }
                                Text(
                                    m.text.ifBlank { "…" },
                                    color = palette.text.copy(alpha = if (m.final) 1f else 0.65f),
                                    fontSize = 14.sp,
                                    fontStyle = if (m.final) FontStyle.Normal else FontStyle.Italic,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


