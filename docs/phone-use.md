# 屏幕读取与应用控制（YIIAI 的眼睛和手）

YIIAI通过一个**无障碍服务**看到你的屏幕、帮你点击和输入。没有用录屏授权，所以截图时不弹授权框、也没有录屏提示条。

**视觉优先，控件树辅助**：模型主要靠持续的屏幕画面理解你在看什么；控件树只用来精确定位按钮坐标。原因很直接——控件树只有结构和文字，用户看到的图片、视频帧、商品图、弹窗样式它都读不到。

## 开启方式

系统设置 → 无障碍 → 已下载的应用 → **YIIAI** → 打开。

App 内的引导：设置 → **屏幕与应用控制**，页面顶部会显示当前状态（未开启 / 已勾选等待连接 / 已开启）。

> **HyperOS 会在重启、强杀进程后自动关闭无障碍服务**，这是系统保护机制，不是 App 的问题。
> 处理方式：
> - 服务第一次成功连上后，App 记下「曾经开通过」；
> - 之后回到 App 时如果发现它又被关掉，首页顶部会出现一条红色提示，点一下直接跳到开启页面；
> - 设置里「屏幕与应用控制」那一行用胶囊滑块显示当前状态（开 / 关）。

## 模型能调用哪些工具

工具在 `app/src/main/java/com/yiiai/app/live/Tools.kt` 里定义，随 `session.update` 一起下发。协议要求每个会话的这份列表逐字一致，所以描述和参数都不含动态内容。

| 工具 | 作用 | 只读 |
| --- | --- | --- |
| `look_at_screen` | 看一眼并**开启持续画面流**（tagged-v1 的 0x02 帧） | 是 |
| `watch_screen(seconds)` | 持续观看屏幕 30–600 秒，到点自动停 | 是 |
| `stop_watching` | 停止观看屏幕 | 是 |
| `read_screen` | 读取当前界面的文字与可点击元素，带序号和坐标 | 是 |
| `tap_screen(x,y)` | 按坐标点击 | 否 |
| `tap_element(index\|text)` | 按序号或文字点击 | 否 |
| `swipe_screen(x1,y1,x2,y2)` | 滑动 / 滚动 | 否 |
| `press_key(back\|home\|recents\|notifications)` | 系统按键 | 否 |
| `type_text(text,index?)` | 往输入框里输入文字 | 否 |
| `open_app(app)` | 按应用名或包名打开 App | 否 |

调用流程按协议走：`response.function_call_arguments.done` → 本地执行 → `conversation.item.create`（function_call_output）→ 收到 `conversation.item.created` → `response.create`。
如果服务端没有回 `item.created`，客户端 1.5 秒后自己发 `response.create`，避免这一轮卡住。

## 屏幕画面流

- 约 **1 秒一帧**，和 `media.configure.video.interval_ms` 一致。
- 只在**通话中**、且用户停在别的 App 时推流；App 自己在前台时不推，避免把自己的界面发给模型。
- `look_at_screen` 开启后默认续期 **180 秒**，期间没有新的请求就自动关掉并通知服务端关通道。
- 连续截图失败（页面禁止截屏）会降频重试，不会刷屏。

## 截图

- 用 `AccessibilityService.takeScreenshot()`（Android 11+），长边缩到 **1024**、JPEG 质量 **80**。
- 格式通过 `media.configure.video` 提前声明；默认 profile 只有长边 512 / 质量 0.75，文字会糊。
- **`jpeg_quality` 必须在 0.1–1 之间**（传 80 会让整条 `media.configure` 被服务端拒绝，音频配置也会一起失效）。
- 发帧前先发 `video.state{source:"screen",enabled:true}`。
- 截图只在内存里转成 JPEG 直接走 WebSocket，**不落盘**。

## 应用列表可见性

Android 11+ 默认看不到别的应用。`AndroidManifest.xml` 里声明了 `<queries>`（MAIN/LAUNCHER），
没有这段的话 `queryIntentActivities` 只返回自己和系统应用，「打开淘宝」会变成「找不到这个应用」。

## 首次启动

第一次打开 App 会先走一遍权限引导页（[PermissionSetupScreen.kt](<app/src/main/java/com/yiiai/app/ui/PermissionSetupScreen.kt>)）：
麦克风、通知当场申请；悬浮窗、无障碍说明后带路到系统页面，回到 App 自动刷新状态。
走完写入 `onboardingDone`，之后不再出现。

## 已知限制

- **截图失败**：支付、银行、部分视频 App 禁止截屏，`takeScreenshot` 返回失败，工具会如实告诉模型「当前页面不允许截屏」。
- **听不到别的声音**：无障碍只能看图，拿不到其他 App 的音频。
- **坐标是屏幕像素**：和截图分辨率一致，不随界面缩放变化。
- **复杂界面会点错**：准确度取决于模型对控件树的理解，必要时让它先 `read_screen`。
- **耗时**：截图 + 上传约 0.3–1 秒，模型会先说一句「我看一下」再回答。

## 安全

- 支付、发送消息、删除这类动作目前**没有**单独的确认弹窗；只有工具结果会进对话日志。
- 后续要做的是：把 `read_only:false` 且涉及金额/发送的动作改成先问用户确认再执行。
