# MOSS-Live 1.0 实时交互协议笔记

> 整理自官方文档（2026-11-11 抓取），供 YIIAI 开发时查阅。以官方文档为准：
> - 模型页：https://platform.moss.cn/docs/models/moss-live?capability=realtime-interaction
> - 接口参考：https://platform.moss.cn/docs/reference/moss-live
> - 场景指南：https://platform.moss.cn/docs/scenarios/moss-live
>
> 状态：**Beta，限时免费**。协议可能变动，客户端要把协议层单独封装。

## 1. 基本信息

| 项 | 值 |
| --- | --- |
| Endpoint | `wss://api.mosi.cn/v1/realtime?model=moss-live-1.0[&user=<urlencoded>]` |
| 鉴权 | 握手头 `Authorization: Bearer <API Key>`（只能放请求头） |
| Model ID | `moss-live-1.0`（稳定）／`moss-live-1.0-2026-11-02`（快照） |
| 输入 | 实时音频 + 实时视频流（摄像头/屏幕 JPEG） |
| 输出 | 音频 + 文本（字幕） |
| 同传 | `wss://api.mosi.cn/v1/realtime/interpret?model=moss-live-1.0&source=en&target=zh` |

消息格式：文本帧为 JSON，客户端事件用 `{"action": "...", ...}`，服务端事件用 `{"action": "...", "data": {...}}`；音视频走二进制帧。

### 调用限额与上下文（2026-11-12 核对官方文档）

- **并发 1 个**：每个用户在该模型下最多同时保持 1 个会话连接；平台容量用满时也可能开不了新会话（关闭码 1013）。
- **没有公布**单次会话的最长时间、空闲超时、上下文窗口大小，也没有说明对话历史是否会压缩。对话历史保存在服务端，客户端无法干预。
- 会话指令累计上限 16000 字符，**不会自动压缩**，满了返回 `instructions_full`。

## 2. 会话流程（客户端视角）

```
WS 握手(带 Authorization)
 → attach_session                         ← session_attached(session_id, memory_scope, delegation)
 → change_voice(可选)                      ← voice_changed
 → session.update(人设/指令/工具，可选)      ← session.updated(data.error 必须为 "")
 → media.configure(可选，换格式/低码率)      ← media.configured(以此为准解码)
 → client_context.update(时区/位置，建议)    ← client_context.updated
 → media.mode {binary_protocol:"tagged-v1"}（要发视频时必须）
 → mic_state {enabled:true}
 → 持续按实时速度上传音频（没说话也要传静音；播放时也继续采集，用于打断）
 ← 字幕 / 音频 / 打断 / 工具调用 / 委派 …
 → 关闭 WebSocket 结束会话
```

## 3. 媒体格式

| 项 | 默认（profile=standard） | profile=low |
| --- | --- | --- |
| 上行音频 | PCM S16LE 16 kHz 单声道，无 WAV 头 | 建议 Opus |
| 下行音频 | PCM S16LE 48 kHz 单声道 | Opus 48 kHz |
| 图像 | 长边 512、JPEG 质量 0.75、每 1000 ms 一帧 | 长边 384、质量 0.5、每 2000 ms |

- 下行 `output_audio.format`：`pcm16`（采样率 48000/24000/16000）、`opus`（48k，MLD1 包表）、`mp3`（CBR）。
- 上行 `input_audio.format`：`pcm16` 或 `opus`（MLD1 包表，建议每条 5 个 20 ms 包）。
- **MLD1 包表**：`"MLD1"` | 包数 uint16 **小端** | 各包长 uint16 **大端** | 包数据依次拼接。下行 opus 每个 `run_id` 新建解码器；长度为 0 的消息照常回执。
- 非 48 kHz PCM 时，`response.audio.context` 带 `duration_ms`。
- **tagged-v1 前缀**（只作用于上行）：`0x00` 音频、`0x01` 摄像头 JPEG、`0x02` 屏幕 JPEG。启用后即使关了画面，音频也要带 `0x00`。下行二进制不带前缀。
- 视频：每帧一条二进制 JPEG，与音频共用连接；不传 Base64/MP4/H.264；过载可能丢旧帧。

## 4. 客户端事件

