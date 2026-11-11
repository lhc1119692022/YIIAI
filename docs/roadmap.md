# YIIAI 路线图

基于 MOSS-Live 1.0 全双工语音模型的安卓聊天助手。

## 已定决策（2026-11-11）

| 项 | 决定 |
| --- | --- |
| 使用范围 | 先自用 / 小范围测试：App 内填写自己的 MOSS API Key（Keystore 加密），手机直连 `wss://api.mosi.cn/v1/realtime`。协议层与 UI 解耦，后续可插入中转服务器 |
| 主力测试机 | 小米 / Redmi（HyperOS 超级岛优先适配） |
| 技术栈 | Kotlin + Jetpack Compose，minSdk 29，target/compileSdk 36，AGP 8.13.2，Gradle 8.14.3，Kotlin 2.2.21，OkHttp 4.12 |
| 构建 | 本机 `D:\Android\Sdk` 命令行 SDK；`gradlew assembleDebug` |

## 里程碑

### M1 语音通话打通 ✅ 真机验证通过（Redmi K80 / Android 16 / HyperOS 3.0）
- WebSocket 连接与鉴权、`attach_session` → 音色 → 人设 → `media.configure` → `client_context` → `tagged-v1` → 开麦
- 16 kHz PCM 上行（VOICE_COMMUNICATION + 系统 AEC/NS），静音时继续推零帧
- 下行 PCM 播放，按 AudioTrack 真实播放头发 `playback.chunk.completed` / `playback.completed`
- 打断：`response.audio.stop` / `response.interrupted` → 重建轨道丢弃未播音频 → `playback.stopped(played_audio_ms)`
- 用户 / AI 实时字幕（含修订）、调试日志面板

**踩过的坑**
- 播放缓冲欠载：AudioTrack 被 AudioFlinger 判 `underrun` 后会从活动列表移除，`playbackHeadPosition` 冻结，回执永远不触发。修法：缓冲 400 ms、预缓冲 200 ms、待播片段写完后补静音维持播放头前进、播放头停滞 500 ms 触发轨道重建。
- `rememberSaveable` 不能保存自定义 data class（`PersonaFields`），必须提供 `listSaver`，否则进入人设页直接崩溃。
- HyperOS 拦 `adb shell input tap`、`pm grant`、`install -g`，自动化测试要用 debug 启动参数代替。
- 麦克风在安静环境电平极低（峰值 20–40/32767），正常说话约 1000+。`VOICE_COMMUNICATION` 的 AEC/NS 会压制弱信号。

### M1.5 界面重构 ✅
- 浅色为默认，深色 / 跟随系统可切
- 去掉通话式按钮条，光球成为唯一控制面：轻点开始 / 静音 / 取消静音，长按挂断
- 光球动效：呼吸、随音量起伏、连接时的流动弧、说话时的粒子与脉动、静音与出错的独立配色
- 字幕压缩为最后一行用户话 + 最后一行 AI 话，点击展开完整记录
- 设置改为分页列表，顺序按使用频率：音色 → 人设 → 长期记忆 → 外观 → 通话音频 → API Key → 关于
- 音色菜单 9 个经实测可用的热门音色；支持按音色 ID 添加；提供音色库链接
- 长期记忆默认所有角色共享，可切换为每角色独立
- 人设改为七段式（身份背景 / 核心价值观 / 说话风格 / 优势表现 / 缺点表现 / 互动原则 / 限制），默认预设为女性御姐


### M2 后台保活 ✅ 真机验证
- `LiveCallService` 前台服务，`foregroundServiceType="microphone"`，`isForeground=true types=0x80`
- CallStyle 通话通知（category=call），带静音 / 挂断 / 回到 App 三个 action
- 界面销毁不再挂断；退到桌面后进程状态 `fg-service-act`，麦克风稳定 50 帧/秒
- 断线自动重连：指数退避（500ms→8s，最多 5 次），带原 `session_id` 恢复会话，
  跳过多余的音色/人设重配，只重发 `media.configure`
- 区分 `1008`（参数错误，直接失败）与 `1013`（并发已满/维护，走重连）

**踩过的坑**
- 服务一启动就自杀：`observe()` 首次收到 `Idle` 被误判为“通话已结束”而 `stopSelf()`，
  通话照常但通知和服务都没了。修法：加 `everActive` 标志，只有真进过通话才允许收尾。
