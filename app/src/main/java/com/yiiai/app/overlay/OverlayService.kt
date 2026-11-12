package com.yiiai.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.yiiai.app.YiiApp
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 悬浮窗形态。 */
enum class OverlayMode {
    /** 用户主动开启的悬浮球：可拖动、贴边吸附。 */
    Bubble,

    /** 通话中切到后台时自动出现的灵动岛：固定在屏幕顶部正中。 */
    Island,
}

/**
 * 悬浮窗服务。
 * - 悬浮球：首页「悬浮」按钮开启，可拖动，靠边吸附，位置持久化；
 * - 灵动岛：通话中 App 退到后台、且没有开悬浮球时自动出现，回到 App 时自动收起；
 * - 两种形态点一下都会从顶部展开同一块面板：字幕 + 静音 / 挂断 / 打开。
 *
 * 窗口不抢焦点（FLAG_NOT_FOCUSABLE），不影响下层输入法。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val app get() = application as YiiApp

    private var mode = OverlayMode.Bubble
    private var mainView: ComposeView? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var mainOwner: OverlayComposeOwner? = null
    private var panelView: ComposeView? = null
    private var panelOwner: OverlayComposeOwner? = null

    private val prefs by lazy { getSharedPreferences("yiiai_overlay", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopAll()
                return START_NOT_STICKY
            }
            else -> {
                val wanted = intent?.getStringExtra(EXTRA_MODE)
                    ?.let { name -> OverlayMode.entries.firstOrNull { it.name == name } }
                    ?: OverlayMode.Bubble
                if (wanted != mode || mainView == null) {
                    hideAll()
                    mode = wanted
                    showMain()
                }
                observeCall()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 呼叫状态

    private var observing = false

    private fun observeCall() {
        if (observing) return
        observing = true
        scope.launch {
            app.live.state.collect { state ->
                // 通话结束后自己收起，避免留下一个点不动的空壳。
                val shouldShow = state.phase.isActive || state.phase == CallPhase.Failed
                if (!shouldShow && mainView != null) stopAll()
            }
        }
    }

    // ---------------------------------------------------------------- 主窗口（悬浮球 / 灵动岛）

    private fun showMain() {
        if (mainView != null) return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val params = when (mode) {
            OverlayMode.Bubble -> {
                val sizePx = (BUBBLE_DP * density).toInt()
                WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 只记住贴在哪一边和高度；横向位置按当前窗口大小重新贴边。
                    val edge = -(EDGE_OVERHANG_DP * density).toInt()
                    val left = prefs.contains(K_X) && prefs.getInt(K_X, 0) + sizePx / 2 < metrics.widthPixels / 2
                    x = if (left) edge else metrics.widthPixels - sizePx - edge
                    y = prefs.getInt(K_Y, (metrics.heightPixels * 0.35f).toInt())
                        .coerceIn(0, metrics.heightPixels - sizePx)
                    _dockedLeft.value = left
                }
            }
            OverlayMode.Island -> {
                val width = (metrics.widthPixels * 0.62f).toInt().coerceAtMost((280 * density).toInt())
                WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = statusBarHeight() + (4 * density).toInt()
                }
            }
        }
        mainParams = params

        val composeOwner = OverlayComposeOwner().also { it.onCreate(); it.onStart() }
        mainOwner = composeOwner
        val view = ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            composeOwner.attachTo(this)
            setContent {
                when (mode) {
                    OverlayMode.Bubble -> OverlayBubbleHost(
                        live = app.live,
                        settingsStore = app.settings,
                        onTap = { togglePanel() },
                        onDrag = { dx, dy -> moveBubble(dx, dy) },
                        onDragEnd = { snapToEdge() },
                        onLongPress = { hangup() },
                    )
                    OverlayMode.Island -> OverlayIslandHost(
                        live = app.live,
                        settingsStore = app.settings,
                        onTap = { togglePanel() },
                    )
                }
            }
        }
        mainView = view
        try {
            wm.addView(view, params)
            _mode.value = mode
        } catch (t: Throwable) {
            Log.e(TAG, "add overlay failed", t)
            mainView = null
            stopAll()
        }
    }

    private fun moveBubble(dx: Float, dy: Float) {
        val params = mainParams ?: return
        val view = mainView ?: return
        params.x = (params.x + dx).toInt()
        params.y = (params.y + dy).toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** 松手后吸附到左右边缘，保留纵向位置。 */
    private fun snapToEdge() {
        val params = mainParams ?: return
        val view = mainView ?: return
        val metrics = resources.displayMetrics
        val margin = (8 * metrics.density).toInt()
        val edge = -(EDGE_OVERHANG_DP * metrics.density).toInt()
        val maxX = metrics.widthPixels - params.width - edge
        val maxY = metrics.heightPixels - params.height - margin
        val left = params.x + params.width / 2 < metrics.widthPixels / 2
        params.x = if (left) edge else maxX
        _dockedLeft.value = left
        params.y = params.y.coerceIn(margin, maxY)
        runCatching { wm.updateViewLayout(view, params) }
        prefs.edit().putInt(K_X, params.x).putInt(K_Y, params.y).apply()
    }

    // ---------------------------------------------------------------- 展开面板

    private fun togglePanel() {
        if (panelView != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panelView != null) return
        // 面板从顶部展开；先把球 / 岛藏起来，避免两个入口同时出现。
        mainView?.visibility = View.INVISIBLE
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.94f).toInt().coerceAtMost((400 * metrics.density).toInt())
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (6 * metrics.density).toInt()
        }
        val composeOwner = OverlayComposeOwner().also { it.onCreate(); it.onStart() }
        panelOwner = composeOwner
        val view = ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            composeOwner.attachTo(this)
            setContent {
                OverlayPanelHost(
                    live = app.live,
                    settingsStore = app.settings,
                    onCollapse = { closePanel() },
                    onHangup = { hangup() },
                    onOpenApp = { openApp() },
                )
            }
        }
        panelView = view
        runCatching { wm.addView(view, params) }.onFailure {
            Log.e(TAG, "add panel failed", it)
            panelView = null
            mainView?.visibility = View.VISIBLE
        }
    }

    private fun closePanel() {
        panelView?.let { v -> runCatching { wm.removeView(v) } }
        panelView = null
        panelOwner?.onDestroy()
        panelOwner = null
        mainView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 动作

    private fun hangup() {
        app.live.hangup()
        stopAll()
    }

    private fun openApp() {
        val intent = Intent(this, com.yiiai.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
        // 界面接管后收起悬浮窗，避免两个入口同时存在。
        stopAll()
    }

    private fun hideAll() {
        closePanel()
        mainView?.let { v -> runCatching { wm.removeView(v) } }
        mainView = null
        mainParams = null
        mainOwner?.onDestroy()
        mainOwner = null
        _mode.value = null
    }

    private fun stopAll() {
        hideAll()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        hideAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun statusBarHeight(): Int =
        resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: (24 * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "YIIAI.Overlay"
        private const val BUBBLE_DP = 112

        /** 窗口比小鹿本身大一圈（给气泡和声波留位置），贴边时让这部分空白探出屏幕外。 */
        private const val EDGE_OVERHANG_DP = 12
        private const val K_X = "bubble_x"
        private const val K_Y = "bubble_y"
        private const val EXTRA_MODE = "mode"
        const val ACTION_HIDE = "com.yiiai.app.overlay.HIDE"

        @Volatile
        var instance: OverlayService? = null
            private set

        private val _mode = MutableStateFlow<OverlayMode?>(null)

        private val _dockedLeft = MutableStateFlow(false)

        /** 悬浮球贴在屏幕左边还是右边；小鹿据此朝屏幕里面看。 */
        val dockedLeft: StateFlow<Boolean> = _dockedLeft.asStateFlow()

        /** 当前显示的形态；null 表示没有悬浮窗。首页「悬浮」按钮据此切换高亮。 */
        val mode: StateFlow<OverlayMode?> = _mode.asStateFlow()

        fun isRunning(): Boolean = instance != null

        fun show(context: Context, mode: OverlayMode = OverlayMode.Bubble) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).putExtra(EXTRA_MODE, mode.name),
                )
            }
        }

        fun hide(context: Context) {
            if (instance == null) return
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_HIDE),
                )
            }
        }

        /** 悬浮窗需要「显示在其他应用上层」权限。 */
        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
    }
}