| action | 关键字段 | 说明 |
| --- | --- | --- |
| `attach_session` | `session_id?`、`memory_scope?: session\|user`、`delegation?: {type: client\|none}` | 连接后第一条。重连传原 session_id、相同 user 与创建选项。`memory_scope=user` 需 URL 带 `user` |
| `session.update` | `session.persona{name,aliases,prompt≤8000}`、`session.instructions`、`session.tools[]≤128` | 省略项保留原值；`persona:null` 恢复默认；tools 整体替换 |
| `change_voice` | `voice_name`（音色 ID） | 下一轮回答生效。内置：暖暖 `6c3152b1-1d4f-4e49-ade3-09f447712fec`、沐沐 `6cc93520-3207-4f7a-bca9-2d560b9c306b` |
| `mic_state` | `enabled` | 开关麦克风 |
| `media.mode` | `binary_protocol: "tagged-v1"` | 发视频前启用 |
| `media.configure` | `profile`、`output_audio{format,sample_rate,bitrate}`、`input_audio{...}`、`video{max_long_side,jpeg_quality,interval_ms}`、`playback: client\|server` | 可多次发；**重连后需重发** |
| `media.output.configure` | `volume_db(-20~16)`、`speed(0.5~1.5)` | 音量/语速；重连后需重发 |
| `client_context.update` | `context.time_zone`、`context.location{latitude,longitude}?` | 未上报按北京时间 |
| `video.state` | `source: camera\|screen`、`enabled` | 关画面不结束会话 |
| `video.stream_focus` | `source: camera\|screen\|auto` | |
| `playback.chunk.completed` | `run_id, turn_id, chunk_id` | 片段**实际播完**后发 |
| `playback.completed` | `run_id, turn_id` | 收到 audio.done 且全部实际播完 |
| `playback.stopped` | `run_id, turn_id, played_audio_ms` | 停止播放后回报：先确认已播完片段，再报队首片段已播毫秒 |
| `conversation.item.create` | `item{type:"function_call_output", call_id, output}` | 回传工具结果（失败也要回） |
| `response.create` | `response.call_ids[]` | 收到 item.created 后请求续答 |
| `session.thinking.append` | `content≤2000, delegation_id?, event_id?` | 静默事实/进度 |
| `session.commentary.append` | 同上 + `status: in_progress\|completed\|failed` | 可播报进度/最终结果 |
| `session.instructions.append` | 同上 | 追加会话指令（累计 ≤16000） |
| `session.delegation.cancel` | `delegation_id` | |
| `session.tool_task.revise` / `.cancel` | `task_id, expected_revision, arguments?` | 异步工具改参/取消 |
| `ping` | `timestamp?` | 保活 |

## 5. 服务端事件

| action | 关键字段 | 客户端处理 |
| --- | --- | --- |
| `session_attached` | `session_id, memory_scope, delegation` | 保存 session_id |
| `session.updated` | `error, tools[]` | error 非空则配置未生效 |
| `voice_changed` | `name` | |
| `media.configured` | `profile, output_audio, input_audio, video, playback, available, error` | 之后按此解码 |
| `media.output.configured` | `volume_db, speed` | 用户口头要求“说慢点”时服务端也会发 |
| `client_context.updated` | `accepted, error` | |
| `input.speech.started` / `.stopped` | — | 更新“用户在说话”UI 状态 |
| `input.transcript.updated` | `text`（完整快照） | 覆盖显示，不拼接 |
| `input.transcript.done` | `text, turn?` | 确认用户字幕 |
| `input.transcript.revised` | `text, previous, turn` | 按 turn 修订历史，不新增 |
| `output.transcript.updated` / `.done` | `text, run_id, turn_id` | 模型字幕 |
| `response.audio.started` | `run_id, turn_id` | 准备接收 |
| `response.audio.context` | `run_id, turn_id, chunk_id, duration_ms?` | 与**紧随的下一条二进制**配对 |
| `response.audio.done` | `run_id, turn_id` | 音频发完，等本地播完再发 playback.completed |
| `response.audio.stop` | `run_id, turn_id, reason` | **立即停播并清空**该回复待播音频 → 发 playback.stopped |
| `response.ended` | `run_id, turn_id, status: completed\|aborted\|failed` | 本轮结束 |
| `response.interrupted` | `run_id?, turn_id?, text, reason` | 已发声回复被打断，作为终态 |
| `response.function_call_arguments.done` | `call_id, name, arguments, execution_mode?, task_id?, revision?` | 执行工具（按 call_id 去重） |
| `response.done` | `response.output[]` | 工具调用汇总，按 call_id 去重 |
| `conversation.item.created` | `item.call_id` | 然后发 response.create |
| `response.function_call.cancel_requested` | `call_id, task_id, revision, reason` | 停止执行、丢弃迟到结果 |
| `session.tool_task.updated` | `task_id, revision, state` | |
| `session.delegation.created` | `delegation.id, objective, context[], purpose: task\|reasoning, timeout_ms?` | 异步执行后台任务 |
| `session.delegation.cancelled` | `delegation_id, reason` | 停止执行 |
| `session.*.appended` | `client_event_id, delegation_id` | 回执 |
| `session.closing` | `code, reason, reconnect` | reconnect=true 可用原 session_id 重连 |
| `pong` | `client_timestamp, server_recv_timestamp` | |
| `error` | `error.code, error.message, error.client_event_id?` | |

