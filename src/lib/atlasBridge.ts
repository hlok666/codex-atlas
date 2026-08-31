import { invoke as tauriInvoke } from '@tauri-apps/api/core'
import { listen as tauriListen } from '@tauri-apps/api/event'

export type CodexFailureKind = 'insufficient-balance' | 'retryable' | 'fatal'

export type RecoveryDecision =
  | { action: 'pause-balance'; kind: 'insufficient-balance'; reason: string; attempt: number; maxAttempts: number }
  | { action: 'continue'; kind: 'retryable'; reason: string; attempt: number; maxAttempts: number }
  | { action: 'stop'; kind: CodexFailureKind; reason: string; attempt: number; maxAttempts: number }
  | { action: 'watch'; kind: CodexFailureKind; reason: string; attempt: number; maxAttempts: number }

export type CcSwitchBalanceRequest = {
  baseUrl: string
  apiKey: string
}

export type CcSwitchBalanceResponse = {
  success: boolean
  remaining?: number
  total?: number
  unit?: string
  provider?: string
  error?: string
}

export type CcSwitchProviderBalance = CcSwitchBalanceResponse & {
  id: string
  name: string
  appType: string
  model?: string
  baseUrl: string
}

export type DesktopApprovalOption = {
  value: string
  label: string
}

export type DesktopApprovalRequest = {
  prompt: string
  options: DesktopApprovalOption[]
}

export type DesktopSessionRecord = {
  id: string
  title: string
  preview: string
  cwd: string
  branch: string
  model: string
  modelProvider: string
  permission: string
  updatedAtMs: number
  createdAtMs: number
  rolloutPath: string
  archived: boolean
  searchText: string
  /** True when a live Codex process was matched to this thread. */
  running?: boolean
  /** Native/event-derived state: working, waiting, idle, completed, failed, or unknown. */
  liveState?: string
  /** All PIDs in the matched Codex process tree (node wrapper + native binary). */
  processIds?: number[]
  /** True when the latest rollout event requires user attention. */
  requiresAttention?: boolean
  /** Where the state came from, useful for diagnostics and support. */
  statusSource?: string
  lastEventAtMs?: number
  lastError?: string
  failureKey?: string
  /** Latest compact human-readable rollout/CLI output. */
  lastOutput?: string
  /** True when this session belongs to the current foreground terminal window. */
  foreground?: boolean
  approval?: DesktopApprovalRequest
}

export type NewCodexSessionRequest = {
  cwd: string
  prompt: string
  model: string
  permission: string
}

export type RunningCodexSession = {
  sessionId: string
  pid: number
  state: string
  cwd?: string
  commandLine: string
  processIds: number[]
  observedAtMs: number
  statusSource: string
  requiresAttention: boolean
  lastEventAtMs: number
  lastError?: string
  failureKey?: string
  lastOutput?: string
  foreground: boolean
}

export type CodexHookStatus = {
  hooksPath: string
  configPath: string
  statePath: string
  configured: boolean
  enabled: boolean
  connected: boolean
  lastEventAtMs: number
  sessionCount: number
  error?: string
}

export type CodexModelOption = {
  slug: string
  displayName: string
  official: boolean
  source: 'codex-cache' | 'current-config'
}

export type VoiceServiceStatus = {
  serviceInstalled: boolean
  serviceVersion?: string
  daemonRunning: boolean
  sttReady: boolean
  ttsReady: boolean
  modelsDir: string
  provider: string
  ready: boolean
  error?: string
}

export type VoiceServiceProgress = {
  state: 'idle' | 'installing' | 'downloading' | 'extracting' | 'starting' | 'ready' | 'error' | string
  step: number
  total: number
  message: string
  downloadedBytes: number
  totalBytes?: number
  startedAtMs: number
  finishedAtMs?: number | null
}

export type PaseoImportSummary = {
  total: number
  imported: number
  failed: number
  errors: string[]
}

export type MobileBridgeConfig = {
  deviceId: string
  deviceName: string
  deviceKind: string
  url: string
  lanUrl: string
  tunnelUrl?: string
  activeUrl: string
  connectionMode: string
  preferTunnel: boolean
  autoStartTunnel: boolean
  tunnelConfigured: boolean
  tunnelRunning: boolean
  tunnelError?: string
  token: string
  port: number
  pairingUri: string
}

