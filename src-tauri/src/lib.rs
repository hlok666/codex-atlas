use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    io::{BufRead, BufReader, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{Arc, Mutex, OnceLock},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use reqwest::Client;
use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, RefreshKind, System, UpdateKind};
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    AppHandle, Emitter, Manager, PhysicalPosition, State, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder,
};
use tauri_plugin_notification::NotificationExt;
use tiny_http::{Header, Method, Response, Server};
use url::Url;
use walkdir::WalkDir;

#[cfg(target_os = "windows")]
use windows_sys::Win32::{
    Foundation::{HWND, LPARAM},
    System::Threading::{AttachThreadInput, GetCurrentThreadId},
    UI::{
        Input::KeyboardAndMouse::{
            SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
            KEYEVENTF_UNICODE, VK_RETURN,
        },
        WindowsAndMessaging::{
            BringWindowToTop, EnumWindows, GetClassNameW, GetForegroundWindow, GetWindowTextW,
            GetWindowThreadProcessId, IsWindowVisible, SetForegroundWindow, ShowWindow, SW_RESTORE,
        },
    },
};

#[derive(Clone)]
pub struct AppState {
    failure_counts: Arc<Mutex<HashMap<String, u8>>>,
    auto_continue: Arc<Mutex<bool>>,
    runtime_cache: Arc<Mutex<RuntimeSessionCache>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            failure_counts: Arc::new(Mutex::new(HashMap::new())),
            auto_continue: Arc::new(Mutex::new(true)),
            runtime_cache: Arc::new(Mutex::new(RuntimeSessionCache::default())),
        }
    }
}

#[derive(Default)]
struct RuntimeSessionCache {
    sessions: Vec<SessionRecord>,
    refreshed_at_ms: i64,
}

#[derive(Default)]
struct MobileSyncState {
    fingerprints: HashMap<String, (String, i64)>,
}