- `NotificationCompat` 需要 androidx 的 `Person` 与 `IconCompat`，不能用平台 `android.app.Person`。
- HyperOS 拦截 `adb shell input tap` / `pm grant` / `install -g`；
  通知权限需用 `cmd appops set --uid <pkg> POST_NOTIFICATION allow` 才能生效。

### M3 悬浮窗 ✅ 真机验证
- `OverlayService`：`TYPE_APPLICATION_OVERLAY` 悬浮球 + 展开面板
- 悬浮球：颜色随状态变化、可拖动、松手自动吸附左右边缘、位置持久化
- 展开面板：最近两句字幕 + 静音 / 挂断 / 打开 App
- 通话结束后自动收起；界面里“悬浮”入口会收起自身并退回桌面
- 窗口不属于 Activity，需手动提供 `LifecycleOwner` / `ViewModelStoreOwner` /
  `SavedStateRegistryOwner`，否则 `rememberSaveable` 与 `collectAsStateWithLifecycle` 失效

### M4 灵动岛 / 超级岛 ✅ 协议打通，卡在厂商签名白名单
实测结论（Redmi K80 / HyperOS 3.0 / Android 16）：
- **系统确实识别了**：`FocusNotifUtils: canShowFocus: true`、`focusType=PARAMS`，
  且 Telecom 的 `VoipCallMonitor` 把通知当作 VoIP 通话监控
- **卡在授权**：`FocusPlugin: onAuthFailed 0|com.yiiai.app|1001`，
  `getFocusNotificationContent inflateFinish authResult=false`，
  最终 `elementSurfaceTransition is null` → 岛上没有内容
- 结论：HyperOS 超级岛对第三方应用有**签名白名单**，debug 签名无法通过；
  需要向小米申请或使用系统级签名

已实现的接入代码（签名通过后应可直接生效）：
- `miui.focus.*` extras 全家桶：`isFocus` / `isPromoted` / `enableAlert` / `ticker` /
  `transparentBg` / `normalHeight` / `hasCustomBg`
- `miui.focus.param` 必须传 **JSON 对象字符串**，纯文本会触发
  `org.json.JSONException: Value custom of type java.lang.String cannot be converted to JSONObject`
- Android 16 Live Updates：`setShortCriticalText`
- 兜底方案：自带悬浮球（M3），在所有机型上都能用

**回退路径**：若拿不到白名单，M4 的价值就只剩“通知栏通话卡片 + 胶囊兜底”，
悬浮球才是跨机型的通用方案。


### M5 能力扩展
- 对话历史持久化（Room）
- 手机端工具：闹钟、打开 App、日历、天气等（同步 / 异步工具）
- 同声传译模式（`/v1/realtime/interpret`）
- 后台 Agent 委派（`delegation: client`）
- 弱网：`profile=low` + Opus

### M6 一起看屏幕（视频输入）
MOSS-Live 支持摄像头与屏幕两路 JPEG 输入，可与语音共用一条连接。

**协议侧（已确认）**
- 先发 `media.mode {binary_protocol:"tagged-v1"}`，之后每帧 JPEG 一条二进制消息：`0x02` + JPEG 表示屏幕，`0x01` + JPEG 表示摄像头。
- `media.configure.video`：长边 128–1920（默认 512）、JPEG 质量 0.1–1、帧间隔 200–10000 ms（默认 1000）。服务端过载会丢旧帧。
- `video.state {source:"screen", enabled:true}` 开关某一路；`video.stream_focus` 指定主画面。

**安卓侧（待实现）**
- MediaProjection 需要用户每次授权，且 Android 14+ 必须搭配 `foregroundServiceType="mediaProjection"` 的前台服务。
- 从后台启动录屏服务受限，需在 App 或悬浮窗可见时发起。
- 采集 → 缩放到长边 512 → JPEG 编码（质量 0.7）→ 按 interval 限速发送。

**能力边界（要说清楚）**
- 每秒最多约 1–5 帧，能看懂"画面里是什么"、吐槽一下、讲讲画面里的文字，**跟不上短视频的快剪和连续剧情**。
- 听不到被采集应用的音频，只能看到画面。
- 部分应用（金融、支付、部分视频 App）会声明 `FLAG_SECURE`，录屏得到黑屏。
- 因此"陪看抖音"是可行的演示和轻度陪伴，但不是"真正一起刷"。

