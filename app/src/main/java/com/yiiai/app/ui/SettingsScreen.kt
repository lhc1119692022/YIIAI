package com.yiiai.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yiiai.app.live.LiveProtocol
import kotlinx.coroutines.launch
import com.yiiai.app.settings.Persona
import com.yiiai.app.settings.PersonaFields
import com.yiiai.app.settings.SettingsStore
import com.yiiai.app.settings.VOICE_LIBRARY_URL
import com.yiiai.app.settings.VoiceOption
import com.yiiai.app.ui.theme.LocalYiiPalette
import com.yiiai.app.ui.theme.ThemeMode

private enum class SettingsPage { Root, ApiKey, Voice, Persona, Memory, Appearance, Audio, PhoneUse, Storage, Log, About }

/** 仅 debug 用：把启动参数里的页面名映射成枚举。 */
fun settingsPageFromKey(key: String?): String? = when (key) {
    "api_key" -> "ApiKey"
    "voice" -> "Voice"
    "persona" -> "Persona"
    "memory" -> "Memory"
    "appearance" -> "Appearance"
    "audio" -> "Audio"
    "phone_use" -> "PhoneUse"
    "storage" -> "Storage"
    "log" -> "Log"
    "about" -> "About"
    else -> null
}

@Composable
fun SettingsScreen(
    store: SettingsStore,
    onBack: () -> Unit,
    initialPage: String? = null,
    logLines: kotlinx.coroutines.flow.StateFlow<List<String>>? = null,
    live: com.yiiai.app.live.LiveSession? = null,
) {
    var pageName by rememberSaveable { mutableStateOf(initialPage ?: SettingsPage.Root.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName } ?: SettingsPage.Root
    fun go(target: SettingsPage) { pageName = target.name }
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()

    // 从首页直达某个子页（比如角色胶囊里的“编辑/添加”）时，返回直接回首页。
    val directEntry = initialPage != null && initialPage != SettingsPage.Root.name
    fun back() { if (page == SettingsPage.Root || directEntry) onBack() else go(SettingsPage.Root) }
    BackHandler(enabled = page != SettingsPage.Root) { back() }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = palette.text)
            }
            Text(
                pageTitle(page),
                color = palette.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when (page) {
            SettingsPage.Root -> RootPage(settings, store, onOpen = { go(it) })
            SettingsPage.ApiKey -> ApiKeyPage(store)
            SettingsPage.Voice -> VoicePage(store)
            SettingsPage.Persona -> PersonaPage(store)
            SettingsPage.Memory -> MemoryPage(store)
            SettingsPage.Appearance -> AppearancePage(store)
            SettingsPage.Audio -> AudioPage(store)
            SettingsPage.PhoneUse -> PhoneUsePage()
            SettingsPage.Storage -> StoragePage(store, live)
            SettingsPage.Log -> LogPage(logLines)
            SettingsPage.About -> AboutPage(store)
        }
    }
}

private fun pageTitle(page: SettingsPage) = when (page) {
    SettingsPage.Root -> "设置"
    SettingsPage.ApiKey -> "API Key"
    SettingsPage.Voice -> "音色"
    SettingsPage.Persona -> "角色与人设"
    SettingsPage.Memory -> "长期记忆"
    SettingsPage.Appearance -> "外观"
    SettingsPage.Audio -> "通话音频"
    SettingsPage.PhoneUse -> "屏幕与应用控制"
    SettingsPage.Storage -> "存储与清理"
    SettingsPage.Log -> "日志"
    SettingsPage.About -> "关于"
}

