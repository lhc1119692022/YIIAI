package com.yiiai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.yiiai.app.live.LiveSession
import com.yiiai.app.settings.SettingsStore

@Composable
fun YiiRoot(
    live: LiveSession,
    settingsStore: SettingsStore,
    onStartCall: () -> Unit,
    onToggleOverlay: () -> Unit = {},
    startInSettings: Boolean = false,
    initialSettingsPage: String? = null,
    designLab: Boolean = false,
    designLabPage: Int = 0,
) {
    if (designLab) {
        DesignLabScreen(initialPage = designLabPage)
        return
    }
    // null = 首页；否则为设置页要打开的子页面名（"Root" 为设置首页）。
    var settingsPage by rememberSaveable {
        mutableStateOf(if (startInSettings) initialSettingsPage ?: "Root" else null)
    }
    val page = settingsPage
    if (page != null) {
        BackHandler { settingsPage = null }
        SettingsScreen(
            store = settingsStore,
            onBack = { settingsPage = null },
            initialPage = page,
            logLines = live.log,
            live = live,
        )
    } else {
        CallScreen(
            live = live,
            settingsStore = settingsStore,
            onStartCall = onStartCall,
            onToggleOverlay = onToggleOverlay,
            onOpenSettings = { settingsPage = "Root" },
            onEditPersona = { settingsPage = "Persona" },
            onAddPersona = {
                settingsStore.addPersona()
                settingsPage = "Persona"
            },
            onOpenPhoneUse = { settingsPage = "PhoneUse" },
        )
    }
}