static MOBILE_SYNC_STATE: OnceLock<Mutex<MobileSyncState>> = OnceLock::new();

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionRecord {
    pub id: String,
    pub title: String,
    pub preview: String,
    pub cwd: String,
    pub branch: String,
    pub model: String,
    pub model_provider: String,
    pub permission: String,
    pub updated_at_ms: i64,
    pub created_at_ms: i64,
    pub rollout_path: String,
    pub archived: bool,
    pub search_text: String,
    /// Runtime fields are deliberately part of the session response so the
    /// renderer does not have to infer activity from a stale database timestamp.
    pub running: bool,
    pub live_state: String,
    pub process_ids: Vec<u32>,
    pub requires_attention: bool,
    pub status_source: String,
    pub last_event_at_ms: i64,
    pub last_error: Option<String>,
    pub failure_key: Option<String>,
    /// Latest human-readable rollout content for the desktop status widget.
    pub last_output: Option<String>,
    /// True only for the running thread matched to the current foreground terminal window.
    pub foreground: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub approval: Option<MobileApprovalRequest>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunningCodexSession {
    pub session_id: String,
    pub pid: u32,
    pub state: String,
    pub cwd: Option<String>,
    pub command_line: String,
    pub process_ids: Vec<u32>,
    pub observed_at_ms: i64,
    pub status_source: String,
    pub requires_attention: bool,
    pub last_event_at_ms: i64,
    pub last_error: Option<String>,
    pub failure_key: Option<String>,
    pub last_output: Option<String>,
    pub foreground: bool,
}

#[derive(Debug, Clone)]
struct ProcessSnapshot {
    pid: u32,
    parent_pid: Option<u32>,
    name: String,
    command_line: String,
    exe: Option<String>,
    cwd: Option<String>,
    start_time_ms: i64,
}

#[derive(Debug, Clone)]
struct ProcessCandidate {
    pid: u32,
    process_ids: Vec<u32>,
    command_line: String,
    session_hint: Option<String>,
    cwd: Option<String>,
    start_time_ms: i64,
}

#[derive(Debug, Clone, Default)]
struct RolloutObservation {
    state: String,
    last_event_at_ms: i64,
    requires_attention: bool,
    last_error: Option<String>,
    failure_key: Option<String>,
    last_output: Option<String>,
}

#[derive(Debug, Clone)]
struct HookObservation {
    session_id: String,
    event_name: String,
    state: String,
    cwd: Option<String>,
    updated_at_ms: i64,
    requires_attention: bool,
    explicit_timestamp: bool,
    last_output: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CodexHookStatus {
    pub hooks_path: String,
    pub config_path: String,
    pub state_path: String,
    pub configured: bool,
    pub enabled: bool,
    pub connected: bool,
    pub last_event_at_ms: i64,
    pub session_count: usize,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PaseoImportSummary {
    pub total: usize,
    pub imported: usize,
    pub failed: usize,
    pub errors: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillRecord {
    pub name: String,
    pub version: String,
    pub description: String,
    pub source: String,
    pub path: String,
    pub enabled: bool,
    pub protected: bool,
    pub managed: bool,
    pub repository: Option<String>,
    pub update_available: Option<bool>,
    pub update_status: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillDetail {
    pub skill: SkillRecord,
    pub content: String,
    pub files: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillActionResult {
    pub path: String,
    pub success: bool,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileBridgeConfig {
    pub url: String,
    pub lan_url: String,
    pub tunnel_url: Option<String>,
    pub active_url: String,
    pub connection_mode: String,
    pub prefer_tunnel: bool,
    pub auto_start_tunnel: bool,
    pub tunnel_configured: bool,
    pub tunnel_running: bool,
    pub tunnel_error: Option<String>,
    pub token: String,
    pub port: u16,
    pub pairing_uri: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ServerTunnelSettings {
    host: String,
    port: u16,
    username: String,
    remote_port: u16,
    tunnel_url: String,
    auto_start: bool,
    #[serde(default)]
    identity_file: String,
}

impl Default for ServerTunnelSettings {
    fn default() -> Self {
        Self {
            host: String::new(),
            port: 22,
            username: String::new(),
            remote_port: MOBILE_BRIDGE_PORT,
            tunnel_url: String::new(),
            auto_start: true,
            identity_file: String::new(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerTunnelInstallRequest {
    host: String,
    port: u16,
    username: String,
    password: String,
    cloudflare_token: String,
    tunnel_url: String,
    remote_port: u16,
    auto_start: bool,
    #[serde(default)]
    identity_file: String,
    #[serde(default)]
    remember_password: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerTunnelStatus {
    configured: bool,
    running: bool,
    host: String,
    port: u16,
    username: String,
    remote_port: u16,
    tunnel_url: String,
    public_url: String,
    auto_start: bool,
    key_path: String,
    credentials_saved: bool,
    error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerTunnelProgress {
    pub state: String,
    pub step: u8,
    pub total: u8,
    pub message: String,
    pub started_at_ms: i64,
    pub finished_at_ms: Option<i64>,
}

impl Default for ServerTunnelProgress {
    fn default() -> Self {
        Self {
            state: "idle".to_string(),
            step: 0,
            total: 7,
            message: "尚未开始部署".to_string(),
            started_at_ms: 0,
            finished_at_ms: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct MobileBridgeSettings {
    #[serde(default)]
    tunnel_url: String,
    #[serde(default)]
    cloudflared_path: String,
    #[serde(default)]
    tunnel_token: String,
    #[serde(default)]
    tunnel_name: String,
    #[serde(default)]
    prefer_tunnel: bool,
    #[serde(default)]
    auto_start_tunnel: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileBridgeSettingsInput {
    #[serde(default)]
    tunnel_url: String,
    #[serde(default)]
    cloudflared_path: String,
    #[serde(default)]
    tunnel_token: String,
    #[serde(default)]
    tunnel_name: String,
    #[serde(default)]
    prefer_tunnel: bool,
    #[serde(default)]
    auto_start_tunnel: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileStatusSnapshot {
    updated_at_ms: i64,
    session_id: String,
    title: String,
    folder: String,
    model: String,
    state: String,
    last_output: String,
    can_activate: bool,
    can_input_continue: bool,
    balance_remaining: Option<f64>,
    balance_unit: String,
    balance_provider: String,
    balance_checked_at_ms: i64,
    requires_attention: bool,
    last_error: Option<String>,
    foreground: bool,
    status_source: String,
    last_event_at_ms: i64,
    #[serde(default)]
    messages: Vec<MobileSessionMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    approval: Option<MobileApprovalRequest>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileApprovalOption {
    value: String,
    label: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileApprovalRequest {
    prompt: String,
    options: Vec<MobileApprovalOption>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileSessionMessage {
    id: String,
    role: String,
    text: String,
    timestamp_ms: i64,
    kind: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileSessionEventBatch {
    session_id: String,
    messages: Vec<MobileSessionMessage>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MobileSyncResponse {
    /// Wall-clock cursor used by clients to request only newer rollout events.
    cursor_ms: i64,
    snapshot: Option<MobileStatusSnapshot>,
    sessions: Vec<SessionRecord>,
    events: Vec<MobileSessionEventBatch>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CodexInfo {
    pub installed: bool,
    pub version: String,
    pub executable: String,
    pub model: Option<String>,
    pub model_provider: Option<String>,
    pub provider_name: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BalanceResponse {
    pub success: bool,
    pub remaining: Option<f64>,
    pub total: Option<f64>,
    pub unit: Option<String>,
    pub provider: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CcSwitchProviderBalance {
    pub id: String,
    pub name: String,
    pub app_type: String,
    pub model: Option<String>,
    pub base_url: String,
    pub success: bool,
    pub remaining: Option<f64>,
    pub total: Option<f64>,
    pub unit: Option<String>,
    pub provider: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BalanceRequest {
    pub base_url: String,
    #[serde(default)]
    pub api_key: String,
    #[serde(default)]
    pub usage_path: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewCodexSessionRequest {
    pub cwd: String,
    #[serde(default)]
    pub prompt: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub permission: String,
}

#[derive(Debug, Clone, Deserialize)]
struct MobileSessionInputRequest {
    #[serde(default)]
    text: String,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

const MOBILE_BRIDGE_PORT: u16 = 15730;

static MOBILE_TUNNEL_CHILD: OnceLock<Mutex<Option<Child>>> = OnceLock::new();
static MOBILE_TUNNEL_ERROR: OnceLock<Mutex<Option<String>>> = OnceLock::new();
static SERVER_TUNNEL_CHILD: OnceLock<Mutex<Option<Child>>> = OnceLock::new();
static SERVER_TUNNEL_ERROR: OnceLock<Mutex<Option<String>>> = OnceLock::new();
static SERVER_TUNNEL_PROGRESS: OnceLock<Mutex<ServerTunnelProgress>> = OnceLock::new();

fn mobile_tunnel_child() -> &'static Mutex<Option<Child>> {
    MOBILE_TUNNEL_CHILD.get_or_init(|| Mutex::new(None))
}

fn mobile_tunnel_error() -> &'static Mutex<Option<String>> {
    MOBILE_TUNNEL_ERROR.get_or_init(|| Mutex::new(None))
}

fn server_tunnel_child() -> &'static Mutex<Option<Child>> {
    SERVER_TUNNEL_CHILD.get_or_init(|| Mutex::new(None))
}

fn server_tunnel_error() -> &'static Mutex<Option<String>> {
    SERVER_TUNNEL_ERROR.get_or_init(|| Mutex::new(None))
}

fn server_tunnel_progress() -> &'static Mutex<ServerTunnelProgress> {
    SERVER_TUNNEL_PROGRESS.get_or_init(|| Mutex::new(ServerTunnelProgress::default()))
}

fn set_server_tunnel_progress(
    state: &str,
    step: u8,
    message: impl Into<String>,
    started_at_ms: Option<i64>,
    finished_at_ms: Option<i64>,
) {
    if let Ok(mut progress) = server_tunnel_progress().lock() {
        if let Some(started_at_ms) = started_at_ms {
            progress.started_at_ms = started_at_ms;
        }
        progress.state = state.to_string();
        progress.step = step;
        progress.message = message.into();
        progress.finished_at_ms = finished_at_ms;
    }
}

fn server_tunnel_settings_path() -> PathBuf {
    codex_home().join("atlas-server-tunnel.json")
}

fn server_tunnel_key_path() -> PathBuf {
    codex_home().join("atlas-server-tunnel-ed25519")
}

fn server_credential_entry(settings: &ServerTunnelSettings) -> Result<keyring::Entry, String> {
    let account = format!(
        "{}@{}:{}",
        settings.username.trim(),
        settings.host.trim(),
        settings.port
    );
    keyring::Entry::new("Codex Atlas Server Tunnel", &account)
        .map_err(|error| format!("打开系统凭据存储失败: {error}"))
}

fn stored_server_password(settings: &ServerTunnelSettings) -> Option<String> {
    server_credential_entry(settings).ok()?.get_password().ok()
}

fn server_tunnel_settings() -> ServerTunnelSettings {
    fs::read_to_string(server_tunnel_settings_path())
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn save_server_tunnel_settings(settings: &ServerTunnelSettings) -> Result<(), String> {
    let raw = serde_json::to_string_pretty(settings)
        .map_err(|error| format!("encode server tunnel settings: {error}"))?;
    write_text_atomically(&server_tunnel_settings_path(), &format!("{raw}\n"))
}

fn shell_single_quote_portable(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn validate_server_tunnel(settings: &ServerTunnelSettings) -> Result<(), String> {
    if settings.host.trim().is_empty() || settings.username.trim().is_empty() {
        return Err("服务器地址和用户名不能为空".to_string());
    }
    if settings.port == 0 || settings.remote_port == 0 {
        return Err("SSH 端口和远程 Bridge 端口必须有效".to_string());
    }
    if !settings.tunnel_url.trim().is_empty() {
        let parsed = Url::parse(settings.tunnel_url.trim())
            .map_err(|error| format!("固定访问地址无效: {error}"))?;
        if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none() {
            return Err("固定访问地址必须是 http/https URL".to_string());
        }
    }
    Ok(())
}

fn resolved_server_host(settings: &ServerTunnelSettings) -> String {
    if let Ok(config) = fs::read_to_string(ssh_config_path()) {
        let mut active = false;
        for line in config.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') {
                continue;
            }
            let mut parts = trimmed.split_whitespace();
            let key = parts.next().unwrap_or_default().to_ascii_lowercase();
            let value = parts.collect::<Vec<_>>().join(" ");
            if key == "host" {
                active = value
                    .split_whitespace()
                    .any(|item| item.eq_ignore_ascii_case(&settings.host));
            } else if active && key == "hostname" && !value.trim().is_empty() {
                return value.trim().to_string();
            }
        }
    }
    settings.host.trim().to_string()
}

fn server_public_url(settings: &ServerTunnelSettings) -> String {
    if !settings.tunnel_url.trim().is_empty() {
        if let Ok(parsed) = Url::parse(settings.tunnel_url.trim()) {
            let configured_host = parsed.host_str().unwrap_or_default();
            let server_host = resolved_server_host(settings);
            // A URL pointing back to this server is direct SSH reverse
            // forwarding, even when an old config contains a path suffix.
            // The bridge is exposed on its own port; preserving `/codex-atlas`
            // would make the pairing URL hit an unrelated web route.
            if configured_host.eq_ignore_ascii_case(&server_host)
                || configured_host.eq_ignore_ascii_case(settings.host.trim())
            {
                return format!(
                    "{}://{}:{}",
                    parsed.scheme(),
                    server_host,
                    settings.remote_port
                );
            }
        }
        return settings.tunnel_url.trim().trim_end_matches('/').to_string();
    }
    format!(
        "http://{}:{}",
        resolved_server_host(settings),
        settings.remote_port
    )
}

fn server_reverse_bind_host(settings: &ServerTunnelSettings) -> &'static str {
    if settings.tunnel_url.trim().is_empty() {
        return "0.0.0.0";
    }
    let Ok(parsed) = Url::parse(settings.tunnel_url.trim()) else {
        return "127.0.0.1";
    };
    let configured_host = parsed.host_str().unwrap_or_default();
    let server_host = resolved_server_host(settings);
    if configured_host.eq_ignore_ascii_case(&server_host)
        || configured_host.eq_ignore_ascii_case(settings.host.trim())
    {
        "0.0.0.0"
    } else {
        "127.0.0.1"
    }
}

fn ssh_config_path() -> PathBuf {
    home_dir().join(".ssh").join("config")
}

fn configured_identity_for_host(host: &str) -> Option<PathBuf> {
    let config = fs::read_to_string(ssh_config_path()).ok()?;
    let mut active = false;
    for line in config.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let key = parts.next()?.to_ascii_lowercase();
        let value = parts.collect::<Vec<_>>().join(" ");
        if key == "host" {
            active = value
                .split_whitespace()
                .any(|item| item.eq_ignore_ascii_case(host));
        } else if active && key == "identityfile" {
            let value = value.trim_matches('"').trim_matches('\'');
            let expanded = if let Some(rest) = value.strip_prefix("~/") {
                home_dir().join(rest)
            } else {
                PathBuf::from(value)
            };
            if expanded.exists() {
                return Some(expanded);
            }
        }
    }
    None
}

fn server_identity_file(settings: &ServerTunnelSettings) -> Option<PathBuf> {
    if !settings.identity_file.trim().is_empty() {
        let path = settings
            .identity_file
            .replace('~', &home_dir().to_string_lossy());
        let candidate = PathBuf::from(path);
        if candidate.exists() {
            return Some(candidate);
        }
    }
    configured_identity_for_host(&settings.host)
}

fn ssh_password_askpass(password: &str) -> Result<PathBuf, String> {
    let path = codex_home().join(if cfg!(target_os = "windows") {
        "atlas-ssh-askpass.cmd"
    } else {
        "atlas-ssh-askpass.sh"
    });
    let escaped = password.replace(['\r', '\n'], "");
    let content = if cfg!(target_os = "windows") {
        format!("@echo off\necho {escaped}\n")
    } else {
        format!(
            "#!/bin/sh\nprintf '%s\\n' '{}'\n",
            escaped.replace('\'', "'\\''")
        )
    };
    write_text_atomically(&path, &content)?;
    #[cfg(not(target_os = "windows"))]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(&path)
            .map_err(|error| format!("读取 askpass 权限失败: {error}"))?
            .permissions();
        permissions.set_mode(0o700);
        fs::set_permissions(&path, permissions)
            .map_err(|error| format!("设置 askpass 权限失败: {error}"))?;
    }
    Ok(path)
}

fn ssh_exec_with_password(
    settings: &ServerTunnelSettings,
    password: &str,
    identity_file: Option<&Path>,
    command: &str,
    stdin: Option<&str>,
) -> Result<String, String> {
    let destination = format!("{}@{}", settings.username.trim(), settings.host.trim());
    if let Some(plink) = executable_candidate("plink") {
        let mut process = Command::new(plink);
        process.args(["-batch", "-P", &settings.port.to_string()]);
        if let Some(identity) = identity_file {
            process.args(["-i", &identity.to_string_lossy()]);
        } else {
            process.args(["-pw", password]);
        }
        process.args([&destination, command]);
        if stdin.is_some() {
            process.stdin(Stdio::piped());
        }
        let mut child = process
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|error| format!("启动 plink 失败: {error}"))?;
        if let Some(input) = stdin {
            if let Some(mut pipe) = child.stdin.take() {
                pipe.write_all(input.as_bytes()).ok();
            }
        }
        let output = child
            .wait_with_output()
            .map_err(|error| format!("读取远程命令结果失败: {error}"))?;
        if output.status.success() {
            return Ok(String::from_utf8_lossy(&output.stdout).to_string());
        }
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    let askpass = if identity_file.is_none() {
        Some(ssh_password_askpass(password)?)
    } else {
        None
    };
    let ssh = executable_candidate("ssh").unwrap_or_else(|| PathBuf::from("ssh"));
    let mut process = Command::new(ssh);
    process.args([
        "-o",
        "StrictHostKeyChecking=accept-new",
        "-o",
        "ConnectTimeout=12",
    ]);
    if let Some(identity) = identity_file {
        process
            .arg("-i")
            .arg(identity)
            .args(["-o", "BatchMode=yes"]);
    } else {
        process.args([
            "-o",
            "PreferredAuthentications=password",
            "-o",
            "PubkeyAuthentication=no",
        ]);
    }
    process
        .arg("-p")
        .arg(settings.port.to_string())
        .arg(&destination)
        .arg(command);
    if let Some(askpass) = askpass.as_ref() {
        process
            .env("SSH_ASKPASS", askpass)
            .env("SSH_ASKPASS_REQUIRE", "force")
            .env("DISPLAY", "atlas");
    }
    process.stdin(if stdin.is_some() {
        Stdio::piped()
    } else {
        Stdio::null()
    });
    let mut child = process
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("启动系统 OpenSSH 失败: {error}"))?;
    if let Some(input) = stdin {
        if let Some(mut pipe) = child.stdin.take() {
            pipe.write_all(input.as_bytes()).ok();
        }
    }
    let output = child
        .wait_with_output()
        .map_err(|error| format!("读取远程命令结果失败: {error}"))?;
    if let Some(askpass) = askpass {
        let _ = fs::remove_file(askpass);
    }
    if output.status.success() {
        return Ok(String::from_utf8_lossy(&output.stdout).to_string());
    }
    let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
    Err(if detail.is_empty() {
        format!("远程命令退出，状态码 {}", output.status)
    } else {
        detail
    })
}

fn ensure_server_tunnel_key() -> Result<(PathBuf, String), String> {
    let private_key = server_tunnel_key_path();
    let public_key = private_key.with_extension("pub");
    if !private_key.exists() || !public_key.exists() {
        if let Some(parent) = private_key.parent() {
            fs::create_dir_all(parent).map_err(|error| format!("创建密钥目录失败: {error}"))?;
        }
        let output = Command::new("ssh-keygen")
            .args([
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                "codex-atlas-server-tunnel",
                "-f",
            ])
            .arg(&private_key)
            .output()
            .map_err(|error| format!("生成 SSH 专用密钥失败: {error}"))?;
        if !output.status.success() {
            return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
        }
    }
    let public = fs::read_to_string(&public_key)
        .map_err(|error| format!("读取 SSH 公钥失败: {error}"))?
        .trim()
        .to_string();
    Ok((private_key, public))
}

fn server_tunnel_running() -> bool {
    let Ok(mut slot) = server_tunnel_child().lock() else {
        return false;
    };
    let Some(child) = slot.as_mut() else {
        return false;
    };
    match child.try_wait() {
        Ok(None) => true,
        Ok(Some(status)) => {
            *slot = None;
            if let Ok(mut error) = server_tunnel_error().lock() {
                *error = Some(format!("反向 SSH 通道已退出: {status}"));
            }
            false
        }
        Err(_) => true,
    }
}

fn cleanup_stale_server_listener(settings: &ServerTunnelSettings, key_path: &Path) {
    if server_reverse_bind_host(settings) != "0.0.0.0" {
        return;
    }
    // A previous Atlas process can leave an orphaned reverse-forwarding sshd
    // child on the configured port. Remove only an sshd process that owns
    // this exact listening port; unrelated services are left untouched.
    let command = format!(
        "if command -v ss >/dev/null 2>&1; then for pid in $(ss -ltnp 2>/dev/null | sed -n 's/.*:{port}[^0-9].*pid=\\([0-9][0-9]*\\).*/\\1/p'); do case \"$(tr -d '\\0' </proc/$pid/cmdline 2>/dev/null)\" in *sshd*) kill \"$pid\" 2>/dev/null || true;; esac; done; fi",
        port = settings.remote_port
    );
    let _ = ssh_exec_with_password(settings, "", Some(key_path), &command, None);
    thread::sleep(Duration::from_millis(120));
}

fn server_tunnel_last_error() -> Option<String> {
    server_tunnel_error()
        .lock()
        .ok()
        .and_then(|value| value.clone())
}

fn start_server_tunnel_process(settings: &ServerTunnelSettings) -> Result<(), String> {
    validate_server_tunnel(settings)?;
    if server_tunnel_running() {
        return Ok(());
    }
    let key_path = server_tunnel_key_path();
    if !key_path.exists() {
        return Err("服务器通道专用 SSH 密钥尚未安装".to_string());
    }
    cleanup_stale_server_listener(settings, &key_path);
    let destination = format!("{}@{}", settings.username.trim(), settings.host.trim());
    let ssh = executable_candidate("ssh").unwrap_or_else(|| PathBuf::from("ssh"));
    let mut preflight = Command::new(&ssh);
    preflight
        .args([
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=12",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "-i",
        ])
        .arg(&key_path)
        .arg("-p")
        .arg(settings.port.to_string())
        .arg(&destination)
        .arg("true");
    let preflight_output = preflight
        .output()
        .map_err(|error| format!("启动 SSH 预检失败: {error}"))?;
    if !preflight_output.status.success() {
        let detail = String::from_utf8_lossy(&preflight_output.stderr)
            .trim()
            .to_string();
        return Err(if detail.is_empty() {
            format!("SSH 预检失败，状态码 {}", preflight_output.status)
        } else {
            format!("SSH 预检失败: {detail}")
        });
    }
    let reverse = format!(
        "{}:{}:127.0.0.1:{}",
        server_reverse_bind_host(settings),
        settings.remote_port,
        MOBILE_BRIDGE_PORT
    );
    let log_path = codex_home().join("atlas-server-tunnel.log");
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .ok();
    let mut command = Command::new(&ssh);
    command
        .args(["-N", "-T", "-p", &settings.port.to_string(), "-i"])
        .arg(&key_path)
        .args([
            "-o",
            "BatchMode=yes",
            "-o",
            "ExitOnForwardFailure=yes",
            "-o",
            "ServerAliveInterval=20",
            "-o",
            "ServerAliveCountMax=3",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "-R",
            &reverse,
            &destination,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null());
    if let Some(log_file) = log_file {
        command.stderr(Stdio::from(log_file));
    } else {
        command.stderr(Stdio::null());
    }
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000008 | 0x08000000);
    }
    let mut child = command
        .spawn()
        .map_err(|error| format!("启动反向 SSH 通道失败: {error}"))?;
    thread::sleep(Duration::from_millis(650));
    if let Some(status) = child
        .try_wait()
        .map_err(|error| format!("检查反向 SSH 通道失败: {error}"))?
    {
        let detail = fs::read_to_string(&log_path)
            .ok()
            .and_then(|content| {
                content
                    .lines()
                    .rev()
                    .take(5)
                    .collect::<Vec<_>>()
                    .into_iter()
                    .rev()
                    .map(str::to_string)
                    .reduce(|left, right| format!("{left}\n{right}"))
            })
            .filter(|content| !content.trim().is_empty());
        return Err(match detail {
            Some(detail) => format!("反向 SSH 通道立即退出: {status}\n{detail}"),
            None => format!("反向 SSH 通道立即退出: {status}"),
        });
    }
    if let Ok(mut slot) = server_tunnel_child().lock() {
        *slot = Some(child);
    }
    if let Ok(mut error) = server_tunnel_error().lock() {
        *error = None;
    }
    Ok(())
}

fn stop_server_tunnel_process() -> Result<(), String> {
    let child = server_tunnel_child()
        .lock()
        .map_err(|_| "服务器通道进程状态不可用".to_string())?
        .take();
    if let Some(mut child) = child {
        let _ = child.kill();
        let _ = child.wait();
    }
    Ok(())
}

fn current_server_tunnel_status() -> ServerTunnelStatus {
    let settings = server_tunnel_settings();
    let credentials_saved = stored_server_password(&settings).is_some();
    let public_url = if settings.host.trim().is_empty() {
        String::new()
    } else {
        server_public_url(&settings)
    };
    ServerTunnelStatus {
        configured: validate_server_tunnel(&settings).is_ok() && server_tunnel_key_path().exists(),
        running: server_tunnel_running(),
        host: settings.host,
        port: settings.port,
        username: settings.username,
        remote_port: settings.remote_port,
        tunnel_url: settings.tunnel_url,
        public_url,
        auto_start: settings.auto_start,
        key_path: server_tunnel_key_path().to_string_lossy().to_string(),
        credentials_saved,
        error: server_tunnel_last_error(),
    }
}

fn install_server_tunnel_now(
    request: ServerTunnelInstallRequest,
) -> Result<ServerTunnelStatus, String> {
    let started_at_ms = now_ms();
    set_server_tunnel_progress("connecting", 1, "正在连接服务器", Some(started_at_ms), None);
    let result = install_server_tunnel_now_inner(request);
    match result {
        Ok(status) => {
            set_server_tunnel_progress(
                "ready",
                7,
                "服务器通道已就绪",
                Some(started_at_ms),
                Some(now_ms()),
            );
            Ok(status)
        }
        Err(error) => {
            set_server_tunnel_progress(
                "error",
                7,
                error.clone(),
                Some(started_at_ms),
                Some(now_ms()),
            );
            Err(error)
        }
    }
}

fn install_server_tunnel_now_inner(
    request: ServerTunnelInstallRequest,
) -> Result<ServerTunnelStatus, String> {
    let settings = ServerTunnelSettings {
        host: request.host.trim().to_string(),
        port: if request.port == 0 { 22 } else { request.port },
        username: request.username.trim().to_string(),
        remote_port: if request.remote_port == 0 {
            MOBILE_BRIDGE_PORT
        } else {
            request.remote_port
        },
        tunnel_url: request.tunnel_url.trim().trim_end_matches('/').to_string(),
        auto_start: request.auto_start,
        identity_file: request.identity_file.trim().to_string(),
    };
    validate_server_tunnel(&settings)?;
    let identity_file = server_identity_file(&settings);
    let password = if request.password.is_empty() {
        stored_server_password(&settings).unwrap_or_default()
    } else {
        request.password.clone()
    };
    if identity_file.is_none() && (password.is_empty() || password.contains(['\r', '\n'])) {
        return Err("SSH 密码不能为空且不能包含换行".to_string());
    }
    if request.cloudflare_token.contains(['\r', '\n']) {
        return Err("Cloudflare Tunnel token 不能包含换行".to_string());
    }
    set_server_tunnel_progress("generating-key", 2, "正在准备通道密钥", None, None);
    let (key_path, public_key) = ensure_server_tunnel_key()?;
    let quoted_key = shell_single_quote_portable(&public_key);
    set_server_tunnel_progress("authorizing", 3, "正在授权服务器密钥", None, None);
    ssh_exec_with_password(
        &settings,
        &password,
        identity_file.as_deref(),
        &format!("umask 077; mkdir -p ~/.ssh; touch ~/.ssh/authorized_keys; grep -qxF {quoted_key} ~/.ssh/authorized_keys || printf '%s\\n' {quoted_key} >> ~/.ssh/authorized_keys; chmod 700 ~/.ssh; chmod 600 ~/.ssh/authorized_keys"),
        None,
    )?;
    set_server_tunnel_progress("checking-root", 4, "正在检查服务器权限", None, None);
    let uid = ssh_exec_with_password(
        &settings,
        &password,
        identity_file.as_deref(),
        "id -u",
        None,
    )?;
    let service = format!(
        "[Unit]\nDescription=Codex Atlas fixed Cloudflare tunnel\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\nType=simple\nEnvironmentFile=/etc/codex-atlas/tunnel.env\nExecStart=/usr/local/bin/cloudflared tunnel --no-autoupdate run\nRestart=always\nRestartSec=3\n\n[Install]\nWantedBy=multi-user.target\n"
    );
    let script = if request.cloudflare_token.trim().is_empty() {
        // Direct server mode: the reverse SSH listener is exposed on the
        // server's public interface. GatewayPorts is required by OpenSSH for
        // a non-loopback remote bind; reload is best-effort across distros.
        format!(
            "set -eu\ninstall -d -m 0755 /etc/ssh/sshd_config.d\nprintf '%s\\n' 'GatewayPorts clientspecified' > /etc/ssh/sshd_config.d/codex-atlas-gateway.conf\nif command -v sshd >/dev/null 2>&1; then sshd -t && (systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true); fi\n# Open the dedicated Atlas port where a host firewall is enabled. Cloud-provider security groups remain the user's responsibility.\nif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then ufw allow {port}/tcp || true; fi\nif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then firewall-cmd --permanent --add-port={port}/tcp || true; firewall-cmd --reload || true; fi\nif command -v iptables >/dev/null 2>&1; then iptables -C INPUT -p tcp --dport {port} -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport {port} -j ACCEPT; if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save || true; fi; fi\n",
            port = settings.remote_port
        )
    } else {
        let token = shell_single_quote_portable(request.cloudflare_token.trim());
        format!(
            "set -eu\nARCH=$(uname -m)\ncase \"$ARCH\" in x86_64|amd64) PKG=amd64 ;; aarch64|arm64) PKG=arm64 ;; *) echo \"Unsupported architecture: $ARCH\" >&2; exit 2 ;; esac\nif ! command -v curl >/dev/null 2>&1; then apt-get update -y && apt-get install -y curl ca-certificates; fi\ncurl -fsSL \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$PKG\" -o /usr/local/bin/cloudflared\nchmod 0755 /usr/local/bin/cloudflared\ninstall -d -m 0700 /etc/codex-atlas\nprintf 'TUNNEL_TOKEN=%s\\n' {token} > /etc/codex-atlas/tunnel.env\nchmod 0600 /etc/codex-atlas/tunnel.env\ncat > /etc/systemd/system/codex-atlas-tunnel.service <<'ATLAS_SERVICE'\n{service}ATLAS_SERVICE\nsystemctl daemon-reload\nsystemctl enable --now codex-atlas-tunnel.service\nsystemctl is-active --quiet codex-atlas-tunnel.service\n",
            token = token,
            service = service
        )
    };
    set_server_tunnel_progress("installing", 5, "正在安装服务器通道", None, None);
    if uid.trim() == "0" {
        ssh_exec_with_password(
            &settings,
            &password,
            identity_file.as_deref(),
            "bash -s",
            Some(&script),
        )?;
    } else {
        let input = format!("{}\n{}", password, script);
        ssh_exec_with_password(
            &settings,
            &password,
            identity_file.as_deref(),
            "sudo -S -p '' bash -s",
            Some(&input),
        )?;
    }
    set_server_tunnel_progress("starting", 6, "正在启动反向通道", None, None);
    if request.remember_password {
        server_credential_entry(&settings)?
            .set_password(&password)
            .map_err(|error| format!("保存系统凭据失败: {error}"))?;
    } else if let Ok(entry) = server_credential_entry(&settings) {
        let _ = entry.delete_credential();
    }
    save_server_tunnel_settings(&settings)?;
    let mut bridge_settings = mobile_bridge_settings();
    bridge_settings.tunnel_url = if settings.tunnel_url.trim().is_empty() {
        server_public_url(&settings)
    } else {
        settings.tunnel_url.clone()
    };
    bridge_settings.prefer_tunnel = false;
    bridge_settings.auto_start_tunnel = false;
    save_mobile_bridge_settings(&bridge_settings)?;
    stop_server_tunnel_process()?;
    start_server_tunnel_process(&settings)?;
    let _ = key_path;
    Ok(current_server_tunnel_status())
}

fn mobile_bridge_settings_path() -> PathBuf {
    codex_home().join("atlas-bridge.json")
}

fn mobile_bridge_settings() -> MobileBridgeSettings {
    let mut settings = fs::read_to_string(mobile_bridge_settings_path())
        .ok()
        .and_then(|raw| serde_json::from_str::<MobileBridgeSettings>(&raw).ok())
        .unwrap_or_default();
    if let Some(value) = std::env::var_os("CODEX_ATLAS_TUNNEL_URL") {
        settings.tunnel_url = value.to_string_lossy().trim().to_string();
    }
    if let Some(value) = std::env::var_os("CODEX_ATLAS_CLOUDFLARED_PATH") {
        settings.cloudflared_path = value.to_string_lossy().trim().to_string();
    }
    if let Some(value) = std::env::var_os("CODEX_ATLAS_TUNNEL_TOKEN") {
        settings.tunnel_token = value.to_string_lossy().trim().to_string();
    }
    if let Some(value) = std::env::var_os("CODEX_ATLAS_TUNNEL_NAME") {
        settings.tunnel_name = value.to_string_lossy().trim().to_string();
    }
    settings
}

fn save_mobile_bridge_settings(settings: &MobileBridgeSettings) -> Result<(), String> {
    let raw = serde_json::to_string_pretty(settings)
        .map_err(|error| format!("encode bridge settings: {error}"))?;
    write_text_atomically(&mobile_bridge_settings_path(), &format!("{raw}\n"))
}

fn mobile_tunnel_running() -> bool {
    let Ok(mut slot) = mobile_tunnel_child().lock() else {
        return false;
    };
    let Some(child) = slot.as_mut() else {
        return false;
    };
    match child.try_wait() {
        Ok(None) => true,
        Ok(Some(status)) => {
            *slot = None;
            if let Ok(mut error) = mobile_tunnel_error().lock() {
                *error = Some(format!("cloudflared exited with {status}"));
            }
            false
        }
        Err(_) => true,
    }
}

fn mobile_tunnel_last_error() -> Option<String> {
    mobile_tunnel_error()
        .lock()
        .ok()
        .and_then(|value| value.clone())
}

fn mobile_tunnel_executable(settings: &MobileBridgeSettings) -> String {
    if !settings.cloudflared_path.trim().is_empty() {
        return settings.cloudflared_path.trim().to_string();
    }
    executable_candidate("cloudflared")
        .map(|path| path.to_string_lossy().to_string())
        .unwrap_or_else(|| "cloudflared".to_string())
}

fn launch_mobile_tunnel(settings: &MobileBridgeSettings) -> Result<(), String> {
    if settings.tunnel_url.trim().is_empty() {
        return Err("请先配置固定 Cloudflare Tunnel 地址".to_string());
    }
    if settings.tunnel_token.trim().is_empty() && settings.tunnel_name.trim().is_empty() {
        return Err("请配置 Cloudflare Tunnel token 或 tunnel name".to_string());
    }
    if mobile_tunnel_running() {
        return Ok(());
    }
    let executable = mobile_tunnel_executable(settings);
    let mut command = Command::new(&executable);
    command.args(["tunnel", "--no-autoupdate", "run"]);
    if !settings.tunnel_token.trim().is_empty() {
        command.args(["--token", settings.tunnel_token.trim()]);
    } else {
        command.arg(settings.tunnel_name.trim());
    }
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000008 | 0x08000000);
    }
    let child = command
        .spawn()
        .map_err(|error| format!("启动 cloudflared 失败: {error}"))?;
    if let Ok(mut slot) = mobile_tunnel_child().lock() {
        *slot = Some(child);
    }
    if let Ok(mut error) = mobile_tunnel_error().lock() {
        *error = None;
    }
    thread::sleep(Duration::from_millis(180));
    if mobile_tunnel_running() {
        Ok(())
    } else {
        Err(mobile_tunnel_last_error().unwrap_or_else(|| "cloudflared 未能保持运行".to_string()))
    }
}

fn stop_mobile_tunnel() -> Result<(), String> {
    let child = mobile_tunnel_child()
        .lock()
        .map_err(|_| "隧道进程状态不可用".to_string())?
        .take();
    if let Some(mut child) = child {
        let _ = child.kill();
        let _ = child.wait();
    }
    Ok(())
}

fn mobile_bridge_token() -> String {
    if let Ok(value) = std::env::var("CODEX_ATLAS_BRIDGE_TOKEN") {
        if !value.trim().is_empty() {
            return value;
        }
    }
    let path = codex_home().join("atlas-bridge-token");
    if let Ok(value) = fs::read_to_string(&path) {
        if !value.trim().is_empty() {
            return value.trim().to_string();
        }
    }
    let token = format!("atlas-{:x}-{:x}", now_ms(), std::process::id());
    let _ = write_text_atomically(&path, &format!("{token}\n"));
    token
}

fn mobile_bridge_config() -> MobileBridgeConfig {
    let host = std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|socket| {
            socket.connect("8.8.8.8:80")?;
            socket.local_addr()
        })
        .map(|address| address.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());
    let lan_url = format!("http://{host}:{MOBILE_BRIDGE_PORT}");
    let settings = mobile_bridge_settings();
    let server_settings = server_tunnel_settings();
    let direct_server_url = if settings.tunnel_url.trim().is_empty()
        && validate_server_tunnel(&server_settings).is_ok()
        && server_tunnel_key_path().exists()
    {
        Some(server_public_url(&server_settings))
    } else {
        None
    };
    let tunnel_url = (!settings.tunnel_url.trim().is_empty())
        .then(|| settings.tunnel_url.trim().trim_end_matches('/').to_string())
        .or(direct_server_url);
    let tunnel_running = mobile_tunnel_running() || server_tunnel_running();
    let use_tunnel = settings.prefer_tunnel && tunnel_url.is_some() && tunnel_running;
    let active_url = if use_tunnel {
        tunnel_url.clone().unwrap_or_else(|| lan_url.clone())
    } else {
        lan_url.clone()
    };
    let connection_mode = if use_tunnel {
        "tunnel"
    } else if tunnel_url.is_some() && tunnel_running {
        "lan-with-tunnel-fallback"
    } else {
        "lan"
    };
    let token = mobile_bridge_token();
    let mut pairing = url::form_urlencoded::Serializer::new(String::from("codex-atlas://connect"));
    pairing.append_pair("lan", &lan_url);
    if let Some(tunnel) = tunnel_url.as_deref() {
        pairing.append_pair("tunnel", tunnel);
    }
    pairing.append_pair("token", &token);
    pairing.append_pair(
        "preferTunnel",
        if settings.prefer_tunnel { "1" } else { "0" },
    );
    pairing.append_pair("mode", connection_mode);
    MobileBridgeConfig {
        url: lan_url.clone(),
        lan_url,
        tunnel_url: tunnel_url.clone(),
        active_url,
        connection_mode: connection_mode.to_string(),
        prefer_tunnel: settings.prefer_tunnel,
        auto_start_tunnel: settings.auto_start_tunnel,
        tunnel_configured: (!settings.tunnel_url.trim().is_empty()
            && (!settings.tunnel_token.trim().is_empty()
                || !settings.tunnel_name.trim().is_empty()))
            || (tunnel_url.is_some() && server_tunnel_key_path().exists()),
        tunnel_running,
        tunnel_error: mobile_tunnel_last_error(),
        token,
        port: MOBILE_BRIDGE_PORT,
        pairing_uri: pairing.finish(),
    }
}

fn mobile_session_messages(session: &SessionRecord) -> Vec<MobileSessionMessage> {
    if session.rollout_path.trim().is_empty() {
        return Vec::new();
    }
    let path = Path::new(&session.rollout_path);
    let modified_ms = file_modified_ms(path).max(session.updated_at_ms);
    let mut messages = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for (index, line) in tail_lines(path, 512 * 1024, 512).into_iter().enumerate() {
        let Ok(value) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let payload = value.get("payload").unwrap_or(&value);
        let kind = event_type(&value, payload);
        let role = payload
            .get("role")
            .or_else(|| value.get("role"))
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_ascii_lowercase();
        let (role, text) = if role == "user"
            || kind.contains("user_message")
            || (kind == "response_item" && role == "user")
        {
            let text = payload
                .get("content")
                .or_else(|| payload.get("text"))
                .or_else(|| payload.get("message"))
                .or_else(|| value.get("content"))
                .or_else(|| value.get("text"))
                .map(text_from_value)
                .unwrap_or_default();
            ("user", text)
        } else {
            let text = output_text_from_event(&value, payload).unwrap_or_default();
            let role = if kind.contains("tool")
                || kind.contains("function_call")
                || kind.contains("custom_tool")
                || kind == "item_started"
                || kind == "item_completed"
            {
                "tool"
            } else {
                "assistant"
            };
            (role, text)
        };
        let text = text.trim().to_string();
        if text.is_empty() {
            continue;
        }
        let timestamp_ms = number_from_value(
            payload,
            &[
                "completed_at_ms",
                "started_at_ms",
                "completed_at",
                "started_at",
            ],
        )
        .or_else(|| number_from_value(&value, &["timestamp_ms", "ts"]))
        .unwrap_or(modified_ms);
        let identity = event_identity(&value, payload)
            .unwrap_or_else(|| format!("{timestamp_ms}:{index}:{}", stable_text_digest(&line)));
        let id = format!("{}:{}", identity, stable_text_digest(&text));
        if !seen.insert(id.clone()) {
            continue;
        }
        messages.push(MobileSessionMessage {
            id,
            role: role.to_string(),
            text: text.chars().take(4000).collect(),
            timestamp_ms,
            kind,
        });
    }
    messages.sort_by_key(|message| message.timestamp_ms);
    messages
}

fn mobile_approval_request(session: &SessionRecord) -> Option<MobileApprovalRequest> {
    if !session.requires_attention {
        return None;
    }
    let prompt = session
        .last_error
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .or(session.last_output.as_deref())
        .unwrap_or_default()
        .trim()
        .to_string();
    if prompt.is_empty() {
        return None;
    }

    let mut options = Vec::new();
    for line in prompt.lines() {
        let trimmed = line.trim();
        let bytes = trimmed.as_bytes();
        let mut index = 0;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        if index == 0 || index >= bytes.len() || !matches!(bytes[index], b'.' | b')' | b':') {
            continue;
        }
        let label = trimmed[index + 1..].trim();
        if !label.is_empty() {
            options.push(MobileApprovalOption {
                value: trimmed[..index].to_string(),
                label: label.to_string(),
            });
        }
    }

    let lower = prompt.to_ascii_lowercase();
    let approval_language = lower.contains("allow")
        || lower.contains("approve")
        || lower.contains("permission")
        || prompt.contains("允许")
        || prompt.contains("审批")
        || prompt.contains("授权");
    let continue_language = lower.contains("continue")
        || lower.contains("proceed")
        || prompt.contains("继续")
        || prompt.contains("是否继续");
    if options.is_empty() && approval_language {
        options = vec![
            MobileApprovalOption {
                value: "1".to_string(),
                label: "Allow".to_string(),
            },
            MobileApprovalOption {
                value: "2".to_string(),
                label: "Deny".to_string(),
            },
            MobileApprovalOption {
                value: "__other__".to_string(),
                label: "Other".to_string(),
            },
        ];
    } else if options.is_empty() && continue_language {
        options = vec![
            MobileApprovalOption {
                value: "1".to_string(),
                label: "Continue".to_string(),
            },
            MobileApprovalOption {
                value: "2".to_string(),
                label: "Cancel".to_string(),
            },
            MobileApprovalOption {
                value: "__other__".to_string(),
                label: "Other".to_string(),
            },
        ];
    }
    if options.is_empty() {
        return None;
    }
    if !options.iter().any(|option| option.value == "__other__") {
        options.push(MobileApprovalOption {
            value: "__other__".to_string(),
            label: "Other".to_string(),
        });
    }
    Some(MobileApprovalRequest { prompt, options })
}

fn mobile_status_snapshot() -> Option<MobileStatusSnapshot> {
    let sessions = list_sessions_sync().ok()?;
    let session = sessions
        .iter()
        .filter(|session| session.running)
        .max_by_key(|session| {
            (
                session.foreground,
                session.last_event_at_ms.max(session.updated_at_ms),
            )
        })
        .or_else(|| sessions.first())?;
    let state = if session.running {
        session.live_state.clone()
    } else {
        "completed".to_string()
    };
    let balance = tauri::async_runtime::block_on(get_cc_switch_provider_balances())
        .ok()
        .and_then(|mut values| values.drain(..).next());
    let balance_checked_at_ms = balance
        .as_ref()
        .filter(|value| value.success && value.remaining.is_some())
        .map(|_| now_ms())
        .unwrap_or(0);
    Some(MobileStatusSnapshot {
        updated_at_ms: session.last_event_at_ms.max(session.updated_at_ms),
        session_id: session.id.clone(),
        title: session.title.clone(),
        folder: Path::new(&session.cwd)
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or_default()
            .to_string(),
        model: session.model.clone(),
        state,
        last_output: session
            .last_output
            .clone()
            .or_else(|| session.last_error.clone())
            .unwrap_or_default(),
        can_activate: true,
        can_input_continue: session.running,
        balance_remaining: balance.as_ref().and_then(|value| value.remaining),
        balance_unit: balance
            .as_ref()
            .and_then(|value| value.unit.clone())
            .unwrap_or_else(|| "USD".to_string()),
        balance_provider: balance
            .as_ref()
            .map(|value| value.name.clone())
            .unwrap_or_default(),
        balance_checked_at_ms,
        requires_attention: session.requires_attention,
        last_error: session.last_error.clone(),
        foreground: session.foreground,
        status_source: session.status_source.clone(),
        last_event_at_ms: session.last_event_at_ms,
        messages: mobile_session_messages(session),
        approval: mobile_approval_request(session),
    })
}

fn bridge_json_response<T: Serialize>(
    value: &T,
    status: u16,
) -> Response<std::io::Cursor<Vec<u8>>> {
    let body = serde_json::to_string(value).unwrap_or_else(|_| "{\"ok\":false}".to_string());
    let mut response = Response::from_string(body).with_status_code(status);
    if let Ok(header) = Header::from_bytes("Content-Type", "application/json") {
        response = response.with_header(header);
    }
    response
}

fn query_parameter(url: &str, key: &str) -> Option<String> {
    let query = url.split_once('?')?.1;
    url::form_urlencoded::parse(query.as_bytes())
        .find_map(|(candidate, value)| (candidate == key).then(|| value.into_owned()))
}

fn mobile_sync_response(since_ms: i64) -> MobileSyncResponse {
    let sessions = list_sessions_sync().unwrap_or_default();
    let _ = mobile_runtime_changed_since(&sessions, since_ms);
    let mut events = Vec::new();
    for session in &sessions {
        let messages = mobile_session_messages(session)
            .into_iter()
            .filter(|message| message.timestamp_ms > since_ms)
            .collect::<Vec<_>>();
        if !messages.is_empty() {
            events.push(MobileSessionEventBatch {
                session_id: session.id.clone(),
                messages,
            });
        }
    }
    MobileSyncResponse {
        cursor_ms: now_ms(),
        snapshot: mobile_status_snapshot(),
        sessions,
        events,
    }
}

fn mobile_sync_has_changes(since_ms: i64) -> bool {
    let Ok(sessions) = list_sessions_sync() else {
        return false;
    };
    if mobile_runtime_changed_since(&sessions, since_ms) {
        return true;
    }
    sessions.iter().any(|session| {
        session.updated_at_ms > since_ms
            || session.last_event_at_ms > since_ms
            || mobile_session_messages(session)
                .iter()
                .any(|message| message.timestamp_ms > since_ms)
    })
}

fn mobile_runtime_changed_since(sessions: &[SessionRecord], since_ms: i64) -> bool {
    let store = MOBILE_SYNC_STATE.get_or_init(|| Mutex::new(MobileSyncState::default()));
    let now = now_ms();
    let mut state = match store.lock() {
        Ok(state) => state,
        Err(_) => return false,
    };
    let active_ids = sessions
        .iter()
        .map(|session| session.id.as_str())
        .collect::<std::collections::HashSet<_>>();
    state
        .fingerprints
        .retain(|session_id, _| active_ids.contains(session_id.as_str()));
    let mut changed = false;
    for session in sessions {
        let fingerprint = format!(
            "{}|{}|{}|{}|{}|{}|{}|{}",
            session.running,
            session.live_state,
            session.foreground,
            session.requires_attention,
            session
                .process_ids
                .iter()
                .map(u32::to_string)
                .collect::<Vec<_>>()
                .join(","),
            session.last_event_at_ms,
            session.last_error.as_deref().unwrap_or_default(),
            session.last_output.as_deref().unwrap_or_default(),
        );
        let entry = state
            .fingerprints
            .entry(session.id.clone())
            .or_insert_with(|| (String::new(), now));
        if entry.0 != fingerprint {
            entry.0 = fingerprint;
            entry.1 = now;
        }
        if entry.1 > since_ms {
            changed = true;
        }
    }
    changed
}

fn wait_for_mobile_sync(since_ms: i64, wait_ms: u64) {
    if since_ms <= 0 || wait_ms == 0 {
        return;
    }
    let deadline = Instant::now() + Duration::from_millis(wait_ms.min(25_000));
    while Instant::now() < deadline {
        if mobile_sync_has_changes(since_ms) {
            return;
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn handle_mobile_bridge_request(mut request: tiny_http::Request) {
    let config = mobile_bridge_config();
    let auth = request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .map(|header| header.value.as_str().to_string())
        .unwrap_or_default();
    if auth != format!("Bearer {}", config.token) {
        let _ = request.respond(bridge_json_response(
            &serde_json::json!({"error": "unauthorized"}),
            401,
        ));
        return;
    }
    let url = request.url().to_string();
    let path = url.split_once('?').map(|(path, _)| path).unwrap_or(&url);
    let method = request.method().clone();
    if method == Method::Get && path == "/v1/sync" {
        let since_ms = query_parameter(&url, "since")
            .and_then(|value| value.parse::<i64>().ok())
            .unwrap_or(0);
        let wait_ms = query_parameter(&url, "wait")
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0);
        wait_for_mobile_sync(since_ms, wait_ms);
        let _ = request.respond(bridge_json_response(&mobile_sync_response(since_ms), 200));
        return;
    }
    if method == Method::Get && path == "/v1/status" {
        match mobile_status_snapshot() {
            Some(snapshot) => {
                let _ = request.respond(bridge_json_response(&snapshot, 200));
            }
            None => {
                let _ = request.respond(bridge_json_response(
                    &serde_json::json!({"error": "no session available"}),
                    404,
                ));
            }
        }
        return;
    }
    if method == Method::Get && url == "/v1/sessions" {
        match list_sessions_sync() {
            Ok(sessions) => {
                let _ = request.respond(bridge_json_response(&sessions, 200));
            }
            Err(error) => {
                let _ = request.respond(bridge_json_response(
                    &serde_json::json!({"error": error}),
                    500,
                ));
            }
        }
        return;
    }
    if method == Method::Get && url.starts_with("/v1/sessions/") && url.ends_with("/messages") {
        let Some(session_id) = url
            .strip_prefix("/v1/sessions/")
            .and_then(|value| value.strip_suffix("/messages"))
        else {
            let _ = request.respond(bridge_json_response(
                &serde_json::json!({"error": "not found"}),
                404,
            ));
            return;
        };
        let Some(session) = find_session(session_id) else {
            let _ = request.respond(bridge_json_response(
                &serde_json::json!({"error": "session not found"}),
                404,
            ));
            return;
        };
        let _ = request.respond(bridge_json_response(
            &mobile_session_messages(&session),
            200,
        ));
        return;
    }
    if method != Method::Post {
        let _ = request.respond(bridge_json_response(
            &serde_json::json!({"error": "method not allowed"}),
            405,
        ));
        return;
    }
    if url == "/v1/paseo/import-all" {
        let response = match paseo_import_all_codex_sessions_sync() {
            Ok(summary) => bridge_json_response(&summary, 200),
            Err(error) => bridge_json_response(&serde_json::json!({"error": error}), 422),
        };
        let _ = request.respond(response);
        return;
    }
    let mut body = String::new();
    let _ = request.as_reader().read_to_string(&mut body);
    if url == "/v1/sessions" {
        let request_body = match serde_json::from_str::<NewCodexSessionRequest>(&body) {
            Ok(request_body) => request_body,
            Err(error) => {
                let _ = request.respond(bridge_json_response(
                    &serde_json::json!({"error": format!("invalid session request: {error}")}),
                    400,
                ));
                return;
            }
        };
        let response = match create_codex_session_sync(request_body) {
            Ok(true) => bridge_json_response(&serde_json::json!({"ok": true}), 201),
            Ok(false) => bridge_json_response(&serde_json::json!({"ok": false}), 409),
            Err(error) => {
                bridge_json_response(&serde_json::json!({"ok": false, "error": error}), 422)
            }
        };
        let _ = request.respond(response);
        return;
    }
    let Some(session_id) = url.strip_prefix("/v1/sessions/").and_then(|value| {
        value
            .strip_suffix("/activate")
            .or_else(|| value.strip_suffix("/input"))
            .or_else(|| value.strip_suffix("/message"))
    }) else {
        let _ = request.respond(bridge_json_response(
            &serde_json::json!({"error": "not found"}),
            404,
        ));
        return;
    };
    let Some(session) = find_session(session_id) else {
        let _ = request.respond(bridge_json_response(
            &serde_json::json!({"error": "session not found"}),
            404,
        ));
        return;
    };
    let result = if url.ends_with("/activate") {
        if session.running {
            focus_session_terminal(&session).or_else(|_| launch_codex_resume_terminal(&session))
        } else {
            launch_codex_resume_terminal(&session)
        }
    } else {
        let input = serde_json::from_str::<MobileSessionInputRequest>(&body)
            .ok()
            .map(|request| request.text)
            .filter(|text| !text.trim().is_empty())
            .unwrap_or_else(|| "继续".to_string());
        if session.running {
            if queue_codex_message(&session, &input) {
                Ok(())
            } else {
                send_text_to_terminal(&session, &input, true)
            }
        } else if url.ends_with("/message") && queue_codex_message(&session, &input) {
            // `codex queue` can persist a message while the interactive
            // terminal is closed; the next resume consumes it.
            Ok(())
        } else if url.ends_with("/message") {
            match launch_codex_resume_terminal(&session) {
                Err(error) => Err(error),
                Ok(()) => {
                    let mut queued = false;
                    for delay in [250, 500, 750, 1_000] {
                        thread::sleep(Duration::from_millis(delay));
                        if let Some(refreshed) = find_session(session_id) {
                            if refreshed.running && queue_codex_message(&refreshed, &input) {
                                queued = true;
                                break;
                            }
                        }
                    }
                    if queued {
                        Ok(())
                    } else {
                        Err(
                            "Codex resume window opened, but the message could not be queued"
                                .to_string(),
                        )
                    }
                }
            }
        } else {
            Err(
                "session is not running; activate the session before sending terminal input"
                    .to_string(),
            )
        }
    };
    let response = match result {
        Ok(()) => bridge_json_response(&serde_json::json!({"ok": true}), 200),
        Err(error) => bridge_json_response(&serde_json::json!({"ok": false, "error": error}), 409),
    };
    let _ = request.respond(response);
}

fn spawn_mobile_bridge() {
    thread::spawn(|| {
        let Ok(server) = Server::http(("0.0.0.0", MOBILE_BRIDGE_PORT)) else {
            return;
        };
        for request in server.incoming_requests() {
            // Long-poll requests can wait for several seconds. Handle each
            // request independently so a waiting mobile client cannot block
            // status, command, or pairing requests from other clients.
            thread::spawn(move || handle_mobile_bridge_request(request));
        }
    });
}

/// Codex has emitted both Unix seconds (`started_at`) and Unix milliseconds
/// (`*_at_ms`) across releases. Keep all runtime comparisons in milliseconds.
fn normalize_epoch_ms(value: i64) -> i64 {
    let magnitude = value.unsigned_abs();
    if magnitude == 0 {
        return 0;
    }
    if magnitude < 10_000_000_000 {
        value.saturating_mul(1_000)
    } else if magnitude < 10_000_000_000_000 {
        value
    } else if magnitude < 10_000_000_000_000_000 {
        value / 1_000
    } else {
        value / 1_000_000
    }
}

fn home_dir() -> PathBuf {
    dirs::home_dir().unwrap_or_else(|| PathBuf::from("."))
}

fn codex_home() -> PathBuf {
    std::env::var_os("CODEX_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| home_dir().join(".codex"))
}

fn normalize_path(value: String) -> String {
    value.strip_prefix("\\\\?\\").unwrap_or(&value).to_string()
}

fn permission_label(approval_mode: Option<String>, sandbox_policy: Option<String>) -> String {
    let raw = approval_mode
        .filter(|value| !value.trim().is_empty())
        .or(sandbox_policy)
        .unwrap_or_else(|| "workspace-write".to_string());
    match raw.as_str() {
        "workspace-write" | "on-request" | "workspace_write" => "Workspace write".to_string(),
        "read-only" | "read_only" | "never" => "Read only".to_string(),
        "full-access" | "full_access" | "danger-full-access" => "Full access".to_string(),
        _ => raw.replace(['_', '-'], " "),
    }
}

fn session_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<SessionRecord> {
    let id: String = row.get(0)?;
    let rollout_path: String = row.get(1)?;
    let cwd: String = row.get(2)?;
    let title: String = row.get(3)?;
    let preview: String = row.get(4)?;
    let model: Option<String> = row.get(5)?;
    let provider: String = row.get(6)?;
    let branch: Option<String> = row.get(7)?;
    let updated_at_ms: Option<i64> = row.get(8)?;
    let recency_at_ms: Option<i64> = row.get(9)?;
    let created_at_ms: Option<i64> = row.get(10)?;
    let approval_mode: Option<String> = row.get(11)?;
    let sandbox_policy: Option<String> = row.get(12)?;
    let archived: i64 = row.get(13)?;
    let first_user_message: String = row.get(14)?;
    let timestamp = if recency_at_ms.unwrap_or_default() > 0 {
        recency_at_ms.unwrap_or_default()
    } else if updated_at_ms.unwrap_or_default() > 0 {
        updated_at_ms.unwrap_or_default()
    } else {
        created_at_ms.unwrap_or_default()
    };
    let title = if title.trim().is_empty() {
        first_user_message
            .lines()
            .next()
            .unwrap_or("Untitled session")
            .to_string()
    } else {
        title
    };
    let preview = if preview.trim().is_empty() {
        first_user_message.clone()
    } else {
        preview
    };
    let cwd = normalize_path(cwd);
    let search_text = format!("{} {} {} {}", title, preview, first_user_message, cwd);
    Ok(SessionRecord {
        id,
        title,
        preview,
        cwd,
        branch: branch.unwrap_or_default(),
        model: model.unwrap_or_default(),
        model_provider: provider,
        permission: permission_label(approval_mode, sandbox_policy),
        updated_at_ms: timestamp,
        created_at_ms: created_at_ms.unwrap_or(timestamp),
        rollout_path: normalize_path(rollout_path),
        archived: archived != 0,
        search_text,
        running: false,
        live_state: "unknown".to_string(),
        process_ids: Vec::new(),
        requires_attention: false,
        status_source: "database".to_string(),
        last_event_at_ms: timestamp,
        last_error: None,
        failure_key: None,
        last_output: None,
        foreground: false,
        approval: None,
    })
}

fn load_sessions_from_db() -> Result<Vec<SessionRecord>, String> {
    let db_path = codex_home().join("state_5.sqlite");
    if !db_path.exists() {
        return Err(format!("state database not found: {}", db_path.display()));
    }
    let connection = Connection::open_with_flags(
        &db_path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| format!("open state database: {error}"))?;
    let mut statement = connection
        .prepare(
            "SELECT id, rollout_path, cwd, title, preview, model, model_provider,
                    git_branch, updated_at_ms, recency_at_ms, created_at_ms,
                    approval_mode, sandbox_policy, archived, first_user_message
             FROM threads
             ORDER BY CASE WHEN recency_at_ms > 0 THEN recency_at_ms
                           WHEN updated_at_ms > 0 THEN updated_at_ms
                           ELSE created_at_ms END DESC",
        )
        .map_err(|error| format!("prepare session query: {error}"))?;
    let rows = statement
        .query_map([], session_from_row)
        .map_err(|error| format!("query sessions: {error}"))?;
    let mut sessions = Vec::new();
    for row in rows {
        sessions.push(row.map_err(|error| format!("read session row: {error}"))?);
    }
    Ok(sessions)
}

fn text_from_value(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Array(items) => items
            .iter()
            .map(text_from_value)
            .filter(|text| !text.trim().is_empty())
            .collect::<Vec<_>>()
            .join(" "),
        Value::Object(map) => {
            if let Some(text) = map.get("text") {
                return text_from_value(text);
            }
            map.values()
                .map(text_from_value)
                .collect::<Vec<_>>()
                .join(" ")
        }
        _ => String::new(),
    }
}

fn first_string_for_keys(value: &Value, keys: &[&str], depth: usize) -> Option<String> {
    if depth > 4 {
        return None;
    }
    let Value::Object(map) = value else {
        return None;
    };
    for key in keys {
        if let Some(text) = map.get(*key).and_then(Value::as_str) {
            let text = text.trim();
            if !text.is_empty() {
                return Some(text.to_string());
            }
        }
    }
    map.values()
        .find_map(|child| first_string_for_keys(child, keys, depth + 1))
}

fn model_from_rollout_event(value: &Value, payload: &Value) -> Option<String> {
    first_string_for_keys(
        payload,
        &["model", "model_name", "modelName", "model_id", "modelId"],
        0,
    )
    .or_else(|| {
        first_string_for_keys(
            value,
            &["model", "model_name", "modelName", "model_id", "modelId"],
            0,
        )
    })
}

fn provider_from_rollout_event(value: &Value, payload: &Value) -> Option<String> {
    first_string_for_keys(payload, &["model_provider", "modelProvider", "provider"], 0).or_else(
        || first_string_for_keys(value, &["model_provider", "modelProvider", "provider"], 0),
    )
}

fn scan_jsonl_sessions() -> Vec<SessionRecord> {
    scan_jsonl_sessions_with_limits(None, None, 96)
}

/// Scan rollout metadata without reading the complete conversation body. The
/// runtime poll uses a short, recent window so a newly-created session can be
/// discovered without making every three-second status tick walk the archive.
fn scan_recent_jsonl_sessions() -> Vec<SessionRecord> {
    scan_jsonl_sessions_with_limits(Some(48 * 60 * 60 * 1000), Some(128), 24)
}

fn scan_jsonl_sessions_with_limits(
    max_age_ms: Option<i64>,
    max_files: Option<usize>,
    max_lines: usize,
) -> Vec<SessionRecord> {
    let roots = [
        codex_home().join("sessions"),
        codex_home().join("archived_sessions"),
    ];
    let now = now_ms();
    let mut files = Vec::<(PathBuf, i64, bool)>::new();
    for root in roots {
        if !root.exists() {
            continue;
        }
        for entry in WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_map(Result::ok)
        {
            let path = entry.path();
            if !entry.file_type().is_file()
                || path.extension().and_then(|value| value.to_str()) != Some("jsonl")
            {
                continue;
            }
            let modified = entry
                .metadata()
                .ok()
                .and_then(|metadata| metadata.modified().ok())
                .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
                .map(|value| value.as_millis() as i64)
                .unwrap_or_else(now_ms);
            if max_age_ms
                .map(|age| now.saturating_sub(modified) > age)
                .unwrap_or(false)
            {
                continue;
            }
            let archived = path.to_string_lossy().contains("archived_sessions");
            files.push((path.to_path_buf(), modified, archived));
        }
    }
    files.sort_by(|left, right| right.1.cmp(&left.1));
    if let Some(limit) = max_files {
        files.truncate(limit);
    }
    let mut sessions = Vec::new();
    for (path, modified, archived) in files {
        let file = match File::open(&path) {
            Ok(file) => file,
            Err(_) => continue,
        };
        let mut id = path
            .file_stem()
            .and_then(|value| value.to_str())
            .unwrap_or_default()
            .to_string();
        let mut cwd = String::new();
        let mut provider = "custom".to_string();
        let mut model = String::new();
        let mut title = String::new();
        let mut preview = String::new();
        let reader = BufReader::new(file);
        for line in reader.lines().take(max_lines) {
            let line = match line {
                Ok(line) => line,
                Err(_) => break,
            };
            let value: Value = match serde_json::from_str(&line) {
                Ok(value) => value,
                Err(_) => continue,
            };
            let payload = value.get("payload").unwrap_or(&value);
            if value.get("type").and_then(Value::as_str) == Some("session_meta") {
                id = payload
                    .get("id")
                    .or_else(|| payload.get("session_id"))
                    .and_then(Value::as_str)
                    .unwrap_or(&id)
                    .to_string();
                cwd = payload
                    .get("cwd")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                provider = provider_from_rollout_event(&value, payload)
                    .unwrap_or_else(|| "custom".to_string());
            }
            if let Some(candidate) = model_from_rollout_event(&value, payload) {
                model = candidate;
            }
            if let Some(candidate) = provider_from_rollout_event(&value, payload) {
                if provider == "custom" || candidate != "custom" {
                    provider = candidate;
                }
            }
            let role = payload.get("role").and_then(Value::as_str);
            if role == Some("user") && (title.is_empty() || preview.is_empty()) {
                let text = text_from_value(payload.get("content").unwrap_or(&Value::Null));
                if !text.trim().is_empty() {
                    title = text
                        .lines()
                        .next()
                        .unwrap_or("Untitled session")
                        .trim()
                        .to_string();
                    preview = text.chars().take(240).collect();
                }
            }
        }
        if title.is_empty() {
            title = "Untitled session".to_string();
        }
        if preview.is_empty() {
            preview = title.clone();
        }
        cwd = normalize_path(cwd);
        sessions.push(SessionRecord {
            id,
            title: title.clone(),
            preview: preview.clone(),
            cwd: cwd.clone(),
            branch: String::new(),
            model,
            model_provider: provider,
            permission: "Workspace write".to_string(),
            updated_at_ms: modified,
            created_at_ms: modified,
            rollout_path: normalize_path(path.to_string_lossy().to_string()),
            archived,
            search_text: format!("{} {} {}", title, preview, cwd),
            running: false,
            live_state: "unknown".to_string(),
            process_ids: Vec::new(),
            requires_attention: false,
            status_source: "rollout".to_string(),
            last_event_at_ms: modified,
            last_error: None,
            failure_key: None,
            last_output: None,
            foreground: false,
            approval: None,
        });
    }
    // A rollout may briefly exist in both `sessions` and
    // `archived_sessions` while the SQLite index is being updated. Collapse
    // those duplicates before matching processes to threads.
    let mut unique = HashMap::<String, SessionRecord>::new();
    for session in sessions {
        match unique.get_mut(&session.id) {
            Some(current) => {
                if session.updated_at_ms > current.updated_at_ms {
                    let replacement = session;
                    *current = replacement;
                }
            }
            None => {
                unique.insert(session.id.clone(), session);
            }
        }
    }
    let mut sessions = unique.into_values().collect::<Vec<_>>();
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    sessions
}

fn normalized_path_for_match(value: &str) -> String {
    let mut normalized = normalize_path(value.to_string())
        .replace('/', "\\")
        .trim()
        .to_ascii_lowercase();
    while normalized.len() > 3 && normalized.ends_with('\\') {
        normalized.pop();
    }
    normalized
}

fn path_is_equal(left: &str, right: &str) -> bool {
    let left = normalized_path_for_match(left);
    let right = normalized_path_for_match(right);
    !left.is_empty() && left == right
}

fn process_command_line(process: &sysinfo::Process) -> String {
    process
        .cmd()
        .iter()
        .map(|part| part.to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join(" ")
}

fn process_basename(value: &str) -> String {
    value
        .rsplit(['\\', '/'])
        .next()
        .unwrap_or(value)
        .to_ascii_lowercase()
}

fn is_codex_cli_name(value: &str) -> bool {
    let basename = process_basename(value);
    matches!(
        basename.as_str(),
        "codex" | "codex.exe" | "codex.cmd" | "codex.bat" | "codex-cli" | "codex-cli.exe"
    )
}

fn looks_like_codex_process(name: &str, command_line: &str, exe: Option<&str>) -> bool {
    let process_name = process_basename(name);
    if is_codex_cli_name(&process_name) || exe.map(is_codex_cli_name).unwrap_or(false) {
        return true;
    }
    let lower_command = command_line.to_ascii_lowercase();
    matches!(
        process_name.as_str(),
        "node" | "node.exe" | "bun" | "bun.exe"
    ) && (lower_command.contains("codex.js")
        || lower_command.contains("@openai/codex")
        || lower_command.contains("\\codex\\bin\\"))
}

fn looks_like_codex_native_process(name: &str, command_line: &str, exe: Option<&str>) -> bool {
    if matches!(
        process_basename(name).as_str(),
        "node" | "node.exe" | "bun" | "bun.exe"
    ) {
        return false;
    }
    // The Codex app-server is a background protocol process, not an
    // interactive user session. Counting it as a session makes the status
    // light permanently active and steals terminal assignments.
    if command_line
        .split_whitespace()
        .any(|part| part.eq_ignore_ascii_case("app-server"))
    {
        return false;
    }
    looks_like_codex_process(name, command_line, exe)
}

fn looks_like_node_wrapper(name: &str, command_line: &str) -> bool {
    let process_name = process_basename(name);
    matches!(
        process_name.as_str(),
        "node" | "node.exe" | "bun" | "bun.exe"
    ) && (command_line.to_ascii_lowercase().contains("codex.js")
        || command_line.to_ascii_lowercase().contains("@openai/codex"))
}

fn session_id_like(value: &str) -> bool {
    let value = value
        .trim_matches(|character: char| !character.is_ascii_alphanumeric() && character != '-');
    if value.len() != 36 {
        return false;
    }
    value.chars().enumerate().all(|(index, character)| {
        if matches!(index, 8 | 13 | 18 | 23) {
            character == '-'
        } else {
            character.is_ascii_hexdigit()
        }
    })
}

fn extract_session_hint(command_line: &str) -> Option<String> {
    let tokens = command_line
        .split(|character: char| character.is_whitespace() || matches!(character, '"' | '\''))
        .filter(|token| !token.trim().is_empty())
        .collect::<Vec<_>>();
    for (index, token) in tokens.iter().enumerate() {
        if session_id_like(token) {
            return Some(
                token
                    .trim_matches(|character: char| {
                        !character.is_ascii_alphanumeric() && character != '-'
                    })
                    .to_string(),
            );
        }
        let lower = token.to_ascii_lowercase();
        if matches!(lower.as_str(), "--thread" | "--session" | "--session-id") {
            if let Some(next) = tokens.get(index + 1) {
                if session_id_like(next) {
                    return Some(next.to_string());
                }
            }
        }
    }
    None
}

fn looks_like_terminal_wrapper(name: &str) -> bool {
    matches!(
        process_basename(name).as_str(),
        "powershell"
            | "powershell.exe"
            | "pwsh"
            | "pwsh.exe"
            | "cmd"
            | "cmd.exe"
            | "bash"
            | "bash.exe"
            | "zsh"
            | "zsh.exe"
            | "sh"
            | "sh.exe"
            | "fish"
            | "fish.exe"
            | "wt"
            | "wt.exe"
            | "openconsole"
            | "openconsole.exe"
            | "conhost"
            | "conhost.exe"
            | "windowsterminal.exe"
    )
}

fn extract_working_directory(command_line: &str) -> Option<String> {
    let lower = command_line.to_ascii_lowercase();
    // `-c` is a common PowerShell/cmd alias for `-Command`; treating it as a
    // working-directory flag produces false paths such as `Get-CimInstance`.
    for marker in ["-workingdirectory", "--working-directory", "--cwd"] {
        let Some(index) = lower.find(marker) else {
            continue;
        };
        let remainder = command_line[index + marker.len()..].trim_start();
        let remainder = remainder
            .strip_prefix('=')
            .unwrap_or(remainder)
            .trim_start();
        if remainder.is_empty() {
            continue;
        }
        if let Some(quoted) = remainder.strip_prefix('"') {
            if let Some(end) = quoted.find('"') {
                let value = quoted[..end].trim();
                if !value.is_empty() {
                    return Some(value.to_string());
                }
            }
        } else if let Some(quoted) = remainder.strip_prefix('\'') {
            if let Some(end) = quoted.find('\'') {
                let value = quoted[..end].trim();
                if !value.is_empty() {
                    return Some(value.to_string());
                }
            }
        } else if let Some(value) = remainder.split_whitespace().next() {
            if !value.is_empty() {
                return Some(value.trim_matches('"').trim_matches('\'').to_string());
            }
        }
    }
    None
}

fn process_snapshots() -> Vec<ProcessSnapshot> {
    let refresh = ProcessRefreshKind::nothing()
        .with_cmd(UpdateKind::Always)
        .with_cwd(UpdateKind::Always)
        .with_exe(UpdateKind::Always);
    let mut system = System::new_with_specifics(RefreshKind::nothing().with_processes(refresh));
    // A second refresh fills command/cwd values for processes created during
    // the initial Toolhelp snapshot on Windows without collecting CPU/memory.
    let _ = system.refresh_processes_specifics(ProcessesToUpdate::All, true, refresh);
    system
        .processes()
        .values()
        .map(|process| ProcessSnapshot {
            pid: process.pid().as_u32(),
            parent_pid: process.parent().map(|pid| pid.as_u32()),
            name: process.name().to_string_lossy().to_string(),
            command_line: process_command_line(process),
            exe: process
                .exe()
                .map(|path| normalize_path(path.to_string_lossy().to_string()))
                .filter(|path| !path.trim().is_empty()),
            cwd: process
                .cwd()
                .map(|path| normalize_path(path.to_string_lossy().to_string()))
                .filter(|path| !path.trim().is_empty()),
            start_time_ms: (process.start_time() as i64).saturating_mul(1000),
        })
        .collect()
}

/// Console hosts are children of PowerShell/cmd rather than ancestors of the
/// Codex process. Include them in the matched process tree so Win32 window
/// lookup can find the real terminal window (including legacy conhost-backed
/// tabs launched from Windows Terminal).
fn append_terminal_descendants(process_ids: &mut Vec<u32>, by_pid: &HashMap<u32, ProcessSnapshot>) {
    let mut queue = process_ids
        .iter()
        .copied()
        .filter(|pid| {
            by_pid
                .get(pid)
                .map(|process| looks_like_terminal_wrapper(&process.name))
                .unwrap_or(false)
        })
        .collect::<Vec<_>>();
    let mut seen = process_ids
        .iter()
        .copied()
        .collect::<std::collections::HashSet<_>>();
    while let Some(parent_pid) = queue.pop() {
        for child in by_pid
            .values()
            .filter(|process| process.parent_pid == Some(parent_pid))
        {
            if !looks_like_terminal_wrapper(&child.name) || !seen.insert(child.pid) {
                continue;
            }
            process_ids.push(child.pid);
            queue.push(child.pid);
        }
    }
}

#[cfg(target_os = "windows")]
fn foreground_process_id() -> Option<u32> {
    let window = unsafe { GetForegroundWindow() };
    if window.is_null() {
        return None;
    }
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(window, &mut pid) };
    (pid > 0).then_some(pid)
}

#[cfg(target_os = "windows")]
fn foreground_window_title() -> Option<String> {
    let window = unsafe { GetForegroundWindow() };
    if window.is_null() {
        return None;
    }
    let mut buffer = [0u16; 512];
    let length = unsafe { GetWindowTextW(window, buffer.as_mut_ptr(), buffer.len() as i32) };
    if length <= 0 {
        return None;
    }
    String::from_utf16(&buffer[..length as usize])
        .ok()
        .filter(|value| !value.trim().is_empty())
}

#[cfg(not(target_os = "windows"))]
fn foreground_window_title() -> Option<String> {
    None
}

#[cfg(not(target_os = "windows"))]
fn foreground_process_id() -> Option<u32> {
    None
}

fn window_title_matches_session(title: &str, session: &SessionRecord) -> bool {
    let title = title.trim().to_lowercase();
    if title.is_empty() {
        return false;
    }
    if !session.id.trim().is_empty() && title.contains(&session.id.to_lowercase()) {
        return true;
    }
    Path::new(&session.cwd)
        .file_name()
        .and_then(|value| value.to_str())
        .map(str::trim)
        .filter(|value| value.len() >= 3)
        .map(|value| title.contains(&value.to_lowercase()))
        .unwrap_or(false)
}

#[cfg(target_os = "windows")]
struct WindowSearch {
    process_ids: Vec<u32>,
    window: HWND,
}

#[cfg(target_os = "windows")]
struct WindowTitleSearch {
    needle: String,
    window: HWND,
}

#[cfg(target_os = "windows")]
fn window_class_name(window: HWND) -> String {
    let mut buffer = [0u16; 128];
    let length = unsafe { GetClassNameW(window, buffer.as_mut_ptr(), buffer.len() as i32) };
    String::from_utf16_lossy(&buffer[..length.max(0) as usize]).to_lowercase()
}

#[cfg(target_os = "windows")]
fn window_title(window: HWND) -> String {
    let mut buffer = [0u16; 512];
    let length = unsafe { GetWindowTextW(window, buffer.as_mut_ptr(), buffer.len() as i32) };
    if length <= 0 {
        return String::new();
    }
    String::from_utf16_lossy(&buffer[..length as usize])
}

#[cfg(target_os = "windows")]
fn is_terminal_window(window: HWND) -> bool {
    if window.is_null() || unsafe { IsWindowVisible(window) } == 0 {
        return false;
    }
    let class_name = window_class_name(window);
    class_name.contains("cascadia_hosting_window_class")
        || class_name.contains("consolewindowclass")
        || class_name.contains("mintty")
        || class_name.contains("conemu")
}

#[cfg(target_os = "windows")]
fn foreground_terminal_window() -> Option<HWND> {
    let window = unsafe { GetForegroundWindow() };
    is_terminal_window(window).then_some(window)
}

#[cfg(target_os = "windows")]
fn window_is_foreground(window: HWND) -> bool {
    !window.is_null() && unsafe { GetForegroundWindow() == window }
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn find_process_window(window: HWND, parameter: LPARAM) -> i32 {
    let search = unsafe { &mut *(parameter as *mut WindowSearch) };
    if !is_terminal_window(window) {
        return 1;
    }
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(window, &mut pid) };
    if search.process_ids.contains(&pid) {
        search.window = window;
        return 0;
    }
    1
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn find_title_window(window: HWND, parameter: LPARAM) -> i32 {
    let search = unsafe { &mut *(parameter as *mut WindowTitleSearch) };
    if !is_terminal_window(window) {
        return 1;
    }
    let title = window_title(window).to_lowercase();
    if title.is_empty() {
        return 1;
    }
    if title.contains(&search.needle) {
        search.window = window;
        return 0;
    }
    1
}

#[cfg(target_os = "windows")]
fn terminal_window_for_processes(process_ids: &[u32]) -> Option<HWND> {
    let foreground = foreground_terminal_window();
    if let Some(foreground) = foreground {
        let mut foreground_pid = 0u32;
        unsafe { GetWindowThreadProcessId(foreground, &mut foreground_pid) };
        if process_ids.contains(&foreground_pid) {
            return Some(foreground);
        }
    }
    let mut search = WindowSearch {
        process_ids: process_ids.to_vec(),
        window: std::ptr::null_mut(),
    };
    unsafe {
        EnumWindows(
            Some(find_process_window),
            (&mut search as *mut WindowSearch) as LPARAM,
        )
    };
    (!search.window.is_null()).then_some(search.window)
}

#[cfg(target_os = "windows")]
fn terminal_window_for_session(session: &SessionRecord) -> Option<HWND> {
    // Windows Terminal owns the visible tab window from a separate
    // WindowsTerminal.exe process. Prefer the active terminal when its title
    // identifies this workspace, even though its PID is not in the Codex
    // process tree.
    if let Some(foreground) = foreground_terminal_window() {
        let title = window_title(foreground);
        if window_title_matches_session(&title, session) {
            return Some(foreground);
        }
    }
    if let Some(window) = terminal_window_for_processes(&session.process_ids) {
        return Some(window);
    }
    let needle = Path::new(&session.cwd)
        .file_name()
        .and_then(|value| value.to_str())?
        .trim()
        .to_lowercase();
    if needle.len() < 3 {
        return None;
    }
    let mut search = WindowTitleSearch {
        needle,
        window: std::ptr::null_mut(),
    };
    unsafe {
        EnumWindows(
            Some(find_title_window),
            (&mut search as *mut WindowTitleSearch) as LPARAM,
        )
    };
    (!search.window.is_null()).then_some(search.window)
}

#[cfg(target_os = "windows")]
fn keyboard_input(vk: u16, scan: u16, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: scan,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

#[cfg(target_os = "windows")]
fn focus_terminal_window(window: HWND) -> Result<(), String> {
    if window_is_foreground(window) {
        return Ok(());
    }
    let current_thread = unsafe { GetCurrentThreadId() };
    let mut target_pid = 0u32;
    let target_thread = unsafe { GetWindowThreadProcessId(window, &mut target_pid) };
    let foreground = unsafe { GetForegroundWindow() };
    let mut foreground_pid = 0u32;
    let foreground_thread = if foreground.is_null() {
        0
    } else {
        unsafe { GetWindowThreadProcessId(foreground, &mut foreground_pid) }
    };
    let mut attached = Vec::new();
    for thread_id in [target_thread, foreground_thread] {
        if thread_id == 0 || thread_id == current_thread || attached.contains(&thread_id) {
            continue;
        }
        if unsafe { AttachThreadInput(current_thread, thread_id, 1) } != 0 {
            attached.push(thread_id);
        }
    }
    unsafe {
        ShowWindow(window, SW_RESTORE);
        BringWindowToTop(window);
    }
    // SetForegroundWindow can be rejected once by Windows focus-stealing
    // protection. Retry briefly and verify the actual foreground handle.
    for _ in 0..4 {
        unsafe {
            SetForegroundWindow(window);
            BringWindowToTop(window);
        }
        thread::sleep(Duration::from_millis(55));
        if window_is_foreground(window) {
            for thread_id in attached.drain(..).rev() {
                unsafe { AttachThreadInput(current_thread, thread_id, 0) };
            }
            return Ok(());
        }
    }
    for thread_id in attached {
        unsafe { AttachThreadInput(current_thread, thread_id, 0) };
    }
    Err("Windows did not allow the Codex terminal to receive focus".to_string())
}

#[cfg(target_os = "windows")]
fn send_keyboard_events(events: &[INPUT]) -> Result<(), String> {
    if events.is_empty() {
        return Ok(());
    }
    let sent = unsafe {
        SendInput(
            events.len() as u32,
            events.as_ptr(),
            std::mem::size_of::<INPUT>() as i32,
        )
    };
    if sent != events.len() as u32 {
        return Err(format!(
            "Windows accepted {sent} of {} keyboard events",
            events.len()
        ));
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn send_text_to_terminal(
    session: &SessionRecord,
    input: &str,
    focus_terminal: bool,
) -> Result<(), String> {
    let window = terminal_window_for_session(session)
        .or_else(|| {
            if !session.foreground {
                return None;
            }
            foreground_terminal_window()
        })
        .ok_or_else(|| "no terminal window was found for the running session".to_string())?;
    let foreground = unsafe { GetForegroundWindow() };
    if foreground != window {
        if !focus_terminal {
            return Err("the Codex terminal is not the active window".to_string());
        }
        focus_terminal_window(window)?;
    }

    let mut text_inputs = Vec::with_capacity(input.encode_utf16().count() * 2);
    for unit in input.encode_utf16() {
        text_inputs.push(keyboard_input(0, unit, KEYEVENTF_UNICODE));
        text_inputs.push(keyboard_input(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
    }
    send_keyboard_events(&text_inputs)?;
    // Give the terminal's pseudo-console a scheduling turn before submitting
    // Enter. Sending both batches in one call can drop the final character on
    // busy Codex screens.
    thread::sleep(Duration::from_millis(35));
    let enter_inputs = [
        keyboard_input(VK_RETURN, 0, 0),
        keyboard_input(VK_RETURN, 0, KEYEVENTF_KEYUP),
    ];
    send_keyboard_events(&enter_inputs)?;
    if !window_is_foreground(window) {
        return Err("Codex terminal lost focus before the prompt was submitted".to_string());
    }
    Ok(())
}

#[cfg(not(target_os = "windows"))]
fn send_text_to_terminal(
    _session: &SessionRecord,
    _input: &str,
    _focus_terminal: bool,
) -> Result<(), String> {
    Err("terminal input fallback is currently available on Windows only".to_string())
}

#[cfg(target_os = "windows")]
fn focus_session_terminal(session: &SessionRecord) -> Result<(), String> {
    let window = terminal_window_for_session(session)
        .ok_or_else(|| "no terminal window was found for the running session".to_string())?;
    focus_terminal_window(window)
}

#[cfg(not(target_os = "windows"))]
fn focus_session_terminal(_session: &SessionRecord) -> Result<(), String> {
    Err("focusing an existing terminal is currently available on Windows only".to_string())
}

fn codex_process_candidates() -> Vec<ProcessCandidate> {
    let snapshots = process_snapshots();
    let by_pid: HashMap<u32, ProcessSnapshot> = snapshots
        .iter()
        .cloned()
        .map(|process| (process.pid, process))
        .collect();
    let native_pids: Vec<u32> = snapshots
        .iter()
        .filter(|process| {
            looks_like_codex_native_process(
                &process.name,
                &process.command_line,
                process.exe.as_deref(),
            )
        })
        .map(|process| process.pid)
        .collect();
    let mut covered = std::collections::HashSet::new();
    let mut candidates = Vec::new();

    for pid in native_pids {
        let Some(root) = by_pid.get(&pid) else {
            continue;
        };
        let mut process_ids = Vec::new();
        let mut command_parts = Vec::new();
        let mut cwd = root.cwd.clone();
        let mut current = Some(root.clone());
        let mut seen = std::collections::HashSet::new();
        for _ in 0..8 {
            let Some(process) = current else {
                break;
            };
            if !seen.insert(process.pid) {
                break;
            }
            covered.insert(process.pid);
            process_ids.push(process.pid);
            if !process.command_line.trim().is_empty()
                && !command_parts.contains(&process.command_line)
            {
                command_parts.push(process.command_line.clone());
            }
            if cwd.is_none() {
                cwd = process.cwd.clone();
            }
            current = process.parent_pid.and_then(|parent| {
                let next = by_pid.get(&parent).cloned();
                next.filter(|next| {
                    looks_like_node_wrapper(&next.name, &next.command_line)
                        || looks_like_terminal_wrapper(&next.name)
                })
            });
        }
        append_terminal_descendants(&mut process_ids, &by_pid);
        if cwd.is_none() {
            cwd = command_parts
                .iter()
                .find_map(|command| extract_working_directory(command));
        }
        candidates.push(ProcessCandidate {
            pid,
            process_ids,
            command_line: command_parts.join(" | "),
            session_hint: extract_session_hint(&command_parts.join(" | ")),
            cwd,
            start_time_ms: root.start_time_ms,
        });
    }

    // npm installs normally expose a node wrapper and a native child. Keep a
    // node-only candidate as a fallback for short process-start races or
    // restricted process snapshots where the native child is not visible.
    for root in snapshots
        .iter()
        .filter(|process| looks_like_node_wrapper(&process.name, &process.command_line))
    {
        if covered.contains(&root.pid) {
            continue;
        }
        let mut process_ids = Vec::new();
        let mut command_parts = Vec::new();
        let mut cwd = root.cwd.clone();
        let mut current = Some(root.clone());
        let mut seen = std::collections::HashSet::new();
        for _ in 0..8 {
            let Some(process) = current else {
                break;
            };
            if !seen.insert(process.pid) {
                break;
            }
            process_ids.push(process.pid);
            if !process.command_line.trim().is_empty()
                && !command_parts.contains(&process.command_line)
            {
                command_parts.push(process.command_line.clone());
            }
            if cwd.is_none() {
                cwd = process.cwd.clone();
            }
            current = process.parent_pid.and_then(|parent| {
                let next = by_pid.get(&parent).cloned();
                next.filter(|next| {
                    looks_like_node_wrapper(&next.name, &next.command_line)
                        || looks_like_terminal_wrapper(&next.name)
                })
            });
        }
        append_terminal_descendants(&mut process_ids, &by_pid);
        if cwd.is_none() {
            cwd = command_parts
                .iter()
                .find_map(|command| extract_working_directory(command));
        }
        candidates.push(ProcessCandidate {
            pid: root.pid,
            process_ids,
            command_line: command_parts.join(" | "),
            session_hint: extract_session_hint(&command_parts.join(" | ")),
            cwd,
            start_time_ms: root.start_time_ms,
        });
    }
    candidates.sort_by_key(|candidate| candidate.pid);
    candidates
}

fn file_modified_ms(path: &Path) -> i64 {
    fs::metadata(path)
        .ok()
        .and_then(|metadata| metadata.modified().ok())
        .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default()
}

fn file_created_ms(path: &Path) -> i64 {
    fs::metadata(path)
        .ok()
        .and_then(|metadata| metadata.created().ok())
        .and_then(|created| created.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default()
}

fn tail_lines(path: &Path, max_bytes: usize, max_lines: usize) -> Vec<String> {
    let Ok(mut file) = File::open(path) else {
        return Vec::new();
    };
    let Ok(length) = file.metadata().map(|metadata| metadata.len()) else {
        return Vec::new();
    };
    let start = length.saturating_sub(max_bytes as u64);
    if file.seek(SeekFrom::Start(start)).is_err() {
        return Vec::new();
    }
    let mut bytes = Vec::with_capacity((length - start) as usize);
    if file.read_to_end(&mut bytes).is_err() {
        return Vec::new();
    }
    let text = String::from_utf8_lossy(&bytes);
    let text = if start > 0 {
        text.split_once('\n').map(|(_, tail)| tail).unwrap_or("")
    } else {
        text.as_ref()
    };
    let mut lines = text
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(ToString::to_string)
        .collect::<Vec<_>>();
    if lines.len() > max_lines {
        let keep_from = lines.len() - max_lines;
        lines.drain(..keep_from);
    }
    lines
}

fn number_from_value(value: &Value, keys: &[&str]) -> Option<i64> {
    for key in keys {
        let Some(candidate) = value.get(*key) else {
            continue;
        };
        if let Some(number) = candidate.as_i64() {
            return Some(normalize_epoch_ms(number));
        }
        if let Some(number) = candidate.as_u64() {
            return i64::try_from(number).ok().map(normalize_epoch_ms);
        }
        if let Some(text) = candidate.as_str() {
            if let Ok(number) = text.trim().parse::<i64>() {
                return Some(normalize_epoch_ms(number));
            }
        }
    }
    None
}

fn error_text_from_value(value: &Value) -> Option<String> {
    let error = value
        .get("error")
        .or_else(|| value.get("last_error"))
        .or_else(|| value.get("lastError"));
    let text = if let Some(error) = error {
        match error {
            Value::String(text) => text.clone(),
            Value::Object(map) => map
                .get("message")
                .or_else(|| map.get("error"))
                .map(text_from_value)
                .unwrap_or_else(|| text_from_value(error)),
            _ => text_from_value(error),
        }
    } else {
        // Some older rollouts encode an error event as `{type: "error",
        // message: ...}` instead of nesting it under `error`.
        let kind = value
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_ascii_lowercase();
        if (kind == "error" || kind.contains("failed")) && value.get("message").is_some() {
            text_from_value(value.get("message").unwrap_or(&Value::Null))
        } else {
            String::new()
        }
    };
    let text = text.trim();
    (!text.is_empty()).then(|| text.chars().take(1200).collect())
}

/// Pull a compact, human-readable line from the Codex JSONL event stream.
/// Ping Island uses the same rollout families (`agent_message`, `response_item`,
/// tool calls and reasoning), so keeping this extraction event-oriented avoids
/// exposing raw JSON in the desktop widget.
fn output_text_from_event(value: &Value, payload: &Value) -> Option<String> {
    let kind = event_type(value, payload);
    let role = payload
        .get("role")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let mut text = String::new();
    if kind.contains("approval")
        || kind.contains("permission")
        || kind.contains("input_required")
        || kind.contains("user_input")
    {
        let prompt = payload
            .get("prompt")
            .or_else(|| payload.get("question"))
            .or_else(|| payload.get("message"))
            .or_else(|| payload.get("content"))
            .or_else(|| payload.get("text"))
            .or_else(|| value.get("prompt"))
            .or_else(|| value.get("question"))
            .or_else(|| value.get("message"))
            .map(text_from_value)
            .unwrap_or_default();
        let options = payload
            .get("options")
            .or_else(|| payload.get("choices"))
            .or_else(|| value.get("options"))
            .map(text_from_value)
            .unwrap_or_default();
        text = if options.trim().is_empty() {
            prompt
        } else if prompt.trim().is_empty() {
            options
        } else {
            format!("{prompt}\n{options}")
        };
    } else if kind.contains("reasoning") {
        text = payload
            .get("summary")
            .or_else(|| payload.get("content"))
            .map(text_from_value)
            .unwrap_or_default();
        if text.trim().is_empty() {
            text = "Thinking…".to_string();
        }
    } else if kind == "item_started" || kind == "item_completed" {
        let item = payload.get("item").unwrap_or(payload);
        let item_type = item.get("type").and_then(Value::as_str).unwrap_or("tool");
        let name = item
            .get("name")
            .or_else(|| item.get("command"))
            .or_else(|| item.get("command_line"))
            .map(text_from_value)
            .unwrap_or_default();
        text = if name.trim().is_empty() {
            if kind == "item_completed" {
                format!("Tool completed: {item_type}")
            } else {
                format!("Running: {item_type}")
            }
        } else if kind == "item_completed" {
            format!("Tool completed: {name}")
        } else {
            format!("Running: {name}")
        };
    } else if kind.contains("custom_tool_call") || kind.contains("function_call") {
        let name = payload
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("tool");
        let arguments = payload
            .get("arguments")
            .or_else(|| payload.get("input"))
            .map(text_from_value)
            .unwrap_or_default();
        text = if arguments.trim().is_empty() {
            format!("Running tool: {name}")
        } else {
            format!("Running tool: {name} · {arguments}")
        };
    } else if kind.contains("tool_output")
        || kind.contains("function_call_output")
        || kind.contains("custom_tool_call_output")
    {
        text = payload
            .get("output")
            .or_else(|| payload.get("result"))
            .or_else(|| payload.get("content"))
            .map(text_from_value)
            .unwrap_or_default();
        if text.trim().is_empty() {
            text = "Tool completed".to_string();
        }
    } else if kind.contains("agent_message")
        || kind.contains("assistant")
        || role == "assistant"
        || (kind == "message" && role != "user")
    {
        text = payload
            .get("message")
            .or_else(|| payload.get("content"))
            .or_else(|| payload.get("text"))
            .map(text_from_value)
            .unwrap_or_default();
    }
    compact_output_line(&text)
}

fn compact_output_line(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if is_approval_prompt(trimmed) {
        // Approval text is user-facing context. Keep its line breaks and the
        // complete prompt so the CRT can wrap and scroll it instead of
        // silently hiding the command or the available choices.
        return (!trimmed.is_empty()).then(|| trimmed.to_string());
    }
    let compact = text
        .chars()
        .map(|character| {
            if character.is_control() || character == '\n' || character == '\r' || character == '\t'
            {
                ' '
            } else {
                character
            }
        })
        .collect::<String>();
    let compact = compact.split_whitespace().collect::<Vec<_>>().join(" ");
    (!compact.is_empty()).then(|| compact.chars().take(320).collect())
}

fn is_approval_prompt(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    [
        "approval",
        "approve",
        "authorize",
        "permission request",
        "allow this",
        "allow once",
        "do you want",
        "would you like",
        "please confirm",
        "please choose",
        "please select",
        "[y/n]",
        "[y/n?]",
        "[yes/no]",
        "[yes/no?]",
        "y/n",
        "yes/no",
    ]
    .iter()
    .any(|marker| lower.contains(marker))
        || lower.contains("proceed?")
        || lower.contains("continue?")
        || text.contains("审批")
        || text.contains("批准")
        || text.contains("授权")
        || text.contains("是否允许")
        || text.contains("是否继续")
        || text.contains("要继续吗")
}

fn stable_text_digest(text: &str) -> String {
    // FNV-1a is tiny, deterministic, and avoids pulling a hashing crate into
    // the desktop shell just to deduplicate a rollout event.
    let mut hash = 14_695_981_039_346_656_037u64;
    for byte in text.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(1_099_511_628_211);
    }
    format!("{hash:016x}")
}

fn event_identity(value: &Value, payload: &Value) -> Option<String> {
    for candidate in [
        payload.get("turn_id"),
        payload.get("turnId"),
        payload.get("event_id"),
        payload.get("eventId"),
        payload.get("id"),
        value.get("ordinal"),
        value.get("id"),
    ] {
        let Some(candidate) = candidate else {
            continue;
        };
        let identity = match candidate {
            Value::String(text) => text.trim().to_string(),
            Value::Number(number) => number.to_string(),
            _ => String::new(),
        };
        if !identity.is_empty() {
            return Some(identity);
        }
    }
    None
}

fn is_insufficient_balance_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    let forbidden = lower.contains("403") || lower.contains("forbidden");
    let balance_cause = lower.contains("insufficient balance")
        || lower.contains("insufficient credit")
        || lower.contains("insufficient quota")
        || lower.contains("balance depleted")
        || lower.contains("balance exhausted")
        || lower.contains("balance is empty")
        || lower.contains("credit exhausted")
        || lower.contains("quota exceeded")
        || lower.contains("no remaining balance")
        || lower.contains("no remaining credit")
        || error.contains("余额不足");
    forbidden && balance_cause
}

fn event_type(value: &Value, payload: &Value) -> String {
    payload
        .get("type")
        .and_then(Value::as_str)
        .or_else(|| value.get("type").and_then(Value::as_str))
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn observation_state_for_event(
    value: &Value,
    payload: &Value,
    error: Option<&str>,
) -> Option<(&'static str, bool)> {
    let kind = event_type(value, payload);
    if let Some(error) = error {
        // A task-complete error is terminal for the turn, but the interactive
        // process may remain open waiting for a user retry.
        return Some((
            if is_insufficient_balance_error(error) {
                "failed"
            } else {
                "waiting"
            },
            true,
        ));
    }
    if kind.contains("approval")
        || kind.contains("permission")
        || kind.contains("input_required")
        || kind.contains("user_input")
        || kind.contains("attention")
    {
        return Some(("waiting", true));
    }
    if kind.contains("task_complete")
        || kind.contains("task_completed")
        || kind.contains("turn_complete")
        || kind.contains("turn_completed")
        || kind.contains("agent_turn_completed")
    {
        return Some(("completed", false));
    }
    if kind.contains("task_failed")
        || kind.contains("turn_failed")
        || kind.contains("turn_aborted")
        || kind == "error"
    {
        return Some(("failed", true));
    }
    if kind.contains("task_started")
        || kind.contains("turn_started")
        || kind.contains("item_started")
        || kind.contains("response_started")
        || kind.contains("user_message")
    {
        return Some(("working", false));
    }
    if value.get("type").and_then(Value::as_str) == Some("response_item")
        && payload.get("role").and_then(Value::as_str) == Some("user")
    {
        return Some(("working", false));
    }
    // `item_completed` is emitted between tool calls and is therefore a
    // better indication of an active stream than an old database timestamp.
    if kind == "item_completed" {
        return Some(("working", false));
    }
    None
}

fn observe_rollout(path: &str, fallback_ms: i64) -> RolloutObservation {
    let path = Path::new(path);
    let modified_ms = file_modified_ms(path).max(fallback_ms);
    let lines = tail_lines(path, 256 * 1024, 256);
    let mut observation = RolloutObservation {
        state: "unknown".to_string(),
        last_event_at_ms: modified_ms,
        ..RolloutObservation::default()
    };
    for line in &lines {
        let Ok(value) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        let payload = value.get("payload").unwrap_or(&value);
        let error = error_text_from_value(payload).or_else(|| error_text_from_value(&value));
        let timestamp = number_from_value(
            payload,
            &[
                "completed_at_ms",
                "started_at_ms",
                "completed_at",
                "started_at",
            ],
        )
        .or_else(|| number_from_value(&value, &["timestamp_ms", "ts"]))
        .unwrap_or(modified_ms);
        if let Some(output) = output_text_from_event(&value, payload) {
            observation.last_output = Some(output);
        }
        if let Some((state, requires_attention)) =
            observation_state_for_event(&value, payload, error.as_deref())
        {
            observation.state = state.to_string();
            observation.requires_attention = requires_attention;
            observation.last_event_at_ms = observation.last_event_at_ms.max(timestamp);
            if let Some(error) = error {
                observation.last_error = Some(error.clone());
                let identity = event_identity(&value, payload);
                let digest = stable_text_digest(&error);
                observation.failure_key = Some(match identity {
                    Some(identity) => format!("{identity}:{digest}"),
                    // Older files may not have an ordinal/turn id. The raw
                    // line digest is stable even as the tail window moves.
                    None => format!("{timestamp}:{}", stable_text_digest(line)),
                });
            } else if state == "working" || state == "completed" {
                // A subsequent successful event clears the visual incident;
                // the failure counter itself is managed by the recovery guard.
                observation.last_error = None;
                observation.failure_key = None;
            }
        }
    }
    observation
}

fn hook_state_paths() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Some(path) = std::env::var_os("CODEX_ATLAS_HOOK_STATE_PATH") {
        paths.push(PathBuf::from(path));
    }
    if let Some(path) = std::env::var_os("CODEX_TRAFFIC_LIGHT_STATE_PATH") {
        paths.push(PathBuf::from(path));
    }
    if let Some(local_app_data) = std::env::var_os("LOCALAPPDATA") {
        paths.push(
            PathBuf::from(local_app_data)
                .join("CodexTrafficLight")
                .join("state.json"),
        );
    }
    paths.push(codex_home().join("atlas-hook-state.json"));
    paths.push(codex_home().join("atlas-hook-events.jsonl"));
    paths.sort();
    paths.dedup();
    paths
}

fn epoch_ms_from_value(value: &Value) -> Option<i64> {
    if let Some(number) = value.as_i64() {
        return Some(normalize_epoch_ms(number));
    }
    if let Some(number) = value.as_u64() {
        return i64::try_from(number).ok().map(normalize_epoch_ms);
    }
    if let Some(number) = value.as_f64() {
        if number.is_finite() {
            let scaled = if number.abs() < 10_000_000_000.0 {
                number * 1_000.0
            } else {
                number
            };
            if scaled >= i64::MIN as f64 && scaled <= i64::MAX as f64 {
                return Some(scaled.round() as i64);
            }
        }
    }
    value
        .as_str()
        .and_then(|text| text.trim().parse::<f64>().ok())
        .and_then(|number| {
            if !number.is_finite() {
                return None;
            }
            let scaled = if number.abs() < 10_000_000_000.0 {
                number * 1_000.0
            } else {
                number
            };
            (scaled >= i64::MIN as f64 && scaled <= i64::MAX as f64)
                .then_some(scaled.round() as i64)
        })
}

fn map_hook_state(raw: &str, reason: &str) -> (&'static str, bool) {
    let state = raw.trim().to_ascii_lowercase();
    let waiting_reason = reason.to_ascii_lowercase();
    match state.as_str() {
        "working" | "yellow" | "thinking" | "running" => ("working", false),
        "waiting" | "red" | "attention" | "blocked" => ("waiting", true),
        "done" | "completed" | "green" => {
            if waiting_reason.contains("await") || waiting_reason.contains("input") {
                ("waiting", true)
            } else {
                ("completed", false)
            }
        }
        "failed" | "error" => ("failed", true),
        "idle" | "quit" | "off" => ("idle", false),
        _ => ("unknown", false),
    }
}

fn hook_event_name(value: &Value, payload: &Value) -> String {
    payload
        .get("hook_event_name")
        .or_else(|| payload.get("hookEventName"))
        .or_else(|| payload.get("event_name"))
        .or_else(|| payload.get("eventName"))
        .or_else(|| payload.get("event"))
        .or_else(|| payload.get("type"))
        .or_else(|| value.get("hook_event_name"))
        .or_else(|| value.get("hookEventName"))
        .or_else(|| value.get("event"))
        .or_else(|| value.get("type"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn hook_last_assistant_message<'a>(value: &'a Value, payload: &'a Value) -> &'a str {
    payload
        .get("last_assistant_message")
        .or_else(|| payload.get("lastAssistantMessage"))
        .or_else(|| value.get("last_assistant_message"))
        .or_else(|| value.get("lastAssistantMessage"))
        .and_then(Value::as_str)
        .unwrap_or_default()
}

fn assistant_message_requires_input(message: &str) -> bool {
    let message = message.trim().to_lowercase();
    if message.is_empty() {
        return false;
    }
    const REQUEST_MARKERS: [&str; 47] = [
        "please provide",
        "please confirm",
        "please choose",
        "please select",
        "please reply",
        "reply with",
        "let me know",
        "tell me which",
        "which option",
        "need your input",
        "need you to",
        "waiting for your",
        "can you confirm",
        "could you confirm",
        "would you like me to",
        "do you want me to",
        "what would you like",
        "how would you like",
        "waiting for user",
        "waiting for your response",
        "need your approval",
        "needs your approval",
        "blocked",
        "permission",
        "approval",
        "请提供",
        "请确认",
        "请选择",
        "请回复",
        "告诉我",
        "需要你",
        "等你确认",
        "你希望",
        "是否需要我",
        "是否要我",
        "等你",
        "需要回复",
        "需要确认",
        "需要授权",
        "需要登录",
        "需要验证码",
        "需要文件",
        "需要截图",
        "需要选择",
        "需要补充",
        "可以吗",
        "行不行",
    ];
    REQUEST_MARKERS
        .iter()
        .any(|marker| message.contains(marker))
        || message.contains('?')
        || message.contains('？')
        || message.ends_with("要继续吗？")
        || message.ends_with("要继续吗?")
}

fn map_hook_event(value: &Value, payload: &Value) -> Option<(&'static str, bool)> {
    let event = hook_event_name(value, payload);
    if event.is_empty() {
        return None;
    }
    if event.contains("error") || event.contains("fail") {
        return Some(("failed", true));
    }
    if event.contains("notification")
        || event.contains("input")
        || event.contains("permission")
        || event.contains("approval")
    {
        return Some(("waiting", true));
    }
    if event.contains("sessionstart") || event.contains("session_start") {
        return Some(("idle", false));
    }
    if event.contains("sessionend") || event.contains("session_end") {
        return Some(("idle", false));
    }
    if event.contains("userprompt")
        || event.contains("user_prompt")
        || event.contains("pretool")
        || event.contains("pre_tool")
        || event.contains("posttool")
        || event.contains("post_tool")
        || event.contains("turnstart")
        || event.contains("turn_start")
    {
        return Some(("working", false));
    }
    if event == "stop" || event == "subagentstop" || event == "subagent_stop" {
        return Some(
            if assistant_message_requires_input(hook_last_assistant_message(value, payload)) {
                ("waiting", true)
            } else {
                ("completed", false)
            },
        );
    }
    if event.contains("complete") || event.contains("success") {
        return Some(("completed", false));
    }
    None
}

fn hook_timestamp_value(map: &serde_json::Map<String, Value>) -> Option<&Value> {
    map.get("atlas_observed_at_ms")
        .or_else(|| map.get("atlasObservedAtMs"))
        .or_else(|| map.get("updated_at"))
        .or_else(|| map.get("updatedAt"))
        .or_else(|| map.get("updated_at_ms"))
        .or_else(|| map.get("updatedAtMs"))
        .or_else(|| map.get("timestamp_ms"))
        .or_else(|| map.get("timestamp"))
        .or_else(|| map.get("ts"))
        .or_else(|| map.get("created_at"))
}

fn parse_hook_state_file(path: &Path) -> Vec<HookObservation> {
    let jsonl = path.extension().and_then(|value| value.to_str()) == Some("jsonl");
    let text = if jsonl {
        tail_lines(path, 512 * 1024, 512).join("\n")
    } else {
        let Ok(text) = fs::read_to_string(path) else {
            return Vec::new();
        };
        text
    };
    let fallback_ms = file_modified_ms(path);
    let mut records = Vec::new();
    let mut push_record = |key: Option<&str>, value: &Value| {
        let payload = value.get("payload").unwrap_or(value);
        let object = payload.as_object().or_else(|| value.as_object());
        let id = object
            .and_then(|map| {
                map.get("session_id")
                    .or_else(|| map.get("sessionId"))
                    .or_else(|| map.get("thread_id"))
                    .or_else(|| map.get("threadId"))
                    .or_else(|| map.get("id"))
                    .and_then(Value::as_str)
            })
            .or_else(|| {
                value
                    .get("session_id")
                    .or_else(|| value.get("sessionId"))
                    .or_else(|| value.get("thread_id"))
                    .or_else(|| value.get("threadId"))
                    .and_then(Value::as_str)
            })
            .or(key)
            .unwrap_or_default()
            .trim()
            .trim_start_matches("session:")
            .to_string();
        if id.is_empty() || id == "default" {
            return;
        }
        let raw_state = object
            .and_then(|map| map.get("state"))
            .and_then(Value::as_str)
            .unwrap_or_default();
        let reason = object
            .and_then(|map| {
                map.get("reason")
                    .or_else(|| map.get("hook_event_name"))
                    .or_else(|| map.get("hookEventName"))
                    .and_then(Value::as_str)
            })
            .unwrap_or_default();
        let last_output = object
            .and_then(|map| {
                map.get("last_assistant_message")
                    .or_else(|| map.get("lastAssistantMessage"))
                    .or_else(|| map.get("approval_prompt"))
                    .or_else(|| map.get("approvalPrompt"))
                    .or_else(|| map.get("question"))
                    .or_else(|| map.get("message"))
                    .or_else(|| map.get("prompt"))
                    .or_else(|| map.get("content"))
                    .or_else(|| map.get("last_output"))
                    .or_else(|| map.get("lastOutput"))
            })
            .or_else(|| {
                value
                    .get("last_assistant_message")
                    .or_else(|| value.get("lastAssistantMessage"))
                    .or_else(|| value.get("approval_prompt"))
                    .or_else(|| value.get("approvalPrompt"))
                    .or_else(|| value.get("question"))
                    .or_else(|| value.get("message"))
                    .or_else(|| value.get("prompt"))
                    .or_else(|| value.get("content"))
                    .or_else(|| value.get("last_output"))
                    .or_else(|| value.get("lastOutput"))
            })
            .map(text_from_value)
            .map(|text| {
                let trimmed = text.trim();
                if is_approval_prompt(trimmed) {
                    trimmed.to_string()
                } else {
                    trimmed.chars().take(1200).collect::<String>()
                }
            })
            .filter(|text| !text.is_empty());
        let (state, requires_attention) = if !raw_state.trim().is_empty() {
            let mapped = map_hook_state(raw_state, reason);
            if matches!(mapped.0, "completed" | "idle")
                && object
                    .and_then(|map| {
                        map.get("last_assistant_message")
                            .or_else(|| map.get("lastAssistantMessage"))
                            .or_else(|| map.get("message"))
                            .and_then(Value::as_str)
                    })
                    .map(assistant_message_requires_input)
                    .unwrap_or(false)
            {
                ("waiting", true)
            } else {
                mapped
            }
        } else if let Some(mapped) = map_hook_event(value, payload) {
            mapped
        } else {
            return;
        };
        if state == "unknown" {
            return;
        }
        let event_name = hook_event_name(value, payload);
        let cwd = object
            .and_then(|map| {
                map.get("workspace")
                    .or_else(|| map.get("cwd"))
                    .or_else(|| map.get("workspace_root"))
                    .or_else(|| map.get("workspaceRoot"))
                    .and_then(Value::as_str)
            })
            .map(|value| normalize_path(value.to_string()))
            .filter(|value| !value.trim().is_empty());
        let timestamp_value = object
            .and_then(hook_timestamp_value)
            .or_else(|| value.as_object().and_then(hook_timestamp_value));
        let explicit_timestamp = timestamp_value
            .and_then(epoch_ms_from_value)
            .filter(|value| *value > 0)
            .is_some();
        let updated_at_ms = timestamp_value
            .and_then(epoch_ms_from_value)
            .filter(|value| *value > 0)
            .unwrap_or(fallback_ms);
        records.push(HookObservation {
            session_id: id,
            event_name,
            state: state.to_string(),
            cwd,
            updated_at_ms,
            requires_attention,
            explicit_timestamp,
            last_output,
        });
    };

    // Traffic-light implementations commonly write one object containing a
    // `tasks` map; the official hook contract is event-per-line JSON. Accept
    // both forms, plus a top-level array used by a few older integrations.
    if jsonl {
        for line in text.lines().filter(|line| !line.trim().is_empty()) {
            if let Ok(value) = serde_json::from_str::<Value>(line) {
                push_record(None, &value);
            }
        }
    } else if let Ok(root) = serde_json::from_str::<Value>(&text) {
        if let Some(tasks) = root.get("tasks").and_then(Value::as_object) {
            for (key, value) in tasks {
                push_record(Some(key), value);
            }
        } else if let Some(items) = root.as_array() {
            for value in items {
                push_record(None, value);
            }
        } else if root.get("state").is_some() || map_hook_event(&root, &root).is_some() {
            push_record(None, &root);
        }
    }
    records
}

fn load_hook_observations() -> Vec<HookObservation> {
    let mut records = Vec::new();
    for path in hook_state_paths() {
        if path.exists() {
            records.extend(parse_hook_state_file(&path));
        }
    }
    // Prefer the newest event when multiple traffic-light implementations are
    // installed on the same machine.
    records.sort_by(|left, right| {
        left.session_id
            .cmp(&right.session_id)
            .then(right.updated_at_ms.cmp(&left.updated_at_ms))
    });
    records
}

fn codex_hooks_path() -> PathBuf {
    codex_home().join("hooks.json")
}

fn codex_config_path() -> PathBuf {
    codex_home().join("config.toml")
}

fn atlas_hook_events_path() -> PathBuf {
    codex_home().join("atlas-hook-events.jsonl")
}

fn value_contains_text(value: &Value, needle: &str) -> bool {
    match value {
        Value::String(text) => text.to_ascii_lowercase().contains(needle),
        Value::Array(items) => items.iter().any(|item| value_contains_text(item, needle)),
        Value::Object(map) => map.values().any(|item| value_contains_text(item, needle)),
        _ => false,
    }
}

fn parse_toml_bool(value: &str) -> Option<bool> {
    match value
        .split('#')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

fn toml_header_name(line: &str) -> Option<String> {
    let header = line.split('#').next()?.trim();
    if header.starts_with('[') && header.ends_with(']') {
        Some(
            header
                .trim_start_matches('[')
                .trim_end_matches(']')
                .trim()
                .to_ascii_lowercase(),
        )
    } else {
        None
    }
}

fn config_hooks_enabled(config: &str) -> bool {
    let mut in_features = false;
    let mut canonical = None;
    let mut deprecated = None;
    for line in config.lines() {
        let trimmed = line.trim();
        if let Some(header) = toml_header_name(trimmed) {
            in_features = header == "features";
            continue;
        }
        if in_features {
            let Some((key, value)) = trimmed.split_once('=') else {
                continue;
            };
            match key.trim() {
                "hooks" => canonical = parse_toml_bool(value),
                "codex_hooks" => deprecated = parse_toml_bool(value),
                _ => {}
            }
        }
    }
    canonical.or(deprecated).unwrap_or(true)
}

fn config_has_inline_hooks(config: &str) -> bool {
    config.lines().any(|line| {
        let Some(header) = toml_header_name(line.trim()) else {
            return false;
        };
        header == "hooks" || header.starts_with("hooks.")
    })
}

fn enable_codex_hooks_in_config(config: &str) -> String {
    let mut output = String::new();
    let mut in_features = false;
    let mut features_found = false;
    let mut feature_written = false;
    let mut had_lines = false;
    for line in config.lines() {
        had_lines = true;
        let trimmed = line.trim();
        if let Some(header) = toml_header_name(trimmed) {
            if in_features && !feature_written {
                output.push_str("hooks = true\n");
                feature_written = true;
            }
            in_features = header == "features";
            if in_features {
                features_found = true;
            }
        }
        if in_features {
            if let Some((key, _)) = trimmed.split_once('=') {
                if matches!(key.trim(), "hooks" | "codex_hooks") {
                    if !feature_written {
                        output.push_str("hooks = true\n");
                        feature_written = true;
                    }
                    continue;
                }
            }
        }
        output.push_str(line);
        output.push('\n');
    }
    if in_features && !feature_written {
        output.push_str("hooks = true\n");
    }
    if !features_found {
        if had_lines && !output.ends_with('\n') {
            output.push('\n');
        }
        output.push_str("\n[features]\nhooks = true\n");
    }
    output
}

fn backup_file(path: &Path) -> Result<Option<PathBuf>, String> {
    if !path.exists() {
        return Ok(None);
    }
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("codex-config");
    let backup = path.with_file_name(format!("{name}.atlas-backup-{}", now_ms()));
    fs::copy(path, &backup).map_err(|error| format!("backup {}: {error}", path.display()))?;
    Ok(Some(backup))
}

fn write_text_atomically(path: &Path, contents: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("create {}: {error}", parent.display()))?;
    }
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("atlas-hook");
    let temporary = path.with_file_name(format!(".{name}.tmp-{}", now_ms()));
    fs::write(&temporary, contents)
        .map_err(|error| format!("write {}: {error}", temporary.display()))?;
    #[cfg(not(target_os = "windows"))]
    {
        use std::os::unix::fs::PermissionsExt;
        if path.extension().and_then(|value| value.to_str()) == Some("sh") {
            let mut permissions = fs::metadata(&temporary)
                .map_err(|error| format!("read temporary permissions: {error}"))?
                .permissions();
            permissions.set_mode(0o755);
            fs::set_permissions(&temporary, permissions)
                .map_err(|error| format!("set hook permissions: {error}"))?;
        }
    }
    if path.exists() {
        fs::remove_file(path).map_err(|error| format!("replace {}: {error}", path.display()))?;
    }
    fs::rename(&temporary, path).map_err(|error| format!("install {}: {error}", path.display()))
}

#[cfg(not(target_os = "windows"))]
fn shell_single_quote(value: &str) -> String {
    value.replace('\'', "'\\''")
}

#[cfg(target_os = "windows")]
fn powershell_single_quote(value: &str) -> String {
    value.replace('\'', "''")
}

#[cfg(target_os = "windows")]
fn atlas_hook_assets() -> (Vec<(PathBuf, String)>, String, PathBuf) {
    let path = codex_home().join("atlas-hook.ps1");
    let wrapper_path = codex_home().join("atlas-hook.cmd");
    let paseo_wrapper_path = codex_home().join("atlas-paseo-hook.cmd");
    let state_path = powershell_single_quote(&atlas_hook_events_path().to_string_lossy());
    let script = format!(
        "# Codex Atlas hook bridge\n$ErrorActionPreference = 'Stop'\ntry {{\n  $statePath = '{state_path}'\n  $parent = Split-Path -Parent $statePath\n  if ($parent) {{ New-Item -ItemType Directory -Force -Path $parent | Out-Null }}\n  $raw = [Console]::In.ReadToEnd()\n  if (-not [string]::IsNullOrWhiteSpace($raw)) {{\n    try {{\n      $event = $raw | ConvertFrom-Json\n      $event | Add-Member -NotePropertyName atlas_observed_at_ms -NotePropertyValue ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) -Force\n      $raw = $event | ConvertTo-Json -Compress -Depth 40\n    }} catch {{ $raw = $raw.Trim() }}\n    $encoding = New-Object System.Text.UTF8Encoding($false)\n    $mutex = New-Object System.Threading.Mutex($false, 'Local\\CodexAtlasHookEvents')\n    $locked = $false\n    try {{\n      try {{ $locked = $mutex.WaitOne(2000) }} catch {{ $locked = $false }}\n      if ($locked) {{ [System.IO.File]::AppendAllText($statePath, $raw + [Environment]::NewLine, $encoding) }}\n    }} finally {{\n      if ($locked) {{ try {{ $mutex.ReleaseMutex() }} catch {{}} }}\n      if ($null -ne $mutex) {{ $mutex.Dispose() }}\n    }}\n  }}\n}} catch {{ }}\nexit 0\n"
    );
    let wrapper = format!(
        "@echo off\r\npowershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File \"{}\"\r\nexit /b 0\r\n",
        path.to_string_lossy()
    );
    let paseo_wrapper = "@echo off\r\nsetlocal EnableExtensions\r\nif not defined PASEO_TERMINAL_ID (echo {}& exit /b 0)\r\nset \"ATLAS_PASEO_EVENT=%~1\"\r\nset \"ATLAS_PASEO_OUTPUT=%TEMP%\\codex-atlas-paseo-%RANDOM%-%RANDOM%.tmp\"\r\nif defined PASEO_HOOK_CLI (\r\n  call \"%PASEO_HOOK_CLI%\" hooks codex \"%ATLAS_PASEO_EVENT%\" >\"%ATLAS_PASEO_OUTPUT%\" 2>nul\r\n) else (\r\n  call paseo hooks codex \"%ATLAS_PASEO_EVENT%\" >\"%ATLAS_PASEO_OUTPUT%\" 2>nul\r\n)\r\nset \"ATLAS_PASEO_EXIT=%ERRORLEVEL%\"\r\nif \"%ATLAS_PASEO_EXIT%\"==\"0\" (\r\n  if exist \"%ATLAS_PASEO_OUTPUT%\" type \"%ATLAS_PASEO_OUTPUT%\"\r\n) else (\r\n  echo {}\r\n)\r\nif exist \"%ATLAS_PASEO_OUTPUT%\" del /q \"%ATLAS_PASEO_OUTPUT%\" >nul 2>&1\r\nexit /b 0\r\n".to_string();
    let command = format!(
        "powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File \"{}\"",
        path.to_string_lossy()
    );
    (
        vec![
            (path, script),
            (wrapper_path, wrapper),
            (paseo_wrapper_path.clone(), paseo_wrapper),
        ],
        command,
        paseo_wrapper_path,
    )
}

#[cfg(not(target_os = "windows"))]
fn atlas_hook_assets() -> (Vec<(PathBuf, String)>, String, PathBuf) {
    let path = codex_home().join("atlas-hook.sh");
    let paseo_wrapper_path = codex_home().join("atlas-paseo-hook.sh");
    let state_path = shell_single_quote(&atlas_hook_events_path().to_string_lossy());
    let script = format!(
        "#!/bin/sh\nstate_path='{state_path}'\nmkdir -p \"$(dirname \"$state_path\")\"\nraw=$(cat)\nobserved_at=$(date +%s)\nif [ -n \"$raw\" ]; then printf '{{\"atlas_observed_at_ms\":%s,\"payload\":%s}}\\n' \"$observed_at\" \"$raw\" >> \"$state_path\"; fi\nexit 0\n"
    );
    let paseo_wrapper = "#!/bin/sh\nevent=$1\nif [ -z \"$PASEO_TERMINAL_ID\" ]; then printf '{}\\n'; exit 0; fi\nout=${TMPDIR:-/tmp}/codex-atlas-paseo-$$\ncli=${PASEO_HOOK_CLI:-paseo}\nif \"$cli\" hooks codex \"$event\" >\"$out\" 2>/dev/null; then cat \"$out\"; else printf '{}\\n'; fi\nrm -f \"$out\"\nexit 0\n".to_string();
    let command = format!("sh '{}'", shell_single_quote(&path.to_string_lossy()));
    (
        vec![(path, script), (paseo_wrapper_path.clone(), paseo_wrapper)],
        command,
        paseo_wrapper_path,
    )
}

const ATLAS_HOOK_EVENTS: [&str; 8] = [
    "SessionStart",
    "UserPromptSubmit",
    "PreToolUse",
    "PostToolUse",
    "PermissionRequest",
    "Stop",
    "SubagentStop",
    "SessionEnd",
];

fn add_atlas_hooks(root: &mut Value, command: &str) -> Result<bool, String> {
    let object = root
        .as_object_mut()
        .ok_or_else(|| "Codex hooks.json must contain a JSON object".to_string())?;
    let hooks_value = object
        .entry("hooks".to_string())
        .or_insert_with(|| Value::Object(serde_json::Map::new()));
    let hooks = hooks_value
        .as_object_mut()
        .ok_or_else(|| "Codex hooks.json `hooks` must be an object".to_string())?;
    let mut changed = false;
    for event_name in ATLAS_HOOK_EVENTS {
        let entries_value = hooks
            .entry(event_name.to_string())
            .or_insert_with(|| Value::Array(Vec::new()));
        let entries = entries_value
            .as_array_mut()
            .ok_or_else(|| format!("Codex hooks `{event_name}` must be an array"))?;
        let mut existing = false;
        for entry in entries.iter_mut() {
            if !value_contains_text(entry, "atlas-hook") {
                continue;
            }
            existing = true;
            let Some(handlers) = entry.get_mut("hooks").and_then(Value::as_array_mut) else {
                continue;
            };
            for handler in handlers {
                if !value_contains_text(handler, "atlas-hook") {
                    continue;
                }
                let Some(handler) = handler.as_object_mut() else {
                    continue;
                };
                if handler.get("command").and_then(Value::as_str) != Some(command) {
                    handler.insert("command".to_string(), Value::String(command.to_string()));
                    changed = true;
                }
            }
        }
        if existing {
            continue;
        }
        let timeout = if event_name == "SessionEnd" { 3 } else { 5 };
        entries.push(serde_json::json!({
            "hooks": [{
                "type": "command",
                "command": command,
                "timeout": timeout
            }]
        }));
        changed = true;
    }
    Ok(changed)
}

fn harden_paseo_hooks(root: &mut Value, wrapper_path: &Path) -> bool {
    let Some(hooks) = root.get_mut("hooks").and_then(Value::as_object_mut) else {
        return false;
    };
    let mut changed = false;
    for (event_name, entries) in hooks {
        let Some(entries) = entries.as_array_mut() else {
            continue;
        };
        for entry in entries {
            let Some(handlers) = entry.get_mut("hooks").and_then(Value::as_array_mut) else {
                continue;
            };
            for handler in handlers {
                let is_paseo = (value_contains_text(handler, "paseo")
                    && value_contains_text(handler, "hooks codex"))
                    || value_contains_text(handler, "atlas-paseo-hook");
                if !is_paseo {
                    continue;
                }
                let Some(handler) = handler.as_object_mut() else {
                    continue;
                };
                #[cfg(target_os = "windows")]
                let (key, command) = (
                    "commandWindows",
                    format!(
                        "call \"{}\" \"{}\"",
                        wrapper_path.to_string_lossy().replace('"', "\\\""),
                        event_name
                    ),
                );
                #[cfg(not(target_os = "windows"))]
                let (key, command) = (
                    "command",
                    format!(
                        "sh '{}' '{}'",
                        shell_single_quote(&wrapper_path.to_string_lossy()),
                        shell_single_quote(event_name)
                    ),
                );
                if handler.get(key).and_then(Value::as_str) != Some(command.as_str()) {
                    handler.insert(key.to_string(), Value::String(command));
                    changed = true;
                }
            }
        }
    }
    changed
}

fn toml_has_atlas_hook_for_event(config: &str, event_name: &str) -> bool {
    let mut current_event = None::<String>;
    for line in config.lines() {
        let trimmed = line.trim();
        let header_line = trimmed.split('#').next().unwrap_or_default().trim();
        if let Some(path) = header_line
            .strip_prefix("[[")
            .and_then(|value| value.strip_suffix("]]"))
        {
            if let Some(rest) = path.trim().strip_prefix("hooks.") {
                if !rest.contains('.') {
                    current_event = Some(rest.to_string());
                }
            } else {
                current_event = None;
            }
        } else if toml_header_name(trimmed).is_some() {
            current_event = None;
        }
        if current_event
            .as_deref()
            .map(|current| current.eq_ignore_ascii_case(event_name))
            .unwrap_or(false)
            && trimmed.to_ascii_lowercase().contains("atlas-hook")
        {
            return true;
        }
    }
    false
}

fn config_has_all_atlas_hooks(config: &str) -> bool {
    ATLAS_HOOK_EVENTS
        .iter()
        .all(|event| toml_has_atlas_hook_for_event(config, event))
}

fn json_has_all_atlas_hooks(root: &Value) -> bool {
    let Some(hooks) = root.get("hooks").and_then(Value::as_object) else {
        return false;
    };
    ATLAS_HOOK_EVENTS.iter().all(|event| {
        hooks
            .get(*event)
            .and_then(Value::as_array)
            .map(|entries| {
                entries
                    .iter()
                    .any(|entry| value_contains_text(entry, "atlas-hook"))
            })
            .unwrap_or(false)
    })
}

fn add_atlas_toml_hooks(config: &str, command: &str) -> Result<String, String> {
    let command = serde_json::to_string(command)
        .map_err(|error| format!("serialize hook command: {error}"))?;
    let mut output = config.to_string();
    for event_name in ATLAS_HOOK_EVENTS {
        if toml_has_atlas_hook_for_event(&output, event_name) {
            continue;
        }
        if !output.is_empty() && !output.ends_with('\n') {
            output.push('\n');
        }
        let timeout = if event_name == "SessionEnd" { 3 } else { 5 };
        output.push_str(&format!(
            "\n[[hooks.{event_name}]]\n\n[[hooks.{event_name}.hooks]]\ntype = \"command\"\ncommand = {command}\ntimeout = {timeout}\n"
        ));
    }
    Ok(output)
}

fn codex_hook_status() -> CodexHookStatus {
    let hooks_path = codex_hooks_path();
    let config_path = codex_config_path();
    let state_path = atlas_hook_events_path();
    let mut error = None;
    let json_configured = match fs::read_to_string(&hooks_path) {
        Ok(text) => match serde_json::from_str::<Value>(&text) {
            Ok(value) => json_has_all_atlas_hooks(&value),
            Err(parse_error) => {
                error = Some(format!("hooks.json parse error: {parse_error}"));
                false
            }
        },
        Err(_) => false,
    };
    let config = fs::read_to_string(&config_path).unwrap_or_default();
    let toml_configured = config_has_all_atlas_hooks(&config);
    let configured = json_configured || toml_configured;
    if hooks_path.exists()
        && config_has_inline_hooks(&config)
        && !json_configured
        && !toml_configured
        && error.is_none()
    {
        error = Some(
            "Codex has both hooks.json and inline hooks; Atlas will keep the existing source choice"
                .to_string(),
        );
    }
    let enabled = config_hooks_enabled(&config);
    let observations = load_hook_observations();
    let last_event_at_ms = observations
        .iter()
        .map(|observation| observation.updated_at_ms)
        .max()
        .unwrap_or_default();
    let session_count = observations
        .iter()
        .map(|observation| observation.session_id.as_str())
        .collect::<std::collections::HashSet<_>>()
        .len();
    // Hooks are event-driven rather than a persistent socket connection. Once
    // a valid event has been observed, an idle period must not be shown as a
    // disconnect.
    let connected = configured && enabled && last_event_at_ms > 0;
    CodexHookStatus {
        hooks_path: hooks_path.to_string_lossy().to_string(),
        config_path: config_path.to_string_lossy().to_string(),
        state_path: state_path.to_string_lossy().to_string(),
        configured,
        enabled,
        connected,
        last_event_at_ms,
        session_count,
        error,
    }
}

#[tauri::command(rename_all = "camelCase")]
fn get_codex_hook_status() -> CodexHookStatus {
    codex_hook_status()
}

/// Installs the Atlas lifecycle bridge into the active user Codex configuration.
///
/// This is kept separate from the Tauri command wrapper so the packaged binary
/// can be invoked directly with `--install-hook` before the desktop shell starts.
pub fn install_codex_hook_now() -> Result<CodexHookStatus, String> {
    let (assets, command, paseo_wrapper_path) = atlas_hook_assets();
    for (path, contents) in assets {
        write_text_atomically(&path, &contents)?;
    }

    let config_path = codex_config_path();
    let config = fs::read_to_string(&config_path).unwrap_or_default();
    let hooks_path = codex_hooks_path();
    let json_has_atlas = hooks_path
        .exists()
        .then(|| fs::read_to_string(&hooks_path).ok())
        .flatten()
        .and_then(|text| serde_json::from_str::<Value>(&text).ok())
        .map(|value| value_contains_text(&value, "atlas-hook"))
        .unwrap_or(false);
    // Keep an already-installed Atlas source stable. If the user already uses
    // inline TOML hooks, extend that source instead of creating hooks.json.
    let use_inline_toml = !json_has_atlas && config_has_inline_hooks(&config);
    let mut updated_config = if use_inline_toml {
        add_atlas_toml_hooks(&config, &command)?
    } else {
        config.clone()
    };

    if !use_inline_toml {
        let mut hooks_root = if hooks_path.exists() {
            let text = fs::read_to_string(&hooks_path)
                .map_err(|error| format!("read {}: {error}", hooks_path.display()))?;
            serde_json::from_str::<Value>(&text)
                .map_err(|error| format!("parse {}: {error}", hooks_path.display()))?
        } else {
            serde_json::json!({"hooks": {}})
        };
        let hooks_changed = add_atlas_hooks(&mut hooks_root, &command)?;
        let paseo_changed = harden_paseo_hooks(&mut hooks_root, &paseo_wrapper_path);
        if hooks_changed || paseo_changed {
            if hooks_path.exists() {
                let _ = backup_file(&hooks_path)?;
            }
            let serialized = serde_json::to_string_pretty(&hooks_root)
                .map_err(|error| format!("serialize hooks.json: {error}"))?;
            write_text_atomically(&hooks_path, &format!("{serialized}\n"))?;
        }
    }

    updated_config = enable_codex_hooks_in_config(&updated_config);
    if updated_config != config {
        if config_path.exists() {
            let _ = backup_file(&config_path)?;
        }
        write_text_atomically(&config_path, &updated_config)?;
    }
    Ok(codex_hook_status())
}

#[tauri::command(rename_all = "camelCase")]
fn install_codex_hook() -> Result<CodexHookStatus, String> {
    install_codex_hook_now()
}

fn hook_observation_for_session<'a>(
    observations: &'a [HookObservation],
    session: &SessionRecord,
    process_running: bool,
) -> Option<&'a HookObservation> {
    let now = now_ms();
    observations
        .iter()
        .filter(|observation| {
            observation.updated_at_ms > 0
                && (process_running
                    || now.saturating_sub(observation.updated_at_ms) <= 15 * 60 * 1000)
        })
        .filter(|observation| {
            observation.session_id == session.id
                || observation
                    .cwd
                    .as_deref()
                    .map(|cwd| path_is_equal(cwd, &session.cwd))
                    .unwrap_or(false)
        })
        .min_by(|left, right| {
            let left_exact = left.session_id == session.id;
            let right_exact = right.session_id == session.id;
            right_exact
                .cmp(&left_exact)
                .then_with(|| right.updated_at_ms.cmp(&left.updated_at_ms))
        })
}

fn hook_can_override(
    hook: &HookObservation,
    rollout: &RolloutObservation,
    process_running: bool,
) -> bool {
    if rollout.state == "unknown" {
        return true;
    }
    // A Stop hook is emitted after an errored turn as well. Preserve the
    // rollout error until a later successful rollout event clears it; otherwise
    // a 403 would flash briefly and then be mislabeled as completed.
    if rollout.last_error.is_some()
        && hook.event_name == "stop"
        && matches!(hook.state.as_str(), "completed" | "idle")
    {
        return false;
    }
    // A timestamp inferred from a shared state file's mtime is only a useful
    // freshness signal while the process is actually alive. Otherwise an old
    // hook file can make a completed rollout appear active again.
    if !hook.explicit_timestamp && !process_running {
        return false;
    }
    hook.updated_at_ms > rollout.last_event_at_ms
        || (hook.updated_at_ms == rollout.last_event_at_ms && hook.explicit_timestamp)
}

fn load_base_sessions() -> Vec<SessionRecord> {
    let mut sessions = match load_sessions_from_db() {
        Ok(sessions) if !sessions.is_empty() => sessions,
        _ => Vec::new(),
    };
    // The SQLite index can lag while Codex is creating a new rollout. Merge
    // the lightweight JSONL metadata scan so a just-started session is visible
    // immediately (this also fixes resume pickers that only read the index).
    let rollout_sessions = scan_jsonl_sessions();
    if sessions.is_empty() {
        return rollout_sessions;
    }
    merge_rollout_sessions(&mut sessions, rollout_sessions);
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    sessions
}

fn merge_rollout_sessions(sessions: &mut Vec<SessionRecord>, rollout_sessions: Vec<SessionRecord>) {
    let mut by_id = sessions
        .iter()
        .enumerate()
        .map(|(index, session)| (session.id.clone(), index))
        .collect::<HashMap<_, _>>();
    for rollout in rollout_sessions {
        if let Some(index) = by_id.get(&rollout.id).copied() {
            let current = &mut sessions[index];
            if rollout.updated_at_ms > current.updated_at_ms {
                current.updated_at_ms = rollout.updated_at_ms;
                current.last_event_at_ms = rollout.last_event_at_ms;
            }
            if current.rollout_path.trim().is_empty() {
                current.rollout_path = rollout.rollout_path;
            }
            if current.cwd.trim().is_empty() {
                current.cwd = rollout.cwd;
            }
            if current.preview.trim().is_empty() {
                current.preview = rollout.preview;
            }
            if current.title.trim().is_empty() {
                current.title = rollout.title;
            }
            // The SQLite index can retain the launch-time default. Rollout
            // events contain the model actually used for the latest turn and
            // are therefore authoritative when present.
            if !rollout.model.trim().is_empty() {
                current.model = rollout.model;
            }
            if !rollout.model_provider.trim().is_empty() && rollout.model_provider != "custom" {
                current.model_provider = rollout.model_provider;
            }
            current.search_text = format!(
                "{} {} {} {}",
                current.title, current.preview, current.cwd, current.branch
            );
        } else {
            by_id.insert(rollout.id.clone(), sessions.len());
            sessions.push(rollout);
        }
    }
}

fn load_runtime_base_sessions() -> Vec<SessionRecord> {
    let mut sessions = match load_sessions_from_db() {
        Ok(sessions) if !sessions.is_empty() => sessions,
        _ => Vec::new(),
    };
    // Only recent rollout headers are needed to catch a session that has just
    // been launched before the SQLite index catches up. Older records remain
    // available through the full index command.
    let recent_rollouts = scan_recent_jsonl_sessions();
    if sessions.is_empty() {
        return recent_rollouts;
    }
    merge_rollout_sessions(&mut sessions, recent_rollouts);
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    sessions
}

fn candidate_score(candidate: &ProcessCandidate, session: &SessionRecord) -> i64 {
    if candidate
        .session_hint
        .as_deref()
        .map(|hint| hint.eq_ignore_ascii_case(&session.id))
        .unwrap_or(false)
    {
        return 2_000_000;
    }
    let command = candidate.command_line.to_ascii_lowercase();
    if !session.id.trim().is_empty() && command.contains(&session.id.to_ascii_lowercase()) {
        return 1_000_000;
    }
    // A writer lock can outlive a process after an abrupt terminal close. Do
    // not let that stale timestamp cross-contaminate another workspace.
    let Some(cwd) = candidate.cwd.as_deref() else {
        return -1;
    };
    if !path_is_equal(cwd, &session.cwd) {
        return -1;
    }
    // Codex keeps a per-thread writer lock while an interactive process owns
    // the session. Its creation/update time is a strong discriminator when
    // several resumed sessions share the same workspace directory.
    let lock_path = codex_home()
        .join("thread-writer-locks")
        .join(format!("{}.lock", session.id));
    if lock_path.exists() {
        let lock_time = file_created_ms(&lock_path).max(file_modified_ms(&lock_path));
        if lock_time > 0 {
            let distance = (candidate.start_time_ms - lock_time).unsigned_abs() as i64;
            if distance <= 5 * 60 * 1000 {
                return 900_000;
            }
            if distance <= 60 * 60 * 1000 {
                return 700_000;
            }
        }
    }
    let reference = session
        .last_event_at_ms
        .max(session.updated_at_ms)
        .max(session.created_at_ms);
    let distance = (candidate.start_time_ms - reference).unsigned_abs() as i64;
    // Prefer a thread that was created/resumed near the process start while
    // still allowing long-running sessions to match after a later resume.
    500_000i64.saturating_sub((distance / 1_000).min(450_000))
}

fn enrich_sessions(sessions: Vec<SessionRecord>) -> (Vec<SessionRecord>, Vec<RunningCodexSession>) {
    enrich_sessions_with_mode(sessions, false)
}

fn enrich_runtime_sessions(
    sessions: Vec<SessionRecord>,
) -> (Vec<SessionRecord>, Vec<RunningCodexSession>) {
    enrich_sessions_with_mode(sessions, true)
}

fn enrich_sessions_with_mode(
    mut sessions: Vec<SessionRecord>,
    runtime_only: bool,
) -> (Vec<SessionRecord>, Vec<RunningCodexSession>) {
    let candidates = codex_process_candidates();
    let hook_observations = load_hook_observations();
    let mut assignments = vec![Vec::<ProcessCandidate>::new(); sessions.len()];
    let mut used_sessions = std::collections::HashSet::new();
    for candidate in candidates {
        let mut ranked = sessions
            .iter()
            .enumerate()
            .map(|(index, session)| (candidate_score(&candidate, session), index))
            .filter(|(score, _)| *score >= 0)
            .collect::<Vec<_>>();
        ranked.sort_by(|left, right| right.0.cmp(&left.0));
        let Some((best_score, best_index)) = ranked.first().copied() else {
            continue;
        };
        let exact = best_score >= 1_000_000;
        let selected_index = if exact {
            best_index
        } else {
            ranked
                .iter()
                .find(|(_, index)| !used_sessions.contains(index))
                .map(|(_, index)| *index)
                .unwrap_or(best_index)
        };
        if !exact {
            used_sessions.insert(selected_index);
        }
        assignments[selected_index].push(candidate);
    }

    // A terminal host can own several Codex tabs. Mark exactly one thread as
    // foreground, preferring the most recently active matched session when
    // multiple candidates share the same Windows Terminal process.
    let foreground_pid = foreground_process_id();
    let foreground_title = foreground_window_title().unwrap_or_default();
    let foreground_session_index = foreground_pid
        .and_then(|pid| {
            assignments
                .iter()
                .enumerate()
                .filter(|(_, group)| {
                    group
                        .iter()
                        .any(|candidate| candidate.process_ids.contains(&pid))
                })
                .max_by_key(|(index, _)| {
                    sessions[*index]
                        .last_event_at_ms
                        .max(sessions[*index].updated_at_ms)
                })
                .map(|(index, _)| index)
        })
        .or_else(|| {
            assignments
                .iter()
                .enumerate()
                .filter(|(_, group)| !group.is_empty())
                .filter(|(index, _)| {
                    window_title_matches_session(&foreground_title, &sessions[*index])
                })
                .max_by_key(|(index, _)| {
                    sessions[*index]
                        .last_event_at_ms
                        .max(sessions[*index].updated_at_ms)
                })
                .map(|(index, _)| index)
        });

    let mut running = Vec::new();
    for (index, session) in sessions.iter_mut().enumerate() {
        let process_group = &assignments[index];
        let mut process_ids = process_group
            .iter()
            .flat_map(|candidate| candidate.process_ids.iter().copied())
            .collect::<Vec<_>>();
        process_ids.sort_unstable();
        process_ids.dedup();
        let process_running = !process_ids.is_empty();
        let hook_observation =
            hook_observation_for_session(&hook_observations, session, process_running);
        let should_observe = process_running
            || (!runtime_only
                && now_ms().saturating_sub(session.updated_at_ms) <= 7 * 24 * 60 * 60 * 1000)
            || (runtime_only && hook_observation.is_some());
        let mut observation = if session.rollout_path.trim().is_empty() || !should_observe {
            RolloutObservation {
                state: "unknown".to_string(),
                last_event_at_ms: session.updated_at_ms,
                ..RolloutObservation::default()
            }
        } else {
            observe_rollout(&session.rollout_path, session.updated_at_ms)
        };
        let hook_is_newer = hook_observation
            .map(|hook| hook_can_override(hook, &observation, process_running))
            .unwrap_or(false);
        if let Some(hook) = hook_observation.filter(|_| hook_is_newer) {
            observation.state = hook.state.clone();
            observation.requires_attention = hook.requires_attention;
            observation.last_event_at_ms = observation.last_event_at_ms.max(hook.updated_at_ms);
            if hook.last_output.is_some() {
                observation.last_output = hook.last_output.clone();
            }
            if !(hook.event_name == "stop" && observation.last_error.is_some()) {
                // A new prompt/tool/session lifecycle event supersedes an old
                // rollout error. Keep the special Stop+error guard above so a
                // balance failure cannot be hidden by the terminal hook.
                observation.last_error = None;
                observation.failure_key = None;
            }
        }
        let mut live_state = observation.state.clone();
        if process_running {
            // An interactive Codex process remains alive after a turn ends.
            // Process presence means "session open", not "model working".
            if live_state == "completed" {
                live_state = "idle".to_string();
            } else if live_state == "unknown" {
                live_state = if now_ms().saturating_sub(observation.last_event_at_ms) <= 15_000 {
                    "working".to_string()
                } else {
                    "idle".to_string()
                };
            }
        } else if live_state == "working"
            && now_ms().saturating_sub(observation.last_event_at_ms) > 90_000
        {
            live_state = "idle".to_string();
        }
        if live_state == "unknown" {
            live_state = if session.updated_at_ms > 0
                && now_ms().saturating_sub(session.updated_at_ms) < 8 * 60 * 1000
            {
                "idle".to_string()
            } else {
                "completed".to_string()
            };
        }
        let mut sources = Vec::new();
        if process_running {
            sources.push("process");
        }
        if observation.state != "unknown" {
            sources.push("rollout");
        }
        if hook_observation.is_some() && hook_is_newer {
            sources.push("hook");
        }
        if sources.is_empty() {
            sources.push("database");
        }
        session.running = process_running;
        session.live_state = live_state.clone();
        session.process_ids = process_ids.clone();
        session.requires_attention = observation.requires_attention;
        session.status_source = sources.join("+");
        session.last_event_at_ms = observation.last_event_at_ms.max(session.updated_at_ms);
        session.last_error = observation.last_error.clone();
        session.failure_key = observation.failure_key.clone();
        session.last_output = observation.last_output.clone();
        session.foreground = foreground_session_index == Some(index);
        session.approval = mobile_approval_request(session);
        if observation.last_event_at_ms > session.updated_at_ms {
            session.updated_at_ms = observation.last_event_at_ms;
        }

        for candidate in process_group {
            running.push(RunningCodexSession {
                session_id: session.id.clone(),
                pid: candidate.pid,
                state: live_state.clone(),
                cwd: candidate.cwd.clone(),
                command_line: candidate.command_line.chars().take(1200).collect(),
                process_ids: candidate.process_ids.clone(),
                observed_at_ms: now_ms(),
                status_source: session.status_source.clone(),
                requires_attention: session.requires_attention,
                last_event_at_ms: session.last_event_at_ms,
                last_error: session.last_error.clone(),
                failure_key: session.failure_key.clone(),
                last_output: session.last_output.clone(),
                foreground: session.foreground,
            });
        }
    }
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    running.sort_by(|a, b| a.session_id.cmp(&b.session_id).then(a.pid.cmp(&b.pid)));
    (sessions, running)
}

fn list_sessions_sync() -> Result<Vec<SessionRecord>, String> {
    let sessions = load_base_sessions();
    Ok(enrich_sessions(sessions).0)
}

#[tauri::command(rename_all = "camelCase")]
async fn list_sessions() -> Result<Vec<SessionRecord>, String> {
    tauri::async_runtime::spawn_blocking(list_sessions_sync)
        .await
        .map_err(|error| format!("session scan task failed: {error}"))?
}

fn cached_runtime_base_sessions(state: &AppState) -> Vec<SessionRecord> {
    // Runtime state is consumed by the desktop event stream. Keep this cache
    // short so a newly-created rollout is visible on the next tick instead of
    // waiting several seconds for the SQLite index to catch up.
    const CACHE_TTL_MS: i64 = 1_000;
    let now = now_ms();
    if let Ok(cache) = state.runtime_cache.lock() {
        if !cache.sessions.is_empty() && now.saturating_sub(cache.refreshed_at_ms) <= CACHE_TTL_MS {
            return cache.sessions.clone();
        }
    }
    let sessions = load_runtime_base_sessions();
    if let Ok(mut cache) = state.runtime_cache.lock() {
        cache.sessions = sessions.clone();
        cache.refreshed_at_ms = now;
    }
    sessions
}

fn runtime_signature(records: &[RunningCodexSession]) -> String {
    records
        .iter()
        .map(|record| {
            format!(
                "{}:{}:{}:{}:{}:{}:{}:{}",
                record.session_id,
                record.pid,
                record.state,
                record.last_event_at_ms,
                record.requires_attention,
                record.failure_key.as_deref().unwrap_or_default(),
                record.last_output.as_deref().unwrap_or_default(),
                record.foreground
            )
        })
        .collect::<Vec<_>>()
        .join("|")
}

/// Push runtime changes to every renderer without waiting for a JavaScript
/// polling interval. The renderer still keeps a slower fallback poll for
/// recovery if the event bridge is temporarily unavailable.
fn spawn_runtime_monitor(app: AppHandle, state: AppState) {
    thread::spawn(move || {
        let mut previous_signature = String::new();
        let mut emitted_initial = false;
        loop {
            let sessions = cached_runtime_base_sessions(&state);
            let records = if sessions.is_empty() {
                Vec::new()
            } else {
                enrich_runtime_sessions(sessions).1
            };
            let signature = runtime_signature(&records);
            if !emitted_initial || signature != previous_signature {
                let _ = app.emit("codex_runtime", &records);
                previous_signature = signature;
                emitted_initial = true;
            }
            thread::sleep(Duration::from_millis(750));
        }
    });
}

#[tauri::command(rename_all = "camelCase")]
async fn list_running_codex_sessions(
    state: State<'_, AppState>,
) -> Result<Vec<RunningCodexSession>, String> {
    let state = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        // The runtime poll intentionally avoids the full archive scan. A short
        // cache still catches new SQLite rows and recent rollout headers while
        // keeping the three-second floating-window tick inexpensive.
        let sessions = cached_runtime_base_sessions(&state);
        if sessions.is_empty() {
            return Ok(Vec::new());
        }
        Ok(enrich_runtime_sessions(sessions).1)
    })
    .await
    .map_err(|error| format!("runtime scan task failed: {error}"))?
}

fn find_session(session_id: &str) -> Option<SessionRecord> {
    list_sessions_sync()
        .ok()?
        .into_iter()
        .find(|session| session.id == session_id)
}

fn rollout_contains(path: &str, needle: &str) -> bool {
    let Ok(file) = File::open(path) else {
        return false;
    };
    let reader = BufReader::new(file);
    reader
        .lines()
        .map_while(Result::ok)
        .any(|line| line.to_lowercase().contains(needle))
}

#[tauri::command(rename_all = "camelCase")]
async fn search_sessions(query: String) -> Result<Vec<SessionRecord>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let needle = query.trim().to_lowercase();
        if needle.is_empty() {
            return list_sessions_sync();
        }
        let sessions = load_base_sessions();
        let matches = sessions
            .into_iter()
            .filter(|session| {
                let metadata = session.search_text.to_lowercase();
                metadata.contains(&needle) || rollout_contains(&session.rollout_path, &needle)
            })
            .collect();
        Ok(enrich_sessions(matches).0)
    })
    .await
    .map_err(|error| format!("session search task failed: {error}"))?
}

fn executable_candidate(name: &str) -> Option<PathBuf> {
    let candidate = PathBuf::from(name);
    if candidate.components().count() > 1 && candidate.exists() {
        return Some(candidate);
    }
    if candidate.exists() {
        return Some(candidate);
    }
    #[cfg(target_os = "windows")]
    {
        let app_data = std::env::var_os("APPDATA").map(PathBuf::from);
        if let Some(app_data) = app_data {
            for suffix in [".exe", ".cmd", ".ps1"] {
                let path = app_data.join("npm").join(format!("{name}{suffix}"));
                if path.exists() {
                    return Some(path);
                }
            }
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let mut candidates = Vec::new();
        if let Some(home) = dirs::home_dir() {
            candidates.extend([
                home.join(".local/bin").join(name),
                home.join(".npm-global/bin").join(name),
            ]);
        }
        candidates.extend([
            PathBuf::from("/usr/local/bin").join(name),
            PathBuf::from("/opt/homebrew/bin").join(name),
            PathBuf::from("/usr/bin").join(name),
        ]);
        if let Some(path) = candidates.into_iter().find(|path| path.exists()) {
            return Some(path);
        }
    }
    None
}

fn command_for(path_or_name: &str, args: &[String]) -> Result<Command, String> {
    let resolved = executable_candidate(path_or_name);
    #[cfg(target_os = "windows")]
    if let Some(path) = resolved {
        let extension = path
            .extension()
            .and_then(|value| value.to_str())
            .map(|value| value.to_ascii_lowercase());
        match extension.as_deref() {
            Some("ps1") => {
                let mut command = Command::new("powershell.exe");
                command.args(["-NoProfile", "-ExecutionPolicy", "Bypass", "-File"]);
                command.arg(path);
                command.args(args);
                return Ok(command);
            }
            Some("cmd") | Some("bat") => {
                let mut command = Command::new("cmd.exe");
                command.args(["/D", "/S", "/C"]);
                command.arg(path);
                command.args(args);
                return Ok(command);
            }
            _ => {}
        }
        let mut command = Command::new(path);
        command.args(args);
        return Ok(command);
    }
    if resolved.is_none() && Path::new(path_or_name).components().count() > 1 {
        return Err(format!("executable not found: {path_or_name}"));
    }
    let mut command = Command::new(path_or_name);
    command.args(args);
    Ok(command)
}

fn spawn_detached(path_or_name: &str, args: &[String]) -> Result<(), String> {
    let mut command = command_for(path_or_name, args)?;
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000008 | 0x08000000);
    }
    command
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("start {path_or_name}: {error}"))
}

fn output_command(path_or_name: &str, args: &[String]) -> Result<std::process::Output, String> {
    let mut command = command_for(path_or_name, args)?;
    command
        .output()
        .map_err(|error| format!("run {path_or_name}: {error}"))
}

fn codex_executable() -> String {
    executable_candidate("codex")
        .map(|path| path.to_string_lossy().to_string())
        .unwrap_or_else(|| "codex".to_string())
}

fn queue_codex_message(session: &SessionRecord, input: &str) -> bool {
    let args = vec![
        "queue".to_string(),
        "--thread".to_string(),
        session.id.clone(),
        "--message".to_string(),
        input.to_string(),
    ];
    let mut command = match command_for(&codex_executable(), &args) {
        Ok(command) => command,
        Err(_) => return false,
    };
    if !session.cwd.trim().is_empty() && Path::new(&session.cwd).exists() {
        command.current_dir(&session.cwd);
    }
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .map(|output| output.status.success())
        .unwrap_or(false)
}

fn is_continue_prompt(input: &str) -> bool {
    let normalized = input.trim();
    normalized.eq_ignore_ascii_case("continue") || normalized == "继续"
}

#[cfg(target_os = "windows")]
fn powershell_resume_command(session: &SessionRecord) -> String {
    let codex = powershell_single_quote(&codex_executable());
    let session_id = powershell_single_quote(&session.id);
    let cwd = powershell_single_quote(&session.cwd);
    format!(
        "$ErrorActionPreference = 'Continue'; Set-Location -LiteralPath '{cwd}'; & '{codex}' resume '{session_id}' -C '{cwd}'"
    )
}

#[cfg(target_os = "windows")]
fn launch_codex_resume_terminal(session: &SessionRecord) -> Result<(), String> {
    if session.cwd.trim().is_empty() || !Path::new(&session.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            session.cwd
        ));
    }
    let script = powershell_resume_command(session);
    let mut errors = Vec::new();
    for shell in ["pwsh.exe", "powershell.exe"] {
        let mut command = Command::new(shell);
        command.args([
            "-NoLogo",
            "-NoExit",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &script,
        ]);
        command.current_dir(&session.cwd);
        // Codex refuses to start its interactive TUI when inherited TERM is
        // `dumb` (common when Atlas itself was launched from a CI-like shell).
        // Give the new console a real terminal capability explicitly.
        command.env_remove("TERM").env("TERM", "xterm-256color");
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000010);
        match command.spawn() {
            Ok(mut child) => {
                thread::sleep(Duration::from_millis(240));
                if let Some(status) = child
                    .try_wait()
                    .map_err(|error| format!("check PowerShell resume window: {error}"))?
                {
                    errors.push(format!("{shell} exited immediately ({status})"));
                    continue;
                }
                return Ok(());
            }
            Err(error) => errors.push(format!("{shell}: {error}")),
        }
    }
    Err(format!(
        "could not open a PowerShell resume window: {}",
        errors.join("; ")
    ))
}

#[cfg(target_os = "macos")]
fn launch_codex_resume_terminal(session: &SessionRecord) -> Result<(), String> {
    if session.cwd.trim().is_empty() || !Path::new(&session.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            session.cwd
        ));
    }
    let command = format!(
        "cd -- '{}' && export TERM=xterm-256color && '{}' resume '{}' -C '{}'",
        shell_single_quote(&session.cwd),
        shell_single_quote(&codex_executable()),
        shell_single_quote(&session.id),
        shell_single_quote(&session.cwd)
    );
    let command_literal = serde_json::to_string(&command)
        .map_err(|error| format!("encode Terminal command: {error}"))?;
    let script =
        format!("tell application \"Terminal\"\nactivate\ndo script {command_literal}\nend tell");
    Command::new("osascript")
        .args(["-e", &script])
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("open Terminal for codex resume: {error}"))
}

#[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
fn launch_codex_resume_terminal(session: &SessionRecord) -> Result<(), String> {
    if session.cwd.trim().is_empty() || !Path::new(&session.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            session.cwd
        ));
    }
    let command = format!(
        "cd -- '{}' && exec '{}' resume '{}' -C '{}'",
        shell_single_quote(&session.cwd),
        shell_single_quote(&codex_executable()),
        shell_single_quote(&session.id),
        shell_single_quote(&session.cwd)
    );
    for terminal in ["x-terminal-emulator", "gnome-terminal", "konsole"] {
        let result = if terminal == "gnome-terminal" {
            Command::new(terminal)
                .args(["--", "sh", "-lc", &command])
                .spawn()
        } else {
            Command::new(terminal)
                .args(["-e", "sh", "-lc", &command])
                .spawn()
        };
        if result.is_ok() {
            return Ok(());
        }
    }
    Err("no supported terminal application was found".to_string())
}

fn codex_permission_overrides(permission: &str) -> (&'static str, &'static str) {
    match permission.trim() {
        "Read only" => ("on-request", "read-only"),
        "Full access" => ("never", "danger-full-access"),
        _ => ("on-request", "workspace-write"),
    }
}

#[cfg(target_os = "windows")]
fn launch_codex_new_terminal(request: &NewCodexSessionRequest) -> Result<(), String> {
    if request.cwd.trim().is_empty() || !Path::new(&request.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            request.cwd
        ));
    }
    let codex = powershell_single_quote(&codex_executable());
    let cwd = powershell_single_quote(&request.cwd);
    let (approval, sandbox) = codex_permission_overrides(&request.permission);
    let mut script = format!(
        "$ErrorActionPreference = 'Continue'; Set-Location -LiteralPath '{cwd}'; & '{codex}' -c 'approval_policy=\"{approval}\"' -c 'sandbox_mode=\"{sandbox}\"'",
    );
    if !request.model.trim().is_empty() {
        script.push_str(&format!(
            " -m '{}'",
            powershell_single_quote(request.model.trim())
        ));
    }
    if !request.prompt.trim().is_empty() {
        script.push_str(&format!(
            " '{}'",
            powershell_single_quote(request.prompt.trim())
        ));
    }
    let mut errors = Vec::new();
    for shell in ["pwsh.exe", "powershell.exe"] {
        let mut command = Command::new(shell);
        command.args([
            "-NoLogo",
            "-NoExit",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &script,
        ]);
        command.current_dir(&request.cwd);
        command.env_remove("TERM").env("TERM", "xterm-256color");
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000010);
        match command.spawn() {
            Ok(mut child) => {
                thread::sleep(Duration::from_millis(260));
                if let Some(status) = child
                    .try_wait()
                    .map_err(|error| format!("检查新 Codex 窗口失败: {error}"))?
                {
                    errors.push(format!("{shell} exited immediately ({status})"));
                    continue;
                }
                return Ok(());
            }
            Err(error) => errors.push(format!("{shell}: {error}")),
        }
    }
    Err(format!("无法打开 Codex 新会话窗口: {}", errors.join("; ")))
}

#[cfg(target_os = "macos")]
fn launch_codex_new_terminal(request: &NewCodexSessionRequest) -> Result<(), String> {
    if request.cwd.trim().is_empty() || !Path::new(&request.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            request.cwd
        ));
    }
    let (approval, sandbox) = codex_permission_overrides(&request.permission);
    let mut command = format!(
        "cd -- '{}' && export TERM=xterm-256color && '{}' -c 'approval_policy=\"{}\"' -c 'sandbox_mode=\"{}\"'",
        shell_single_quote(&request.cwd),
        shell_single_quote(&codex_executable()),
        approval,
        sandbox
    );
    if !request.model.trim().is_empty() {
        command.push_str(&format!(
            " -m '{}'",
            shell_single_quote(request.model.trim())
        ));
    }
    if !request.prompt.trim().is_empty() {
        command.push_str(&format!(" '{}'", shell_single_quote(request.prompt.trim())));
    }
    let command_literal = serde_json::to_string(&command)
        .map_err(|error| format!("encode Terminal command: {error}"))?;
    let script =
        format!("tell application \"Terminal\"\nactivate\ndo script {command_literal}\nend tell");
    Command::new("osascript")
        .args(["-e", &script])
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("open Terminal for new Codex session: {error}"))
}

#[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
fn launch_codex_new_terminal(request: &NewCodexSessionRequest) -> Result<(), String> {
    if request.cwd.trim().is_empty() || !Path::new(&request.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            request.cwd
        ));
    }
    let (approval, sandbox) = codex_permission_overrides(&request.permission);
    let mut command = format!(
        "cd -- '{}' && exec '{}' -c 'approval_policy=\"{}\"' -c 'sandbox_mode=\"{}\"'",
        shell_single_quote(&request.cwd),
        shell_single_quote(&codex_executable()),
        approval,
        sandbox
    );
    if !request.model.trim().is_empty() {
        command.push_str(&format!(
            " -m '{}'",
            shell_single_quote(request.model.trim())
        ));
    }
    if !request.prompt.trim().is_empty() {
        command.push_str(&format!(" '{}'", shell_single_quote(request.prompt.trim())));
    }
    for terminal in ["x-terminal-emulator", "gnome-terminal", "konsole"] {
        let result = if terminal == "gnome-terminal" {
            Command::new(terminal)
                .args(["--", "sh", "-lc", &command])
                .spawn()
        } else {
            Command::new(terminal)
                .args(["-e", "sh", "-lc", &command])
                .spawn()
        };
        if result.is_ok() {
            return Ok(());
        }
    }
    Err("no supported terminal application was found".to_string())
}

fn create_codex_session_sync(request: NewCodexSessionRequest) -> Result<bool, String> {
    if request.cwd.trim().is_empty() || !Path::new(&request.cwd).is_dir() {
        return Err(format!(
            "session working directory is unavailable: {}",
            request.cwd
        ));
    }
    if request.prompt.chars().count() > 32_000 {
        return Err("new session prompt is too long (maximum 32000 characters)".to_string());
    }
    launch_codex_new_terminal(&request).map(|_| true)
}

#[tauri::command(rename_all = "camelCase")]
async fn create_codex_session(request: NewCodexSessionRequest) -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || create_codex_session_sync(request))
        .await
        .map_err(|error| format!("create Codex session task failed: {error}"))?
}