export type MobileBridgeSettings = {
  tunnelUrl: string
  cloudflaredPath: string
  tunnelToken: string
  tunnelName: string
  preferTunnel: boolean
  autoStartTunnel: boolean
}

export type ServerTunnelStatus = {
  configured: boolean
  running: boolean
  host: string
  port: number
  username: string
  remotePort: number
  tunnelUrl: string
  publicUrl?: string
  autoStart: boolean
  keyPath: string
  credentialsSaved: boolean
  error?: string
}

export type ServerTunnelProgress = {
  state: 'idle' | 'connecting' | 'generating-key' | 'authorizing' | 'checking-root' | 'installing' | 'starting' | 'ready' | 'error' | string
  step: number
  total: number
  message: string
  startedAtMs: number
  finishedAtMs?: number | null
}

export type ServerTunnelInstallRequest = {
  host: string
  port: number
  username: string
  password: string
  cloudflareToken: string
  tunnelUrl: string
  remotePort: number
  autoStart: boolean
  rememberPassword: boolean
  identityFile: string
}

export type SkillRecord = {
  name: string
  version: string
  description: string
  descriptionZh?: string
  source: string
  path: string
  enabled: boolean
  protected: boolean
  managed: boolean
  repository?: string
  updateAvailable?: boolean
  updateStatus: 'available' | 'current' | 'unmanaged' | 'no-upstream' | 'managed-by-codex' | 'unknown' | string
}

export type SkillDetail = {
  skill: SkillRecord
  content: string
  files: string[]
  sections?: Array<{ heading: string; content: string }>
}

export type SkillActionResult = {
  path: string
  success: boolean
  message: string
}

export type SkillDetailResult = {
  detail: SkillDetail | null
  error?: string
}

/**
 * The balance error emitted by local proxy providers is intentionally matched
 * by meaning, not one exact sentence. This covers CC Switch and other
 * OpenAI-compatible gateways that keep the useful cause in the response body.
 */
export function classifyCodexFailure(errorText: string): CodexFailureKind {
  const text = errorText.toLowerCase()
  const forbidden = /\b403\b|forbidden/i.test(text)
  const balanceCause = /insufficient\s+(balance|credit|credits|quota)|(?:balance|credit|credits|quota)\s+(?:is\s+)?(?:insufficient|depleted|exhausted|empty|exceeded)|no\s+(?:remaining\s+)?(?:balance|credit|credits)|余额不足/i.test(text)
  if (forbidden && balanceCause) {
    return 'insufficient-balance'
  }
  if (/(408|409|425|429|500|502|503|504|timeout|timed out|temporar)/i.test(text)) {
    return 'retryable'
  }
  return 'fatal'
}

export function decideRecovery(errorText: string, consecutiveFailures: number, autoContinue: boolean, maxAttempts = 3): RecoveryDecision {
  const kind = classifyCodexFailure(errorText)
  if (kind === 'insufficient-balance') {
    return { action: 'pause-balance', kind, reason: errorText, attempt: consecutiveFailures, maxAttempts }
  }
  const attempt = consecutiveFailures + 1
  // `consecutiveFailures` counts continue attempts already sent. Allow the
  // configured third continue, then stop when the next failure arrives.
  if (kind === 'retryable' && autoContinue && consecutiveFailures < maxAttempts) {
    return { action: 'continue', kind, reason: errorText, attempt, maxAttempts }
  }
  if (consecutiveFailures >= maxAttempts) {
    return { action: 'stop', kind, reason: errorText, attempt: maxAttempts, maxAttempts }
  }
  return { action: 'watch', kind, reason: errorText, attempt, maxAttempts }
}

type TauriBridge = {
  invoke?: <T = unknown>(command: string, args?: Record<string, unknown>) => Promise<T>
  core?: {
    invoke?: <T = unknown>(command: string, args?: Record<string, unknown>) => Promise<T>
  }
  event?: {
    listen?: <T = unknown>(event: string, handler: (event: { payload: T }) => void) => Promise<() => void>
  }
}

type TauriInternals = {
  invoke?: <T = unknown>(command: string, args?: Record<string, unknown>) => Promise<T>
  transformCallback?: (callback: unknown, once?: boolean) => number
}

export type DesktopCommandError = {
  command: string
  error: string
}

export type DesktopPlatform = 'windows' | 'macos' | 'linux' | 'browser'

