# Codex Atlas

![Codex Atlas](docs/screenshots/overview.png)

Codex Atlas 是一个面向 Codex CLI 的原生会话控制中心。它读取本机已有的 Codex 会话，识别真实运行状态，在桌面端和 Android 端提供统一的搜索、恢复、消息提交、异常监控与设备连接体验。

> 当前版本：`0.1.42` · Windows 桌面端 + Android 伴侣

[项目主页](https://github.com/hlok666/codex-atlas) · [下载 Releases](https://github.com/hlok666/codex-atlas/releases) · [许可证](LICENSE)

## 核心能力

- 会话索引：扫描 `state_5.sqlite`、rollout JSONL、进程树、writer lock 和 Codex app-server 事件，识别从 Atlas 外启动的会话。
- 会话管理：首页显示最近 5 个会话；按标题、提示词、工作区、分支和对话内容搜索；快速打开工作区。
- 可靠操作：激活/恢复指定会话、向指定 CLI 提交消息、排队或打断当前 turn，并使用 `/exit` 退出会话后关闭对应终端。
- 实时状态：区分工作中、等待输入、空闲、完成、失败和需要审批；桌面端与 Android 共享同一份状态和增量消息。
- 异常恢复：识别 403、超时、5xx、并发限制和会话中断；只在当前启用供应商确认有余额时自动继续，连续失败达到上限后停止并通知。
- CC Switch：只监控当前启用的供应商，读取余额、模型目录和延迟，不会把其他中转站的余额当成当前余额。
- Codex 默认设置：模型、CLI 权限和思考程度直接写入 Codex 配置文件，修改默认值不会新建窗口或恢复旧会话。
- 桌面 CRT：独立置顶悬浮窗，支持多种外形、线性尺寸和字体调节、红绿灯状态、最新回复轮播、审批操作、快捷输入、附件路径和右键菜单。
- Atlas Voice：Atlas 自己管理本地 STT/TTS 服务、模型安装、进度和 daemon 生命周期，不依赖其他桌面助手。
- Skills：中文解析、详情页、仓库地址、更新检查、启用/停用、批量更新和删除。
- Android 伴侣：局域网优先，支持固定隧道、SSH 服务器通道、多设备切换、持久化消息队列、断线补偿、语音入口、文件预览和 ColorOS 卡片。
- 软件更新：从 GitHub Releases 检查并缓存 Windows 安装包和 Android APK，完整文件会复用，不重复下载。

## 界面

![会话与恢复](docs/screenshots/recovery-monitor.png)

![技能管理](docs/screenshots/skills.png)

![桌面 CRT](docs/screenshots/floating-crt.png)

桌面端使用连续的白色画布和统一的圆角控件；Android 端采用同一套 Atlas 标识、颜色和交互语义。CRT 是真正的桌面窗口，不是网页内的模拟浮层。

## 工作方式

```text
React + TypeScript + Vite
            │ Tauri IPC
Tauri 2 + Rust native shell
   ├─ Codex state_5.sqlite / rollout JSONL
   ├─ Codex app-server WebSocket
   ├─ native process and terminal control
   ├─ CC Switch provider/model/balance adapter
   ├─ Atlas Voice daemon
   └─ authenticated Mobile Bridge (LAN / fixed server route)
```

Windows Tauri 进程是本机执行权威：它负责读取 Codex 状态、控制终端、运行 Bridge 和推送事件。Android 只作为受认证的客户端，不直接修改 rollout 文件，也不会在手机上伪造运行状态。Bridge 使用持久化游标和消息回执，手机切到后台或网络短暂断开后可以从缺失位置补偿。

## 快速开始

### Windows

1. 从 [Releases](https://github.com/hlok666/codex-atlas/releases) 下载 `Codex Atlas_<version>_x64-setup.exe`。
2. 确认已经安装 Codex CLI；需要余额和模型监控时启动 CC Switch。
3. 打开 Atlas，先扫描会话，再从“运行设置”确认模型、权限和思考程度。
4. 在“连接”中显示 Android 配对二维码，或配置局域网、固定服务器通道。

本地构建的安装包路径：

```text
src-tauri/target/release/bundle/nsis/Codex Atlas_<version>_x64-setup.exe
```

### Android

从同一个 Release 下载 `codex-atlas-android.apk`。首次连接时扫描桌面端二维码，或粘贴配对链接；应用会优先尝试局域网，失败后再使用已配置的固定通道。Android 不需要保存 Codex 或供应商密钥。

## 连接与服务器通道

- 局域网：桌面端 Bridge 默认提供认证地址，适合在同一 Wi-Fi 下使用。
- 固定服务器：可在 Atlas 内填写 Host、SSH 端口、用户名、密码或使用免密 SSH，部署后保存配置并自动重连。
- 多设备：每台 Windows、macOS 或云服务器都有独立设备标识，手机可以切换设备；消息和会话状态按设备隔离。
- 推送与补偿：桌面端主动发送状态唤醒事件，Android 通过 SSE 和带游标的同步接口获取增量；连接恢复后不会重复提交消息。

不要把 Bridge 端口暴露到不可信网络。配对 token 等同密码，部署服务器时请使用防火墙、TLS、VPN 或可信的 SSH 转发。

## 自动恢复规则

1. Atlas 从 Codex 输出和结构化事件中识别错误类型。
2. 只查询 CC Switch 当前启用的供应商余额；余额查询失败不会被当作余额不足。
3. 已确认余额为零时暂停自动继续；余额恢复且开关开启时，向原会话提交继续并回车。
4. 网络、超时、并发限制和可恢复的会话中断最多自动尝试 3 次；达到上限后停止并发送桌面通知。

## 开发

环境要求：Node.js 22、Rust stable、Windows WebView2；Android 构建使用 JDK 17 和 Android SDK 35。

```bash
npm install
npm run dev
npm run build
npm test
cargo test --manifest-path src-tauri/Cargo.toml --lib
npm run tauri:dev
npm run tauri:build -- --bundles nsis
```

Android：

```bash
cd android
./gradlew :app:testReleaseUnitTest
./gradlew :app:assembleRelease
```

桌面版本需要同时更新 `package.json`、`package-lock.json`、`src-tauri/Cargo.toml`、`src-tauri/Cargo.lock` 和 `src-tauri/tauri.conf.json`。Android 需要同时递增 `versionName` 和 `versionCode`。提交前运行：

```bash
git diff --check
```

推送 `v*` 标签后，GitHub Actions 会构建 Windows NSIS 安装包和 Android APK，并将它们作为同一个 Release 的资产。

## 许可证与署名

本项目使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。允许个人学习、研究、实验、教育和其他非商业用途；禁止商业、营利或预期商业使用。本项目是 source-available 软件，不声称是 OSI 批准的开源许可证项目。

复制、修改或分发本项目代码时，必须保留 `LICENSE` 和 `NOTICE`，并明确注明作者与原始仓库：

> Codex Atlas by Huang Weicong (GitHub: [hlok666](https://github.com/hlok666))
>
> Original source: https://github.com/hlok666/codex-atlas

第三方库、字体、图标、模型和工具遵循各自的许可证；本许可证只覆盖作者有权许可的 Codex Atlas 原创代码。

## English

Codex Atlas is a native control center for Codex CLI sessions. It indexes existing local sessions, detects real runtime state, searches conversation content, resumes or exits the exact terminal, submits queued or interrupting messages, and synchronizes the desktop and Android clients through an authenticated bridge.

### What it includes

- Local session indexing from Codex SQLite, rollout JSONL, process trees, writer locks, and app-server events.
- Recent-session home, full-text search, workspace actions, activation/resume, queue and interrupt submission, and precise `/exit` handling.
- Live working, waiting, idle, completed, failed, approval, balance, latency, and recovery states.
- CC Switch monitoring for the active provider only, including upstream model discovery and balance checks.
- Defaults written directly to Codex configuration: model, CLI permission, and reasoning effort.
- A real desktop CRT window with skins, linear size/font controls, output paging, approval actions, attachments, and quick input.
- Atlas-owned local voice service, skills management, GitHub-based updates, and Android companion support.
- LAN-first, fixed-server, and SSH-based device connections with persistent cursors, receipts, and reconnect compensation.

The desktop Tauri/Rust process remains the execution authority. Android is an authenticated client and never edits rollout files directly. Atlas does not require a separate assistant application to run its desktop, bridge, or voice features.

### License

Codex Atlas is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial or profit-oriented use is not permitted. Redistributions and derivative works must retain `LICENSE` and `NOTICE`, credit **Huang Weicong (GitHub: hlok666)**, and link to the [original repository](https://github.com/hlok666/codex-atlas). Third-party components retain their own licenses.

### Build

Use Node.js 22, stable Rust, JDK 17, and Android SDK 35. Run `npm test`, the Rust library tests, and the Android release unit tests before packaging. Push a `v*` tag to let GitHub Actions build the Windows installer and Android APK.
