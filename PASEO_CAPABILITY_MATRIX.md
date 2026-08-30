# Paseo 能力对齐矩阵

这份矩阵基于 `getpaseo/paseo` 主仓库源码和 `public-docs` 文档整理，目标是
明确 Atlas 要继承的是能力和架构，而不是 Paseo 的品牌或视觉样式。

## 结论

Paseo 的关键设计是：daemon 负责所有 agent、终端、工作区和语音服务，桌面端、
移动端和 CLI 都是客户端；客户端通过实时协议订阅同一份状态。因此 Atlas 的
Android 与 Windows 不能各自猜测状态，也不能只靠独立页面缓存。

## 能力矩阵

| 能力 | Paseo 做法 | Atlas 当前状态 | 优先级 |
| --- | --- | --- | --- |
| 执行权威 | daemon 管理 agent 生命周期、PTY、工作区和语音服务 | Windows Tauri 负责 Codex 进程和 Bridge | P0 |
| 工作区模型 | project -> workspace -> 多个 agent/terminal/browser | 目前按 Codex session 平铺 | P0 |
| 多会话 | 同一工作区可并行多个 agent，支持创建、恢复、导入、取消、归档 | 已能扫描/创建/恢复/导入，缺工作区聚合和归档操作 | P0 |
| 实时同步 | WebSocket `agent_update`、`agent_stream`、timeline 增量和重连恢复 | Android 使用带游标的 HTTP 长轮询，变化立即返回；仍缺 WebSocket/事件确认重放 | P0 |
| 消息时间线 | 用户消息、assistant markdown、代码块、工具调用、计划、权限卡片、时间和复制 | Android 已支持角色、工具、代码围栏、标题、列表、时间和自动滚动 | P0 |
| 工具输出 | 工具调用有独立状态、可展开详情、运行中动画、失败信息 | Bridge 已分类 `tool`，缺结构化参数和折叠详情 | P1 |
| 权限审批 | `agent_permission_request/response`，显示完整命令、计划和 Allow/Deny 选项 | Android/Windows 已解析编号选项并支持 Allow/Deny/Continue/Cancel/Other 输入；仍缺独立结构化审批事件契约 | P0 |
| 终端 | 创建/订阅/恢复 PTY，输入、resize、滚动、捕获、结束 | Windows 有匹配终端和输入注入，Android 没有终端页 | P1 |
| 语音输入 | dictation 流式 PCM、partial/final、ACK、重连、取消、重试、插入/发送 | Android 已有状态机、partial、音量、重试、插入/发送和连续 voice mode；仍是系统 STT | P0 |
| 语音模式 | 持续监听，VAD，STT + 隐藏 agent + TTS，语音打断和思考提示音 | 连续模式可自动发送文本，Android 新增可选系统 TTS 朗读回复；仍缺 daemon 级 VAD/打断/思考提示音 | P1 |
| 本地语音 | daemon 可下载并运行 Parakeet STT、Kokoro TTS ONNX 模型 | Windows Runtime 已提供 Paseo CLI、模型安装、进度与就绪检测；桌面录音 transport 仍待接入 | P1 |
| 供应商语音 | STT/TTS 可分别使用 OpenAI endpoint，凭据留在执行环境 | 未实现 | P2 |
| 断线与恢复 | transport 重连后重放未确认音频/事件，客户端保留游标 | HTTP 长轮询有 LAN/tunnel fallback，客户端保留消息游标并退避重试；仍缺确认/重放窗口 | P0 |
| 配对与安全 | relay 或直连，二维码包含密钥，端到端加密，密码和 Host allowlist | Bridge Bearer token + LAN/JD/Cloudflare 通道 | P0 |
| 供应商/模型 | provider catalog、model/mode/thinking/feature 设置和快照 | Codex 默认模型/权限在桌面管理，Android 只读当前模型 | P1 |
| Skills | 全局/项目 skills，详情、启停、更新、批量操作 | Windows 已有详情、更新、启停、删除 | P1 |
| 工作区脚本 | script list/start/stop，状态健康度 | 未实现 | P2 |
| Git/变更 | worktree、branch、diff、PR checkout、文件链接 | 未实现 | P2 |
| 通知/关注 | agent attention、完成/失败/权限、终端关注和桌面通知 | Windows 有通知和悬浮窗，Android 主要是 Toast | P1 |
| 更新 | Stable/Beta 通道，daemon/client 版本兼容 | Codex npm 更新，Atlas 包更新待补 | P2 |

## Atlas 整合顺序

### P0：必须先完成

1. Bridge 增加统一的 `workspace/session/message/event/permission` 契约。
2. 用事件游标或 WebSocket 替换 Android 纯轮询，桌面和 Android 读取相同增量。
3. 将 rollout 事件解析为结构化 timeline 行，保留 markdown、工具调用和审批字段。
4. 增加权限审批响应接口，Android/悬浮窗显示完整内容和 Allow/Deny 操作。
5. 语音 dictation 保留当前系统入口，同时预留 Paseo 风格的流式 STT transport；连续模式必须支持取消、重试、静音、自动发送和断线恢复。
6. 配对链接继续使用 Bearer token，但公网通道必须有 TLS 或用户自有 VPN/隧道，不能把 token 暴露在日志中。

### P1：产品完整性

1. Android 增加工作区分组、会话搜索、终端输出页、工具详情和通知渠道。
2. Windows Runtime 已提供 voice readiness、Paseo CLI 与 STT/TTS 模型下载状态；后续接入桌面录音 transport。
3. Android 使用 TTS 播放当前会话 assistant 输出，并在播放时暂停麦克风，形成真正的双向 voice mode。
4. 桌面与 Android 共享 provider/model/permission 默认值和设置变更事件。

### P2：增强能力

工作区 worktree/Git、脚本、浏览器标签、PR、Provider catalog、Stable/Beta 更新
通道等，在 P0/P1 的统一协议稳定后再加入，避免为每个端重复实现状态逻辑。

## 明确不照搬的部分

- 不复制 Paseo 的 UI、字体、颜色或品牌；Atlas 保持白色、简洁、圆润的视觉契约。
- 不把 Android 做成只读面板；所有会话操作必须经过 Windows daemon/Bridge 授权。
- 不把语音密钥放到 Android；语音 provider 和 Codex 凭据应留在执行端。
