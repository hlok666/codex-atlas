# Paseo 深度源码研究与 Atlas 对齐方案

研究基线：`getpaseo/paseo` 主仓库 `main`，提交
`f2f93087ed782327a2eda11764471ae7032db2a2`（2026-08-30）。本报告只提取
Paseo 的架构与交互能力，不复制它的品牌、视觉或运行时依赖。由于本机未安装
`parallel-cli`，源码通过 GitHub API/raw 内容核对，结论以仓库源码为准。

## 1. 核心架构

Paseo 的执行权威是每台开发机上的 Node daemon。桌面端、Android/iOS、Web 和 CLI
都是客户端；客户端不直接维护 agent 进程，也不把 PTY、Codex 状态或语音服务分别
实现一遍。daemon 负责：

- agent 生命周期、provider 进程、PTY、工作区和持久化；
- WebSocket 连接、心跳、订阅和断线后的补偿；
- 统一的 protocol schema，所有端使用相同的消息类型；
- STT/TTS、语音流、权限请求和通知事件。

这意味着 Atlas 的 Windows Tauri 进程应继续作为本机执行权威，Android 只作为
Bridge client。Android 不应根据自己的轮询结果推断“运行中”，也不应直接写 Codex
rollout 文件。

源码：

- [architecture.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/architecture.md)
- [data-model.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/data-model.md)
- [protocol messages](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/protocol/src/messages.ts)

## 2. 会话、工作区和生命周期

Paseo 的对象层级是 `project -> workspace -> agent`。`agentId` 是 Paseo 自己的稳定
身份，provider 的 Codex thread id 只是 `persistence.sessionId/nativeHandle`，两者
不能混用。一个 agent 可以处于 `initializing -> idle -> running -> idle/error`，
也可以是 `closed`：记录仍可恢复，但 provider runtime 已释放。归档是独立的软删除
边界，不等于关闭。

重要行为：

- 打开、发送或恢复 agent 时，daemon 通过持久化 handle 重新加载 provider；
- 同一 workspace 可有多个 agent，子 agent 有 parent relationship；detach 后不再随父
  agent 归档；
- 取消不是“把 UI 改成 idle”，而是等待 provider 确认旧 turn 已不能继续；确认超时
  时拒绝开始下一次工作，防止两个 turn 同时拥有同一 provider；
- `turnId`、生命周期状态、权限和时间线是分离字段，不能从最后一条消息或目录时间戳
  重新猜测运行状态。

源码：

- [agent-lifecycle.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/agent-lifecycle.md)
- [agent-manager.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/agent-manager.ts)
- [agent-prompt.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/agent-prompt.ts)

## 3. 消息提交与队列：Paseo 实际做法

Paseo 的“队列”不是一个简单的 `List<String>`。它把一次用户提交拆成三个层次：

1. 一条带稳定 `clientMessageId` 的本地 user message presentation；
2. 一条 submission transaction，记录 provider 是否确认、RPC 是否结束；
3. provider 的 turn/stream，由 daemon 最终决定是否接受、steer、interrupt 或 replace。

当 agent 正在运行时，composer 默认将新消息放入客户端队列；用户可以选择：

- `steer`：在现有 turn 中追加输入，provider 支持时不打断当前工作；
- `interrupt`：先请求可确认的中断，再开始新的 turn；
- 发送失败：保留草稿/提交状态，显示失败，而不是假装已发送。

服务端用 `clientMessageId` 去重。daemon 接受消息时先记录 canonical user row，之后
provider 的 echo 只补充 native message id，不再产生第二条用户消息。canonical ack 和
RPC settle 谁先到都可以，二者都完成才删除 transaction；如果 canonical ack 先到，后续
网络错误不能把已经显示的消息回滚。

Paseo 的队列主要解决“运行中的 agent 上连续提交”和“提交状态可靠显示”；移动端离线
outbox 仍需要 Atlas 自己提供。Atlas 这次新增的 outbox 保留了这个 identity/ack 原则，
并额外对 Bridge 投递做了持久化幂等 receipt。

源码：

- [submission model](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/composer/submission/model.ts)
- [composer submit](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/composer/submit.ts)
- [submission writer](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/composer/submission/writer.ts)
- [timeline sync specification](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/timeline-sync.md)

