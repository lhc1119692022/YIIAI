package com.yiiai.app.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * YIIAI能用的工具。协议要求：所有会话下发同一份定义，名称、顺序、描述、参数逐字不变；
 * 动态内容只能放在结果里。改动这份列表等于换协议。
 */
object Tools {

    const val LOOK = "look_at_screen"
    const val WATCH = "watch_screen"
    const val STOP_WATCH = "stop_watching"
    const val READ = "read_screen"
    const val TAP = "tap_screen"
    const val TAP_ELEMENT = "tap_element"
    const val SWIPE = "swipe_screen"
    const val KEY = "press_key"
    const val TYPE = "type_text"
    const val OPEN_APP = "open_app"
    const val LIST_APPS = "list_apps"
    const val LOOK_CAMERA = "look_at_camera"

    /** 需要无障碍服务（眼睛和手）的工具。 */
    val phoneTools = setOf(LOOK, WATCH, STOP_WATCH, READ, TAP, TAP_ELEMENT, SWIPE, KEY, TYPE)

    /** 只靠应用列表就能完成、不需要无障碍服务的工具。 */
    val catalogTools = setOf(OPEN_APP, LIST_APPS)
    val visionTools = setOf(LOOK_CAMERA)

    /** 会动手机的工具：执行时说明事情还在进行，屏幕画面要多留一会儿。 */
    val actionTools = setOf(TAP, TAP_ELEMENT, SWIPE, KEY, TYPE, OPEN_APP, READ)

    val all: JsonArray = buildJsonArray {
        add(
            tool(
                name = LOOK,
                description = "看一眼用户此刻的手机屏幕，并开始持续接收画面（约 3 分钟）：用户换页面、滚动、点开别的东西，" +
                    "你都会接着看到。用户问「这个怎么样」「你看这个」、或需要讨论屏幕上任何东西时调用。" +
                    "这是看东西的首选工具；只有用户明确要你看现实中的周围环境、实物或用户本人时才改用 look_at_camera。" +
                    "read_screen 只在需要精确找按钮坐标时作为辅助。",
                readOnly = true,
            ),
        )
        add(
            tool(
                name = WATCH,
                description = "持续看用户的手机屏幕一段时间，或给正在进行的观看续期。" +
                    "要陪用户看视频、带着用户一步步操作、或 look_at_screen 的时间快到而事情还没做完时调用。" +
                    "到时间会自动停止，并通知你画面已关闭。",
                readOnly = true,
                params = { integer("seconds", "要看多少秒，30 到 600；省略时为 180") },
            ),
        )
        add(
            tool(
                name = STOP_WATCH,
                description = "停止看屏幕和摄像头。用户说「不用看了」「别看了」，或事情已经做完时调用。",
                // 它会改变状态：标成非只读，服务端就不会在同一轮里复用旧结果。
                readOnly = false,
                params = {
                    enumString("target", "要停哪一路：screen 屏幕，camera 摄像头，all 全部；省略时为 all", listOf("screen", "camera", "all"))
                },
            ),
        )
        add(
            tool(
                name = LOOK_CAMERA,
                description = "打开手机摄像头看现实中的东西：镜头打开后会连续发送一段时间的画面，然后自动关闭。" +
                    "只在用户明确要你看现实中的东西时调用：" +
                    "周围环境、手里/面前的实物、用户本人，或明确提到摄像头/镜头；讨论手机屏幕内容时不要用它，用 look_at_screen。" +
                    "只在用户已开启视觉时使用。默认用用户设置的镜头，" +
                    "用户要求自拍/看自己时指定 front，要求看环境/后置时指定 back。" +
                    "镜头已经开着时再次调用是续期，不会重新打开。",
                readOnly = true,
                params = {
                    enumString("facing", "可选镜头：front 前摄，back 后摄；省略时使用用户默认设置", listOf("front", "back"))
                    integer("seconds", "要看多少秒，3 到 120；省略时使用用户设置的默认时长")
                },
            ),
        )
        add(
            tool(
                name = READ,
                description = "辅助工具：读取当前界面的控件树（文字、序号、坐标）。" +
                    "它只反映结构，看不到图片、视频画面和弹窗样式，所以只在需要精确点击某个按钮时用；" +
                    "理解用户在看什么请用 look_at_screen。",
                readOnly = true,
            ),
        )
        add(
            tool(
                name = TAP,
                description = "按屏幕坐标点一下，坐标以 read_screen 或截图里给出的像素为准。",
                readOnly = false,
                params = {
                    number("x", "横坐标像素")
                    number("y", "纵坐标像素")
                },
                required = listOf("x", "y"),
            ),
        )
        add(
            tool(
                name = TAP_ELEMENT,
                description = "点击界面上的元素：优先用 read_screen 返回的序号，也可以用元素上的文字。",
                readOnly = false,
                params = {
                    integer("index", "read_screen 列表里的序号")
                    string("text", "元素上的文字，模糊匹配")
                },
            ),
        )
        add(
            tool(
                name = SWIPE,
                description = "滑动屏幕，例如往下滚动列表。",
                readOnly = false,
                params = {
                    number("x1", "起点横坐标")
                    number("y1", "起点纵坐标")
                    number("x2", "终点横坐标")
                    number("y2", "终点纵坐标")
                    integer("duration_ms", "滑动时长毫秒，默认 300")
                },
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        )
        add(
            tool(
                name = KEY,
                description = "按系统按键。",
                readOnly = false,
                params = { string("key", "back、home、recents 或 notifications 之一") },
                required = listOf("key"),
            ),
        )
        add(
            tool(
                name = TYPE,
                description = "在当前输入框里输入文字，通常先 tap_element 点一下输入框。",
                readOnly = false,
                params = {
                    string("text", "要输入的文字")
                    integer("index", "可选，read_screen 里标了「可输入」的控件序号")
                },
                required = listOf("text"),
            ),
        )
        add(
            tool(
                name = LIST_APPS,
                description = "查设备上装了哪些应用。用户提到某个 App 名字不确定、或 open_app 没找到时先调它。" +
                    "不传关键词返回前若干个应用名。",
                readOnly = true,
                params = { string("query", "可选，按名称或包名过滤，例如「淘宝」「taobao」") },
            ),
        )
        add(
            tool(
                name = OPEN_APP,
                description = "打开一个已安装的应用，参数是应用名（如「淘宝」）或包名。" +
                    "用户要打开、切到某个应用时直接调用它：不需要先看屏幕，也不要去桌面上找图标。",
                readOnly = false,
                params = { string("app", "应用名或包名") },
                required = listOf("app"),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        readOnly: Boolean,
        params: JsonObjectBuilder.() -> Unit = {},
        required: List<String> = emptyList(),
    ) = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("execution_mode", "sync")
        put("read_only", readOnly)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties", params)
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private fun JsonObjectBuilder.string(name: String, desc: String) = putJsonObject(name) {
        put("type", "string")
        put("description", desc)
    }

    private fun JsonObjectBuilder.number(name: String, desc: String) = putJsonObject(name) {
        put("type", "number")
        put("description", desc)
    }

    private fun JsonObjectBuilder.integer(name: String, desc: String) = putJsonObject(name) {
        put("type", "integer")
        put("description", desc)
    }

    private fun JsonObjectBuilder.enumString(name: String, desc: String, values: List<String>) = putJsonObject(name) {
        put("type", "string")
        put("description", desc)
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
}
