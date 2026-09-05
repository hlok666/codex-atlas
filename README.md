# Codex Atlas

Codex Atlas 是一个面向 Codex CLI 的原生会话控制中心：扫描本机已有会话，按状态和工作区整理，在一个窗口内搜索、恢复、监控和切换 Codex。桌面端使用 Tauri 2 + Rust，Android 端通过 Atlas Bridge 与桌面或服务器保持同步。

[English](#english) · [项目主页](https://github.com/hlok666/codex-atlas) · [Releases](https://github.com/hlok666/codex-atlas/releases) · [许可证](#许可证)

## 能力概览

- 首页展示最近 5 个会话；全部会话支持标题、提示词、分支、工作区和 rollout 内容搜索。
- 原生进程树扫描：识别从 Atlas 外启动的 PowerShell、`node.exe`、`codex.exe` 会话，并融合 rollout、writer lock、app-server 事件。
- Codex `app-server` WebSocket 优先：新建会话、恢复会话、消息队列、立即打断、工具输出和审批事件使用同一条会话通道。
- 外部旧 CLI 会话保留兼容回退：没有 app-server 时使用 `codex queue` 或对应终端的标准输入。
- 异常恢复保护：403 只有在 CC Switch 当前启用供应商余额确认不足时才暂停；临时 5xx/超时最多自动继续 3 次，连续失败后停止并通知。
- CC Switch 余额和延迟监控只读取当前启用的 Codex 供应商，避免把其他中转站余额误判为当前余额。
- 桌面 CRT 小组件：始终置顶、动态尺寸、多个真实外形预设、红绿灯状态、最新输出、审批按钮、会话切换和快捷输入。
- 快捷输入支持排队发送或打断当前 turn 立即发送，并可附加文本文件、图片和本地路径。
- Codex 默认模型、CLI 权限、思考程度和 npm 更新统一管理；模型目录合并本地 CLI、当前配置和 CC Switch 当前供应商数据。
- Skills 管理：中文解析、章节详情、仓库地址查找、更新检查、启用/停用、批量更新和删除。
- Paseo 兼容能力：启动 Paseo、全量导入 Codex 会话、修复最近会话；Atlas 自己负责桥接、消息同步和语音服务，不依赖 Paseo 才能运行。
- Atlas Voice：本地 Parakeet/Kokoro 模型安装、进度、健康检查和 daemon 生命周期管理。
- Android 伴侣：局域网优先，可切换固定隧道或服务器通道；支持多设备、多电脑、多服务器、实时会话同步、消息队列、语音输入和 ColorOS 交互卡片。
- 桌面端软件内更新：从 GitHub Releases 检查、下载并启动 Windows 安装包，已完整下载的版本直接复用。
- Windows 与 macOS 原生窗口控制：最小化、最大化、关闭、窗口拖动和独立桌面小组件。

## 技术架构

```text
React + TypeScript + Vite
            │ Tauri IPC
Tauri 2 + Rust native shell
   ├─ Codex state_5.sqlite / rollout JSONL
   ├─ Codex app-server WebSocket
   ├─ native process and terminal control
   ├─ CC Switch / Paseo adapters
   ├─ Atlas Voice daemon
   └─ authenticated Mobile Bridge (LAN / fixed tunnel)
```

桌面端不会因为 Atlas 退出而批量结束 Codex 或 app-server 进程。Bridge 使用持久化同步游标（`syncEpoch + sequence`）和消息回执，Android 重连后只补发缺失消息，不重复执行同一条提交。

### Codex 会话识别

Atlas 读取 `$CODEX_HOME/state_5.sqlite`，JSONL 为回退来源；同时扫描原生进程树，解析工作目录、resume 参数、writer lock 和 rollout 元数据。实时状态来自 app-server 通知，并在外部 CLI 场景使用进程/rollout 事件兜底。旧版 Atlas Hook 已清理，不再为每个工具调用注入 5 秒超时的 PostToolUse Hook。

### 异常恢复规则

1. 读取 Codex 输出并分类错误。
2. 查询 CC Switch 当前启用供应商的余额和延迟。
3. 余额确认大于 0 时，不把泛化的 403 文本误报为余额不足。
4. 确认余额为 0 时暂停自动继续；余额恢复且开关开启时，后台向原会话发送“继续”并回车。
5. 可恢复网络错误最多继续三次，第三次失败后停止并发送 Windows 通知。

## 开发

环境要求：Node.js 22、Rust stable、Windows WebView2；Android 构建使用 JDK 17 和 Android SDK 35。

```bash
npm install
npm run dev
npm run build
npm run tauri:dev
npm run tauri:build -- --bundles nsis
```

浏览器预览会使用演示数据并把不可用的 Tauri 命令显示为提示；完整会话扫描、终端控制、通知、窗口和 Bridge 需要运行桌面壳。

### Android

```bash
cd android
./gradlew assembleDebug
```

Android 版本号同时维护 `versionName` 和 `versionCode`。Release 构建会优先使用 GitHub Actions 的固定签名；没有签名 secrets 时会回退为 debug APK，并在日志中标记该 APK 不能覆盖安装旧签名版本。

## Mobile Bridge 与服务器通道

桌面端默认启动认证 Bridge（端口 `15730`），运行设置页提供 LAN 地址、固定隧道地址、token 和二维码。手机在同一局域网优先走 LAN，离开局域网后切换固定隧道；用户也可以填写自己的服务器 Host、SSH 端口、用户名、密码或私钥，一次部署并持久化配置。

服务器直连模式不要求 Cloudflare。Atlas 会为 SSH 反向转发生成专用 ed25519 密钥，部署时写入服务器 `authorized_keys`，并在服务器端配置 Bridge 端口防火墙。固定 URL 模式会清理旧的 Atlas sshd 监听，避免 `remote port forwarding failed for listen port 15730`；端口被其他服务占用时会保留完整 SSH 错误供诊断。

Bridge API 包括：

```text
GET  /v1/status
GET  /v1/events                         (authenticated SSE push stream)
GET  /v1/sessions
GET  /v1/sessions/{id}/messages
POST /v1/sessions
POST /v1/sessions/{id}/message
POST /v1/sessions/{id}/activate
POST /v1/sessions/{id}/input
POST /v1/paseo/import-all
GET  /v1/sync?since=<cursorMs>&wait=<milliseconds>
```

手机连接后会保持 `/v1/events` 长连接。桌面端检测到 Codex 输出、状态或余额变化时主动发送唤醒事件，Android 服务立即请求 `/v1/sync` 获取增量；SSE 断开时自动回到带游标的增量同步，不会丢消息。局域网和反向 SSH 服务器通道都支持这条推送链路。

请只在可信网络开放 `15730`，并把 Bridge token 当作密码保管。

## 许可证

Codex Atlas 采用 [PolyForm Noncommercial License 1.0.0](LICENSE)。你可以将代码用于个人学习、研究、实验及其他非商业用途，但不得将本软件或其代码用于商业、营利或预期商业应用。由于包含非商业限制，本项目是源码可用软件，不属于 OSI 定义的开源软件。

复制、修改或分发本项目代码时，必须同时保留 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)，并注明以下出处和作者：