#[tauri::command(rename_all = "camelCase")]
fn resume_codex_session(state: State<'_, AppState>, session_id: String) -> Result<bool, String> {
    let session =
        find_session(&session_id).ok_or_else(|| format!("session not found: {session_id}"))?;
    if session.running {
        // Prefer the already-running terminal. If it is hosted by a terminal
        // tab that Windows no longer exposes, start an exact resume in the
        // recorded workspace so activation remains a useful user action.
        if focus_session_terminal(&session).is_err() {
            launch_codex_resume_terminal(&session)?;
        }
        return Ok(true);
    }
    state
        .failure_counts
        .lock()
        .map_err(|_| "process state unavailable".to_string())?
        .remove(&session_id);
    launch_codex_resume_terminal(&session)?;
    Ok(true)
}

#[tauri::command(rename_all = "camelCase")]
fn send_session_input(
    session_id: String,
    input: String,
    focus_terminal: Option<bool>,
) -> Result<bool, String> {
    // External terminals do not expose stdin to Atlas. `codex queue` is the
    // supported cross-process path. A manual click can additionally focus the
    // exact terminal window and inject the prompt if queue is unavailable.
    let Some(session) = find_session(&session_id) else {
        return Ok(false);
    };
    if !session.running {
        return Ok(false);
    }
    if queue_codex_message(&session, &input) {
        return Ok(true);
    }
    if focus_terminal.unwrap_or(false) || is_continue_prompt(&input) {
        return send_text_to_terminal(&session, &input, focus_terminal.unwrap_or(false))
            .map(|_| true);
    }
    Ok(false)
}