## 4. 实时同步与历史一致性

Paseo 明确区分两条路径：

- `agent_stream` WebSocket 只追求即时反馈，允许是 delta-shaped lifecycle update；
- `fetch_agent_timeline_request` 是正确性来源，返回完整 projected timeline。

每条 canonical timeline row 有 `epoch` 和单调 `seq`。客户端保存 `startSeq/endSeq`，
检测到 gap 后用 `direction=after` 分页补全，直到 `hasNewer=false`；epoch 改变或 rewind
则原子替换旧范围。投影时还保留 `sourceSeqRanges`、`seqStart/seqEnd` 和 `collapsed`，
因此 assistant 增量、reasoning 增量和 tool lifecycle 合并后仍能正确推进游标。

Paseo 不用 heartbeat 或当前焦点决定是否发送时间线。焦点只影响通知路由；后台设备仍
会收到 canonical stream，重连时再用游标补偿。

Atlas 当前 Bridge 仍是 HTTP long-poll + wall-clock cursor，这是可工作的过渡方案，但
需要继续演进到：`epoch + sequence + bounded pages + gap reset`。仅比较 rollout 文件
mtime 在同毫秒写入、系统时间调整或读取窗口超出 tail 时会漏消息。

源码：

- [timeline-sync.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/timeline-sync.md)
- [agent-timeline-store.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/agent-timeline-store.ts)
- [agent-stream-coalescer.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/agent-stream-coalescer.ts)

## 5. 消息和工具渲染

Paseo 的 timeline item 是结构化 union，而不是预先拼接的纯文本：

- user / assistant / reasoning；
- tool call：`running/completed/failed/canceled`，带 `callId` 和 typed detail；
- todo/plan、compaction、error；
- permission request：工具、计划、问题、模式等不同 kind，包含完整输入、描述和动作；
- provider 原生 turnId 与 usage 独立展示。

流式文本按约 60ms 合并，首 token 立即刷新，持续 burst 合并后再刷；tool call 按
`callId` 更新而不是追加重复卡片，终态立即 flush。工具输出在进入实时流和历史前都
先限长，避免一条 shell 输出撑爆 relay/frame。

Atlas 已有基本 role/tool/approval 显示，但移动端仍应把 `callId/status/detail` 作为
字段传输，再由 UI 折叠展示，不要只发送 `lastOutput` 的截断字符串。

源码：

- [agent-types.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/protocol/src/agent-types.ts)
- [tool-call-details.tsx](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/components/tool-call-details.tsx)
- [message.tsx](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/components/message.tsx)

## 6. 语音能力

Paseo 的 dictation 是可靠的音频流协议，不是一次性把录音文件丢给 STT：

- PCM chunk 带递增 `seq`；daemon 允许乱序到达，按连续序号转发；
- 每个 chunk 返回 `ackSeq`，客户端只删除已确认的音频；
- partial transcript 可重复更新，final segment 用 `segmentId` 去重并按提交顺序拼接；
- finish 带 `finalSeq`，服务端会等待缺失 chunk、最后 commit 和 final transcript，超时
  会返回可用结果并标记错误；
- STT provider、采样率转换、静音过滤、调试录音和重连都在 daemon，凭据不放手机；
- voice mode 另外负责 agent turn、TTS、播放期间暂停麦克风和语音打断。

Atlas Android 目前的系统 STT/连续语音体验可以保留，但真正的 Paseo 能力边界应是
`voice stream -> Bridge -> Windows STT/TTS`，并沿用 seq/ack/finalSeq，而不是让手机
承担本地模型和 provider 凭据。

源码：

- [dictation-stream-manager.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/dictation/dictation-stream-manager.ts)
- [stt-manager.ts](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/stt-manager.ts)
- [voice session](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/session/voice/voice-session.ts)

## 7. 权限、审批和停止

权限请求是服务端事件，不是客户端猜测文本。请求有 `id/kind/title/description/input/
detail/actions`；响应明确是 allow 或 deny，可带 selected action、updated input 和
interrupt。停止/取消同样通过 request/response 与 turn identity 关联，旧请求不能清除
新 turn 的状态。

