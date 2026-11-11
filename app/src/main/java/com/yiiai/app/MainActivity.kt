package com.yiiai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yiiai.app.call.LiveCallService
import com.yiiai.app.overlay.OverlayService
import com.yiiai.app.settings.SettingsStore
import com.yiiai.app.live.isActive
import com.yiiai.app.ui.PermissionSetupScreen
import com.yiiai.app.ui.YiiRoot
import com.yiiai.app.ui.settingsPageFromKey
import com.yiiai.app.ui.theme.ThemeMode
import com.yiiai.app.ui.theme.YiiTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as YiiApp

    /**
     * 仅 debug 构建的验收开关，都在 `BuildConfig.DEBUG` 后面，release 包不会生效：
     * - `--ez open_settings true` 直接打开设置页
     * - `--es settings_page voice|persona|...` 直达设置子页
     * - `--es set_api_key <key>` 写入 API Key
     * - `--ez auto_start true` 启动后自动开始通话
     * - `--es theme_mode light|dark` 临时覆盖主题
     * - `--ez show_overlay true` 通话中自动开启悬浮窗
     */
    /**
     * 调试参数只在「这一次」从命令行启动时生效：
     * 从最近任务或桌面图标回到同一个 Activity 时，intent 还是旧的那份，
     * 之前把 theme_mode 当成常驻覆盖，导致浅色模式被锁成深色。现在只读一次，离开界面就清掉。
     */
    private var debugIntent: android.content.Intent? = null
    private val debugExtras: android.content.Intent? get() = debugIntent

    /** 调试用的临时主题覆盖；用户离开界面或手动改主题后失效。 */
    private val themeOverride = androidx.compose.runtime.mutableStateOf<ThemeMode?>(null)

    private val startInSettings: Boolean
        get() = debugExtras?.getBooleanExtra("open_settings", false) == true

    private val initialSettingsPage: String?
        get() = settingsPageFromKey(debugExtras?.getStringExtra("settings_page"))


    private val debugShowOverlay: Boolean
        get() = debugExtras?.getBooleanExtra("show_overlay", false) == true

    private val debugDesignLab: Boolean
        get() = debugExtras?.getBooleanExtra("design_lab", false) == true

    private val debugDesignPage: Int
        get() = debugExtras?.getIntExtra("design_page", 0) ?: 0

    /** 刚刚主动开了悬浮球，这次退后台不要再叠一个灵动岛。 */
    private var bubbleJustRequested = false

    /** 待授予麦克风后要执行的动作。 */
    private var afterMicGranted: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val action = afterMicGranted
            afterMicGranted = null
            action?.invoke()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音对话", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fromHistory = intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        debugIntent = if (BuildConfig.DEBUG && savedInstanceState == null && !fromHistory) intent else null
        themeOverride.value = debugExtras?.getStringExtra("theme_mode")?.let { ThemeMode.of(it) }
        if (BuildConfig.DEBUG) {
            // 设计验收用：锁屏状态下也能把预览页亮出来截图。
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        debugExtras?.getStringExtra("set_api_key")?.let { key ->
            if (key.isNotBlank() && savedInstanceState == null) {
                app.settings.update { it.copy(apiKey = key.trim()) }
            }
        }
        debugExtras?.getStringExtra("probe_phone")?.let { what ->
            // 调试：无障碍自检（read / shot / tap / key）。
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1500)
                val svc = com.yiiai.app.phone.PhoneUseService.instance
                android.util.Log.i("YIIAI.PhoneUse", "ready=${svc != null} enabled=${com.yiiai.app.phone.PhoneUse.isEnabled(this@MainActivity)}")
                if (svc != null) {
                    when (what) {
                        "read" -> android.util.Log.i("YIIAI.PhoneUse", "UI:\n" + svc.dumpUi())
                        "shot" -> {
                            val (bytes, why) = svc.screenshot()
                            android.util.Log.i("YIIAI.PhoneUse", "shot=${bytes?.size ?: -1} $why")
                        }
                        "tap" -> android.util.Log.i("YIIAI.PhoneUse", "tap=" + svc.tap(720f, 1600f))
                        "key" -> android.util.Log.i("YIIAI.PhoneUse", "back=" + svc.pressBack())
                    }
                }
            }
        }
        debugExtras?.getStringExtra("probe_voice")?.let { id ->
            // 调试：用已保存的 Key 查一次音色名，结果和原始响应都打到 logcat（YIIAI.Voice）。
            lifecycleScope.launch {
                val r = com.yiiai.app.settings.VoiceLookup.lookup(app.settings.current.apiKey, id)
                android.util.Log.i("YIIAI.Voice", "probe $id -> $r")
            }
        }
        val autoStart = debugExtras?.getBooleanExtra("auto_start", false) == true && savedInstanceState == null
        requestNotificationPermissionIfNeeded()

        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val override by themeOverride
            // 用户在外观页改了主题，就放弃调试覆盖。
            val savedTheme = settings.themeModeKey
            val firstTheme = androidx.compose.runtime.remember { savedTheme }
            if (savedTheme != firstTheme && override != null) themeOverride.value = null
            val themeMode = override ?: ThemeMode.of(savedTheme)
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !themeMode.resolvesToDark(view.context)
                androidx.core.view.WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightNavigationBars = !themeMode.resolvesToDark(view.context)
            }
            YiiTheme(mode = themeMode) {
                val state by app.live.state.collectAsStateWithLifecycle()
                val inCall = state.phase.isActive
                LaunchedEffect(inCall) {
                    // 前台服务保活后不再强制亮屏；只在界面可见期间保持屏幕常亮。
                    if (inCall) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                LaunchedEffect(autoStart) {
                    if (autoStart) startCall()
                }
                LaunchedEffect(inCall, debugShowOverlay) {
                    if (debugShowOverlay && inCall && OverlayService.canDraw(this@MainActivity)) {
                        OverlayService.show(this@MainActivity)
                    }
                }
                // 首次启动：先走一遍权限引导，走完才进主界面。
                var showSetup by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(
                        !app.settings.current.onboardingDone && !startInSettings && !debugDesignLab,
                    )
                }
                if (showSetup) {
                    PermissionSetupScreen(
                        onMicRequest = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onNotificationRequest = { requestNotificationPermissionIfNeeded() },
                        onDone = {
                            app.settings.update { it.copy(onboardingDone = true) }
                            showSetup = false
                        },
                    )
                } else {
                    YiiRoot(
                        live = app.live,
                        settingsStore = app.settings,
                        onStartCall = ::startCall,
                        onToggleOverlay = ::toggleOverlay,
                        startInSettings = startInSettings,
                        initialSettingsPage = initialSettingsPage,
                        designLab = debugDesignLab,
                        designLabPage = debugDesignPage,
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        // 回到界面：自动出现的灵动岛收起；用户主动开的悬浮球保留。
        if (OverlayService.mode.value == com.yiiai.app.overlay.OverlayMode.Island) OverlayService.hide(this)
    }

    override fun onStop() {
        super.onStop()
        themeOverride.value = null
        debugIntent = null
        // 后台常驻改由系统超级岛承担（见 CallNotification 的 Live Update），不再自己画顶部胶囊。
        bubbleJustRequested = false
    }

    override fun onDestroy() {
        // 通话由前台服务托管，退出界面不再挂断。
        super.onDestroy()
    }

    /** 开始通话：先起前台服务（麦克风类型必须由前台服务承载），再建立连接。 */
    private fun startCall() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            LiveCallService.start(this, startCall = true)
        } else {
            afterMicGranted = { LiveCallService.start(this, startCall = true) }
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 开/关悬浮窗。没有权限时跳到系统设置页。 */
    private fun toggleOverlay() {
        if (OverlayService.mode.value == com.yiiai.app.overlay.OverlayMode.Bubble) {
            OverlayService.hide(this)
            return
        }
        if (!OverlayService.canDraw(this)) {
            Toast.makeText(this, "需要「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
            return
        }
        bubbleJustRequested = true
        OverlayService.show(this, com.yiiai.app.overlay.OverlayMode.Bubble)
        // 悬浮窗接管展示；退回桌面让用户继续用别的 App。
        moveTaskToBack(true)
    }
}