#[tauri::command(rename_all = "camelCase")]
fn send_terminal_input(
    session_id: String,
    input: String,
    focus_terminal: Option<bool>,
) -> Result<bool, String> {
    let session =
        find_session(&session_id).ok_or_else(|| format!("session not found: {session_id}"))?;
    // A freshly focused Windows Terminal can be visible before the process
    // tree poll catches up. For this manual, window-visible action, the
    // matched terminal itself is the authoritative liveness signal.
    if !session.running && terminal_window_for_session(&session).is_none() {
        return Err(
            "the session is not currently running and no matching terminal was found".to_string(),
        );
    }
    send_text_to_terminal(&session, &input, focus_terminal.unwrap_or(true)).map(|_| true)
}

#[tauri::command(rename_all = "camelCase")]
fn set_auto_continue(state: State<'_, AppState>, enabled: bool) -> Result<bool, String> {
    let mut auto_continue = state
        .auto_continue
        .lock()
        .map_err(|_| "recovery state unavailable".to_string())?;
    *auto_continue = enabled;
    if !enabled {
        state
            .failure_counts
            .lock()
            .map_err(|_| "recovery state unavailable".to_string())?
            .clear();
    }
    Ok(enabled)
}

#[tauri::command]
async fn minimize_window(app: AppHandle) -> Result<bool, String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    window
        .minimize()
        .map(|_| true)
        .map_err(|error| format!("minimize window: {error}"))
}