## 6. 工具调用

- 定义：`{type:"function", name, description, parameters(JSON Schema), execution_mode: sync|async_query|async_effect, read_only}`。
  - `async_query` 必须 `read_only:true`；`async_effect` 必须 `read_only:false`。
  - 可能超过约 2 秒的工具声明为异步。
- 同步流程：`function_call_arguments.done` → 执行 → `conversation.item.create` → `conversation.item.created` → `response.create`。
- 异步：模型先告诉用户“正在处理”，本轮结束；结果回来后同样走 item.create → response.create，服务端发起新回复。
- 限制：每会话最多 128 个异步任务，每任务最多 32 个版本，同时最多 4 个在执行，单次执行最长 120 秒。
- **工具列表保持稳定**：所有会话同一份定义，名称、顺序、描述、参数逐字不变；动态信息放指令或结果里。启用音频前一次配置完。

## 7. 后台 Client 委派

- `attach_session` 时带 `delegation:{type:"client"}`。
- 收到 `session.delegation.created` → 异步执行 → `session.commentary.append(status=in_progress)` 报进度 → `completed/failed` 报最终结果。
- 内容单次 ≤2000 字符；委派 context 最多 6 条、每条 ≤600 字符；每会话最多 32 条委派记录。
- 官方 JS SDK：`@mossai/mosslive@0.1.0`（含 Pi 适配）。Android 端需自行实现同等逻辑。
- **不启用委派也能用会话级 append**（`delegation_id` 省略）。YIIAI 用到两处，都未在真机验证过服务端的实际表现：
  - `session.thinking.append`：屏幕画面或摄像头关闭时静默告诉模型；
  - `session.commentary.append`：用户安静一段时间后发一条「安静提醒」，让角色主动开口（设置 → 主动开口，默认关闭）。

## 8. 播放回执（实现要点）

1. `response.audio.context` + 随后一条二进制 → 配对入队。
2. 某片段实际播完 → `playback.chunk.completed`。
3. 收到 `response.audio.done` 且该 run 全部实际播完 → `playback.completed`。
4. 收到 `response.audio.stop` → 停止该 run、清空待播 → `playback.stopped(played_audio_ms=队首片段已播毫秒)`。
5. 回执必须基于播放器**真实播放进度**（Android 上用 `AudioTrack.getPlaybackHeadPosition()` 换算），不能按收到/写入时间。
6. 不在本地实时播放的场景（录制/转写）可设 `playback:"server"`。

## 9. 错误与断开

- 会话参数错误：先发 `error`，再以 **1008 / Invalid session option** 关闭。
- 并发已满或维护：以 **1013**（Session limit / Server draining）关闭 → 指数退避重连。
- 常见错误码：`memory_scope_requires_user`、`session_option_mismatch`、`content_too_long`、`unknown_delegation_id`、`instructions_full`、`unknown_call_id`、`invalid_public_request`、`event_id_conflict`、`delegation_terminal`、`presentation_capacity`、`tool_task_conflict`、`unknown_tool_task`。

## 10. 同声传译（/v1/realtime/interpret）

- 查询参数：`model`、`target`（必填）、`source`、`bidirectional=1|0`、`voice`。
- 语言：zh en ja ko de fr es ru it pt tr el id ms。
- 第一条 `attach_session` → `media.mode(tagged-v1)` → 可选 `media.configure` → 持续推 `0x00`+音频（无人说话也推静音）。
- 一条连接只接一个说话人；不支持恢复会话。
- `playback=client` 时必须发 `playback.chunk.completed`，否则下一句会一直等。
- `input.transcript.updated` 额外带 `stable_text`；`output.transcript.*` 带 `source_turn`。
