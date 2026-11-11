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
    val phoneTools = setOf(LOOK, WATCH, STOP_WATCH, READ, TAP, TAP_ELEMENT, SWIPE, KEY, TYPE, OPEN_APP, LIST_APPS)
    val visionTools = setOf(LOOK_CAMERA)

    val all: JsonArray = buildJsonArray {
        add(
            tool(
                name = LOOK,
                description = "看一眼用户此刻的手机屏幕，并开始持续接收画面：用户换页面、滚动、点开别的东西，" +
                    "你都会接着看到。仅当用户明确在说手机屏幕、当前应用或屏幕上的内容时调用；" +
                    "如果用户只是说「你看」「快看」且没有提到手机屏幕，视觉已开启时应使用 look_at_camera。" +
                    "read_screen 只在需要精确找按钮坐标时作为辅助。",
                readOnly = true,
            ),
        )
        add(
            tool(
                name = LOOK_CAMERA,
                description = "按需短暂打开手机摄像头并发送最多 3 帧画面，然后立即关闭摄像头。用户说「你看一下」「快看这里」、" +
                    "希望你看周围环境或进行面对面视频交流时调用；只在用户已开启视觉时使用。默认用用户设置的镜头，" +
                    "用户要求自拍/看自己时指定 front，要求看环境/后置时指定 back。不要把它当作持续直播。",
                readOnly = true,
                params = { enumString("facing", "可选镜头：front 前摄，back 后摄；省略时使用用户默认设置", listOf("front", "back")) },
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
                description = "打开一个已安装的应用，参数是应用名（如「淘宝」）或包名。",
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