#[tauri::command]
async fn toggle_maximize_window(app: AppHandle) -> Result<bool, String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    let maximized = window
        .is_maximized()
        .map_err(|error| format!("read window state: {error}"))?;
    if maximized {
        window
            .unmaximize()
            .map_err(|error| format!("restore window: {error}"))?;
        Ok(false)
    } else {
        window
            .maximize()
            .map_err(|error| format!("maximize window: {error}"))?;
        Ok(true)
    }
}

#[tauri::command]
async fn start_window_drag(window: WebviewWindow) -> Result<bool, String> {
    window
        .start_dragging()
        .map(|_| true)
        .map_err(|error| format!("start window drag: {error}"))
}

#[tauri::command]
async fn close_main_window(app: AppHandle) -> Result<bool, String> {
    let _ = stop_server_tunnel_process();
    let _ = stop_mobile_tunnel();
    if let Some(window) = app.get_webview_window("floating") {
        let _ = window.close();
    }
    app.exit(0);
    Ok(true)
}

#[tauri::command]
async fn show_main_window(app: AppHandle) -> Result<bool, String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    window
        .show()
        .map_err(|error| format!("show main window: {error}"))?;
    window
        .unminimize()
        .map_err(|error| format!("restore main window: {error}"))?;
    window
        .set_focus()
        .map_err(|error| format!("focus main window: {error}"))?;
    Ok(true)
}