export function detectDesktopPlatform(): DesktopPlatform {
  if (typeof navigator === 'undefined' || (!tauriBridge() && !tauriInternals())) return 'browser'
  const platform = navigator.platform.toLowerCase()
  if (platform.includes('mac')) return 'macos'
  if (platform.includes('win')) return 'windows'
  if (platform.includes('linux')) return 'linux'
  return 'browser'
}

function tauriBridge(): TauriBridge | null {
  const globals = globalThis as typeof globalThis & { __TAURI__?: TauriBridge; __TAURI_INTERNALS__?: TauriInternals }
  const candidate = globals.__TAURI__
  return candidate?.invoke || candidate?.core?.invoke ? candidate : null
}

function tauriInternals(): TauriInternals | null {
  return (globalThis as typeof globalThis & { __TAURI_INTERNALS__?: TauriInternals }).__TAURI_INTERNALS__ || null
}

export async function invokeDesktop<T>(command: string, args?: Record<string, unknown>): Promise<T | null> {
  const bridge = tauriBridge()
  const internals = tauriInternals()
  // Tauri 2's package API is the canonical path. The injected global object
  // is kept as a compatibility fallback for older packaged shells, but must
  // not shadow the official API when both are present.
  const candidates: Array<() => Promise<T>> = []
  if (internals || bridge) candidates.push(() => tauriInvoke<T>(command, args))
  if (internals?.invoke) candidates.push(() => internals.invoke!(command, args))
  if (bridge?.core?.invoke) candidates.push(() => bridge.core!.invoke!(command, args))
  if (bridge?.invoke) candidates.push(() => bridge.invoke!(command, args))
  let lastError: unknown = null
  for (const candidate of candidates) {
    try {
      return await candidate()
    } catch (error) {
      lastError = error
    }
  }
  if (!lastError && candidates.length === 0) {
    try {
      return await tauriInvoke<T>(command, args)
    } catch (error) {
      lastError = error
    }
  }
  const message = lastError instanceof Error ? lastError.message : String(lastError || 'Tauri IPC is unavailable')
  // Keep native failures observable. Callers still receive null for backwards
  // compatibility, while the app can surface actionable feedback for commands
  // initiated by the user.
  console.error(`[Codex Atlas] ${command} failed`, lastError)
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent<DesktopCommandError>('codex-atlas:command-error', {
      detail: { command, error: message },
    }))
  }
  return null
}

/** Returns all Codex threads from the local state database (with JSONL fallback in Rust). */
export async function listCodexSessions(): Promise<DesktopSessionRecord[] | null> {
  return invokeDesktop<DesktopSessionRecord[]>('list_sessions')
}

/** Returns the native process/session matches used by the live status light. */
export async function listRunningCodexSessions(): Promise<RunningCodexSession[] | null> {
  return invokeDesktop<RunningCodexSession[]>('list_running_codex_sessions')
}

export async function getCodexHookStatus(): Promise<CodexHookStatus | null> {
  return invokeDesktop<CodexHookStatus>('get_codex_hook_status')
}

export async function installCodexHook(): Promise<CodexHookStatus | null> {
  return invokeDesktop<CodexHookStatus>('install_codex_hook')
}

/** Searches indexed metadata and, when needed, the raw JSONL conversation body. */
export async function searchCodexSessions(query: string): Promise<DesktopSessionRecord[] | null> {
  return invokeDesktop<DesktopSessionRecord[]>('search_sessions', { query })
}

export async function createCodexSession(request: NewCodexSessionRequest): Promise<boolean> {
  return (await invokeDesktop<boolean>('create_codex_session', request)) ?? false
}

/** Subscribe to a Tauri event while remaining a no-op in browser preview mode. */
export async function listenDesktopEvent<T>(eventName: string, handler: (payload: T) => void): Promise<(() => void) | null> {
  const bridge = tauriBridge()
  try {
    // Prefer the official Tauri 2 event API for the same reason as invoke.
    if (tauriInternals() || bridge) return await tauriListen<T>(eventName, (event) => handler(event.payload))
  } catch {
    // A legacy shell may expose only the injected event bridge.
  }
  try {
    if (bridge?.event?.listen) return await bridge.event.listen<T>(eventName, (event) => handler(event.payload))
  } catch {
    // Event listeners are optional in browser preview and older shells.
  }
  return null
}

/** Cross-platform window controls implemented by the Tauri shell. */
export async function minimizeDesktopWindow(): Promise<boolean | null> {
  return invokeDesktop<boolean>('minimize_window')
}

