package com.yiiai.app.phone

import android.content.Context
import android.util.Log
import com.yiiai.app.live.Tools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 执行模型发来的工具调用。返回给模型的字符串会直接进对话，所以要写人话、带可操作的信息。
 *
 * 截图走 tagged-v1 的 0x02 前缀直接发进实时连接（不是文本），
 * 工具结果只回一句「已经发出屏幕截图」，模型随后就能看到画面。
 */
class PhoneToolRunner(
    private val context: Context,
    /** 发送一帧 tagged-v1 屏幕 JPEG（已带 0x02 前缀）。 */
    private val sendScreenFrame: (ByteArray) -> Boolean,
    /** 发送一条客户端事件（用于先打开屏幕画面）。 */
    private val sendEvent: (String) -> Boolean = { false },
) {
    private val appContext = context.applicationContext

    suspend fun run(name: String, args: JsonObject): String {
        val svc = PhoneUseService.instance
        if (name in Tools.phoneTools && svc == null) {
            return "还没开启无障碍服务，看不到也点不了。请让用户在设置里打开「YIIAI的无障碍服务」后再试。"
        }
        return when (name) {
            Tools.LOOK -> lookAtScreen(svc!!)
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
            Tools.OPEN_APP -> PhoneUse.launchApp(appContext, args.str("app").orEmpty())
            else -> "不认识的工具：$name"
        }
    }

    private suspend fun lookAtScreen(svc: PhoneUseService): String {
        // 协议要求先声明要发屏幕帧，否则服务端可能忽略这条 0x02。
        sendEvent(com.yiiai.app.live.LiveProtocol.videoState("screen", true))
        kotlinx.coroutines.delay(120)
        val (bytes, reason) = svc.screenshot(maxSide = 1024, quality = 80)
        if (bytes == null) return reason
        val ok = sendScreenFrame(bytes)
        return if (ok) {
            "已经把当前屏幕截图发给你了（${bytes.size / 1024}KB），现在可以看到画面。"
        } else {
            "截图成功但没能发出去，连接可能断了。"
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

    private companion object {
        @Suppress("unused")
        val TAG = "YIIAI.Tool"
    }
}