fn floating_window_size_path() -> PathBuf {
    codex_home().join("atlas-floating-window.json")
}

fn stored_floating_window_size() -> f64 {
    fs::read_to_string(floating_window_size_path())
        .ok()
        .and_then(|raw| raw.trim().parse::<f64>().ok())
        .filter(|size| size.is_finite())
        .map(|size| size.clamp(180.0, 720.0))
        .unwrap_or(252.0)
}

#[tauri::command(rename_all = "camelCase")]
async fn set_floating_window_size(app: AppHandle, size: f64) -> Result<f64, String> {
    let size = size.clamp(180.0, 720.0).round();
    write_text_atomically(&floating_window_size_path(), &format!("{size}\n"))?;
    if let Some(window) = app.get_webview_window("floating") {
        window
            .set_size(tauri::Size::Logical(tauri::LogicalSize::new(size, size)))
            .map_err(|error| format!("resize floating window: {error}"))?;
        if window.is_visible().unwrap_or(false) {
            position_floating_window(&window)?;
        }
    }
    Ok(size)
}

fn position_floating_window(window: &tauri::WebviewWindow) -> Result<(), String> {
    let monitor = window
        .current_monitor()
        .map_err(|error| format!("read current monitor: {error}"))?
        .or_else(|| window.primary_monitor().ok().flatten())
        .or_else(|| {
            window
                .available_monitors()
                .ok()
                .and_then(|monitors| monitors.into_iter().next())
        });
    let Some(monitor) = monitor else {
        return Err("no display monitor available for floating window".to_string());
    };
    let size = monitor.size();
    let monitor_position = monitor.position();
    let window_size = window
        .outer_size()
        .unwrap_or_else(|_| tauri::PhysicalSize::new(252, 252));
    let x = monitor_position.x + size.width as i32 - window_size.width as i32 - 28;
    let y = monitor_position.y + size.height as i32 - window_size.height as i32 - 76;
    window
        .set_position(PhysicalPosition::new(x, y))
        .map_err(|error| format!("position floating window: {error}"))
}

