package com.yiiai.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiiai.app.R
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.CallState
import com.yiiai.app.live.ChatMessage
import com.yiiai.app.live.Role
import com.yiiai.app.ui.theme.LocalYiiPalette
import com.yiiai.app.ui.theme.YiiPalette

/**
 * 仅 debug 构建使用的设计预览页：
 * 启动参数 `--ez design_lab true --es theme_mode dark` 可直接看小鹿的全部状态与灵动岛。
 */
@Composable
fun DesignLabScreen(initialPage: Int = 0) {
    val palette = LocalYiiPalette.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    var islandExpanded by remember { mutableStateOf(false) }
    val demoMessages = remember {
        listOf(
            ChatMessage("u1", Role.User, "今天下班好累，不想讲道理。"),
            ChatMessage("a1", Role.Assistant, "那先不讲道理。跟我说说，今天最烦的那件事是什么？"),
        )
    }
    val liveState = remember { CallState(phase = CallPhase.Live, userSpeaking = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 20.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp)) {
                Text("YIIAI", color = palette.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(
                    "设计实验室 · 小鹿",
                    color = palette.textDim,
                    fontSize = 12.sp,
                )
            }
        }

        item {
            LabSection("APP ICON / 桌面图标") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    listOf(96, 72, 56, 40).forEach { size ->
                        IconTile(size)
                    }
                }
            }
        }

        item {
            LabSection("MASCOT / 状态") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        OrbMode.Idle to "待命",
                        OrbMode.Connecting to "连接",
                        OrbMode.Listening to "聆听",
                        OrbMode.UserSpeaking to "你在说",
                    ).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { (mode, label) ->
                                MascotCard(mode, label, Modifier.weight(1f))
                            }
                        }
                    }
                    listOf(
                        OrbMode.AssistantSpeaking to "小鹿在说",
                        OrbMode.Muted to "静音",
                        OrbMode.Error to "出错",
                    ).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { (mode, label) ->
                                MascotCard(mode, label, Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            LabSection("FLOATING / 悬浮球") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF131B12), Color(0xFF24301C)),
                            ),
                        ),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .fillMaxWidth(0.92f),
                    ) {
                        YiiIsland(
                            name = "YIIAI",
                            state = liveState,
                            messages = demoMessages,
                            level = 0.5f,
                            expanded = false,
                            onToggle = {},
                            onMute = {},
                            onHangup = {},
                            onOpen = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 10.dp, bottom = 4.dp)
                            .size(112.dp),
                    ) {
                        DeerFloat(
                            eyes = DeerEyes.Closed,
                            glow = palette.orbHalo,
                            level = 0f,
                            energy = 1f,
                            earMood = 0f,
                            faceRight = false,
                            lean = 0f,
                            scale = 1f,
                            idle = true,
                        )
                    }
                }
            }
        }

        item {
            // 悬浮小鹿没人说话时的小动作：每格固定在动作中段，方便对照；最上面一行是会动的实况。
            LabSection("FLOATING IDLE / 悬浮小动作") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IdleAct.entries.chunked(3).forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { col, act ->
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(18.dp))
                                        // 一半放在浅色底、一半放在深色底上，确认两种背景都看得清。
                                        .background(if ((rowIndex + col) % 2 == 0) Color(0xFFFFFFFF) else Color(0xFF20261D)),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.size(104.dp)) {
                                        DeerFloat(
                                            eyes = DeerEyes.Closed,
                                            glow = palette.orbHalo,
                                            level = 0f,
                                            energy = 1f,
                                            earMood = 0f,
                                            faceRight = col == 2,
                                            lean = 0f,
                                            scale = 1f,
                                                            idle = false,
                                            preview = act to 0.58f,
                                        )
                                    }
                                    Text(
                                        act.label,
                                        color = if ((rowIndex + col) % 2 == 0) Color(0xFF7D8473) else Color(0xFF99A18E),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            LabSection("DYNAMIC ISLAND / 灵动岛") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    YiiIsland(
                        name = "YIIAI",
                        state = liveState,
                        messages = demoMessages,
                        level = 0.62f,
                        expanded = islandExpanded,
                        onToggle = { islandExpanded = !islandExpanded },
                        onMute = {},
                        onHangup = {},
                        onOpen = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (islandExpanded) "展开态：字幕 + 三个动作" else "收起态：轻点展开",
                        color = palette.textDim,
                        fontSize = 11.sp,
                    )
                    Text("展开态 / 固定示例", color = palette.textDim, fontSize = 10.sp)
                    YiiIsland(
                        name = "YIIAI",
                        state = liveState,
                        messages = demoMessages,
                        level = 0.4f,
                        expanded = true,
                        onToggle = {},
                        onMute = {},
                        onHangup = {},
                        onOpen = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            LabSection("PALETTE / 色板") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatch("墨", palette.background, Modifier.weight(1f))
                        Swatch("纸", palette.text, Modifier.weight(1f))
                        Swatch("苔光", palette.orbHalo, Modifier.weight(1f))
                        Swatch("暖橙", palette.orbAssistant, Modifier.weight(1f))
                        Swatch("湖绿", palette.orbUser, Modifier.weight(1f))
                    }
                }
            }
        }

        item { Spacer(Modifier.size(40.dp)) }
    }
}

@Composable
private fun LabSection(title: String, content: @Composable () -> Unit) {
    val palette = LocalYiiPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = palette.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun IconTile(sizeDp: Int) {
    val palette = LocalYiiPalette.current
    val radius = (sizeDp * 0.28f).dp
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(radius))
            .background(DeerColors.tile),
        contentAlignment = Alignment.Center,
    ) {
        // 自适应图标前景是 108dp，桌面只露中间 72dp，这里按同样比例放大预览。
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize((sizeDp * 1.5f).dp),
        )
    }
}

@Composable
private fun MascotCard(mode: OrbMode, label: String, modifier: Modifier = Modifier) {
    val palette = LocalYiiPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(palette.surface)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(104.dp)) {
            DeerMascot(mode = mode, level = 0.6f, modifier = Modifier.fillMaxSize(), compact = true)
        }
        Text(label, color = palette.textDim, fontSize = 11.sp)
    }
}

@Composable
private fun Swatch(label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(label, color = LocalYiiPalette.current.textDim, fontSize = 10.sp)
    }
}