Atlas 当前把 rollout 中的编号选项解析成移动审批卡，这可以作为兼容 fallback，但应
优先增加结构化 `approval` 字段和 request id；点击“允许/拒绝/其他”必须发送对应值，
而不是只发送默认“继续”。

源码：

- [permissions.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/permissions.md)
- [permission response](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/server/src/server/agent/permission-response.ts)
- [protocol permission messages](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/protocol/src/messages.ts)

## 8. 移动端和后台行为

Paseo 移动端使用 host-scoped runtime：连接、缓存、目录同步和时间线 owner 由运行时
统一管理，React 只消费投影。AppState 改变不会销毁 host runtime；恢复前台后先用缓存
绘制，再做 authoritative catch-up。紧凑布局的 agent-list/agent/file-explorer 是一个
互斥面板状态，拖拽和命令都通过同一个 revision，避免两个抽屉同时打开或动画结束回写
旧状态。

Atlas 已增加 Android 前台 `START_STICKY` 同步服务、持久化游标和队列，这符合 Paseo
的“runtime 不属于页面生命周期”原则。ColorOS 仍需要用户开启自启动、后台活动和不受
限制电池策略；系统强制停止是平台边界，应用无法绕过。

源码：

- [mobile-panels.md](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/mobile-panels.md)
- [session context](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/packages/app/src/contexts/session-context.tsx)
- [Android notes](https://github.com/getpaseo/paseo/blob/f2f93087ed782327a2eda11764471ae7032db2a2/docs/android.md)

## 9. Atlas 对齐状态

| 领域 | Paseo 必须继承的原则 | Atlas 当前状态 | 下一步 |
| --- | --- | --- | --- |
| 执行权威 | daemon 统一管理进程/状态 | Windows Tauri + Bridge | 把 Bridge 协议抽成版本化 schema |
| 提交身份 | 稳定 client id，canonical ack 与 RPC settle 分离 | Android outbox 已有稳定 id；桌面入口仍缺统一 id | 桌面 `send_session_input`/Bridge 统一 request id |
| 队列 | pending/sending/failed，停止与取消有明确边界 | Android 已补齐状态和重启恢复 | 增加“取消当前请求”与失败分类 |
| 同步 | WebSocket 即时 + seq/epoch 权威补偿 | HTTP long-poll + 时间游标 | 增加 monotonic sequence、gap/reset 和分页 |
| 时间线 | 结构化 tool/approval/reasoning | 基本字符串分类 | 扩展 `MobileSessionMessage` typed detail |
| 语音 | chunk seq/ack/final、daemon STT/TTS | Android 系统 STT + TTS | Bridge dictation stream 协议 |
| 后台 | host runtime 不随页面销毁 | Android 前台服务 | 增加网络、电量和服务存活诊断 |

## 10. 本次实现变更

- `QueuedAtlasMessage` 增加 `clientMessageId/state/lastAttemptAtMs/nextAttemptAtMs`；
- 队列新增原子 `claim`，避免同步循环重复领取同一条消息；
- 服务启动时将遗留 `sending` 恢复为 `pending`；
- 失败项记录退避时间，手动重试清除错误并立即进入 pending；
- Android `/message` 请求携带 `clientMessageId`；
- Windows Bridge 在 `$CODEX_HOME/atlas-mobile-message-receipts.json` 持久化最近已接受
  的消息 ID（30 天、最多 2048 条），超时重试返回幂等成功，不会再次执行 `codex queue`；
- 队列面板显示发送中、失败次数和等待重试，而不再只显示“等待发送”。

## 11. 后续优先级

1. Bridge 增加 `epoch/seq`，移动端按 gap 重新拉取有界 timeline，而不是依赖 wall-clock。
2. 把 `/input`、`/message`、桌面发送和审批响应统一成带 request/client id 的操作日志。
3. 结构化 Codex tool call/permission/turn 事件，移动端与 CRT 共用同一渲染模型。
4. 实现 Bridge dictation chunk/ack/final 协议，再接入 Windows 本地 Parakeet/Kokoro 服务。
5. 将 workspace/session/agent 关系补到 Atlas 模型，保留 Codex thread id 作为 provider
   persistence handle，而不是唯一业务主键。