#[tauri::command]
async fn set_floating_window_visible(app: AppHandle, visible: bool) -> Result<bool, String> {
    let window = match app.get_webview_window("floating") {
        Some(window) => window,
        None => WebviewWindowBuilder::new(
            &app,
            "floating",
            WebviewUrl::App("index.html?view=floating".into()),
        )
        .title("Atlas Mini")
        .inner_size(stored_floating_window_size(), stored_floating_window_size())
        .position(0.0, 0.0)
        .decorations(false)
        .resizable(false)
        .maximizable(false)
        .minimizable(false)
        .closable(false)
        .visible(false)
        .transparent(true)
        .shadow(false)
        .focusable(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .build()
        .map_err(|error| format!("create floating window: {error}"))?,
    };
    if visible {
        window
            .show()
            .map_err(|error| format!("show floating window: {error}"))?;
        // Monitor lookup for a hidden WebView can block on Windows. Show the
        // native window first, then place it using the now-resolved monitor.
        position_floating_window(&window)?;
    } else {
        window
            .hide()
            .map_err(|error| format!("hide floating window: {error}"))?;
    }
    Ok(visible)
}

#[tauri::command(rename_all = "camelCase")]
async fn set_floating_always_on_top(app: AppHandle, enabled: bool) -> Result<bool, String> {
    let Some(window) = app.get_webview_window("floating") else {
        return Ok(false);
    };
    window
        .set_always_on_top(enabled)
        .map_err(|error| format!("set floating window always-on-top: {error}"))?;
    Ok(enabled)
}

#[tauri::command(rename_all = "camelCase")]
fn launch_external_app(executable_path: String, args: Vec<String>) -> Result<bool, String> {
    let path = if executable_path.trim().is_empty() {
        "paseo"
    } else {
        executable_path.trim()
    };
    spawn_detached(path, &args).map(|_| true)
}

#[tauri::command(rename_all = "camelCase")]
fn open_url(url: String) -> Result<bool, String> {
    let trimmed = url.trim();
    let parsed = Url::parse(trimmed).map_err(|error| format!("invalid URL: {error}"))?;
    if parsed.scheme() != "https" {
        return Err("only https URLs can be opened".to_string());
    }
    #[cfg(target_os = "windows")]
    {
        Command::new("rundll32.exe")
            .args(["url.dll,FileProtocolHandler", trimmed])
            .spawn()
            .map_err(|error| format!("open URL: {error}"))?;
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg(trimmed)
            .spawn()
            .map_err(|error| format!("open URL: {error}"))?;
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        Command::new("xdg-open")
            .arg(trimmed)
            .spawn()
            .map_err(|error| format!("open URL: {error}"))?;
    }
    Ok(true)
}

#[tauri::command(rename_all = "camelCase")]
fn launch_paseo(executable_path: Option<String>) -> Result<bool, String> {
    let path = executable_path
        .filter(|value| !value.trim().is_empty())
        .or_else(|| executable_candidate("paseo").map(|value| value.to_string_lossy().to_string()))
        .unwrap_or_else(|| "paseo".to_string());
    if paseo_daemon_ready_with(&path) {
        return Ok(true);
    }
    spawn_detached(&path, &["start".to_string()])
        .map_err(|error| format!("Paseo 启动失败: {error}"))?;
    // `paseo start` daemonizes, so process creation succeeding is not enough.
    // Wait for its HTTP/CLI endpoint before reporting success to the UI.
    for delay in [180, 320, 520, 800, 1200, 1600, 2200] {
        thread::sleep(Duration::from_millis(delay));
        if paseo_daemon_ready_with(&path) {
            return Ok(true);
        }
    }
    Err("Paseo 已启动进程，但本地 daemon 未在 6767 端口就绪".to_string())
}

fn paseo_daemon_ready() -> bool {
    paseo_daemon_ready_with("paseo")
}

fn paseo_daemon_ready_with(path: &str) -> bool {
    output_command(
        path,
        &[
            "ls".to_string(),
            "--json".to_string(),
            "--no-headers".to_string(),
        ],
    )
    .map(|output| output.status.success())
    .unwrap_or(false)
}

fn ensure_paseo_daemon() -> Result<(), String> {
    if paseo_daemon_ready() {
        return Ok(());
    }
    spawn_detached("paseo", &["start".to_string()])?;
    for delay in [200, 400, 800, 1200] {
        thread::sleep(Duration::from_millis(delay));
        if paseo_daemon_ready() {
            return Ok(());
        }
    }
    // A stale PID file can make `paseo start` exit without replacing the old
    // process. Restart once, then give the daemon another short readiness
    // window before surfacing the import error.
    let _ = spawn_detached("paseo", &["restart".to_string()]);
    for delay in [300, 600, 1000] {
        thread::sleep(Duration::from_millis(delay));
        if paseo_daemon_ready() {
            return Ok(());
        }
    }
    Err("Paseo daemon did not become ready on localhost:6767".to_string())
}

fn import_paseo_agent(provider: &str, session_id: &str, cwd: &str) -> Result<bool, String> {
    if provider != "codex" {
        return Err(format!("unsupported Paseo provider: {provider}"));
    }
    let args = vec![
        "import".to_string(),
        "--provider".to_string(),
        "codex".to_string(),
        session_id.to_string(),
        "--cwd".to_string(),
        cwd.to_string(),
    ];
    let output = output_command("paseo", &args)?;
    if output.status.success() {
        Ok(true)
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[tauri::command(rename_all = "camelCase")]
async fn paseo_import_agent(
    provider: String,
    session_id: String,
    cwd: String,
) -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        paseo_import_agent_sync(&provider, &session_id, &cwd)
    })
    .await
    .map_err(|error| format!("Paseo import task failed: {error}"))?
}

fn paseo_import_agent_sync(provider: &str, session_id: &str, cwd: &str) -> Result<bool, String> {
    ensure_paseo_daemon()?;
    import_paseo_agent(&provider, &session_id, &cwd)
}

#[tauri::command(rename_all = "camelCase")]
async fn paseo_import_all_codex_sessions() -> Result<PaseoImportSummary, String> {
    tauri::async_runtime::spawn_blocking(paseo_import_all_codex_sessions_sync)
        .await
        .map_err(|error| format!("Paseo bulk import task failed: {error}"))?
}

fn paseo_import_all_codex_sessions_sync() -> Result<PaseoImportSummary, String> {
    ensure_paseo_daemon()?;
    let sessions = list_sessions_sync()?;
    let total = sessions.len();
    let mut imported = 0;
    let mut errors = Vec::new();
    for session in sessions {
        match import_paseo_agent("codex", &session.id, &session.cwd) {
            Ok(true) => imported += 1,
            Ok(false) => errors.push(format!("{}: not imported", session.id)),
            Err(error) => {
                if errors.len() < 8 {
                    errors.push(format!("{}: {error}", session.id));
                }
            }
        }
    }
    Ok(PaseoImportSummary {
        total,
        imported,
        failed: total.saturating_sub(imported),
        errors,
    })
}

#[tauri::command(rename_all = "camelCase")]
async fn get_balance(request: BalanceRequest) -> Result<BalanceResponse, String> {
    let base = Url::parse(request.base_url.trim())
        .map_err(|error| format!("invalid CC Switch URL: {error}"))?;
    if !matches!(base.scheme(), "http" | "https") {
        return Err("CC Switch URL must use http or https".to_string());
    }
    if request.api_key.trim().is_empty() {
        return Ok(BalanceResponse {
            success: false,
            remaining: None,
            total: None,
            unit: None,
            provider: None,
            error: Some("Provider API key is required for a balance query".to_string()),
        });
    }
    if matches!(
        base.host_str(),
        Some("127.0.0.1") | Some("localhost") | Some("::1")
    ) {
        return Ok(BalanceResponse {
            success: false,
            remaining: None,
            total: None,
            unit: None,
            provider: Some("CC Switch".to_string()),
            error: Some("CC Switch local proxy exposes /v1/responses, not provider balance. Enter the upstream provider URL for this credential.".to_string()),
        });
    }
    let client = Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|error| error.to_string())?;
    let host = base.host_str().unwrap_or_default().to_lowercase();
    let mut roots = Vec::new();
    let base_text = base.as_str().trim_end_matches('/');
    let mut custom_path_added = false;
    if let Some(path) = request
        .usage_path
        .as_deref()
        .map(str::trim)
        .filter(|path| !path.is_empty())
    {
        let candidate = if path.starts_with("http://") || path.starts_with("https://") {
            Url::parse(path).ok()
        } else {
            let suffix = if path.starts_with('/') {
                path.to_string()
            } else {
                format!("/{path}")
            };
            Url::parse(&format!("{base_text}{suffix}")).ok()
        };
        if let Some(candidate) = candidate {
            roots.push(candidate);
            custom_path_added = true;
        }
    }
    if host.contains("deepseek.com") {
        roots.push(Url::parse("https://api.deepseek.com/user/balance").unwrap());
    } else if host.contains("stepfun.ai") || host.contains("stepfun.com") {
        roots.push(Url::parse("https://api.stepfun.com/v1/accounts").unwrap());
    } else if host.contains("siliconflow.cn") {
        roots.push(Url::parse("https://api.siliconflow.cn/v1/user/info").unwrap());
    } else if host.contains("siliconflow.com") {
        roots.push(Url::parse("https://api.siliconflow.com/v1/user/info").unwrap());
    } else if host.contains("openrouter.ai") {
        roots.push(Url::parse("https://openrouter.ai/api/v1/credits").unwrap());
    } else if host.contains("novita.ai") {
        roots.push(Url::parse("https://api.novita.ai/v3/user/balance").unwrap());
    }
    if roots.is_empty() || custom_path_added {
        for suffix in [
            "/v1/usage",
            "/usage",
            "/v1/balance",
            "/balance",
            "/api/balance",
            "/v1/credits",
            "/credits",
        ] {
            if let Ok(url) = Url::parse(&format!("{base_text}{suffix}")) {
                if !roots.iter().any(|candidate| candidate == &url) {
                    roots.push(url);
                }
            }
        }
    }
    if !roots.iter().any(|url| url.as_str() == base.as_str()) {
        roots.push(base.clone());
    }
    let mut last_error = None;
    for url in roots {
        let mut request_builder = client.get(url).header("Accept", "application/json");
        if !request.api_key.trim().is_empty() {
            request_builder = request_builder
                .bearer_auth(request.api_key.trim())
                .header("X-API-Key", request.api_key.trim());
        }
        let response = match request_builder.send().await {
            Ok(response) => response,
            Err(error) => {
                last_error = Some(error.to_string());
                continue;
            }
        };
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        if !status.is_success() {
            last_error = Some(format!(
                "HTTP {}: {}",
                status.as_u16(),
                body.chars().take(180).collect::<String>()
            ));
            continue;
        }
        if let Some((remaining, total, unit, provider)) = extract_balance(&body) {
            return Ok(BalanceResponse {
                success: true,
                remaining: Some(remaining),
                total,
                unit,
                provider,
                error: None,
            });
        }
        last_error = Some("response did not contain a balance value".to_string());
    }
    Ok(BalanceResponse {
        success: false,
        remaining: None,
        total: None,
        unit: None,
        provider: None,
        error: last_error.or_else(|| Some("CC Switch balance endpoint unavailable".to_string())),
    })
}

fn number_at(value: &Value, keys: &[&str]) -> Option<f64> {
    for key in keys {
        if let Some(candidate) = value.get(*key) {
            if let Some(number) = candidate.as_f64() {
                return Some(number);
            }
            if let Some(text) = candidate.as_str() {
                if let Ok(number) = text.trim().parse::<f64>() {
                    return Some(number);
                }
            }
        }
    }
    None
}

fn extract_balance(body: &str) -> Option<(f64, Option<f64>, Option<String>, Option<String>)> {
    let value: Value = serde_json::from_str(body).ok()?;
    let objects = [
        value.clone(),
        value.get("data").cloned().unwrap_or(Value::Null),
        value.get("balance").cloned().unwrap_or(Value::Null),
    ];
    for object in objects {
        if !object.is_object() {
            continue;
        }
        if let (Some(total), Some(used)) = (
            number_at(&object, &["total_credits"]),
            number_at(&object, &["total_usage"]),
        ) {
            return Some((
                total - used,
                Some(total),
                Some("USD".to_string()),
                object
                    .get("provider")
                    .and_then(Value::as_str)
                    .map(ToString::to_string),
            ));
        }
        let remaining = number_at(
            &object,
            &[
                "remaining",
                "remaining_balance",
                "balance",
                "credit",
                "credits",
                "available",
            ],
        )?;
        let total = number_at(&object, &["total", "limit", "quota", "monthly_limit"]);
        let unit = object
            .get("unit")
            .or_else(|| object.get("currency"))
            .and_then(Value::as_str)
            .map(ToString::to_string);
        let provider = object
            .get("provider")
            .and_then(Value::as_str)
            .map(ToString::to_string);
        return Some((remaining, total, unit, provider));
    }
    None
}

fn parse_toml_string(config: &str, key: &str) -> Option<String> {
    config.lines().find_map(|line| {
        let (candidate_key, value) = line.split_once('=')?;
        if candidate_key.trim() != key {
            return None;
        }
        let value = value.trim().trim_matches('"').trim_matches('\'');
        (!value.is_empty()).then(|| value.to_string())
    })
}

fn parse_toml_section_string(config: &str, section: &str, key: &str) -> Option<String> {
    let mut active = false;
    for line in config.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            active = trimmed.trim_matches(['[', ']']) == section;
            continue;
        }
        if !active {
            continue;
        }
        let Some((candidate, value)) = trimmed.split_once('=') else {
            continue;
        };
        if candidate.trim() != key {
            continue;
        }
        let value = value.trim();
        if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
            return Some(value[1..value.len() - 1].to_string());
        }
    }
    None
}

fn upsert_root_toml_string(config: &str, key: &str, value: &str) -> String {
    let escaped = value.replace('\\', "\\\\").replace('"', "\\\"");
    let replacement = format!("{key} = \"{escaped}\"");
    let mut lines = config.lines().map(ToString::to_string).collect::<Vec<_>>();
    let section_index = lines
        .iter()
        .position(|line| line.trim_start().starts_with('['))
        .unwrap_or(lines.len());
    if let Some(index) = lines[..section_index].iter().position(|line| {
        line.split_once('=')
            .map(|(candidate, _)| candidate.trim() == key)
            .unwrap_or(false)
    }) {
        lines[index] = replacement;
    } else {
        lines.insert(section_index, replacement);
    }
    let mut updated = lines.join("\n");
    if config.ends_with('\n') || !updated.is_empty() {
        updated.push('\n');
    }
    updated
}

fn usage_path_from_meta(meta: &Value) -> Option<String> {
    let code = meta
        .get("usage_script")
        .and_then(Value::as_object)
        .and_then(|script| script.get("code"))
        .and_then(Value::as_str)?;
    let marker = "{{baseUrl}}";
    let remainder = code.split_once(marker)?.1;
    let path = remainder
        .split(['\"', '\'', '`'])
        .next()
        .unwrap_or_default()
        .trim();
    (!path.is_empty()).then(|| path.to_string())
}

fn provider_credentials(
    settings: &Value,
    meta: &Value,
) -> (String, String, Option<String>, Option<String>) {
    let mut base_url = String::new();
    let mut api_key = String::new();
    let mut model = None;
    let usage_path = usage_path_from_meta(meta);
    if let Some(config) = settings.get("config").and_then(Value::as_str) {
        base_url = parse_toml_string(config, "base_url").unwrap_or_default();
        model = parse_toml_string(config, "model");
    }
    if let Some(auth) = settings.get("auth").and_then(Value::as_object) {
        api_key = auth
            .get("OPENAI_API_KEY")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
    }
    if let Some(env) = settings.get("env").and_then(Value::as_object) {
        if base_url.is_empty() {
            base_url = env
                .get("OPENAI_BASE_URL")
                .or_else(|| env.get("ANTHROPIC_BASE_URL"))
                .or_else(|| env.get("GOOGLE_GEMINI_BASE_URL"))
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
        }
        if api_key.is_empty() {
            api_key = env
                .get("OPENAI_API_KEY")
                .or_else(|| env.get("ANTHROPIC_AUTH_TOKEN"))
                .or_else(|| env.get("ANTHROPIC_API_KEY"))
                .or_else(|| env.get("GEMINI_API_KEY"))
                .or_else(|| env.get("GOOGLE_API_KEY"))
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
        }
        if model.is_none() {
            model = env
                .get("OPENAI_MODEL")
                .or_else(|| env.get("ANTHROPIC_MODEL"))
                .and_then(Value::as_str)
                .map(ToString::to_string);
        }
    }
    if let Some(script) = meta.get("usage_script").and_then(Value::as_object) {
        if let Some(value) = script.get("baseUrl").and_then(Value::as_str) {
            if !value.trim().is_empty() {
                base_url = value.trim().to_string();
            }
        }
        if let Some(value) = script.get("apiKey").and_then(Value::as_str) {
            if !value.trim().is_empty() {
                api_key = value.trim().to_string();
            }
        }
    }
    (
        base_url.trim_end_matches('/').to_string(),
        api_key,
        model,
        usage_path,
    )
}

