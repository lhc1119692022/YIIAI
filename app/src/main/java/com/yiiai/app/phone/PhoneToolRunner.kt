package com.yiiai.app.phone

import android.content.Context
import com.yiiai.app.live.LiveProtocol
import com.yiiai.app.live.Tools
import com.yiiai.app.settings.CAMERA_LOOK_MAX_SECONDS
import com.yiiai.app.settings.CAMERA_LOOK_MIN_SECONDS
import com.yiiai.app.settings.CameraFacing
import com.yiiai.app.settings.VideoQuality
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 执行模型发来的工具调用。返回给模型的字符串会直接进对话，所以要写人话、带可操作的信息。
 *
 * 屏幕与摄像头 JPEG 分别使用 tagged-v1 的 0x02 / 0x01 前缀直接发进实时连接（不是文本）。
 */
class PhoneToolRunner(
    private val context: Context,
    /** 发送一帧 tagged-v1 屏幕 JPEG（已带 0x02 前缀）。 */
    private val sendScreenFrame: (ByteArray) -> Boolean,
    /** 发送一条客户端事件（开关画面通道、切换主画面）。 */
    private val sendEvent: (String) -> Boolean = { false },
    /** 开启或续期屏幕画面流，返回是否真的在推流。 */
    private val watchScreen: (Int) -> Boolean = { false },
    /** 停止屏幕画面流，返回之前是否在推流。 */
    private val stopScreen: () -> Boolean = { false },
    /** 当前是否已经在推屏幕画面。 */
    private val isWatching: () -> Boolean = { false },
    private val screenQuality: () -> VideoQuality,
    /** 打开摄像头并持续发送指定秒数；已经开着时是续期。 */
    private val startCamera: suspend (CameraFacing, Int) -> CameraStreamer.Started = { _, _ ->
        throw IllegalStateException("摄像头采集不可用")
    },
    private val stopCamera: () -> Boolean = { false },
    private val visionEnabled: () -> Boolean = { false },
    private val defaultCameraFacing: () -> CameraFacing = { CameraFacing.Back },
    private val defaultCameraSeconds: () -> Int = { 5 },
) {
    private val appContext = context.applicationContext

    suspend fun run(name: String, args: JsonObject): String {
        val svc = PhoneUseService.instance
        // 停止观看不需要无障碍：服务没了也要能把状态收干净。
        if (name == Tools.STOP_WATCH) return stopWatching(args.str("target"))
        if (name in Tools.phoneTools && svc == null) {
            return "还没开启无障碍服务，看不到也点不了。请让用户在设置里打开「YIIAI的无障碍服务」后再试。"
        }
        if (name in Tools.visionTools && !visionEnabled()) {
            return "用户还没有开启视觉功能，不能调用摄像头。请告知用户可在设置中开启「开启视觉」。"
        }
        return when (name) {
            Tools.LOOK_CAMERA -> lookAtCamera(args.str("facing"), args.num("seconds")?.toInt())
            Tools.LOOK -> lookAtScreen(svc!!, LOOK_SECONDS, firstLook = true)
            Tools.WATCH -> {
                val seconds = args.num("seconds")?.toInt()?.coerceIn(WATCH_MIN_SECONDS, WATCH_MAX_SECONDS) ?: LOOK_SECONDS
                lookAtScreen(svc!!, seconds, firstLook = false)
            }
            Tools.READ -> svc!!.dumpUi()
            Tools.TAP -> {
                val x = args.num("x")
                val y = args.num("y")
                if (x == null || y == null) {
                    "缺少坐标"
                } else if (svc!!.tap(x, y)) {
                    "已经点了 (${x.toInt()}, ${y.toInt()})"
                } else {
                    "点击没有生效"
                }
            }
            Tools.TAP_ELEMENT -> {
                val index = args.num("index")?.toInt()
                val text = args.str("text")
                when {
                    index != null && svc!!.tapNode(index) -> "已经点了编号 $index 的控件"
                    !text.isNullOrBlank() && svc!!.tapNodeText(text) -> "已经点了「$text」"
                    else -> "没找到要点的元素，可以先用 read_screen 看看当前界面"
                }
            }
            Tools.SWIPE -> {
                val x1 = args.num("x1")
                val y1 = args.num("y1")
                val x2 = args.num("x2")
                val y2 = args.num("y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    "缺少滑动坐标"
                } else {
                    val ms = args.num("duration_ms")?.toLong() ?: 300L
                    if (svc!!.swipe(x1, y1, x2, y2, ms.coerceIn(60L, 2000L))) "已经滑动" else "滑动没有生效"
                }
            }
            Tools.KEY -> when (args.str("key")?.lowercase()) {
                "back" -> if (svc!!.pressBack()) "已经返回" else "返回没有生效"
                "home" -> if (svc!!.pressHome()) "已经回到桌面" else "回桌面没有生效"
                "recents" -> if (svc!!.pressRecents()) "已经打开最近任务" else "打开最近任务失败"
                "notifications" -> if (svc!!.openNotifications()) "已经拉下通知栏" else "拉下通知栏失败"
                else -> "不认识的按键，只支持 back、home、recents、notifications"
            }
            Tools.TYPE -> {
                val text = args.str("text").orEmpty()
                if (text.isEmpty()) {
                    "没有要输入的文字"
                } else if (svc!!.setText(args.num("index")?.toInt(), text)) {
                    "已经输入「${text.take(40)}」"
                } else {
                    "没找到可以输入的输入框，先点一下输入框再试"
                }
            }
            Tools.OPEN_APP -> AppCatalog.launchVerified(appContext, args.str("app").orEmpty())
            Tools.LIST_APPS -> {
                val query = args.str("query").orEmpty()
                val hits = PhoneUse.searchApps(appContext, query, 20)
                if (hits.isEmpty()) {
                    "没找到匹配「$query」的应用。设备上共 ${PhoneUse.appCount(appContext)} 个可启动应用。"
                } else {
                    "找到 ${hits.size} 个：" + hits.joinToString("、") { it.first }
                }
            }
            else -> "不认识的工具：$name"
        }
    }

    /**
     * 看屏幕 / 续期。画面流负责之后的持续更新，这里只补发当前这一帧，模型不用等下一轮刷新。
     */
    private suspend fun lookAtScreen(svc: PhoneUseService, seconds: Int, firstLook: Boolean): String {
        val already = isWatching()
        if (!watchScreen(seconds)) {
            return "屏幕画面没能开启：无障碍服务没有连上。请让用户到 设置 → 屏幕与应用控制 重新打开。"
        }
        // 协议要求先声明要发屏幕帧，否则服务端可能忽略这条 0x02。
        sendEvent(LiveProtocol.videoState("screen", true))
        sendEvent(LiveProtocol.videoStreamFocus("screen"))
        val quality = screenQuality()
        if (!already) kotlinx.coroutines.delay(120)
        val (bytes, reason) = svc.screenshot(maxSide = quality.maxLongSide, quality = quality.jpegQuality)
        val time = describeSeconds(seconds)
        if (bytes == null) {
            // 当前页面禁止截屏：画面流会继续尝试，用户换个页面就能看到。
            return "$reason。画面流已经开着（约 $time），用户切到别的页面后你就能看到。"
        }
        if (!sendScreenFrame(bytes)) return "截图成功但没能发出去，连接可能断了。"
        return when {
            already && !firstLook -> "已续期：接下来约 $time 你都能继续看到屏幕。"
            already -> "屏幕画面一直开着，刚又发了一帧最新的（${bytes.size / 1024}KB）。接下来约 $time 你都能继续看到。"
            else -> "已经把当前屏幕发给你了（${bytes.size / 1024}KB）。接下来约 $time 屏幕会持续更新，" +
                "用户换页面、滚动、点开别的东西，你都能接着看到。" +
                "时间快到而事情没做完，就调用 watch_screen 续期。"
        }
    }

    private suspend fun lookAtCamera(requestedFacing: String?, requestedSeconds: Int?): String {
        val facing = when (requestedFacing?.lowercase()) {
            "front" -> CameraFacing.Front
            "back" -> CameraFacing.Back
            else -> defaultCameraFacing()
        }
        val seconds = (requestedSeconds ?: defaultCameraSeconds())
            .coerceIn(CAMERA_LOOK_MIN_SECONDS, CAMERA_LOOK_MAX_SECONDS)
        sendEvent(LiveProtocol.videoState("camera", true))
        sendEvent(LiveProtocol.videoStreamFocus("camera"))
        val started = try {
            startCamera(facing, seconds)
        } catch (t: Throwable) {
            // 没开成：把主画面还回去，否则服务端会一直盯着没有画面的摄像头。
            sendEvent(LiveProtocol.videoState("camera", false))
            sendEvent(LiveProtocol.videoStreamFocus(if (isWatching()) "screen" else "auto"))
            if (t is kotlinx.coroutines.CancellationException) throw t
            return "摄像头没能打开：${t.message ?: "未知原因"}"
        }
        val time = describeSeconds(seconds)
        return if (started.extended) {
            "${facing.label}摄像头本来就开着，已续期：接下来约 $time 画面会继续送到你这里。"
        } else {
            "已打开${facing.label}摄像头，第一帧已经发给你了。接下来约 $time 画面会持续送达（${started.quality.rateLabel}），" +
                "到时间自动关闭。请根据看到的画面回应用户；还没看完就再调用一次 look_at_camera 续期。"
        }
    }

    private fun stopWatching(target: String?): String {
        val what = target?.lowercase()
        val screen = if (what == null || what == "all" || what == "screen") stopScreen() else false
        val camera = if (what == null || what == "all" || what == "camera") stopCamera() else false
        if (camera) {
            sendEvent(LiveProtocol.videoState("camera", false))
            sendEvent(LiveProtocol.videoStreamFocus(if (isWatching()) "screen" else "auto"))
        }
        return when {
            screen && camera -> "已经停止看屏幕，摄像头也关了。"
            screen -> "已经停止看屏幕。"
            camera -> "摄像头已经关了。"
            else -> "现在本来就没有在看。"
        }
    }

    private fun describeSeconds(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

    companion object {
        /** look_at_screen 默认看多久。 */
        const val LOOK_SECONDS = 180
        const val WATCH_MIN_SECONDS = 30
        const val WATCH_MAX_SECONDS = 600
    }
}
