# Codex Atlas 0.1.5 发布说明

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

安装包：

- Windows：下载 `.exe` 安装包。
- Android：下载 `codex-atlas-android.apk`，安装时允许当前来源安装应用。
