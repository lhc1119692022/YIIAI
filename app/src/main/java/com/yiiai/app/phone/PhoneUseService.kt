package com.yiiai.app.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * YIIAI的「眼睛」和「手」。
 *
 * 用无障碍服务做两件事：
 * 1. 看一眼：截图当前屏幕（takeScreenshot，API 30+），压成 JPEG 发给模型；
 * 2. 动一下：读控件树、点按、滑动、返回/主页、输入文字、打开 App。
 *
 * 截图不会弹授权框、也没有录屏提示条；代价是用户要手动在系统设置里打开本服务，
 * 而且部分页面（支付、银行、部分视频 App）禁止截屏，会返回黑图。
 */
class PhoneUseService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 300
        }
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 当前前台窗口的控件树，转成人类可读的紧凑列表。 */
    suspend fun dumpUi(maxNodes: Int = 120): String = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: return@withContext "读不到当前界面（可能被系统限制）"
        val lines = ArrayList<String>()
        var index = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && lines.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            val label = describe(node)
            if (label != null) {
                lines.add("[${index}] ${"  ".repeat(depth.coerceAtMost(6))}$label")
                index++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        if (lines.isEmpty()) return@withContext "当前界面没有可读的控件"
        val pkg = root.packageName?.toString().orEmpty()
        "前台应用：$pkg\n" + lines.joinToString("\n")
    }

    private fun describe(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val label = text.ifEmpty { desc }
        val clickable = if (node.isClickable) "可点" else ""
        if (label.isEmpty() && id.isEmpty() && !node.isEditable) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return buildString {
            append(label.take(60).ifEmpty { "（无文字）" })
            if (node.isEditable) append("［可输入］")
            if (clickable.isNotEmpty()) append("｜$clickable")
            if (id.isNotEmpty()) append("｜id=$id")
            append("｜${bounds.centerX()},${bounds.centerY()}")
        }
    }

    /** 按序号点击 dumpUi 里列出的控件；失败返回 false。 */
    fun tapNode(index: Int): Boolean {
        val node = findNode(index) ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        // 控件本身不可点：退化成点它的中心坐标。
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun tapNodeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        val node = nodes.firstOrNull { it.isVisibleToUser } ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /** 在指定序号的可输入控件里填写文字；不传序号则填当前焦点。 */
    fun setText(index: Int?, text: String): Boolean {
        val node = if (index == null) {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            findNode(index)
        } ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 有些输入框不支持直接设置文本，退化成聚焦后逐字粘贴。
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.parent?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
    }

    private fun findNode(index: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var counter = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (describe(node) != null) {
                if (counter == index) return node
                counter++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** 点按屏幕坐标。 */
    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0, durationMs)
    }

    /** 从 (x1,y1) 滑到 (x2,y2)。 */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, 0, durationMs)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
                    .build()
                val ok = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!ok && cont.isActive) cont.resume(false)
            }
        } ?: false

    /**
     * 截图，长边缩到 [maxSide]，JPEG 质量 [quality]。
     * 失败（页面禁止截屏 / 服务未就绪）返回 null 和一句原因。
     */
    suspend fun screenshot(maxSide: Int = 1024, quality: Int = 80): Pair<ByteArray?, String> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return null to "系统版本低于 Android 11，无障碍截图不可用"
        }
        val shot = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                moreExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (cont.isActive) cont.resume(bmp?.copy(Bitmap.Config.ARGB_8888, false))
                        result.hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed code=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } ?: return null to "截图失败：当前页面不允许截屏（支付、银行、部分视频 App 会这样）"

        val scaled = withContext(Dispatchers.Default) {
            val longSide = maxOf(shot.width, shot.height)
            val ratio = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
            val w = (shot.width * ratio).toInt().coerceAtLeast(1)
            val h = (shot.height * ratio).toInt().coerceAtLeast(1)
            val out = Bitmap.createScaledBitmap(shot, w, h, true)
            if (out !== shot) shot.recycle()
            val bytes = ByteArrayOutputStream().use { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
            out.recycle()
            bytes
        }
        return scaled to ""
    }

    private fun moreExecutor() = java.util.concurrent.Executor { it.run() }

    companion object {
        private const val TAG = "YIIAI.PhoneUse"

        @Volatile
        var instance: PhoneUseService? = null
            private set

        val isReady: Boolean get() = instance != null
    }
}