/** Starts native window dragging from the custom title bar. */
export async function startDesktopWindowDrag(): Promise<boolean | null> {
  return invokeDesktop<boolean>('start_window_drag')
}

/** Toggles the native main window between maximized and restored states. */
export async function toggleMaximizeDesktopWindow(): Promise<boolean | null> {
  return invokeDesktop<boolean>('toggle_maximize_window')
}

/** Closes the native desktop application, including its tray/floating windows. */
export async function closeDesktopWindow(): Promise<boolean | null> {
  return invokeDesktop<boolean>('close_main_window')
}

/** Brings the main native desktop window back when the floating ball is clicked. */
export async function showMainDesktopWindow(): Promise<boolean | null> {
  return invokeDesktop<boolean>('show_main_window')
}

/** Opens or hides the separate always-on-top status window. */
export async function setFloatingWindowVisible(visible: boolean): Promise<boolean | null> {
  return invokeDesktop<boolean>('set_floating_window_visible', { visible })
}

export async function setFloatingWindowSize(size: number): Promise<number | null> {
  return invokeDesktop<number>('set_floating_window_size', { size })
}

/** Matches CC Switch's get_balance Tauri command: base_url + api_key. */
export async function getCcSwitchBalance(request: CcSwitchBalanceRequest): Promise<CcSwitchBalanceResponse | null> {
  return invokeDesktop<CcSwitchBalanceResponse>('get_balance', request)
}

export async function getCcSwitchProviderBalances(): Promise<CcSwitchProviderBalance[] | null> {
  return invokeDesktop<CcSwitchProviderBalance[]>('get_cc_switch_provider_balances')
}

/** Matches Paseo's daemon importAgent contract for a single Codex handle. */
export async function importPaseoSession(sessionId: string, cwd: string): Promise<unknown | null> {
  return invokeDesktop('paseo_import_agent', { provider: 'codex', sessionId, cwd })
}

export async function launchPaseo(executablePath: string): Promise<boolean> {
  const result = await invokeDesktop<boolean>('launch_paseo', { executablePath })
  return result ?? false
}