#[tauri::command]
async fn get_cc_switch_provider_balances() -> Result<Vec<CcSwitchProviderBalance>, String> {
    let db_path = home_dir().join(".cc-switch").join("cc-switch.db");
    if !db_path.exists() {
        return Ok(Vec::new());
    }
    // CC Switch marks several application providers as `is_current`. The
    // Codex selector in settings.json is the source of truth for this view;
    // otherwise a current Claude provider can be mistaken for Codex balance.
    let selected_provider = fs::read_to_string(home_dir().join(".cc-switch").join("settings.json"))
        .ok()
        .and_then(|raw| serde_json::from_str::<Value>(&raw).ok())
        .and_then(|settings| {
            settings
                .get("currentProviderCodex")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .filter(|value| !value.trim().is_empty());
    let provider_rows: Vec<(String, String, String, String, String, bool)> = {
        let connection = Connection::open_with_flags(
            &db_path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(|error| format!("open CC Switch database: {error}"))?;
        let mut statement = connection
            .prepare(
                "SELECT id, name, app_type, settings_config, meta, is_current
                 FROM providers
                 WHERE app_type = 'codex'
                 ORDER BY sort_index ASC, name ASC",
            )
            .map_err(|error| format!("prepare CC Switch provider query: {error}"))?;
        let rows = statement
            .query_map([], |row| {
                let id: String = row.get(0)?;
                let name: String = row.get(1)?;
                let app_type: String = row.get(2)?;
                let settings: String = row.get(3)?;
                let meta: String = row.get(4)?;
                let is_current: i64 = row.get(5)?;
                Ok((id, name, app_type, settings, meta, is_current != 0))
            })
            .map_err(|error| format!("query CC Switch providers: {error}"))?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|error| format!("read CC Switch providers: {error}"))?
    };
    let selected_row = selected_provider
        .as_deref()
        .and_then(|selector| {
            provider_rows
                .iter()
                .find(|row| row.0 == selector || row.1 == selector)
                .cloned()
        })
        .or_else(|| provider_rows.iter().find(|row| row.5).cloned());
    let mut records = Vec::new();
    if let Some((id, name, app_type, settings_text, meta_text, _)) = selected_row {
        let settings: Value = serde_json::from_str(&settings_text).unwrap_or(Value::Null);
        let meta: Value = serde_json::from_str(&meta_text).unwrap_or(Value::Null);
        let (base_url, api_key, model, usage_path) = provider_credentials(&settings, &meta);
        let result = if base_url.is_empty() || api_key.is_empty() {
            BalanceResponse {
                success: false,
                remaining: None,
                total: None,
                unit: None,
                provider: None,
                error: Some("当前 Codex 供应商缺少余额查询地址或 API key".to_string()),
            }
        } else {
            match get_balance(BalanceRequest {
                base_url: base_url.clone(),
                api_key,
                usage_path,
            })
            .await
            {
                Ok(result) => result,
                Err(error) => BalanceResponse {
                    success: false,
                    remaining: None,
                    total: None,
                    unit: None,
                    provider: None,
                    error: Some(error),
                },
            }
        };
        records.push(CcSwitchProviderBalance {
            id,
            name,
            app_type,
            model,
            base_url,
            success: result.success,
            remaining: result.remaining,
            total: result.total,
            unit: result.unit,
            provider: result.provider,
            error: result.error,
        });
    }
    Ok(records)
}

#[tauri::command]
fn show_notification(app: AppHandle, title: String, body: String) -> Result<bool, String> {
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map(|_| true)
        .map_err(|error| format!("show notification: {error}"))
}

#[tauri::command]
fn get_codex_info() -> CodexInfo {
    let executable = codex_executable();
    let mut version = String::new();
    if let Ok(output) = output_command(&executable, &["--version".to_string()]) {
        version = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if version.is_empty() {
            version = String::from_utf8_lossy(&output.stderr).trim().to_string();
        }
    }
    if version.is_empty() {
        let version_file = codex_home().join("version.json");
        if let Ok(text) = fs::read_to_string(version_file) {
            if let Ok(value) = serde_json::from_str::<Value>(&text) {
                version = value
                    .get("version")
                    .or_else(|| value.get("cli_version"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
            }
        }
    }
    let config = fs::read_to_string(codex_config_path()).unwrap_or_default();
    let model = parse_toml_string(&config, "model");
    let model_provider = parse_toml_string(&config, "model_provider");
    let provider_name = model_provider.as_deref().and_then(|provider| {
        parse_toml_section_string(&config, &format!("model_providers.{provider}"), "name")
    });
    CodexInfo {
        installed: !version.is_empty() || executable != "codex",
        version: version.trim().trim_start_matches("codex-cli ").to_string(),
        executable,
        model,
        model_provider,
        provider_name,
    }
}

#[tauri::command]
fn get_mobile_bridge_config() -> MobileBridgeConfig {
    mobile_bridge_config()
}

#[tauri::command(rename_all = "camelCase")]
fn configure_mobile_bridge(
    settings: MobileBridgeSettingsInput,
) -> Result<MobileBridgeConfig, String> {
    let next = MobileBridgeSettings {
        tunnel_url: settings.tunnel_url.trim().trim_end_matches('/').to_string(),
        cloudflared_path: settings.cloudflared_path.trim().to_string(),
        tunnel_token: settings.tunnel_token.trim().to_string(),
        tunnel_name: settings.tunnel_name.trim().to_string(),
        prefer_tunnel: settings.prefer_tunnel,
        auto_start_tunnel: settings.auto_start_tunnel,
    };
    if !next.tunnel_url.is_empty() {
        Url::parse(&next.tunnel_url).map_err(|error| format!("固定隧道地址无效: {error}"))?;
    }
    let current = mobile_bridge_settings();
    let credentials_changed = current.tunnel_url != next.tunnel_url
        || current.cloudflared_path != next.cloudflared_path
        || current.tunnel_token != next.tunnel_token
        || current.tunnel_name != next.tunnel_name;
    if credentials_changed && mobile_tunnel_running() {
        stop_mobile_tunnel()?;
    }
    save_mobile_bridge_settings(&next)?;
    if next.auto_start_tunnel && next.prefer_tunnel {
        let _ = launch_mobile_tunnel(&next);
    }
    Ok(mobile_bridge_config())
}

#[tauri::command]
fn start_mobile_bridge_tunnel() -> Result<MobileBridgeConfig, String> {
    let settings = mobile_bridge_settings();
    launch_mobile_tunnel(&settings)?;
    Ok(mobile_bridge_config())
}

#[tauri::command]
fn stop_mobile_bridge_tunnel() -> Result<MobileBridgeConfig, String> {
    stop_mobile_tunnel()?;
    Ok(mobile_bridge_config())
}

#[tauri::command(rename_all = "camelCase")]
fn get_server_tunnel_status() -> ServerTunnelStatus {
    current_server_tunnel_status()
}

#[tauri::command(rename_all = "camelCase")]
fn get_server_tunnel_progress() -> ServerTunnelProgress {
    server_tunnel_progress()
        .lock()
        .map(|progress| progress.clone())
        .unwrap_or_default()
}

#[tauri::command(rename_all = "camelCase")]
async fn install_server_tunnel(
    request: ServerTunnelInstallRequest,
) -> Result<ServerTunnelStatus, String> {
    tauri::async_runtime::spawn_blocking(move || install_server_tunnel_now(request))
        .await
        .map_err(|error| format!("服务器通道部署任务失败: {error}"))?
}

#[tauri::command]
fn start_server_tunnel() -> Result<ServerTunnelStatus, String> {
    let settings = server_tunnel_settings();
    match start_server_tunnel_process(&settings) {
        Ok(()) => Ok(current_server_tunnel_status()),
        Err(error) => {
            if let Ok(mut state) = server_tunnel_error().lock() {
                *state = Some(error.clone());
            }
            Err(error)
        }
    }
}

#[tauri::command]
fn stop_server_tunnel() -> Result<ServerTunnelStatus, String> {
    stop_server_tunnel_process()?;
    Ok(current_server_tunnel_status())
}

#[tauri::command]
fn update_codex() -> Result<CodexInfo, String> {
    let args = vec![
        "install".to_string(),
        "--global".to_string(),
        "@openai/codex@latest".to_string(),
    ];
    let output = command_for("npm", &args).and_then(|mut command| {
        command
            .stdin(Stdio::null())
            .output()
            .map_err(|error| format!("run npm update: {error}"))
    })?;
    if !output.status.success() {
        let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if detail.is_empty() {
            format!("npm exited with status {}", output.status)
        } else {
            detail
        });
    }
    Ok(get_codex_info())
}

#[tauri::command]
fn set_codex_defaults(model: String, permission: String) -> Result<CodexInfo, String> {
    let model = model.trim();
    if model.is_empty() || model.len() > 160 || model.contains(['\r', '\n']) {
        return Err("model name is invalid".to_string());
    }
    let (approval_policy, sandbox_mode) = match permission.as_str() {
        "Read only" => ("on-request", "read-only"),
        "Full access" => ("never", "danger-full-access"),
        _ => ("on-request", "workspace-write"),
    };
    let path = codex_config_path();
    let original = fs::read_to_string(&path).unwrap_or_default();
    let updated = upsert_root_toml_string(&original, "model", model);
    let updated = upsert_root_toml_string(&updated, "approval_policy", approval_policy);
    let updated = upsert_root_toml_string(&updated, "sandbox_mode", sandbox_mode);
    if path.exists() {
        let _ = backup_file(&path)?;
    }
    write_text_atomically(&path, &updated)?;
    Ok(get_codex_info())
}

fn skill_roots() -> [(PathBuf, bool); 2] {
    [
        (codex_home().join("skills"), true),
        (codex_home().join("skills-disabled"), false),
    ]
}

fn frontmatter_value(text: &str, key: &str) -> Option<String> {
    let mut lines = text.lines();
    if lines.next().map(str::trim) != Some("---") {
        return None;
    }
    for line in lines {
        let trimmed = line.trim();
        if trimmed == "---" {
            break;
        }
        let Some((candidate, value)) = trimmed.split_once(':') else {
            continue;
        };
        if candidate.trim().eq_ignore_ascii_case(key) {
            return Some(value.trim().trim_matches(['"', '\'']).to_string());
        }
    }
    None
}

fn skill_git_command(root: &Path, args: &[&str]) -> Command {
    let mut command = Command::new("git");
    command.arg("-C").arg(root).args(args);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    command
}

fn git_output(root: &Path, args: &[&str]) -> Option<String> {
    let output = skill_git_command(root, args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn skill_git_root(path: &Path, boundary: &Path) -> Option<PathBuf> {
    let mut current = Some(path);
    while let Some(candidate) = current {
        if candidate.join(".git").exists() {
            return Some(candidate.to_path_buf());
        }
        if candidate == boundary {
            break;
        }
        current = candidate.parent();
    }
    None
}

fn skill_update_state(git_root: Option<&Path>) -> (Option<bool>, String) {
    let Some(root) = git_root else {
        return (None, "unmanaged".to_string());
    };
    let Some(counts) = git_output(
        root,
        &["rev-list", "--left-right", "--count", "HEAD...@{upstream}"],
    ) else {
        return (None, "no-upstream".to_string());
    };
    let values = counts
        .split_whitespace()
        .filter_map(|value| value.parse::<u64>().ok())
        .collect::<Vec<_>>();
    if values.len() != 2 {
        return (None, "unknown".to_string());
    }
    let behind = values[1];
    (
        Some(behind > 0),
        if behind > 0 { "available" } else { "current" }.to_string(),
    )
}

fn skill_record(path: &Path, root: &Path, enabled: bool) -> Option<SkillRecord> {
    let skill_file = path.join("SKILL.md");
    if !skill_file.exists() {
        return None;
    }
    let text = fs::read_to_string(&skill_file).unwrap_or_default();
    let fallback_name = path.file_name()?.to_string_lossy().to_string();
    let name = frontmatter_value(&text, "name").unwrap_or(fallback_name);
    let description = frontmatter_value(&text, "description")
        .unwrap_or_else(|| "Installed Codex skill".to_string())
        .chars()
        .take(240)
        .collect::<String>();
    let version = frontmatter_value(&text, "version").unwrap_or_else(|| "local".to_string());
    let relative = path.strip_prefix(root).unwrap_or(path);
    let protected = relative
        .components()
        .next()
        .map(|part| part.as_os_str() == ".system")
        .unwrap_or(false);
    let git_root = skill_git_root(path, root);
    let repository = git_root
        .as_deref()
        .and_then(|repo| git_output(repo, &["config", "--get", "remote.origin.url"]))
        .filter(|value| !value.is_empty())
        .or_else(|| frontmatter_value(&text, "repository"))
        .or_else(|| frontmatter_value(&text, "source"))
        .or_else(|| frontmatter_value(&text, "homepage"));
    let (update_available, update_status) = if protected {
        (None, "managed-by-codex".to_string())
    } else {
        skill_update_state(git_root.as_deref())
    };
    Some(SkillRecord {
        name,
        version,
        description,
        source: if protected {
            "system"
        } else if enabled {
            "global"
        } else {
            "disabled"
        }
        .to_string(),
        path: path.to_string_lossy().to_string(),
        enabled,
        protected,
        managed: git_root.is_some(),
        repository,
        update_available,
        update_status,
    })
}

fn collect_installed_skills() -> Vec<SkillRecord> {
    let mut seen = HashMap::new();
    let mut result = Vec::new();
    for (root, enabled) in skill_roots() {
        if !root.exists() {
            continue;
        }
        for entry in WalkDir::new(&root)
            .follow_links(false)
            .max_depth(5)
            .into_iter()
            .filter_map(Result::ok)
        {
            if !entry.file_type().is_file() || entry.file_name() != "SKILL.md" {
                continue;
            }
            let Some(path) = entry.path().parent() else {
                continue;
            };
            let canonical = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
            if seen.insert(canonical, true).is_some() {
                continue;
            }
            if let Some(record) = skill_record(path, &root, enabled) {
                result.push(record);
            }
        }
    }
    result.sort_by(|a, b| {
        b.enabled
            .cmp(&a.enabled)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    result
}

fn resolve_skill_path(value: &str) -> Result<(PathBuf, PathBuf, bool), String> {
    let path = PathBuf::from(value);
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("resolve skill path: {error}"))?;
    for (root, enabled) in skill_roots() {
        let root_canonical = root.canonicalize().unwrap_or(root.clone());
        if canonical.starts_with(&root_canonical) && canonical != root_canonical {
            return Ok((canonical, root_canonical, enabled));
        }
    }
    Err("skill path is outside the managed Codex skill roots".to_string())
}

#[tauri::command]
fn list_installed_skills() -> Vec<SkillRecord> {
    collect_installed_skills()
}

#[tauri::command]
fn get_skill_detail(path: String) -> Result<SkillDetail, String> {
    let (resolved, root, enabled) = resolve_skill_path(&path)?;
    let skill = skill_record(&resolved, &root, enabled)
        .ok_or_else(|| "SKILL.md was not found in the selected directory".to_string())?;
    let content = fs::read_to_string(resolved.join("SKILL.md"))
        .map_err(|error| format!("read SKILL.md: {error}"))?;
    let files = WalkDir::new(&resolved)
        .follow_links(false)
        .max_depth(3)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .filter_map(|entry| {
            entry
                .path()
                .strip_prefix(&resolved)
                .ok()
                .map(|value| value.to_string_lossy().to_string())
        })
        .take(100)
        .collect();
    Ok(SkillDetail {
        skill,
        content,
        files,
    })
}

#[tauri::command]
fn check_skill_updates(paths: Vec<String>) -> Result<Vec<SkillRecord>, String> {
    let mut repositories = HashMap::<PathBuf, bool>::new();
    for value in paths {
        let (path, root, _) = resolve_skill_path(&value)?;
        if let Some(repository) = skill_git_root(&path, &root) {
            repositories.insert(repository, true);
        }
    }
    for repository in repositories.keys() {
        let _ = skill_git_command(repository, &["fetch", "--quiet", "--prune"]).status();
    }
    Ok(collect_installed_skills())
}

#[tauri::command]
fn set_skills_enabled(paths: Vec<String>, enabled: bool) -> Result<Vec<SkillActionResult>, String> {
    let roots = skill_roots();
    let target_root = roots
        .iter()
        .find(|(_, state)| *state == enabled)
        .map(|(path, _)| path.clone())
        .ok_or_else(|| "skill target root is unavailable".to_string())?;
    fs::create_dir_all(&target_root).map_err(|error| format!("create skill root: {error}"))?;
    let mut results = Vec::new();
    for value in paths {
        let operation = (|| -> Result<String, String> {
            let (path, root, current_enabled) = resolve_skill_path(&value)?;
            let record = skill_record(&path, &root, current_enabled)
                .ok_or_else(|| "invalid skill directory".to_string())?;
            if record.protected {
                return Err("system skills are managed by Codex".to_string());
            }
            if current_enabled == enabled {
                return Ok(if enabled {
                    "already enabled"
                } else {
                    "already disabled"
                }
                .to_string());
            }
            let relative = path
                .strip_prefix(&root)
                .map_err(|_| "cannot resolve relative skill path".to_string())?;
            let target = target_root.join(relative);
            if target.exists() {
                return Err(format!("target already exists: {}", target.display()));
            }
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)
                    .map_err(|error| format!("create target directory: {error}"))?;
            }
            fs::rename(&path, &target).map_err(|error| format!("move skill: {error}"))?;
            Ok(if enabled { "enabled" } else { "disabled" }.to_string())
        })();
        results.push(SkillActionResult {
            path: value,
            success: operation.is_ok(),
            message: operation.unwrap_or_else(|error| error),
        });
    }
    Ok(results)
}

#[tauri::command]
fn update_skills(paths: Vec<String>) -> Result<Vec<SkillActionResult>, String> {
    let mut repository_results = HashMap::<PathBuf, Result<String, String>>::new();
    let mut resolved = Vec::new();
    for value in paths {
        let operation = resolve_skill_path(&value).and_then(|(path, root, enabled)| {
            let record = skill_record(&path, &root, enabled)
                .ok_or_else(|| "invalid skill directory".to_string())?;
            if record.protected {
                return Err("system skills are updated with Codex".to_string());
            }
            let repository = skill_git_root(&path, &root)
                .ok_or_else(|| "no Git repository is associated with this skill".to_string())?;
            Ok(repository)
        });
        resolved.push((value, operation));
    }
    for (_, repository) in &resolved {
        let Ok(repository) = repository else {
            continue;
        };
        if repository_results.contains_key(repository) {
            continue;
        }
        let result = skill_git_command(repository, &["pull", "--ff-only"])
            .output()
            .map_err(|error| format!("run git pull: {error}"))
            .and_then(|output| {
                if output.status.success() {
                    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
                } else {
                    Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
                }
            });
        repository_results.insert(repository.clone(), result);
    }
    Ok(resolved
        .into_iter()
        .map(|(path, repository)| {
            let operation = repository.and_then(|repo| {
                repository_results
                    .get(&repo)
                    .cloned()
                    .unwrap_or_else(|| Err("repository update did not run".to_string()))
            });
            SkillActionResult {
                path,
                success: operation.is_ok(),
                message: operation.unwrap_or_else(|error| error),
            }
        })
        .collect())
}

#[tauri::command]
fn delete_skills(paths: Vec<String>) -> Result<Vec<SkillActionResult>, String> {
    let mut results = Vec::new();
    for value in paths {
        let operation = (|| -> Result<String, String> {
            let (path, root, enabled) = resolve_skill_path(&value)?;
            let record = skill_record(&path, &root, enabled)
                .ok_or_else(|| "invalid skill directory".to_string())?;
            if record.protected {
                return Err("system skills cannot be deleted".to_string());
            }
            fs::remove_dir_all(&path).map_err(|error| format!("delete skill: {error}"))?;
            Ok("deleted".to_string())
        })();
        results.push(SkillActionResult {
            path: value,
            success: operation.is_ok(),
            message: operation.unwrap_or_else(|error| error),
        });
    }
    Ok(results)
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "Show Codex Atlas", true, None::<&str>)?;
    let mini = MenuItem::with_id(app, "mini", "Toggle Atlas Mini", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &mini, &quit])?;
    let _tray = TrayIconBuilder::new()
        .menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "mini" => {
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    let _ = set_floating_window_visible(app, true).await;
                });
            }
            "quit" => {
                let _ = stop_server_tunnel_process();
                let _ = stop_mobile_tunnel();
                app.exit(0)
            }
            _ => {}
        })
        .build(app)?;
    Ok(())
}

pub fn run() {
    let state = Arc::new(AppState::default());
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .manage((*state).clone())
        .setup(|app| {
            // Keep the monitoring bridge self-healing across Codex and Paseo
            // upgrades. Installation is atomic and idempotent.
            let _ = install_codex_hook_now();
            let _ = build_tray(app.handle());
            spawn_mobile_bridge();
            let bridge_settings = mobile_bridge_settings();
            if bridge_settings.auto_start_tunnel {
                let _ = launch_mobile_tunnel(&bridge_settings);
            }
            let server_settings = server_tunnel_settings();
            if server_settings.auto_start && server_tunnel_key_path().exists() {
                let _ = start_server_tunnel_process(&server_settings);
            }
            let state = app.state::<AppState>().inner().clone();
            spawn_runtime_monitor(app.handle().clone(), state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            minimize_window,
            toggle_maximize_window,
            start_window_drag,
            close_main_window,
            show_main_window,
            set_floating_window_visible,
            set_floating_window_size,
            set_floating_always_on_top,
            show_notification,
            list_sessions,
            list_running_codex_sessions,
            search_sessions,
            create_codex_session,
            resume_codex_session,
            send_session_input,
            send_terminal_input,
            set_auto_continue,
            launch_external_app,
            open_url,
            launch_paseo,
            paseo_import_agent,
            paseo_import_all_codex_sessions,
            get_balance,
            get_cc_switch_provider_balances,
            get_codex_info,
            get_mobile_bridge_config,
            configure_mobile_bridge,
            start_mobile_bridge_tunnel,
            stop_mobile_bridge_tunnel,
            get_server_tunnel_status,
            get_server_tunnel_progress,
            install_server_tunnel,
            start_server_tunnel,
            stop_server_tunnel,
            update_codex,
            set_codex_defaults,
            list_installed_skills,
            get_skill_detail,
            check_skill_updates,
            set_skills_enabled,
            update_skills,
            delete_skills,
            get_codex_hook_status,
            install_codex_hook
        ])
        .run(tauri::generate_context!())
        .expect("error while running Codex Atlas");
}

#[cfg(test)]
mod runtime_probe_tests {
    use super::*;

    #[test]
    fn normalizes_common_epoch_units() {
        assert_eq!(normalize_epoch_ms(1_700_000_000), 1_700_000_000_000);
        assert_eq!(normalize_epoch_ms(1_700_000_000_000), 1_700_000_000_000);
        assert_eq!(normalize_epoch_ms(1_700_000_000_000_000), 1_700_000_000_000);
    }

    #[test]
    fn skill_detail_keeps_the_skill_record_nested() {
        let detail = SkillDetail {
            skill: SkillRecord {
                name: "test-skill".to_string(),
                version: "local".to_string(),
                description: "test".to_string(),
                source: "global".to_string(),
                path: "C:\\skills\\test-skill".to_string(),
                enabled: true,
                protected: false,
                managed: false,
                repository: None,
                update_available: None,
                update_status: "unmanaged".to_string(),
            },
            content: "---\nname: test-skill\n---".to_string(),
            files: vec!["SKILL.md".to_string()],
        };
        let value = serde_json::to_value(detail).expect("serialize skill detail");
        assert_eq!(value["skill"]["name"], "test-skill");
        assert!(value.get("name").is_none());
        assert_eq!(value["files"][0], "SKILL.md");
    }

    #[test]
    fn extracts_session_ids_from_resume_commands() {
        let id = "01a04645-5ce2-7e92-a076-cf4302ed2492";
        assert!(session_id_like(id));
        assert_eq!(
            extract_session_hint(&format!("codex resume {id}")),
            Some(id.to_string())
        );
        assert_eq!(
            extract_session_hint(&format!("codex queue --thread {id}")),
            Some(id.to_string())
        );
        assert!(!session_id_like("not-a-session-id"));
    }

    #[test]
    fn recognizes_localized_continue_prompts() {
        assert!(is_continue_prompt("continue"));
        assert!(is_continue_prompt("  CONTINUE\n"));
        assert!(is_continue_prompt("继续"));
        assert!(!is_continue_prompt("resume"));
    }

    #[test]
    fn approval_output_preserves_multiline_prompt() {
        let prompt = format!(
            "Would you like to proceed?\n{}\n1. Allow\n2. Deny",
            "command details ".repeat(120)
        );
        assert!(prompt.len() > 1200);
        assert_eq!(
            compact_output_line(&prompt).as_deref(),
            Some(prompt.as_str())
        );
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn powershell_resume_uses_the_session_directory_and_id() {
        let session = SessionRecord {
            id: "01a04645-5ce2-7e92-a076-cf4302ed2492".to_string(),
            title: "Resume fixture".to_string(),
            preview: String::new(),
            cwd: "C:\\work folder\\project's files".to_string(),
            branch: String::new(),
            model: String::new(),
            model_provider: String::new(),
            permission: String::new(),
            updated_at_ms: 0,
            created_at_ms: 0,
            rollout_path: String::new(),
            archived: false,
            search_text: String::new(),
            running: false,
            live_state: String::new(),
            process_ids: Vec::new(),
            requires_attention: false,
            status_source: String::new(),
            last_event_at_ms: 0,
            last_error: None,
            failure_key: None,
            last_output: None,
            foreground: false,
            approval: None,
        };
        let command = powershell_resume_command(&session);
        assert!(command.contains("Set-Location -LiteralPath 'C:\\work folder\\project''s files'"));
        assert!(command.contains("resume '01a04645-5ce2-7e92-a076-cf4302ed2492'"));
        assert!(command.contains("-C 'C:\\work folder\\project''s files'"));
    }

    #[test]
    fn hook_repair_updates_atlas_and_isolates_paseo_failures() {
        let mut hooks = serde_json::json!({
            "hooks": {
                "PostToolUse": [
                    {"hooks": [{"type": "command", "command": "powershell old-atlas-hook.ps1"}]},
                    {"hooks": [{
                        "type": "command",
                        "command": "paseo hooks codex PostToolUse",
                        "commandWindows": "paseo hooks codex PostToolUse"
                    }]}
                ]
            }
        });
        assert!(add_atlas_hooks(&mut hooks, "new-atlas-hook-command").expect("repair Atlas hook"));
        let wrapper = Path::new("C:\\Users\\Test\\.codex\\atlas-paseo-hook.cmd");
        assert!(harden_paseo_hooks(&mut hooks, wrapper));
        let serialized = hooks.to_string();
        assert!(serialized.contains("new-atlas-hook-command"));
        assert!(serialized.contains("atlas-paseo-hook"));
        assert!(!add_atlas_hooks(&mut hooks, "new-atlas-hook-command")
            .expect("idempotent Atlas hook repair"));
        assert!(!harden_paseo_hooks(&mut hooks, wrapper));
    }

    #[test]
    fn excludes_codex_companion_processes_from_cli_detection() {
        assert!(is_codex_cli_name("codex.exe"));
        assert!(is_codex_cli_name("C:\\tools\\codex.cmd"));
        for companion in [
            "codex-atlas.exe",
            "codex-code-mode-host.exe",
            "codex-command-runner.exe",
            "codex-windows-sandbox-setup.exe",
        ] {
            assert!(!is_codex_cli_name(companion), "{companion}");
        }
    }

    #[test]
    fn balance_guard_requires_an_actual_depletion_cause() {
        assert!(is_insufficient_balance_error(
            "unexpected status 403 Forbidden; cause: insufficient balance"
        ));
        assert!(!is_insufficient_balance_error(
            "403 Forbidden while requesting the balance endpoint"
        ));
        assert!(!is_insufficient_balance_error("403 Forbidden"));
    }

    #[test]
    fn reads_skill_frontmatter_without_treating_delimiters_as_content() {
        let skill = "---\nname: frontend-design\ndescription: Refined interface guidance\nversion: 1.2.0\n---\n# Body\n";
        assert_eq!(
            frontmatter_value(skill, "name").as_deref(),
            Some("frontend-design")
        );
        assert_eq!(
            frontmatter_value(skill, "description").as_deref(),
            Some("Refined interface guidance")
        );
        assert_eq!(
            frontmatter_value(skill, "version").as_deref(),
            Some("1.2.0")
        );
    }

    #[test]
    fn updates_only_root_codex_defaults() {
        let original = "model = \"old\"\n\n[model_providers.custom]\nmodel = \"provider-model\"\n";
        let updated = upsert_root_toml_string(original, "model", "gpt-current");
        assert!(updated.starts_with("model = \"gpt-current\""));
        assert!(updated.contains("model = \"provider-model\""));
        let updated = upsert_root_toml_string(&updated, "sandbox_mode", "workspace-write");
        assert!(updated.contains("sandbox_mode = \"workspace-write\"\n[model_providers.custom]"));
    }

    #[test]
    fn config_feature_update_is_idempotent() {
        let original = "model = \"gpt-5\"\n\n[features]\n# keep this\n";
        let updated = enable_codex_hooks_in_config(original);
        assert!(config_hooks_enabled(&updated));
        assert!(updated.contains("hooks = true"));
        assert!(!updated.contains("codex_hooks"));
        assert_eq!(enable_codex_hooks_in_config(&updated), updated);

        let without_section = enable_codex_hooks_in_config("model = \"gpt-5\"\n");
        assert!(config_hooks_enabled(&without_section));
        assert!(without_section.contains("[features]"));

        assert!(config_hooks_enabled("model = \"gpt-5\"\n"));
        assert!(!config_hooks_enabled("[features]\nhooks = false\n"));
        let migrated = enable_codex_hooks_in_config("[features]\ncodex_hooks = false\n");
        assert!(config_hooks_enabled(&migrated));
        assert!(!migrated.contains("codex_hooks"));
    }

    #[test]
    fn inline_hook_install_uses_one_toml_representation() {
        let original = "[[hooks.PostToolUse]] # existing tool hook\nmatcher = \"Bash\"\n";
        assert!(config_has_inline_hooks(original));
        let updated = add_atlas_toml_hooks(original, "powershell atlas-hook.ps1")
            .expect("append Atlas hooks");
        for event in ATLAS_HOOK_EVENTS {
            assert!(toml_has_atlas_hook_for_event(&updated, event));
        }
        assert_eq!(
            add_atlas_toml_hooks(&updated, "powershell atlas-hook.ps1")
                .expect("repair Atlas hooks"),
            updated
        );
        assert!(config_has_inline_hooks("[hooks] # inline table\n"));
    }

    #[test]
    fn hook_event_jsonl_is_mapped_to_runtime_states() {
        let path = std::env::temp_dir().join(format!("codex-atlas-hook-test-{}.jsonl", now_ms()));
        let id = "01a04645-5ce2-7e92-a076-cf4302ed2492";
        let contents = format!(
            "{{\"hook_event_name\":\"SessionStart\",\"session_id\":\"{id}\",\"cwd\":\"C:\\\\work\",\"atlas_observed_at_ms\":1700000000000}}\n{{\"hook_event_name\":\"UserPromptSubmit\",\"session_id\":\"{id}\",\"cwd\":\"C:\\\\work\",\"atlas_observed_at_ms\":1700000001000}}\n{{\"hook_event_name\":\"Stop\",\"session_id\":\"{id}\",\"cwd\":\"C:\\\\work\",\"last_assistant_message\":\"Please confirm which option to use.\",\"atlas_observed_at_ms\":1700000002000}}\n"
        );
        fs::write(&path, contents).expect("write hook fixture");
        let observations = parse_hook_state_file(&path);
        fs::remove_file(&path).ok();
        assert_eq!(observations.len(), 3);
        assert!(observations
            .iter()
            .any(|observation| observation.state == "idle"));
        assert!(observations
            .iter()
            .any(|observation| observation.state == "working"));
        assert!(observations
            .iter()
            .any(|observation| observation.state == "waiting" && observation.requires_attention));
        assert!(observations
            .iter()
            .all(|observation| observation.explicit_timestamp));
    }

    #[test]
    fn traffic_light_stop_questions_are_waiting() {
        let stop = serde_json::json!({
            "hook_event_name": "Stop",
            "session_id": "01a04645-5ce2-7e92-a076-cf4302ed2492",
            "last_assistant_message": "需要你确认后再继续吗？"
        });
        assert_eq!(map_hook_event(&stop, &stop), Some(("waiting", true)));
        let subagent = serde_json::json!({
            "hook_event_name": "SubagentStop",
            "session_id": "01a04645-5ce2-7e92-a076-cf4302ed2492",
            "last_assistant_message": "Finished the requested pass."
        });
        assert_eq!(
            map_hook_event(&subagent, &subagent),
            Some(("completed", false))
        );
        let end = serde_json::json!({
            "hook_event_name": "SessionEnd",
            "session_id": "01a04645-5ce2-7e92-a076-cf4302ed2492"
        });
        assert_eq!(map_hook_event(&end, &end), Some(("idle", false)));
    }

    #[test]
    fn stale_hook_cannot_override_rollout() {
        let rollout = RolloutObservation {
            state: "failed".to_string(),
            last_event_at_ms: 2_000,
            requires_attention: true,
            last_error: None,
            failure_key: None,
            last_output: None,
        };
        let stale = HookObservation {
            session_id: "session".to_string(),
            event_name: "stop".to_string(),
            state: "completed".to_string(),
            cwd: None,
            updated_at_ms: 1_000,
            requires_attention: false,
            explicit_timestamp: true,
            last_output: None,
        };
        assert!(!hook_can_override(&stale, &rollout, true));
        let fresh = HookObservation {
            updated_at_ms: 3_000,
            ..stale
        };
        assert!(hook_can_override(&fresh, &rollout, true));
    }

    #[test]
    fn stop_hook_does_not_hide_a_rollout_error() {
        let rollout = RolloutObservation {
            state: "failed".to_string(),
            last_event_at_ms: 2_000,
            requires_attention: true,
            last_error: Some("unexpected status 403: insufficient balance".to_string()),
            failure_key: Some("failure".to_string()),
            last_output: None,
        };
        let stop = HookObservation {
            session_id: "session".to_string(),
            event_name: "stop".to_string(),
            state: "completed".to_string(),
            cwd: None,
            updated_at_ms: 3_000,
            requires_attention: false,
            explicit_timestamp: true,
            last_output: None,
        };
        assert!(!hook_can_override(&stop, &rollout, true));
        let working = HookObservation {
            event_name: "userpromptsubmit".to_string(),
            state: "working".to_string(),
            ..stop
        };
        assert!(hook_can_override(&working, &rollout, true));
    }

    #[test]
    #[ignore = "manual diagnostic: prints the Codex process/session mapping on this machine"]
    fn probe_current_codex_processes() {
        let candidates = codex_process_candidates();
        println!("codex candidates: {}", candidates.len());
        for candidate in candidates {
            println!(
                "pid={} pids={:?} cwd={:?} cmd={}",
                candidate.pid, candidate.process_ids, candidate.cwd, candidate.command_line
            );
        }
        let sessions = load_base_sessions();
        let (enriched, running) = enrich_sessions(sessions);
        println!("enriched={} running={}", enriched.len(), running.len());
        for session in enriched.iter().filter(|session| session.running) {
            println!(
                "session={} cwd={} state={} pids={:?} source={}",
                session.id,
                session.cwd,
                session.live_state,
                session.process_ids,
                session.status_source
            );
        }
    }
}