@Composable
private fun RootPage(
    settings: com.yiiai.app.settings.AppSettings,
    store: SettingsStore,
    onOpen: (SettingsPage) -> Unit,
) {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    val theme = ThemeMode.of(settings.themeModeKey)

    // 三组：连接、角色、数据
    LazyColumn(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ── 第 1 组：连接
        item {
            GroupLabel("连接")
            SurfaceCard {
                RootRow(
                    title = "API Key",
                    summary = when {
                        settings.apiKey.isBlank() -> "未填写"
                        settings.apiKeyVerified -> "已连接"
                        else -> "已设置，未验证"
                    },
                    tone = when {
                        settings.apiKey.isBlank() -> RowTone.Danger
                        settings.apiKeyVerified -> RowTone.Success
                        else -> RowTone.Warning
                    },
                    onClick = { onOpen(SettingsPage.ApiKey) },
                    trailing = {
                        if (settings.apiKey.isNotBlank() && settings.apiKeyVerified) {
                            StatusDot(palette.positive)
                        }
                    },
                )
                RowDivider()
                RootRow(
                    title = "音色",
                    summary = settings.allVoices().firstOrNull { it.id == settings.voiceId }?.label ?: "未选择",
                    onClick = { onOpen(SettingsPage.Voice) },
                )
                RowDivider()
                RootRow(
                    title = "通话音频",
                    summary = if (settings.speakerOn) "外放" else "听筒",
                    onClick = { onOpen(SettingsPage.Audio) },
                )
            }
        }

        item { GroupGap() }

        // ── 第 2 组：角色
        item {
            GroupLabel("角色")
            SurfaceCard {
                RootRow(
                    title = "角色与人设",
                    summary = settings.personaName,
                    onClick = { onOpen(SettingsPage.Persona) },
                )
                RowDivider()
                // 记忆共享：内联滑块
                InlineSwitchRow(
                    title = "记忆共享",
                    detail = if (settings.sharedMemory) "所有角色共用一份记忆" else "每个角色各自独立记忆",
                    checked = settings.sharedMemory,
                    onToggle = { store.update { s -> s.copy(sharedMemory = it) } },
                )
                RowDivider()
                // 外观：内联三选一
                InlineThemeRow(
                    current = theme,
                    onPick = { store.update { s -> s.copy(themeModeKey = it.storageKey) } },
                )
            }
        }

        item { GroupGap() }

        // ── 第 3 组：数据与关于
        item {
            GroupLabel("数据")
            SurfaceCard {
                RootRow(
                    title = "存储与清理",
                    summary = "缓存与记录",
                    onClick = { onOpen(SettingsPage.Storage) },
                )
                RowDivider()
                RootRow(
                    title = "日志",
                    summary = "协议事件，排查用",
                    onClick = { onOpen(SettingsPage.Log) },
                )
                RowDivider()
                // 屏幕与应用控制：组内最后一项，内联滑块 + 说明
                InlineSwitchRow(
                    title = "屏幕与应用控制",
                    detail = "开启后能看到当前屏幕、帮你点按和输入。系统设置 → 无障碍 → 已下载的应用 → YIIAI。",
                    checked = com.yiiai.app.phone.PhoneUse.isReady(),
                    onToggle = { com.yiiai.app.phone.PhoneUse.openSettings(context) },
                    showChevron = false,
                )
                RowDivider()
                RootRow(
                    title = "关于",
                    summary = "",
                    onClick = { onOpen(SettingsPage.About) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private enum class RowTone { Normal, Success, Warning, Danger }

private fun RowTone.color(palette: com.yiiai.app.ui.theme.YiiPalette) = when (this) {
    RowTone.Normal -> palette.textDim
    RowTone.Success -> palette.positive
    RowTone.Warning -> palette.orbAssistant
    RowTone.Danger -> palette.danger
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        color = LocalYiiPalette.current.textDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun GroupGap() = Spacer(Modifier.height(16.dp))

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalYiiPalette.current.surface),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(LocalYiiPalette.current.divider.copy(alpha = 0.5f)),
    )
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun RootRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    tone: RowTone = RowTone.Normal,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = LocalYiiPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = palette.text, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        if (summary.isNotEmpty()) {
            Text(
                summary,
                color = tone.color(palette),
                fontSize = 13.sp,
                fontWeight = if (tone == RowTone.Success) FontWeight.Medium else FontWeight.Normal,
            )
        }
        trailing?.invoke()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.textDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 内联滑块行：标题 + 说明 + 滑块，不跳子页。 */
@Composable
private fun InlineSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    showChevron: Boolean = false,
) {
    val palette = LocalYiiPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = palette.text, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            CapsuleSwitch(checked = checked)
        }
        Text(
            detail,
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
        )
    }
}

/** 外观：横排三选一（系统 / 浅色 / 深色）。 */
@Composable
private fun InlineThemeRow(
    current: ThemeMode,
    onPick: (ThemeMode) -> Unit,
) {
    val palette = LocalYiiPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ThemeMode.SYSTEM to "系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色",
            ).forEach { (mode, label) ->
                val selected = mode == current
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) palette.accent else palette.surfaceRaised)
                        .clickable { onPick(mode) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) {
                            if (palette.isDark) androidx.compose.ui.graphics.Color(0xFF10140E)
                            else androidx.compose.ui.graphics.Color.White
                        } else {
                            palette.text
                        },
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun ApiKeyPage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    var key by rememberSaveable { mutableStateOf(store.current.apiKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // null = 还没验证过；true/false = 上次验证结果
    var status by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = store.current.apiKey
    val configured = saved.isNotBlank()
    val dirty = key.trim() != saved

    FieldPage {
        // 顶部状态：一颗彩色圆点 + 文字，比一行灰字清楚。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            checking -> palette.textDim
                            status == true -> palette.positive
                            status == false -> palette.danger
                            configured -> palette.positive
                            else -> palette.danger
                        },
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    checking -> "正在验证连接…"
                    status == true -> "已连接"
                    status == false -> "连接失败"
                    configured -> "已设置（未验证）"
                    else -> "未连接"
                },
                color = when {
                    checking -> palette.textDim
                    status == true || (status == null && configured) -> palette.positive
                    status == false -> palette.danger
                    else -> palette.danger
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                status = null
                message = null
            },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } },
            modifier = Modifier.fillMaxWidth(),
        )

        // 「连接」：验证通过才写入设置，失败不保存。
        PrimaryAction(
            when {
                checking -> "正在连接…"
                status == true && !dirty -> "已连接"
                else -> "连接"
            },
        ) {
            if (checking) return@PrimaryAction
            scope.launch {
                checking = true
                message = null
                when (val r = com.yiiai.app.settings.ApiKeyCheck.verify(key)) {
                    is com.yiiai.app.settings.ApiKeyCheck.Result.Ok -> {
                        store.update { it.copy(apiKey = key.trim()) }
                        status = true
                        message = "连接成功，已自动保存"
                    }
                    is com.yiiai.app.settings.ApiKeyCheck.Result.Invalid -> {
                        status = false
                        message = r.message
                    }
                    is com.yiiai.app.settings.ApiKeyCheck.Result.Network -> {
                        status = null
                        message = "连不上服务器：${r.message}。可以检查网络后重试。"
                    }
                }
                checking = false
            }
        }

        message?.let {
            Text(
                it,
                color = if (status == false) palette.danger else palette.textDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        LinkAction("去 platform.moss.cn 创建") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.moss.cn")))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Key 只保存在本机，用 Android Keystore 加密；只有连接成功才会保存。",
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun VoicePage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        settings.allVoices().forEach { v ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .clickable { store.update { it.copy(voiceId = v.id) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(v.label, color = palette.text, fontSize = 15.sp)
                val custom = com.yiiai.app.settings.Voices.isCustom(v.id)
                if (custom) {
                    Text("自定义", color = palette.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (custom) {
                    Text(
                        "删除",
                        color = palette.danger,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { deleteFor = v.id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                RadioButton(
                    selected = settings.voiceId == v.id,
                    onClick = { store.update { it.copy(voiceId = v.id) } },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction("添加音色 ID", Modifier.weight(1f)) { showAdd = true }
            SecondaryAction("音色库", Modifier.weight(1f)) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_LIBRARY_URL)))
            }
        }
        Text(
            "默认只内置暖暖和沐沐。其他音色可以在音色库里试听后按 ID 添加；克隆音色多数不能用于实时对话。",
            color = palette.textDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(16.dp))
    }

    deleteFor?.let { target ->
        val label = settings.allVoices().firstOrNull { it.id == target }?.label ?: target.take(8)
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除音色") },
            text = { Text(if (settings.voiceId == target) "删除「$label」后会切回暖暖。" else "确定删除「$label」？") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeCustomVoice(target)
                    deleteFor = null
                }) { Text("删除", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }

    if (showAdd) {
        var id by rememberSaveable { mutableStateOf("") }
        var checking by remember { mutableStateOf(false) }
        var found by remember { mutableStateOf<String?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun close() { showAdd = false }
        fun add(label: String) {
            store.addCustomVoice(label, id)
            store.update { it.copy(voiceId = id.trim()) }
            close()
        }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("添加音色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = id,
                        onValueChange = {
                            id = it
                            found = null
                            problem = null
                        },
                        label = { Text("音色 ID") },
                        placeholder = { Text("从音色库复制") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        checking -> Text("正在查询音色名称…", color = palette.textDim, fontSize = 13.sp)
                        found != null -> Text("找到音色：$found", color = palette.positive, fontSize = 13.sp)
                        problem != null -> Text(problem!!, color = palette.danger, fontSize = 13.sp)
                        else -> Text("名称会自动从音色库获取。", color = palette.textDim, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                val name = found
                TextButton(
                    enabled = id.isNotBlank() && !checking,
                    onClick = {
                        when {
                            name != null -> add(name)
                            problem != null -> add("")
                            else -> scope.launch {
                                checking = true
                                when (val r = com.yiiai.app.settings.VoiceLookup.lookup(settings.apiKey, id)) {
                                    is com.yiiai.app.settings.VoiceLookup.Result.Found -> found = r.name
                                    is com.yiiai.app.settings.VoiceLookup.Result.NotFound ->
                                        problem = "音色库里没查到这个 ID 的名称，可以检查一下 ID，或者直接添加。"
                                    is com.yiiai.app.settings.VoiceLookup.Result.Failed ->
                                        problem = "查询失败（${r.message}），可以稍后再试，或者直接添加。"
                                }
                                checking = false
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            name != null -> "添加「$name」"
                            problem != null -> "仍然添加"
                            else -> "查询并添加"
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text("取消") } },
        )
    }
}

@Composable
private fun PersonaPage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    val current = settings.activePersona
    // 切换角色时整页重置为该角色的已保存内容。
    var name by rememberSaveable(current.id) { mutableStateOf(current.name) }
    var prompt by rememberSaveable(current.id) { mutableStateOf(current.prompt) }
    var instructions by rememberSaveable(current.id) { mutableStateOf(current.instructions) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val dirty = name != current.name || prompt != current.prompt || instructions != current.instructions
    val maxPersona = LiveProtocol.MAX_PERSONA_CHARS
    val maxInstr = LiveProtocol.MAX_INSTRUCTION_CHARS

    FieldPage {
        // 角色切换条：和首页左上角的胶囊是同一份数据。
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            settings.personas.forEach { p ->
                val selected = p.id == current.id
                Text(
                    p.name.ifBlank { "未命名" },
                    color = if (selected) palette.onAccent() else palette.text,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) palette.accent else palette.surfaceRaised)
                        .clickable { store.setActivePersona(p.id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Text(
                "＋ 添加",
                color = palette.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable { store.addPersona() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("名字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it.take(maxPersona) },
            label = { Text("人设") },
            placeholder = { Text("写这个角色是谁、怎么说话、和你是什么关系。想到什么写什么，不用分段。") },
            minLines = 8,
            maxLines = 14,
            supportingText = { Text("${prompt.length} / $maxPersona 字") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it.take(maxInstr) },
            label = { Text("额外会话指令（可选）") },
            minLines = 2,
            supportingText = { Text("${instructions.length} / $maxInstr 字") },
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction(if (dirty) "保存" else "已保存") {
            store.savePersona(
                current.copy(
                    name = name.trim().ifBlank { PersonaFields.DEFAULT_NAME },
                    prompt = prompt,
                    instructions = instructions,
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                name = PersonaFields.DEFAULT_NAME
                prompt = Persona.DEFAULT_PROMPT
            }) { Text("恢复默认预设") }
            if (settings.personas.size > 1) {
                TextButton(onClick = { confirmDelete = true }) { Text("删除这个角色", color = palette.danger) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "字数上限（MOSS-Live 协议规定）：\n" +
                "· 人设：${prompt.length} / $maxPersona 字。作为 persona.prompt 发送，超出部分会被截掉。\n" +
                "· 额外会话指令：${instructions.length} / $maxInstr 字。作为 session.instructions 单独发送，" +
                "和通话中追加的指令共用这 $maxInstr 字的累计额度。\n" +
                "· 改动在下一次接通时生效。",
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除角色") },
            text = { Text("删除「${current.name}」？它的人设和指令都会一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deletePersona(current.id)
                    confirmDelete = false
                }) { Text("删除", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun LogPage(lines: kotlinx.coroutines.flow.StateFlow<List<String>>?) {
    val palette = LocalYiiPalette.current
    val log by (lines ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsStateWithLifecycle()
    if (log.isEmpty()) {
        Text(
            "还没有日志。开始一次通话后，这里会显示协议事件。",
            color = palette.textDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .navigationBarsPadding(),
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(log.asReversed()) { line ->
            Text(
                line,
                color = palette.textDim,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun MemoryPage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("所有角色共享长期记忆", color = palette.text, fontSize = 15.sp)
                Text(
                    if (settings.sharedMemory) "现在：共用一份记忆，换角色也记得你" else "现在：每个角色独立记忆",
                    color = palette.textDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = settings.sharedMemory,
                onCheckedChange = { on -> store.update { it.copy(sharedMemory = on) } },
            )
        }
        Text(
            "关闭后，记忆按角色名分开保存；切换角色时新的角色不会知道你和别的角色聊过什么。",
            color = palette.textDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AppearancePage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeMode.all.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .clickable { store.update { it.copy(themeModeKey = mode.storageKey) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mode.label, color = palette.text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                RadioButton(
                    selected = settings.themeModeKey == mode.storageKey,
                    onClick = { store.update { it.copy(themeModeKey = mode.storageKey) } },
                )
            }
        }
    }
}

@Composable
private fun AudioPage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("默认外放", color = palette.text, fontSize = 15.sp)
                Text("接耳机时自动走耳机", color = palette.textDim, fontSize = 12.sp)
            }
            Switch(
                checked = settings.speakerOn,
                onCheckedChange = { on -> store.update { it.copy(speakerOn = on) } },
            )
        }
        listOf(24_000 to "24 kHz（省流量）", 48_000 to "48 kHz（最佳音质）").forEach { (rate, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .clickable { store.update { it.copy(outputSampleRate = rate) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = palette.text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                RadioButton(
                    selected = settings.outputSampleRate == rate,
                    onClick = { store.update { it.copy(outputSampleRate = rate) } },
                )
            }
        }
    }
}

@Composable
private fun AboutPage(store: SettingsStore) {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    val settings by store.state.collectAsStateWithLifecycleCompat()
    FieldPage {
        UpdateSection(Modifier.padding(vertical = 6.dp))
        InfoRow("模型", "MOSS-Live 1.0（moss-live-1.0）")
        InfoRow("设备 ID", settings.memoryUser().takeLast(12))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            LinkAction("音色库") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_LIBRARY_URL)))
            }
            LinkAction("MOSS 开放平台") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.moss.cn")))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = LocalYiiPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = palette.text, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = palette.textDim, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    val palette = LocalYiiPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.onAccent(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalYiiPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.text, fontSize = 14.sp)
    }
}

@Composable
private fun LinkAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = LocalYiiPalette.current.accent,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

/** 小工具：把 StateFlow 读成 State。 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat(): androidx.compose.runtime.State<T> =
    this.collectAsStateWithLifecycle()

/** 强调色上的文字：深色主题的强调色是亮黄绿，要配深色字。 */
private fun com.yiiai.app.ui.theme.YiiPalette.onAccent(): androidx.compose.ui.graphics.Color =
    if (isDark) androidx.compose.ui.graphics.Color(0xFF10140E) else androidx.compose.ui.graphics.Color.White
/** 屏幕读取与应用控制：无障碍服务的引导页。 */
@Composable
private fun PhoneUsePage() {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    // 从系统设置回来时刷新一次状态。
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val ready = com.yiiai.app.phone.PhoneUse.isReady()
    val enabled = remember(tick) { com.yiiai.app.phone.PhoneUse.isEnabled(context) }

    FieldPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("服务状态", color = palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    ready -> "已开启"
                    enabled -> "已勾选，等待系统连接"
                    else -> "未开启"
                },
                color = if (ready) palette.positive else palette.textDim,
                fontSize = 13.sp,
            )
        }
        PrimaryAction(if (ready) "重新打开无障碍设置" else "去开启无障碍服务") {
            com.yiiai.app.phone.PhoneUse.openSettings(context)
        }
        Text(
            "YIIAI需要无障碍权限才能看到你的屏幕、帮你点击和输入。" +
                "开启方式：系统设置 → 无障碍 → 已下载的应用 → YIIAI → 打开。",
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "能做什么：\n" +
                "· 看一眼：截一张当前屏幕发给模型，用来聊「这件衣服怎么样」这类问题；\n" +
                "· 读界面：拿到当前页面的文字和按钮；\n" +
                "· 帮你操作：点按、滑动、输入文字、打开应用、返回或回桌面。",
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "要注意：\n" +
                "· 只有你说话或主动要求时才会截图，截图只用于当次对话，不会保存；\n" +
                "· 支付、银行和部分视频 App 禁止截屏，会返回黑图；\n" +
                "· 系统有时会自动关掉无障碍服务，发现用不了就回来重新打开。",
            color = palette.textDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/** 存储与清理：缓存、日志、对话记录。 */
@Composable
private fun StoragePage(store: SettingsStore, live: com.yiiai.app.live.LiveSession?) {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    val messages by (live?.messages?.collectAsStateWithLifecycleCompat()
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<com.yiiai.app.live.ChatMessage>()) })
    val logLines by (live?.log?.collectAsStateWithLifecycleCompat()
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<String>()) })
    var tick by remember { mutableStateOf(0) }
    val cacheBytes = remember(tick) { dirSize(context.cacheDir) + dirSize(context.externalCacheDir) }
    var confirm by remember { mutableStateOf<String?>(null) }

    FieldPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("缓存文件", color = palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(formatSize(cacheBytes), color = palette.textDim, fontSize = 13.sp)
        }
        Text(
            "截图、临时音频都放在缓存里；系统空间紧张时也会自动清理。",
            color = palette.textDim,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("对话记录", color = palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${messages.size} 条", color = palette.textDim, fontSize = 13.sp)
        }
        Text(
            "只保留最近 200 条，再早的会自动丢弃；" +
                "记录只在本次运行内有效，退出 App 就清空。",
            color = palette.textDim,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("运行日志", color = palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${logLines.size} 条", color = palette.textDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryAction("清理缓存") {
            confirm = "cache"
        }
        SecondaryAction("清空对话记录") { confirm = "messages" }
        SecondaryAction("清空运行日志") { confirm = "log" }
        Text(
            "清理不会动你的设置、角色和 API Key。",
            color = palette.textDim,
            fontSize = 12.sp,
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when (what) { "cache" -> "清理缓存"; "messages" -> "清空对话记录"; else -> "清空运行日志" }) },
            text = {
                Text(
                    when (what) {
                        "cache" -> "删除缓存文件（${formatSize(cacheBytes)}）？"
                        "messages" -> "清空当前对话记录？通话中的字幕会一起消失。"
                        else -> "清空运行日志？"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (what) {
                        "cache" -> {
                            context.cacheDir?.deleteRecursively()
                            context.externalCacheDir?.deleteRecursively()
                        }
                        "messages" -> live?.clearMessages()
                        else -> live?.clearLog()
                    }
                    confirm = null
                    tick++
                }) { Text("清理", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

private fun dirSize(dir: java.io.File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 普通按钮（非主色），用于清理类动作。 */
@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    val palette = LocalYiiPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.text, fontSize = 14.sp)
    }
}

/**
 * 胶囊滑块：只表示开关状态，不自己切换（点整行进子页）。
 * 关：灰色轨道、左边圆点；开：强调色轨道、右边圆点。
 */
@Composable
private fun CapsuleSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalYiiPalette.current
    val track by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) palette.accent else palette.surfaceRaised,
        label = "capsule-track",
    )
    val knob by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f),
        label = "capsule-knob",
    )
    Box(
        modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        if (palette.isDark) androidx.compose.ui.graphics.Color(0xFF10140E) else androidx.compose.ui.graphics.Color.White
                    } else {
                        palette.textDim
                    },
                ),
        )
    }
}