/** Opens a trusted public URL in the user's default browser. */
export async function openExternalUrl(url: string): Promise<boolean> {
  const normalized = url.trim()
  if (!/^https:\/\//i.test(normalized)) return false
  if (detectDesktopPlatform() === 'browser') {
    window.open(normalized, '_blank', 'noopener,noreferrer')
    return true
  }
  return (await invokeDesktop<boolean>('open_url', { url: normalized })) ?? false
}

/** Opens a local workspace folder in the platform file manager. */
export async function openWorkspace(path: string): Promise<boolean> {
  const normalized = path.trim()
  if (!normalized) return false
  return (await invokeDesktop<boolean>('open_workspace', { path: normalized })) ?? false
}

export async function importAllPaseoSessions(): Promise<PaseoImportSummary | null> {
  return invokeDesktop<PaseoImportSummary>('paseo_import_all_codex_sessions')
}

export async function getCodexInfo(): Promise<{ installed: boolean; version: string; executable: string; model?: string; modelProvider?: string; providerName?: string } | null> {
  return invokeDesktop('get_codex_info')
}

/** Returns the model catalog exposed by the installed Codex CLI. */
export async function getCodexModels(): Promise<CodexModelOption[] | null> {
  return invokeDesktop<CodexModelOption[]>('get_codex_models')
}

export async function getVoiceServiceStatus(): Promise<VoiceServiceStatus | null> {
  return invokeDesktop<VoiceServiceStatus>('get_voice_service_status')
}

export async function getVoiceServiceProgress(): Promise<VoiceServiceProgress | null> {
  return invokeDesktop<VoiceServiceProgress>('get_voice_service_progress')
}

export async function installVoiceService(): Promise<VoiceServiceStatus | null> {
  return invokeDesktop<VoiceServiceStatus>('install_voice_service')
}

export async function getMobileBridgeConfig(): Promise<MobileBridgeConfig | null> {
  return invokeDesktop('get_mobile_bridge_config')
}

export async function configureMobileBridge(settings: MobileBridgeSettings): Promise<MobileBridgeConfig | null> {
  return invokeDesktop<MobileBridgeConfig>('configure_mobile_bridge', settings)
}

export async function startMobileBridgeTunnel(): Promise<MobileBridgeConfig | null> {
  return invokeDesktop<MobileBridgeConfig>('start_mobile_bridge_tunnel')
}

export async function stopMobileBridgeTunnel(): Promise<MobileBridgeConfig | null> {
  return invokeDesktop<MobileBridgeConfig>('stop_mobile_bridge_tunnel')
}

export async function getServerTunnelStatus(): Promise<ServerTunnelStatus | null> {
  return invokeDesktop<ServerTunnelStatus>('get_server_tunnel_status')
}

export async function getServerTunnelProgress(): Promise<ServerTunnelProgress | null> {
  return invokeDesktop<ServerTunnelProgress>('get_server_tunnel_progress')
}

export async function installServerTunnel(request: ServerTunnelInstallRequest): Promise<ServerTunnelStatus | null> {
  return invokeDesktop<ServerTunnelStatus>('install_server_tunnel', request)
}

export async function startServerTunnel(): Promise<ServerTunnelStatus | null> {
  return invokeDesktop<ServerTunnelStatus>('start_server_tunnel')
}

export async function stopServerTunnel(): Promise<ServerTunnelStatus | null> {
  return invokeDesktop<ServerTunnelStatus>('stop_server_tunnel')
}

export async function updateCodex(): Promise<{ installed: boolean; version: string; executable: string; model?: string; modelProvider?: string; providerName?: string } | null> {
  return invokeDesktop('update_codex')
}

export async function setCodexDefaults(model: string, permission: string): Promise<{ installed: boolean; version: string; executable: string; model?: string; modelProvider?: string; providerName?: string } | null> {
  return invokeDesktop('set_codex_defaults', { model, permission })
}

export async function listInstalledSkills(): Promise<SkillRecord[] | null> {
  return invokeDesktop('list_installed_skills')
}

export async function getSkillDetail(path: string): Promise<SkillDetailResult> {
  const detail = await invokeDesktop<SkillDetail>('get_skill_detail', { path })
  return detail ? { detail } : { detail: null, error: 'Desktop shell is unavailable or the skill could not be read' }
}

export async function checkSkillUpdates(paths: string[]): Promise<SkillRecord[] | null> {
  return invokeDesktop<SkillRecord[]>('check_skill_updates', { paths })
}

export async function setSkillsEnabled(paths: string[], enabled: boolean): Promise<SkillActionResult[] | null> {
  return invokeDesktop<SkillActionResult[]>('set_skills_enabled', { paths, enabled })
}

export async function updateSkills(paths: string[]): Promise<SkillActionResult[] | null> {
  return invokeDesktop<SkillActionResult[]>('update_skills', { paths })
}

export async function deleteSkills(paths: string[]): Promise<SkillActionResult[] | null> {
  return invokeDesktop<SkillActionResult[]>('delete_skills', { paths })
}

export async function sendCodexContinue(sessionId: string, focusTerminal = false): Promise<boolean> {
  const result = await invokeDesktop<boolean>('send_session_input', { sessionId, input: '继续', focusTerminal })
  return result ?? false
}

/** Sends an arbitrary response to a live Codex session (approval choices included). */
export async function sendSessionInput(sessionId: string, input: string, focusTerminal = true): Promise<boolean> {
  const result = await invokeDesktop<boolean>('send_session_input', { sessionId, input, focusTerminal })
  return result ?? false
}

/** Queues Chinese `继续` without focusing or raising the session terminal. */
export async function inputCodexContinue(sessionId: string): Promise<boolean> {
  const result = await invokeDesktop<boolean>('send_session_input', { sessionId, input: '继续', focusTerminal: false })
  return result ?? false
}

/** Focuses the matching terminal and submits an arbitrary approval choice. */
export async function sendTerminalInput(sessionId: string, input: string): Promise<boolean> {
  const result = await invokeDesktop<boolean>('send_terminal_input', { sessionId, input, focusTerminal: true })
  return result ?? false
}

export async function setDesktopAutoContinue(enabled: boolean): Promise<boolean | null> {
  return invokeDesktop<boolean>('set_auto_continue', { enabled })
}

export async function setFloatingAlwaysOnTop(enabled: boolean): Promise<boolean | null> {
  return invokeDesktop<boolean>('set_floating_always_on_top', { enabled })
}

/** Opens a session using the shell's native `codex resume` implementation. */
export async function resumeCodexSession(sessionId: string): Promise<boolean> {
  const result = await invokeDesktop<boolean>('resume_codex_session', { sessionId })
  return result ?? false
}