> Codex Atlas by Huang Weicong (GitHub: hlok666)
>
> Original source: https://github.com/hlok666/codex-atlas

第三方库、字体、图标和工具仍遵循各自的许可证；本许可证仅覆盖作者有权许可的 Codex Atlas 原创代码。

## 发布

桌面端版本在以下文件中保持一致：`package.json`、`package-lock.json`、`src-tauri/Cargo.toml`、`src-tauri/Cargo.lock`、`src-tauri/tauri.conf.json`。Android 的 `versionName` 和 `versionCode` 也必须同步递增。发布前执行：

```bash
npm run build
cargo test --manifest-path src-tauri/Cargo.toml --lib
git diff --check
npm run tauri:build -- --bundles nsis
```

推送 `v*` 标签后，`.github/workflows/release.yml` 会并行构建并发布：

- Windows：`Codex Atlas_<version>_x64-setup.exe`
- Android：`codex-atlas-android.apk`

本地 Windows 安装包位于：

```text
src-tauri/target/release/bundle/nsis/Codex Atlas_<version>_x64-setup.exe
```

## 截图

![首页与最近会话](docs/screenshots/overview.png)

![恢复监控](docs/screenshots/recovery-monitor.png)

![技能管理](docs/screenshots/skills.png)

![Atlas Mini CRT 桌面组件](docs/screenshots/floating-crt.png)

## English

Codex Atlas is a native session control center for Codex CLI. It indexes existing sessions, searches conversation content, resumes or queues work, monitors live runtime state, and keeps desktop and Android clients synchronized.

The desktop app is built with Tauri 2, Rust, React, TypeScript, and Vite. It prefers the Codex `app-server` WebSocket for session operations and keeps `codex queue`/terminal input as a compatibility fallback for external legacy sessions. Balance-aware recovery uses the currently enabled CC Switch provider, and never auto-continues a confirmed zero-balance incident. Paseo import and launch are supported, while Atlas owns its mobile bridge and voice service.

Codex Atlas is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use is not permitted. Copies, modifications, and distributions must retain [LICENSE](LICENSE) and [NOTICE](NOTICE), credit **Huang Weicong (GitHub: hlok666)**, and link to the [original source](https://github.com/hlok666/codex-atlas). This is source-available software rather than OSI-approved open source. Third-party components retain their own licenses.

Use `npm run tauri:dev` for the native shell and `npm run tauri:build -- --bundles nsis` for the Windows installer. Push a `v*` tag to build a Windows NSIS installer and an Android APK through GitHub Actions.
