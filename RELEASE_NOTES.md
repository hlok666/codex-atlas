# Codex Atlas 0.1.18 发布说明

- Android 15 / ColorOS 页面完整适配状态栏、底部手势区和输入法安全区，会话页不再被系统栏或键盘遮挡。
- 会话消息改为持久化后立即发送到当前设备 Bridge，失败时保留队列并显示发送中、已发送或具体错误。
- 后台同步与发送队列拆分运行，长轮询不再阻塞消息提交，断线状态也不会继续显示绿色已连接。
- 语音输入与连续输入不再因 ColorOS 的错误能力探测而永久禁用，并统一了按钮、页面和导航视觉。

# Codex Atlas 0.1.17 发布说明

- Android 会话内容按会话和设备隔离，切换会话时不会被慢请求覆盖。
- Android UI 改为连续白色画布，加入独立的会话、消息队列和设置页面。
- 增加统一底部导航、连接通道、语音朗读和应用更新入口。

# Codex Atlas 0.1.16 发布说明

- 启动时将 `hooks.json` 中所有 `SessionEnd` hook 的超时上限修复为 3 秒。
- 自动隧道启动增加重试和错误保留，并支持 SSH 配置中的免密身份文件。
- 悬浮窗消息优先通过 Codex app-server 的精确会话队列提交，避免假成功和终端注入丢失。
- Android 会话页统一消息时间线、输入区和状态层级，支持中英文。

# Codex Atlas 0.1.15 发布说明

- 修复 Windows 桌面悬浮窗黑屏后改用不透明 WebView 带来的方形底色：原生窗口现在按 CRT 外观裁剪，边角真实透明且不会遮挡桌面点击。
- 皮肤切换和无级尺寸调整会立即同步原生窗口轮廓，不需要重启悬浮窗。
- 桌面主程序与 Android App 统一使用 `0.1.15`，Android `versionCode` 更新为 `12`。

# Codex Atlas 0.1.14 发布说明

- 桌面主程序与 Android App 统一使用 `0.1.14` 版本号，避免更新检测、下载和发布页出现版本错位。
- Android `versionCode` 更新为 `11`，支持从已安装版本正常升级。
- 包含 `0.1.13` 的悬浮窗消息发送、Codex app-server 鉴权、Android 后台同步和界面修复。

# Codex Atlas 0.1.13 发布说明

- 修复悬浮窗发送调用已移除的 `thread/queue/*` app-server 方法，改用 Codex 0.151 支持的 `thread/resume`、`turn/start` 和 `turn/steer`。
- 为 Atlas 内置 app-server 增加 capability token 握手，修复本地 WebSocket 连接被 401/403 拒绝导致的发送无效。
- 悬浮窗排队发送继续优先使用 Codex 原生 `codex queue`，旧版本 CLI 保留终端输入回退。
- 桌面版本更新为 `0.1.13`；Android 伴侣更新为 `0.1.9`（versionCode `10`）。

# Codex Atlas 0.1.10 发布说明

本版本包含：

- 修复固定 URL 模式下反向 SSH 通道未清理旧监听，导致 `remote port forwarding failed for listen port 15730` 的问题。
- 服务器通道部署会保留可诊断的 SSH 错误，并兼容 `ss`、`lsof`、`fuser` 监听检查。
- 主软件版本更新为 `0.1.10`；Android 伴侣更新为 `0.1.7`（versionCode `8`）。


# Codex Atlas 0.1.9 发布说明

本版本包含：

- 主软件运行设置增加 Atlas 桌面端软件内更新：检查 GitHub Release、复用已下载安装包、下载并启动安装。
- 修复 GitHub Release 发布流程，Android APK 会与 Windows 安装包一起上传为 Release 资产。
- Android 伴侣版本更新为 `0.1.6`（versionCode `7`）。


本版本包含：

- 使用持久 Codex `app-server` WebSocket 连接处理 Atlas 会话的恢复、后台队列、立即打断和实时状态/输出通知。
- 新建或恢复会话优先连接同一个 app-server；外部旧 CLI 会话保留 `codex queue` 和终端输入兼容回退。
- 移除 Atlas 每次工具调用的旧 `PostToolUse` Hook，避免 `hook timed out after 5s`；用户已有的其他 Hook 不受影响。


本版本包含：

- Windows / macOS 桌面端会话扫描、搜索、状态监控和 `codex resume`。
- CC Switch 当前启用供应商余额监控与异常恢复保护。
- Paseo 启动、Codex 会话全量导入和 Bridge 双端同步。
- Atlas Mini 桌面 CRT 状态组件、系统通知和审批快捷操作。
- Android 原生伴侣、ColorOS 主屏卡片、语音输入和 GitHub Release 软件内更新。
- 修复 Atlas Mini CRT 浮窗的黑色/方形背景，底部控制条与 CRT 外壳统一为透明表面。
- 修复移动端会话激活与输入继续的安全处理，并同步审批事件和实时状态。
- Android 配对页增加“自动 / 局域网 / 服务器”路线选择，选择会持久化到设备和主屏卡片。
- 修复旧版配对链接缺少 `?` 查询标记的问题，并兼容旧格式二维码。
- 修复服务器反向代理路径被错误改写为 `:15730` 导致 502；保留 `/codex-atlas` 等用户配置路径。
- Android 对旧版公网端口链接增加 `/codex-atlas` 路径兜底，连接失败时会显示实际尝试的地址。
- 更新器会校验 APK 完整性和签名；已完整下载的版本直接复用，不会重复下载。
- 发布构建改用固定 release 签名，后续版本支持 Android 覆盖更新；从旧 debug 签名版本迁移需卸载一次。
- 技能页增加中文摘要、结构化章节和原始文档查看，自动恢复 GitHub 仓库地址并支持一键打开或查找。
- 全部会话页保留完整工作目录，支持快速打开工作区，长标题和预览内容改为可读的多行布局。
- Atlas Mini 增加电视侧控、街机控制台、终端键盘、塔式工作站、宽屏控制台和便携横屏等结构化 CRT 外形；预览与桌面悬浮窗同步切换。
- 悬浮窗支持通过 Codex 原生 `queue --thread` 在后台排队发送消息，并支持图片、文档和路径附件。
- 悬浮窗增加排队与立即打断两种输入方式，以及模型、CLI 权限和思考程度快捷设置。
- Codex 模型列表合并本地缓存、当前配置、CC Switch 当前供应商和供应商模型接口。

当前限制：

- “立即打断”仍使用 Windows 终端输入注入，提交时可能短暂激活对应终端；后续将迁移到 Codex app-server 协议。
- 模型、权限和思考程度会立即写入 Codex 配置，但已在运行的当前 turn 是否热重载由 Codex CLI 决定。

安装包：

- Windows：下载 `.exe` 安装包。
- Android：下载 `codex-atlas-android.apk`，安装时允许当前来源安装应用。
