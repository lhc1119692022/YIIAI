# YIIAI

基于 [MOSS-Live 1.0](https://platform.moss.cn/docs/models/moss-live?capability=realtime-interaction) 全双工语音模型的安卓 AI 聊天助手：边听边说、随时打断，后续支持悬浮窗、后台常驻和灵动岛（超级岛）。

- 协议笔记：[docs/moss-live-protocol.md](docs/moss-live-protocol.md)
- 屏幕读取与应用控制：[docs/phone-use.md](docs/phone-use.md)
- 视觉语言：[docs/design-language.md](docs/design-language.md)
- 路线图与进度：[docs/roadmap.md](docs/roadmap.md)

## 构建

需要 JDK 17 和 Android SDK（本机在 `D:\Android\Sdk`，路径写在不入库的 `local.properties` 里）。

```powershell
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

装到手机（USB 调试已开启）：

```powershell
D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 使用

1. 到 [platform.moss.cn](https://platform.moss.cn) 创建 API Key
2. 打开 App → 右上角设置 → 填入 API Key → 保存
3. 点绿色按钮开始对话；AI 说话时可以直接插话打断
4. 设置 →「屏幕与应用控制」可开启无障碍服务，让YIIAI能看到你的屏幕、帮你点击和输入
5. 设置 →「开启视觉」可按需使用前摄或后摄；相机画面仅在模型调用时短暂采集，不会持续开启
6. 应用内安装更新时，按提示在系统设置允许 YIIAI 安装未知应用，返回后会继续安装
7. 设置 →「日志」可查看协议事件，排查问题时把这里的内容发出来

## 代码结构

```
app/src/main/java/com/yiiai/app/
├── live/       MOSS-Live 协议与会话编排（LiveSession 为核心）
├── audio/      麦克风采集、带真实播放回执的播放器、音频路由
├── settings/   设置存储，API Key 用 Android Keystore 加密
├── phone/      无障碍服务：截图、控件树、点按滑动、输入、打开应用
├── overlay/    悬浮球与灵动岛
└── ui/         Compose 界面：通话页、设置页、YIIAI形象
```
