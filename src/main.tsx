import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Bell,
  BellRing,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  Clock3,
  Command,
  Cpu,
  Download,
  Eye,
  EyeOff,
  ExternalLink,
  FileCode2,
  FileText,
  FolderOpen,
  GitBranch,
  GripVertical,
  Layers3,
  LayoutDashboard,
  ListFilter,
  ListTree,
  LoaderCircle,
  Maximize2,
  Minus,
  Minimize2,
  MoreHorizontal,
  Palette,
  PackageCheck,
  Paperclip,
  Image as ImageIcon,
  Pause,
  PictureInPicture2,
  Play,
  Plus,
  Power,
  PlugZap,
  RadioTower,
  RefreshCw,
  RotateCcw,
  Search,
  Settings2,
  ShieldCheck,
  Square,
  Sparkles,
  TerminalSquare,
  Trash2,
  Upload,
  WalletCards,
  Wrench,
  X,
} from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { checkDesktopUpdate, checkSkillUpdates, classifyCodexFailure, closeDesktopWindow, configureMobileBridge, createCodexSession, decideRecovery, deleteSkills, detectDesktopPlatform, downloadDesktopUpdate, floatingWindowHeartbeat, getCcSwitchBalance, getCcSwitchProviderBalances, getCodexHookStatus, getCodexInfo, getCodexModels, getMobileBridgeConfig, getServerTunnelProgress, getServerTunnelStatus, getSkillDetail, getVoiceServiceProgress, getVoiceServiceStatus, importAllPaseoSessions, inputCodexContinue, installCodexHook, installDesktopUpdate, installServerTunnel, installVoiceService, invokeDesktop, launchPaseo, listCodexSessions, listInstalledSkills, listRunningCodexSessions, listenDesktopEvent, minimizeDesktopWindow, openExternalUrl, openWorkspace as openWorkspacePath, resumeCodexSession, searchCodexSessions, sendCodexContinue, sendFloatingMessage, sendTerminalInput, setCodexDefaults, setDesktopAutoContinue, setFloatingAlwaysOnTop, setFloatingWindowShape, setFloatingWindowSize, setFloatingWindowVisible, setSkillsEnabled, showMainDesktopWindow, startDesktopWindowDrag, startMobileBridgeTunnel, startServerTunnel, stopMobileBridgeTunnel, stopServerTunnel, toggleMaximizeDesktopWindow, updateCodex, updateSkills } from './lib/atlasBridge'
import type { DesktopUpdateInfo, DesktopUpdateProgress } from './lib/atlasBridge'
import type { CcSwitchProviderBalance, CodexHookStatus, CodexModelOption, DesktopCommandError, DesktopSessionRecord, FloatingAttachment, FloatingInputMode, MobileBridgeConfig, MobileBridgeSettings, NewCodexSessionRequest, PaseoImportSummary, RunningCodexSession, ServerTunnelInstallRequest, ServerTunnelProgress, ServerTunnelStatus, SkillDetail, SkillRecord, VoiceServiceProgress, VoiceServiceStatus } from './lib/atlasBridge'
import { FloatingSessionTargetLock } from './lib/floatingSessionTarget'
import { appendFloatingReply, splitFloatingReply } from './lib/floatingReply'
import { ATLAS_GITHUB_REPOSITORY, ATLAS_GITHUB_URL, ATLAS_RELEASES_URL } from './lib/projectMeta'
import packageJson from '../package.json'
import '@fontsource-variable/geist'
import './styles.css'

const ATLAS_VERSION = packageJson.version

type Session = {
  id: string
  title: string
  preview: string
  branch: string
  folder: string
  cwd?: string
  model: string
  permission: string
  updated: string
  timestamp: number
  status: 'active' | 'idle' | 'done'
  tags: string[]
  accent: string
  provider: string
  recovery: RecoveryState
  retryCount: number
  lastError?: string
  paseoImported: boolean
  searchText?: string
  rolloutPath?: string
  liveState?: string
  processIds?: number[]
  statusSource?: string
  requiresAttention?: boolean
  failureKey?: string
  lastEventAtMs?: number
  lastOutput?: string
  foreground?: boolean
}

type RecoveryState = 'healthy' | 'watching' | 'retrying' | 'paused-balance' | 'stopped'

type Provider = {
  name: string
  model: string
  balance: string
  balanceValue: number
  currency: string
  latency: string
  status: 'healthy' | 'warning' | 'offline'
  updated: string
}

const sessions: Session[] = [
  {
    id: 's-2418',
    title: 'Refactor auth middleware',
    preview: 'Add rotating refresh tokens and tighten the cookie boundary in the API layer.',
    branch: 'feat/auth-rotation',
    folder: 'api-gateway',
    model: 'gpt-5-codex',
    permission: 'Workspace write',
    updated: '2 min ago',
    timestamp: 2418,
    status: 'active',
    tags: ['typescript', 'security'],
    accent: 'orange',
    provider: 'Codex2API',
    recovery: 'healthy',
    retryCount: 0,
    paseoImported: true,
  },
  {
    id: 's-2413',
    title: 'Triage flaky e2e suite',
    preview: 'Investigate the checkout timeout and split the retry policy by browser target.',
    branch: 'test/checkout-retries',
    folder: 'web-console',
    model: 'gpt-5-codex',
    permission: 'Workspace write',
    updated: '18 min ago',
    timestamp: 2413,
    status: 'idle',
    tags: ['playwright', 'testing'],
    accent: 'teal',
    provider: 'Codex2API',
    recovery: 'watching',
    retryCount: 1,
    paseoImported: false,
  },
  {
    id: 's-2409',
    title: 'Design token migration',
    preview: 'Map the legacy palette to semantic tokens and produce a safe incremental rollout.',
    branch: 'chore/token-map',
    folder: 'design-system',
    model: 'gpt-5-codex',
    permission: 'Read only',
    updated: '1 hr ago',
    timestamp: 2409,
    status: 'done',
    tags: ['css', 'design'],
    accent: 'violet',
    provider: 'OpenRouter',
    recovery: 'healthy',
    retryCount: 0,
    paseoImported: true,
  },
  {
    id: 's-2401',
    title: 'Document release checklist',
    preview: 'Turn the launch notes into a concise, versioned checklist for the platform team.',
    branch: 'docs/release-playbook',
    folder: 'platform-docs',
    model: 'gpt-5-codex',
    permission: 'Read only',
    updated: 'Yesterday',
    timestamp: 2401,
    status: 'done',
    tags: ['docs', 'release'],
    accent: 'blue',
    provider: 'Codex2API',
    recovery: 'healthy',
    retryCount: 0,
    paseoImported: true,
  },
  {
    id: 's-2392',
    title: 'Query plan regression',
    preview: 'Compare the new index strategy against production traces and propose a rollback gate.',
    branch: 'perf/query-plan',
    folder: 'data-core',
    model: 'gpt-5-codex',
    permission: 'Workspace write',
    updated: 'Yesterday',
    timestamp: 2392,
    status: 'idle',
    tags: ['postgres', 'performance'],
    accent: 'yellow',
    provider: 'Codex2API',
    recovery: 'paused-balance',
    retryCount: 0,
    lastError: '403 Forbidden · insufficient balance',
    paseoImported: false,
  },
  {
    id: 's-2388',
    title: 'Bootstrap CLI diagnostics',
    preview: 'Add an environment doctor command with actionable checks for local contributors.',
    branch: 'feat/doctor-command',
    folder: 'codex-tools',
    model: 'gpt-5-codex',
    permission: 'Workspace write',
    updated: '3 days ago',
    timestamp: 2388,
    status: 'done',
    tags: ['rust', 'cli'],
    accent: 'pink',
    provider: 'OpenRouter',
    recovery: 'stopped',
    retryCount: 3,
    lastError: 'continue failed 3/3',
    paseoImported: true,
  },
]

const navItems = [
  { id: 'overview', icon: LayoutDashboard },
  { id: 'sessions', icon: Layers3 },
  { id: 'monitor', icon: RadioTower },
  { id: 'integrations', icon: PlugZap },
  { id: 'skills', icon: Sparkles },
  { id: 'floating', icon: PictureInPicture2 },
  { id: 'runtime', icon: Cpu },
]

type UiLanguage = 'zh' | 'en'

type FloatingSkin = 'classic' | 'macintosh' | 'mono' | 'mint' | 'amber' | 'blue' | 'imac' | 'workstation' | 'pocket' | 'television' | 'arcade' | 'terminal' | 'tower' | 'console' | 'portable'

const floatingSkinOptions: Array<{ id: FloatingSkin; labelZh: string; labelEn: string }> = [
  { id: 'classic', labelZh: '经典白', labelEn: 'Classic' },
  { id: 'macintosh', labelZh: '初代 Mac', labelEn: 'Macintosh' },
  { id: 'mono', labelZh: '单色终端', labelEn: 'Mono' },
  { id: 'mint', labelZh: '薄荷绿', labelEn: 'Mint' },
  { id: 'amber', labelZh: '琥珀屏', labelEn: 'Amber' },
  { id: 'blue', labelZh: '蓝灰屏', labelEn: 'Blue' },
  { id: 'imac', labelZh: '彩虹一体机', labelEn: 'iMac all-in-one' },
  { id: 'workstation', labelZh: '工作站', labelEn: 'Workstation' },
  { id: 'pocket', labelZh: '便携终端', labelEn: 'Pocket terminal' },
  { id: 'television', labelZh: '电视侧控', labelEn: 'Television set' },
  { id: 'arcade', labelZh: '街机控制台', labelEn: 'Arcade cabinet' },
  { id: 'terminal', labelZh: '终端键盘', labelEn: 'Terminal keyboard' },
  { id: 'tower', labelZh: '塔式工作站', labelEn: 'Tower workstation' },
  { id: 'console', labelZh: '宽屏控制台', labelEn: 'Wide console' },
  { id: 'portable', labelZh: '便携横屏', labelEn: 'Portable wide' },
]

function normalizeFloatingSkin(value: unknown): FloatingSkin {
  return floatingSkinOptions.some((option) => option.id === value) ? value as FloatingSkin : 'classic'
}

const uiText = {
  zh: {
    navigate: '导航', collections: '收藏', activeNow: '当前运行', needsReview: '待处理', pinned: '已固定',
    indexStorage: '索引存储', storageMeta: '4.8 GB / 7 GB · SSD 缓存', localIndexSynced: '本地索引已同步',
    runningSessions: '个 Codex 运行中', switchLanguage: '切换语言', alertsOn: '通知开启', alertsOff: '通知关闭',
    refreshIndex: '刷新索引', openSettings: '打开设置', toggleSidebar: '切换导航栏',
    overview: '首页', sessions: '全部会话', monitor: '恢复监控', integrations: '连接', skills: '技能', floating: '悬浮窗', runtime: '运行设置',
    localSessionIndex: '本地会话索引', recentSessions: '最近会话', everySessionReady: '所有 Codex 会话都已索引，可直接继续。',
    scanSessions: '扫描会话', sessionStream: '会话流', readyToResume: '可继续的会话', shown: '个', searchSessions: '搜索会话、分支或内容…',
    all: '全部', active: '运行中', done: '已完成', session: '会话', workspaceBranch: '工作区 / 分支', model: '模型', updated: '更新时间',
    noSearchResults: '没有匹配的会话。', viewAllSessions: '查看全部会话', resume: '继续', resumeSession: '继续会话', activateSession: '激活会话', inputContinue: '输入继续', newSession: '新建会话', createSession: '创建会话', creatingSession: '正在创建…', sessionCreated: 'Codex 新会话已打开', sessionCreateFailed: '无法创建 Codex 会话', workingDirectory: '工作目录', initialPrompt: '初始提示词', directoryHint: '例如 E:\\projects\\my-app', promptHint: '可选；留空进入交互式 Codex',
    moreSessionActions: '更多会话操作', closeDetail: '关闭详情', workspace: '工作区', folder: '文件夹', branch: '分支', permission: '权限',
    sessionArchive: '会话归档', allSessions: '全部会话', archiveDescription: '搜索标题、提示词、分支和已索引内容。', exportIndex: '导出索引',
    searchArchive: '搜索全部会话…', matchingRecords: '条匹配记录', extensions: '扩展', installedSkills: '已安装技能',
    skillsDescription: '管理每个新 Codex 会话可使用的能力。', syncRegistry: '同步技能库', installed: '已安装技能', enabled: '已启用', localSkills: '本地技能',
    skillOptions: '技能选项', enable: '启用', disable: '停用',
    errorRecovery: '异常恢复', recoveryMonitor: '恢复监控', monitorDescription: '监控 Codex 输出，分类错误，并只在保护规则允许时继续。',
    liveWatcher: '实时监控', guardrailsActive: '恢复保护已开启', balanceBlocked: '余额不足错误会被阻止；其他临时错误最多自动继续 3 次。',
    decisionRules: '决策规则', automaticContinuePolicy: '自动继续策略', balanceRule: '403 + 余额不足', balanceRuleDetail: '匹配供应商响应，暂停会话，不注入 continue。',
    transientRule: '临时 5xx / 超时', transientRuleDetail: '向活跃 Codex 标准输入发送 continue。', threeFailuresRule: '连续三次失败',
    threeFailuresDetail: '停止自动化并发送桌面提醒。', stop: '停止', retry: '重试', desktopNotifications: '桌面通知',
    notifyOnStop: '余额暂停或 3/3 失败时提醒', autoResumeBalance: '余额恢复后自动继续', autoResumeBalanceDescription: '供应商余额重新大于 0 时向已暂停会话发送继续并回车', liveIncidents: '实时事件', sessionsNeedAttention: '个会话需要处理', watchingRecoverable: '正在等待可恢复错误', balance: '余额', failed: '失败',
    inspectIncident: '查看事件', continueAction: '继续', recheck: '重新检查', lastEvent: '最近事件', watcherHealthy: '监控正常',
    connections: '连接', localTools: '本地工具', integrationsDescription: '供应商余额与 Paseo 会话同步。', refresh: '刷新', providerBalanceMonitor: '供应商余额监控',
    sessionCompanion: '会话助手', providersConnected: '个供应商已连接', readyToCheck: '等待检查', balanceRegistry: '余额来自本地供应商注册表', lastChecked: '上次检查', check: '检查',
    refreshProviderBalance: '刷新供应商余额', insufficientBalance: '余额不足时会暂停自动恢复。', launch: '启动', importAll: '全部导入', sessionBridge: '会话桥接',
    readySync: '可同步 Codex 会话', recentRepair: '最近会话修复', synced: '已同步', allSynced: '全部会话已同步', repair: '修复',
    settings: '设置', runtimeTitle: '运行设置', runtimeDescription: '设置新 Codex 会话的启动方式。', save: '保存', sessionDefaults: '会话默认值',
    appliedNewResumes: '应用于新 resume', permissionField: '权限', scanOnLaunch: '启动时扫描', refreshOnLaunch: 'Atlas 启动时刷新本地索引',
    recoveryGuardrails: '恢复保护', pauseBalance: '暂停余额不足并在 3 次重试后停止', on: '开启', off: '关闭', desktopStatusObject: '桌面状态组件', keepObject: '保持小组件置顶显示',
    codexVersion: 'Codex 版本', detectedCli: '从已安装的 CLI 检测', installedBadge: '已安装', detecting: '检测中…', readyResume: '可执行 resume 命令', checkUpdates: '用 npm 更新', atlasProject: 'Codex Atlas', atlasProjectDescription: '项目主页与 GitHub Release', openProject: '打开项目', openRelease: '打开 Release',
    packageManager: 'npm install -g @openai/codex@latest', codexStatusHook: 'Codex 状态 Hook', officialEvents: '优先读取官方事件，再用进程与 rollout 兜底',
    voiceService: 'Atlas 语音服务', voiceServiceDescription: 'Atlas 本地 STT / TTS', voiceServiceReady: '语音服务已就绪', voiceServiceMissing: '尚未安装本地语音', voiceServiceDaemonStopped: 'Atlas 语音服务未运行', voiceServiceInstalling: '正在安装…', installVoiceService: '安装', repairVoiceService: '修复', voiceServiceDaemon: 'Atlas voice daemon', voiceServiceModels: '语音模型', voiceServiceProvider: '本地模型 · Parakeet + Kokoro', voiceServiceChecking: '检测中…', voiceServiceInstallFailed: '语音服务安装失败',
    connected: '状态正常', configuredWaiting: '等待首次事件', notConfigured: '未配置', sessionEvents: '个会话事件', refreshHook: '刷新 Hook 状态', recoverSession: '恢复监控', runningNow: '正在运行',
    repairHook: '重新安装 / 修复', installHook: '安装状态 Hook', browserWindowControls: '浏览器预览不支持窗口控制', desktopWindowUnavailable: '桌面窗口命令尚未连接',
    desktopNotificationsOn: '桌面通知已开启', desktopNotificationsOff: '桌面通知已关闭', browserPreviewSessions: '浏览器预览使用演示会话', noReadableSessions: '未找到可读的 Codex 会话',
    scanComplete: '已扫描', sessionUnit: '个 Codex 会话', providerBalanceLow: '检测到供应商余额不足，已停止自动继续', retriesStopped: '连续 {count} 次失败，自动继续已停止',
    continueSent: '已自动发送 continue · {attempt}/{max}', waitingManual: 'Codex 任务异常，等待人工处理', resumePrepared: '已准备 resume · {title}', resumeOpened: '已打开 codex resume · {title}',
    paseoStarted: 'Paseo 已启动', paseoUnavailable: 'Paseo 暂不可用', connectingCc: '正在连接 CC Switch…',
    floatingOpen: '打开 Codex Atlas', floatingResume: '激活当前会话', floatingInputContinue: '输入继续', floatingHide: '隐藏桌面悬浮电视', floatingIdle: '无运行会话',
    desktopShellUnavailable: '桌面壳不可用', codexResumed: 'Codex 已继续', resumeUnavailable: '暂时无法继续会话', commandFailed: '操作失败', statusPrefix: 'Codex Atlas 状态',
    clickToOpen: '点击打开 Codex Atlas；拖动底部控制栏',
    floatingTitle: '桌面悬浮窗', floatingDescription: '管理桌面小组件、启动行为与异常提醒。', widgetPreview: '实时预览', widgetPreviewDescription: '状态来自当前 Codex 会话',
    widgetVisible: '正在桌面显示', widgetHidden: '当前已隐藏', showWidget: '显示悬浮窗', hideWidget: '隐藏悬浮窗', floatingControls: '悬浮窗设置',
    floatingWidget: '桌面悬浮组件', floatingWidgetDescription: '置顶显示会话状态和快捷操作', showOnLaunch: '启动时显示', showOnLaunchDescription: 'Atlas 启动后自动显示桌面小组件',
    floatingNotifications: '异常提醒', floatingNotificationsDescription: '余额不足或自动恢复停止时发送系统通知', quickActions: '快捷操作', resumeActive: '继续活动会话', alwaysOnTop: '始终置顶', alwaysOnTopDescription: '让 CRT 保持在其他窗口上方', showOutput: '显示最新输出', showOutputDescription: '在屏幕中保留 Codex 最新内容', autoPickSession: '自动跟随活跃会话', autoPickSessionDescription: '哪个会话有变化就显示哪个', floatingScale: '组件尺寸', floatingOpacity: '组件透明度', rightClickMenu: '右键快捷菜单', rightClickMenuDescription: '右键打开激活、输入和主窗口操作', small: '小', medium: '标准', large: '大',
    floatingCrtPreview: 'CRT 预览', floatingCrtPreviewDescription: '桌面悬浮窗使用当前外形和皮肤', floatingSkin: 'CRT 外形与皮肤', floatingSkinDescription: '选择桌面窗口和预览共用的外观', skinClassic: '经典白', skinMacintosh: '初代 Mac', skinMono: '单色终端', skinMint: '薄荷绿', skinAmber: '琥珀屏', skinBlue: '蓝灰屏', quickInputPlaceholder: '点击屏幕输入消息…', quickInputSubmit: '提交消息', modelMenu: '模型', permissionMenu: 'CLI 权限', currentModel: '当前模型', officialModel: 'Codex 官方', configuredModel: '当前配置',
    mobileBridge: 'Android 伴侣', mobileBridgeDescription: '局域网优先，固定隧道作为备用连接', bridgeUrl: 'Bridge 地址', bridgeToken: '访问令牌', copyBridge: '复制连接信息', bridgeCopied: '连接信息已复制', bridgeOffline: 'Bridge 未启动', bridgeLan: '局域网', bridgeTunnel: '固定隧道', bridgeActive: '当前连接', bridgeModeLan: '局域网优先', bridgeModeTunnel: '隧道优先', tunnelUrl: '固定 Tunnel 地址', tunnelToken: 'Tunnel token', tunnelName: 'Tunnel 名称', cloudflaredPath: 'cloudflared 路径', saveBridge: '保存连接', startTunnel: '启动隧道', stopTunnel: '停止隧道', tunnelRunning: '隧道运行中', tunnelStopped: '隧道未运行', tunnelNotConfigured: '未配置固定隧道', tunnelAutoStart: 'Atlas 启动时自动开启隧道', scanToConnect: '扫码连接 Android', bridgePairingHint: '扫码后优先尝试局域网，离开局域网自动切换固定隧道', serverTunnel: '我的服务器通道', serverTunnelDescription: 'SSH 部署服务器通道；Cloudflare 为可选增强', serverHost: 'Host / IP 地址', serverPort: 'SSH 端口', serverUsername: '用户名', serverPassword: '密码', cloudflareToken: 'Cloudflare Tunnel token（可选）', serverRemotePort: '服务器 Bridge 端口', rememberPassword: '记住密码', installAndConnect: '部署', serverInstalling: '正在部署…', serverRunning: '服务器通道运行中', serverStopped: '服务器通道未运行', serverNotConfigured: '尚未部署服务器通道', serverKeyHint: '留空 Cloudflare 字段时使用服务器公网地址直连；部署后使用专用 SSH 密钥自动重连',
    noActiveSession: '当前没有活动会话', activeSession: '活动', waiting: '等待', blocked: '阻塞', otherChoice: '其他', otherChoicePlaceholder: '输入自定义回复', submitOtherChoice: '提交', launchPreferenceOn: '已设为启动时显示', launchPreferenceOff: '已取消启动时显示',
  },
  en: {
    navigate: 'Navigate', collections: 'Collections', activeNow: 'Active now', needsReview: 'Needs review', pinned: 'Pinned',
    indexStorage: 'Index storage', storageMeta: '4.8 GB / 7 GB · SSD cache', localIndexSynced: 'Local index synced',
    runningSessions: 'Codex sessions running', switchLanguage: 'Switch language', alertsOn: 'Alerts on', alertsOff: 'Alerts off',
    refreshIndex: 'Refresh index', openSettings: 'Open settings', toggleSidebar: 'Toggle navigation',
    overview: 'Overview', sessions: 'All sessions', monitor: 'Recovery monitor', integrations: 'Integrations', skills: 'Skills', floating: 'Floating window', runtime: 'Runtime',
    localSessionIndex: 'LOCAL SESSION INDEX', recentSessions: 'Recent sessions', everySessionReady: 'Every Codex session is indexed and ready to resume.',
    scanSessions: 'Scan sessions', sessionStream: 'SESSION STREAM', readyToResume: 'Ready to resume', shown: 'shown', searchSessions: 'Search sessions, branches, content…',
    all: 'All', active: 'Active', done: 'Done', session: 'SESSION', workspaceBranch: 'WORKSPACE / BRANCH', model: 'MODEL', updated: 'UPDATED',
    noSearchResults: 'No sessions match that search.', viewAllSessions: 'View all sessions', resume: 'Resume', resumeSession: 'Resume session', activateSession: 'Activate session', inputContinue: 'Type continue', newSession: 'New session', createSession: 'Create session', creatingSession: 'Creating…', sessionCreated: 'New Codex session opened', sessionCreateFailed: 'Could not create Codex session', workingDirectory: 'Working directory', initialPrompt: 'Initial prompt', directoryHint: 'For example C:\\projects\\my-app', promptHint: 'Optional; leave blank for interactive Codex',
    moreSessionActions: 'More session actions', closeDetail: 'Close detail', workspace: 'WORKSPACE', folder: 'Folder', branch: 'Branch', permission: 'Permission',
    sessionArchive: 'SESSION ARCHIVE', allSessions: 'All sessions', archiveDescription: 'Search across titles, prompts, branches, and indexed content.', exportIndex: 'Export index',
    searchArchive: 'Search the full session archive…', matchingRecords: 'matching records', extensions: 'EXTENSIONS', installedSkills: 'Installed skills',
    skillsDescription: 'Control the capabilities available to every new Codex session.', syncRegistry: 'Sync registry', installed: 'installed skills', enabled: 'enabled', localSkills: 'local skills',
    skillOptions: 'Skill options', enable: 'enable', disable: 'disable',
    errorRecovery: 'ERROR RECOVERY', recoveryMonitor: 'Recovery monitor', monitorDescription: 'Watch Codex output, classify failures, and continue only when the guardrails allow it.',
    liveWatcher: 'Live watcher', guardrailsActive: 'Recovery guardrails are active', balanceBlocked: '403 balance failures are blocked. Other transient errors can continue up to 3 times.',
    decisionRules: 'DECISION RULES', automaticContinuePolicy: 'Automatic continue policy', balanceRule: '403 + insufficient balance', balanceRuleDetail: 'Match provider response, pause session, never inject continue.',
    transientRule: 'Transient 5xx / timeout', transientRuleDetail: 'Send continue to the active Codex stdin.', threeFailuresRule: 'Three consecutive failures',
    threeFailuresDetail: 'Stop automation and surface a desktop alert.', stop: 'STOP', retry: 'RETRY', desktopNotifications: 'Desktop notifications',
    notifyOnStop: 'Notify on balance pause and 3/3 failure stop', autoResumeBalance: 'Auto-continue after balance recovery', autoResumeBalanceDescription: 'Type continue and press Enter when the current provider balance is above zero again', liveIncidents: 'LIVE INCIDENTS', sessionsNeedAttention: 'sessions need attention', watchingRecoverable: 'Watching for recoverable errors', balance: 'BALANCE', failed: 'FAILED',
    inspectIncident: 'Inspect incident', continueAction: 'Continue', recheck: 'Recheck', lastEvent: 'Last event', watcherHealthy: 'watcher healthy',
    connections: 'CONNECTIONS', localTools: 'Local tools', integrationsDescription: 'Provider balances and Paseo session sync.', refresh: 'Refresh', providerBalanceMonitor: 'Provider balance monitor',
    sessionCompanion: 'Session companion', providersConnected: 'providers connected', readyToCheck: 'Ready to check', balanceRegistry: 'Balance is checked from the local provider registry', lastChecked: 'Last checked', check: 'Check',
    refreshProviderBalance: 'Refresh provider balance', insufficientBalance: 'Insufficient balance pauses automatic recovery.', launch: 'Launch', importAll: 'Import all', sessionBridge: 'Session bridge',
    readySync: 'Ready to sync Codex sessions', recentRepair: 'Recent session repair', synced: 'synced', allSynced: 'All sessions synced', repair: 'Repair',
    settings: 'SETTINGS', runtimeTitle: 'Runtime', runtimeDescription: 'Choose how new Codex sessions start.', save: 'Save', sessionDefaults: 'Session defaults',
    appliedNewResumes: 'Applied to new resumes', permissionField: 'PERMISSION', scanOnLaunch: 'Scan on launch', refreshOnLaunch: 'Refresh the local index when Atlas opens',
    recoveryGuardrails: 'Recovery guardrails', pauseBalance: 'Pause balance failures and stop after 3 retries', on: 'On', off: 'Off', desktopStatusObject: 'Desktop status object', keepObject: 'Keep the small status object above other apps',
    codexVersion: 'Codex version', detectedCli: 'Detected from the installed CLI', installedBadge: 'INSTALLED', detecting: 'Detecting…', readyResume: 'Ready for resume commands', checkUpdates: 'Update with npm', atlasProject: 'Codex Atlas', atlasProjectDescription: 'Project home and GitHub releases', openProject: 'Open project', openRelease: 'Open Release',
    packageManager: 'npm install -g @openai/codex@latest', codexStatusHook: 'Codex status hook', officialEvents: 'Official events first, with process and rollout fallbacks',
    voiceService: 'Atlas voice service', voiceServiceDescription: 'Atlas local STT / TTS', voiceServiceReady: 'Voice service is ready', voiceServiceMissing: 'Local voice is not installed', voiceServiceDaemonStopped: 'Atlas voice service is not running', voiceServiceInstalling: 'Installing…', installVoiceService: 'Install', repairVoiceService: 'Repair', voiceServiceDaemon: 'Atlas voice daemon', voiceServiceModels: 'Voice models', voiceServiceProvider: 'Local models · Parakeet + Kokoro', voiceServiceChecking: 'Checking…', voiceServiceInstallFailed: 'Voice service installation failed',
    connected: 'Monitoring', configuredWaiting: 'Waiting for first event', notConfigured: 'Not configured', sessionEvents: 'session events', refreshHook: 'Refresh hook status', recoverSession: 'Recover monitor', runningNow: 'Running now',
    repairHook: 'Repair hook', installHook: 'Install status hook', browserWindowControls: 'Window controls are unavailable in browser preview', desktopWindowUnavailable: 'Desktop window command is unavailable',
    desktopNotificationsOn: 'Desktop notifications on', desktopNotificationsOff: 'Desktop notifications off', browserPreviewSessions: 'Browser preview uses demo sessions', noReadableSessions: 'No readable Codex sessions found',
    scanComplete: 'Scanned', sessionUnit: 'Codex sessions', providerBalanceLow: 'Insufficient provider balance detected; automatic continue stopped', retriesStopped: 'Automatic continue stopped after {count} failures',
    continueSent: 'Automatically sent continue · {attempt}/{max}', waitingManual: 'Codex task needs attention', resumePrepared: 'Resume prepared · {title}', resumeOpened: 'Opened codex resume · {title}',
    paseoStarted: 'Paseo started', paseoUnavailable: 'Paseo unavailable', connectingCc: 'Connecting to CC Switch…',
    floatingOpen: 'Open Codex Atlas', floatingResume: 'Activate current session', floatingInputContinue: 'Type continue', floatingHide: 'Hide desktop CRT widget', floatingIdle: 'No running sessions',
    desktopShellUnavailable: 'Desktop shell unavailable', codexResumed: 'Codex resumed', resumeUnavailable: 'Resume unavailable', commandFailed: 'Command failed', statusPrefix: 'Codex Atlas status',
    clickToOpen: 'Click to open Codex Atlas; drag from the bottom bar',
    floatingTitle: 'Desktop floating window', floatingDescription: 'Manage the desktop widget, launch behavior, and alerts.', widgetPreview: 'Live preview', widgetPreviewDescription: 'Status reflects current Codex sessions',
    widgetVisible: 'Visible on desktop', widgetHidden: 'Currently hidden', showWidget: 'Show floating window', hideWidget: 'Hide floating window', floatingControls: 'Floating window settings',
    floatingWidget: 'Desktop floating widget', floatingWidgetDescription: 'Keep session status and quick actions above other apps', showOnLaunch: 'Show on launch', showOnLaunchDescription: 'Show the desktop widget when Atlas starts',
    floatingNotifications: 'Incident alerts', floatingNotificationsDescription: 'Send a system notification for low balance or stopped recovery', quickActions: 'Quick actions', resumeActive: 'Resume active session', alwaysOnTop: 'Always on top', alwaysOnTopDescription: 'Keep the CRT above other windows', showOutput: 'Show latest output', showOutputDescription: 'Keep the newest Codex content on screen', autoPickSession: 'Follow active session', autoPickSessionDescription: 'Show whichever session is changing', floatingScale: 'Widget size', floatingOpacity: 'Widget opacity', rightClickMenu: 'Right-click menu', rightClickMenuDescription: 'Open activate, input, and main-window actions', small: 'Small', medium: 'Standard', large: 'Large',
    floatingCrtPreview: 'CRT preview', floatingCrtPreviewDescription: 'The desktop widget uses the selected form and skin', floatingSkin: 'CRT form and skin', floatingSkinDescription: 'Use one appearance for the preview and desktop window', skinClassic: 'Classic', skinMacintosh: 'Macintosh', skinMono: 'Mono terminal', skinMint: 'Mint', skinAmber: 'Amber', skinBlue: 'Blue gray', quickInputPlaceholder: 'Click the screen to type…', quickInputSubmit: 'Send message', modelMenu: 'Model', permissionMenu: 'CLI permission', currentModel: 'Current model', officialModel: 'Codex official', configuredModel: 'Current config',
    mobileBridge: 'Android companion', mobileBridgeDescription: 'Prefer LAN, then fall back to a fixed tunnel', bridgeUrl: 'Bridge URL', bridgeToken: 'Access token', copyBridge: 'Copy connection info', bridgeCopied: 'Connection info copied', bridgeOffline: 'Bridge is offline', bridgeLan: 'LAN', bridgeTunnel: 'Fixed tunnel', bridgeActive: 'Active connection', bridgeModeLan: 'Prefer LAN', bridgeModeTunnel: 'Prefer tunnel', tunnelUrl: 'Fixed Tunnel URL', tunnelToken: 'Tunnel token', tunnelName: 'Tunnel name', cloudflaredPath: 'cloudflared path', saveBridge: 'Save connection', startTunnel: 'Start tunnel', stopTunnel: 'Stop tunnel', tunnelRunning: 'Tunnel running', tunnelStopped: 'Tunnel stopped', tunnelNotConfigured: 'Fixed tunnel is not configured', tunnelAutoStart: 'Start tunnel when Atlas launches', scanToConnect: 'Scan to connect Android', bridgePairingHint: 'The phone tries LAN first and switches to the fixed tunnel outside your network', serverTunnel: 'My server tunnel', serverTunnelDescription: 'Deploy a server channel over SSH; Cloudflare is optional', serverHost: 'Host / IP address', serverPort: 'SSH port', serverUsername: 'Username', serverPassword: 'Password', cloudflareToken: 'Cloudflare Tunnel token (optional)', serverRemotePort: 'Server Bridge port', rememberPassword: 'Remember password', installAndConnect: 'Deploy', serverInstalling: 'Deploying…', serverRunning: 'Server tunnel running', serverStopped: 'Server tunnel stopped', serverNotConfigured: 'Server tunnel is not deployed', serverKeyHint: 'Leave Cloudflare fields empty for direct public-server access; a dedicated SSH key is used for reconnects',
    noActiveSession: 'No active session right now', activeSession: 'Active', waiting: 'Waiting', blocked: 'Blocked', otherChoice: 'Other', otherChoicePlaceholder: 'Enter a custom response', submitOtherChoice: 'Submit', launchPreferenceOn: 'Will show when Atlas starts', launchPreferenceOff: 'Will stay hidden when Atlas starts',
  },
} satisfies Record<UiLanguage, Record<string, string>>

function tr(language: UiLanguage, key: string): string {
  const selected = uiText[language] as Record<string, string>
  const fallback = uiText.en as Record<string, string>
  return selected[key] || fallback[key] || key
}

function interpolate(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))
}

const providers: Provider[] = [
  { name: 'Codex2API', model: 'gpt-5.6-sol', balance: '$18.42', balanceValue: 18.42, currency: 'USD', latency: '184 ms', status: 'healthy', updated: 'just now' },
  { name: 'OpenRouter', model: 'gpt-5-codex', balance: '$42.08', balanceValue: 42.08, currency: 'USD', latency: '231 ms', status: 'healthy', updated: '2 min ago' },
  { name: 'Local fallback', model: 'qwen3-coder', balance: 'local', balanceValue: 1, currency: 'LOCAL', latency: '12 ms', status: 'warning', updated: '7 min ago' },
]

const skillItems = [
  { name: 'frontend-design', version: '2.4.0', description: 'Distinctive production-grade interfaces', enabled: true, source: 'local' },
  { name: 'ui-ux-pro-max', version: '1.8.2', description: 'Searchable UI/UX design intelligence', enabled: true, source: 'local' },
  { name: 'playwright', version: '1.3.1', description: 'Browser automation and verification', enabled: true, source: 'global' },
  { name: 'code-review', version: '0.9.8', description: 'Structured engineering review workflow', enabled: false, source: 'global' },
]

type DesktopFailureEvent = {
  sessionId: string
  error: string
  kind: 'insufficient-balance' | 'retryable' | 'fatal'
  action: 'pause-balance' | 'continue' | 'stop' | 'watch'
  attempt: number
  maxAttempts: number
}

type DesktopOutputEvent = {
  sessionId: string
  line?: string
  lastOutput?: string
}

type CodexAppServerEvent = {
  method: string
  params?: {
    threadId?: string
    turnId?: string
    delta?: string
    status?: { type?: string; activeFlags?: string[] }
    turn?: { id?: string; status?: string; error?: { message?: string } | null }
  }
}

function appServerSessionPatch(event: CodexAppServerEvent): Partial<Session> | null {
  const params = event.params || {}
  const method = event.method || ''
  const sessionId = params.threadId
  if (!sessionId) return null
  if (method === 'thread/status/changed') {
    const status = params.status?.type || 'idle'
    const active = status === 'active'
    const flags = params.status?.activeFlags || []
    return { status: active ? 'active' : 'done', liveState: flags.join(',') || status === 'active' ? (flags.includes('waitingOnApproval') ? 'waiting' : 'working') : 'idle', processIds: active ? [1] : [], lastEventAtMs: Date.now(), timestamp: Date.now(), updated: relativeUpdated(Date.now()), statusSource: 'app-server' }
  }
  if (method === 'turn/started') return { status: 'active', liveState: 'working', processIds: [1], lastEventAtMs: Date.now(), timestamp: Date.now(), updated: relativeUpdated(Date.now()), statusSource: 'app-server' }
  if (method === 'turn/completed') return { status: 'active', liveState: 'idle', lastEventAtMs: Date.now(), timestamp: Date.now(), updated: relativeUpdated(Date.now()), statusSource: 'app-server', lastError: params.turn?.error?.message || undefined }
  if (method === 'item/agentMessage/delta' || method === 'item/reasoning/summaryTextDelta' || method === 'item/commandExecution/outputDelta') {
    return { status: 'active', liveState: method.includes('commandExecution') ? 'tool' : 'working', lastOutput: params.delta || '', lastEventAtMs: Date.now(), timestamp: Date.now(), updated: relativeUpdated(Date.now()), statusSource: 'app-server' }
  }
  return null
}

function applyAppServerSessionPatch(
  item: Session,
  event: CodexAppServerEvent,
  streamedReplies: Map<string, string>,
): Session {
  const patch = appServerSessionPatch(event)
  const sessionId = event.params?.threadId
  if (!patch || !sessionId) return item
  if (event.method === 'turn/started') {
    // A new turn starts a new assistant message. Do not let the previous
    // completed answer become the prefix of the next streamed response.
    streamedReplies.delete(sessionId)
    return { ...item, ...patch, lastOutput: '' }
  }
  if (event.method === 'item/agentMessage/delta') {
    const merged = appendFloatingReply(streamedReplies.get(sessionId) || '', event.params?.delta || '')
    if (merged) streamedReplies.set(sessionId, merged)
    return { ...item, ...patch, lastOutput: merged || item.lastOutput }
  }
  if (event.method === 'turn/completed') {
    const merged = streamedReplies.get(sessionId)
    return merged ? { ...item, ...patch, lastOutput: merged } : { ...item, ...patch }
  }
  return { ...item, ...patch }
}

type ApprovalOption = {
  label: string
  value: string
  kind?: 'choice' | 'other'
}

type ApprovalRequest = {
  prompt: string
  options: ApprovalOption[]
}

function parseApprovalRequest(item: Session | undefined, language: UiLanguage): ApprovalRequest | null {
  if (!item?.requiresAttention) return null
  const prompt = (item.lastOutput || item.lastError || '').replace(/\r/g, '').trim()
  if (!prompt || classifyCodexFailure(prompt) === 'insufficient-balance') return null
  const approvalMarker = /(?:approval|approve|allow|authorize|permission request|needs? your approval|please confirm|please choose|please select|do you want|would you like|proceed\s*\?|continue\s*\?|\[\s*y\s*\/\s*n\s*\]|\[\s*yes\s*\/\s*no\s*\]|\by\s*\/\s*n\b|\byes\s*\/\s*no\b|审批|批准|授权|是否允许|是否继续|需要确认|要继续吗)/i
  const options: ApprovalOption[] = []
  const lines = prompt.split('\n')
  for (const line of lines) {
    const numbered = line.match(/^\s*(?:[>›❯]\s*)?(\d+)[.)]\s+(.+?)\s*$/)
    const checklist = line.match(/^\s*(?:[-*•]|[>›❯])\s*(?:\[[ xX]\]\s*)?(.+?)\s*$/)
    const match = numbered || checklist
    if (!match) continue
    const value = numbered ? match[1] : match[1]
    const rawLabel = (numbered ? match[2] : match[1]).replace(/[：:]$/, '').trim()
    if (!rawLabel || options.some((option) => option.value === value)) continue
    const kind = isOtherApprovalLabel(rawLabel) ? 'other' : 'choice'
    options.push({
      label: kind === 'other' ? (language === 'zh' ? '其他' : 'Other') : rawLabel,
      value,
      kind,
    })
  }
  if (!approvalMarker.test(prompt) && !options.length) return null
  if (!options.length && /(?:\[\s*(?:y\s*\/\s*n|yes\s*\/\s*no)\s*\]|\b(?:y\s*\/\s*n|yes\s*\/\s*no)\b)/i.test(prompt)) {
    options.push(
      { label: language === 'zh' ? '允许' : 'Allow', value: 'y' },
      { label: language === 'zh' ? '拒绝' : 'Deny', value: 'n' },
    )
  }
  if (!options.length) {
    options.push(
      { label: language === 'zh' ? '确认' : 'Approve', value: 'y' },
      { label: language === 'zh' ? '取消' : 'Deny', value: 'n' },
    )
  }
  const visible = options.slice(0, 8)
  const parsedOther = options.find((option) => option.kind === 'other')
  if (parsedOther && !visible.some((option) => option.value === parsedOther.value)) visible.push(parsedOther)
  if (!visible.some((option) => option.kind === 'other')) {
    visible.push({ label: language === 'zh' ? '其他' : 'Other', value: '__other__', kind: 'other' })
  }
  return { prompt, options: visible }
}

function isOtherApprovalLabel(label: string) {
  return /(?:\bother\b|tell\s+codex|different\s+(?:instructions|approach|way)|provide\s+(?:feedback|instructions)|custom|补充|其他|其它|自定义)/i.test(label)
}

function relativeUpdated(timestamp: number, language: UiLanguage = 'en') {
  const elapsed = Math.max(0, Date.now() - timestamp)
  const minutes = Math.floor(elapsed / 60000)
  if (minutes < 1) return language === 'zh' ? '刚刚' : 'just now'
  if (minutes < 60) return language === 'zh' ? `${minutes} 分钟前` : `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return language === 'zh' ? `${hours} 小时前` : `${hours} hr ago`
  const days = Math.floor(hours / 24)
  return days === 1 ? (language === 'zh' ? '昨天' : 'Yesterday') : language === 'zh' ? `${days} 天前` : `${days} days ago`
}

function formatByteCount(bytes: number, language: UiLanguage) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / (1024 ** index)
  return `${value.toLocaleString(language === 'zh' ? 'zh-CN' : 'en-US', {
    maximumFractionDigits: index === 0 || value >= 10 ? 0 : 1,
  })} ${units[index]}`
}

function formatSessionUpdated(session: Session, language: UiLanguage) {
  // Demo records use small fixture timestamps; real Codex records use epoch ms.
  if (session.timestamp > 100_000_000_000) return relativeUpdated(session.timestamp, language)
  if (language === 'en') return session.updated
  return session.updated
    .replace(/just now/gi, '刚刚')
    .replace(/(\d+)\s*min(?:ute)?s? ago/gi, '$1 分钟前')
    .replace(/(\d+)\s*hr(?:s|our)? ago/gi, '$1 小时前')
    .replace(/Yesterday/gi, '昨天')
    .replace(/(\d+)\s*days? ago/gi, '$1 天前')
}

function formatPermission(permission: string, language: UiLanguage) {
  if (language === 'en') return permission
  const labels: Record<string, string> = {
    'Workspace write': '工作区写入',
    'Read only': '只读',
    'Full access': '完全访问',
  }
  return labels[permission] || permission
}

function localizeSessionValue(value: string, language: UiLanguage) {
  if (language === 'en') return value
  const labels: Record<string, string> = {
    'Untitled session': '未命名会话',
    'No preview available': '暂无预览内容',
    'default model': '默认模型',
    'model not detected': '未读取模型',
    custom: '自定义',
    'no branch': '无分支',
  }
  return labels[value] || value
}

function readStored<T>(key: string, fallback: T): T {
  try {
    const value = window.localStorage.getItem(`codex-atlas:${key}`)
    return value === null ? fallback : JSON.parse(value) as T
  } catch {
    return fallback
  }
}

function writeStored(key: string, value: unknown) {
  try {
    window.localStorage.setItem(`codex-atlas:${key}`, JSON.stringify(value))
  } catch {
    // Browser preview and locked-down webviews may disable local storage.
  }
}

function mapDesktopSession(record: DesktopSessionRecord, index: number): Session {
  const age = Math.max(0, Date.now() - record.updatedAtMs)
  const liveState = (record.liveState || '').toLowerCase()
  const live = record.running === true
    || (record.running === undefined && age < 8 * 60 * 1000)
    || (liveState === 'working' && age < 90 * 1000)
  const status: Session['status'] = live ? 'active' : age < 48 * 60 * 60 * 1000 ? 'idle' : 'done'
  const folder = record.cwd.split(/[\\/]/).filter(Boolean).pop() || 'workspace'
  const branch = record.branch || 'no branch'
  const accents = ['orange', 'teal', 'violet', 'blue', 'yellow', 'pink']
  return {
    id: record.id,
    title: record.title || 'Untitled session',
    preview: record.preview || record.searchText || 'No preview available',
    branch,
    folder,
    cwd: record.cwd,
    model: record.model || 'model not detected',
    permission: record.permission || 'Workspace write',
    updated: relativeUpdated(record.updatedAtMs),
    timestamp: record.updatedAtMs || record.createdAtMs,
    status,
    tags: [folder, record.modelProvider].filter(Boolean),
    accent: accents[index % accents.length],
    provider: record.modelProvider || 'custom',
    recovery: record.requiresAttention || record.lastError ? 'watching' : 'healthy',
    retryCount: 0,
    paseoImported: false,
    searchText: record.searchText,
    rolloutPath: record.rolloutPath,
    lastError: record.lastError,
    liveState: record.liveState,
    processIds: record.processIds,
    statusSource: record.statusSource,
    requiresAttention: record.requiresAttention,
    failureKey: record.failureKey,
    lastEventAtMs: record.lastEventAtMs || record.updatedAtMs,
    lastOutput: record.lastOutput,
    foreground: record.foreground,
  }
}

function DesktopWindowControls({ language, onMessage }: { language: 'zh' | 'en'; onMessage?: (message: string) => void }) {
  const platform = detectDesktopPlatform()
  const [maximized, setMaximized] = useState(false)
  const label = (zh: string, en: string) => language === 'zh' ? zh : en
  const unavailable = () => onMessage?.(platform === 'browser' ? label('浏览器预览不支持窗口控制', 'Window controls are unavailable in browser preview') : label('桌面窗口命令尚未连接', 'Desktop window command is unavailable'))
  const minimize = async () => {
    const handled = await minimizeDesktopWindow()
    if (handled === null) unavailable()
  }
  const toggleMaximize = async () => {
    const next = await toggleMaximizeDesktopWindow()
    if (next === null) unavailable()
    else setMaximized(next)
  }
  const close = async () => {
    const handled = await closeDesktopWindow()
    if (handled === null) unavailable()
  }
  return <div className="window-controls" aria-label={label('窗口控制', 'Window controls')}>
    <button className="window-button minimize" aria-label={label('最小化窗口', 'Minimize window')} title={label('最小化', 'Minimize')} onClick={() => void minimize()}><Minus size={14} /></button>
    <button className="window-button maximize" aria-label={maximized ? label('还原窗口', 'Restore window') : label('最大化窗口', 'Maximize window')} title={maximized ? label('还原', 'Restore') : label('最大化', 'Maximize')} onClick={() => void toggleMaximize()}>{maximized ? <Minimize2 size={13} /> : <Maximize2 size={13} />}</button>
    <button className="window-button close" aria-label={label('关闭程序', 'Close application')} title={label('关闭', 'Close')} onClick={() => void close()}><X size={14} /></button>
  </div>
}

function beginWindowDrag(event: React.MouseEvent<HTMLElement>) {
  if (event.button !== 0) return
  const target = event.target as HTMLElement | null
  if (target?.closest('button, input, select, textarea, a, [data-no-window-drag]')) return
  void startDesktopWindowDrag()
}

function App() {
  const [language, setLanguage] = useState<'zh' | 'en'>(() => readStored('language', 'zh'))
  const [activeNav, setActiveNav] = useState('overview')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<'all' | Session['status']>('all')
  const [sortNewest, setSortNewest] = useState(true)
  const [selected, setSelected] = useState<Session | null>(null)
  const mainContentRef = useRef<HTMLElement | null>(null)
  const [toast, setToast] = useState('')
  const [sessionItems, setSessionItems] = useState<Session[]>([])
  const [sessionLoadState, setSessionLoadState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [installedSkills, setInstalledSkills] = useState<SkillRecord[]>([])
  const [skillsLoadState, setSkillsLoadState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [codexVersion, setCodexVersion] = useState('detecting…')
  const [codexProvider, setCodexProvider] = useState('')
  const storedDefaultModelRef = useRef(readStored('defaultModel', ''))
  const [defaultModel, setDefaultModel] = useState(() => storedDefaultModelRef.current || 'model not detected')
  const [defaultPermission, setDefaultPermission] = useState(() => readStored('defaultPermission', 'Workspace write'))
  const [defaultReasoningEffort, setDefaultReasoningEffort] = useState(() => readStored('defaultReasoningEffort', 'medium'))
  const [autoScan, setAutoScan] = useState(() => readStored('autoScan', true))
  const [autoContinue, setAutoContinue] = useState(() => readStored('autoContinue', true))
  const autoContinueRef = useRef(autoContinue)
  const [autoResumeOnBalance, setAutoResumeOnBalance] = useState(() => readStored('autoResumeOnBalance', false))
  const autoResumeOnBalanceRef = useRef(autoResumeOnBalance)
  const [notifyEnabled, setNotifyEnabled] = useState(() => readStored('notifyEnabled', true))
  const notifyEnabledRef = useRef(notifyEnabled)
  // Keep the main window as the first visible surface. The desktop widget is
  // opt-in and can still be enabled from Floating; showing it during startup
  // makes Windows report the widget title as the app's primary window.
  const [floatingLaunchOnStart, setFloatingLaunchOnStart] = useState(() => readStored('floatingLaunchOnStart', false))
  const [floatingEnabled, setFloatingEnabled] = useState(() => readStored('floatingLaunchOnStart', false))
  const [ccSwitchCheckedAt, setCcSwitchCheckedAt] = useState('not checked')
  const [providerBalances, setProviderBalances] = useState<Provider[]>([])
  const currentProviderBalanceRef = useRef<CcSwitchProviderBalance | null>(null)
  const previousBalanceRef = useRef<number | undefined>(undefined)
  const sessionItemsRef = useRef<Session[]>([])
  const [paseoImportedCount, setPaseoImportedCount] = useState(0)
  const [desktopPlatform] = useState(() => detectDesktopPlatform())
  const [hookStatus, setHookStatus] = useState<CodexHookStatus | null>(null)
  const [mobileBridge, setMobileBridge] = useState<MobileBridgeConfig | null>(null)
  const [serverTunnel, setServerTunnel] = useState<ServerTunnelStatus | null>(null)
  const [newSessionOpen, setNewSessionOpen] = useState(false)
  const t = (key: string) => tr(language, key)
  const commandErrorSeenRef = useRef<Map<string, number>>(new Map())
  const runtimeSyncRef = useRef<((records: RunningCodexSession[]) => void) | null>(null)
  const streamedRepliesRef = useRef(new Map<string, string>())

  const showToast = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2600)
  }

  const toggleFloatingWindow = async () => {
    const next = !floatingEnabled
    setFloatingEnabled(next)
    const handled = await setFloatingWindowVisible(next)
    if (handled === null) showToast(next ? (language === 'zh' ? '悬浮窗已开启（桌面壳连接后置顶显示）' : 'Floating window enabled (topmost after desktop shell connects)') : (language === 'zh' ? '悬浮窗已隐藏' : 'Floating window hidden'))
  }

  const notifyDesktop = (title: string, body: string) => {
    if (!notifyEnabledRef.current) return
    void invokeDesktop('show_notification', { title, body })
    if ('Notification' in window) {
      if (Notification.permission === 'granted') new Notification(title, { body })
      else if (Notification.permission === 'default') void Notification.requestPermission()
    }
  }

  useEffect(() => {
    notifyEnabledRef.current = notifyEnabled
  }, [notifyEnabled])

  useEffect(() => {
    autoContinueRef.current = autoContinue
  }, [autoContinue])

  useEffect(() => {
    autoResumeOnBalanceRef.current = autoResumeOnBalance
  }, [autoResumeOnBalance])

  sessionItemsRef.current = sessionItems

  useEffect(() => {
    const interactiveCommands = new Set([
      'resume_codex_session',
      'create_codex_session',
      'send_session_input',
      'send_floating_message',
      'send_terminal_input',
      'launch_paseo',
      'paseo_import_all_codex_sessions',
      'get_balance',
      'install_codex_hook',
      'install_voice_service',
      'set_codex_defaults',
      'update_codex',
      'check_desktop_update',
      'download_desktop_update',
      'install_desktop_update',
      'get_skill_detail',
      'check_skill_updates',
      'set_skills_enabled',
      'update_skills',
      'delete_skills',
      'set_floating_window_visible',
      'set_floating_window_size',
      'set_floating_always_on_top',
      'configure_mobile_bridge',
      'start_mobile_bridge_tunnel',
      'stop_mobile_bridge_tunnel',
      'get_server_tunnel_status',
      'install_server_tunnel',
      'start_server_tunnel',
      'stop_server_tunnel',
      'minimize_window',
      'toggle_maximize_window',
      'close_main_window',
      'show_main_window',
      'open_workspace',
    ])
    const onCommandError = (event: Event) => {
      const detail = (event as CustomEvent<DesktopCommandError>).detail
      if (!detail || !interactiveCommands.has(detail.command)) return
      const key = `${detail.command}:${detail.error}`
      const now = Date.now()
      const previous = commandErrorSeenRef.current.get(key) || 0
      if (now - previous < 8_000) return
      commandErrorSeenRef.current.set(key, now)
      showToast(`${t('commandFailed')}: ${detail.error}`)
    }
    window.addEventListener('codex-atlas:command-error', onCommandError)
    return () => window.removeEventListener('codex-atlas:command-error', onCommandError)
  }, [language])

  useEffect(() => {
    mainContentRef.current?.scrollTo({ top: 0 })
  }, [activeNav])

  useEffect(() => {
    writeStored('language', language)
    writeStored('defaultModel', defaultModel)
    writeStored('defaultPermission', defaultPermission)
    writeStored('defaultReasoningEffort', defaultReasoningEffort)
    writeStored('autoScan', autoScan)
    writeStored('autoContinue', autoContinue)
    writeStored('autoResumeOnBalance', autoResumeOnBalance)
    writeStored('notifyEnabled', notifyEnabled)
    writeStored('floatingLaunchOnStart', floatingLaunchOnStart)
  }, [language, defaultModel, defaultPermission, defaultReasoningEffort, autoScan, autoContinue, autoResumeOnBalance, notifyEnabled, floatingLaunchOnStart])

  useEffect(() => {
    void setDesktopAutoContinue(autoContinue)
  }, [autoContinue])

  useEffect(() => {
    if (desktopPlatform === 'browser') return
    let disposed = false
    const refreshHookStatus = async () => {
      const status = await getCodexHookStatus()
      if (!disposed && status) setHookStatus(status)
    }
    void refreshHookStatus()
    const timer = window.setInterval(() => void refreshHookStatus(), 10_000)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [desktopPlatform])

  useEffect(() => {
    if (desktopPlatform === 'browser') return
    let disposed = false
    const delays = [0, 150, 450, 1_000]
    const showAtStartup = async () => {
      for (const delay of delays) {
        if (delay > 0) await new Promise((resolve) => window.setTimeout(resolve, delay))
        if (disposed) return
        const handled = await setFloatingWindowVisible(floatingLaunchOnStart)
        if (handled !== null) return
      }
    }
    void showAtStartup()
    return () => { disposed = true }
  }, [])

  const updateAutoContinue = (enabled: boolean) => {
    setAutoContinue(enabled)
    void setDesktopAutoContinue(enabled)
  }

  const handledRuntimeFailuresRef = useRef<Set<string>>(new Set())
  const runtimeFailureCountsRef = useRef<Map<string, number>>(new Map())
  const balanceResumeSentRef = useRef<Set<string>>(new Set())

  const providerKey = (provider: string) => provider.trim().toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]+/g, '')
  const currentProviderMatchesSession = (item: Session) => {
    const current = currentProviderBalanceRef.current
    if (!current || current.appType !== 'codex') return false
    const sessionProvider = providerKey(item.provider)
    const aliases = [current.id, current.name, current.provider || '', current.model || '']
      .map(providerKey)
      .filter(Boolean)
    // Session rollouts often report the generic `custom` provider. Since the
    // native layer now returns exactly one current Codex provider, it is safe
    // to apply its balance to such sessions as well.
    return aliases.includes(sessionProvider) || sessionProvider === 'custom' || sessionProvider === ''
  }
  const hasPositiveCurrentBalance = () => {
    const current = currentProviderBalanceRef.current
    return Boolean(current?.success && current.remaining !== undefined && Number.isFinite(current.remaining) && current.remaining > 0)
  }
  const hasKnownEmptyCurrentBalance = () => {
    const current = currentProviderBalanceRef.current
    return Boolean(current?.success && current.remaining !== undefined && Number.isFinite(current.remaining) && current.remaining <= 0)
  }
  const clearRecoveredBalancePause = (item: Session): Session => {
    if ((hasPositiveCurrentBalance() && currentProviderMatchesSession(item))
      && item.recovery === 'paused-balance'
      && item.lastError
      && (classifyCodexFailure(item.lastError) === 'insufficient-balance' || /CC Switch provider balance/i.test(item.lastError))) {
      return { ...item, recovery: 'healthy', retryCount: 0, lastError: undefined }
    }
    return item
  }

  const handleRuntimeFailures = (records: Array<{ sessionId: string; running: boolean; lastError?: string; failureKey?: string; lastEventAtMs?: number }>) => {
    // Rollout files are the event source for sessions started outside Atlas.
    // Handle each distinct failure marker once, then let the native queue
    // command deliver `continue` to the already-running session.
    for (const record of records) {
      const errorText = record.lastError
      // A rate-limit response can finish the turn and briefly make the
      // process look idle, while the thread remains resumable. Do not require
      // a live PID before applying the bounded recovery policy.
      if (!errorText) continue
      const matchingSession = sessionItemsRef.current.find((item) => item.id === record.sessionId)
      if (!record.running && !matchingSession) continue
      const marker = `${record.sessionId}:${record.failureKey || `${record.lastEventAtMs}:${errorText}`}`
      if (classifyCodexFailure(errorText) === 'insufficient-balance'
        && matchingSession
        && (!hasKnownEmptyCurrentBalance() || hasPositiveCurrentBalance())) {
        // A 403 can contain a generic provider message. Do not pause or
        // notify until the current provider balance endpoint confirms zero.
        if (hasPositiveCurrentBalance() && autoResumeOnBalanceRef.current && !balanceResumeSentRef.current.has(marker)) {
          balanceResumeSentRef.current.add(marker)
          setSessionItems((current) => current.map((item) => item.id === record.sessionId
            ? { ...item, recovery: 'retrying', lastError: errorText }
            : item))
          void inputCodexContinue(record.sessionId)
        }
        continue
      }
      if (handledRuntimeFailuresRef.current.has(marker)) continue
      handledRuntimeFailuresRef.current.add(marker)
      const previousFailures = runtimeFailureCountsRef.current.get(record.sessionId) || 0
      const decision = decideRecovery(errorText, previousFailures, autoContinueRef.current)
      if (decision.action === 'pause-balance') {
        runtimeFailureCountsRef.current.delete(record.sessionId)
        setSessionItems((current) => current.map((item) => item.id === record.sessionId
          ? { ...item, recovery: 'paused-balance', lastError: errorText }
          : item))
      } else if (decision.action === 'continue') {
        runtimeFailureCountsRef.current.set(record.sessionId, decision.attempt)
        setSessionItems((current) => current.map((item) => item.id === record.sessionId
          ? { ...item, recovery: 'retrying', retryCount: decision.attempt, lastError: errorText }
          : item))
        void sendCodexContinue(record.sessionId).then((ok) => {
          if (!ok) {
            setSessionItems((current) => current.map((item) => item.id === record.sessionId
              ? { ...item, recovery: 'watching', lastError: language === 'zh' ? '无法向外部 Codex 会话发送 continue' : 'Unable to send continue to the external Codex session' }
              : item))
          }
        })
      } else if (decision.action === 'stop') {
        runtimeFailureCountsRef.current.set(record.sessionId, decision.maxAttempts)
        notifyDesktop('Codex Atlas recovery guard', interpolate(t('retriesStopped'), { count: decision.maxAttempts }))
        setSessionItems((current) => current.map((item) => item.id === record.sessionId
          ? { ...item, recovery: 'stopped', retryCount: decision.maxAttempts, lastError: errorText }
          : item))
      }
    }
  }

  const syncSessionRecords = (records: DesktopSessionRecord[], announce = false) => {
    const mapped = records.map(mapDesktopSession).map(clearRecoveredBalancePause)
    setSessionItems((current) => {
      const previous = new Map(current.map((item) => [item.id, item]))
      return mapped.map((item) => {
        const old = previous.get(item.id)
        // Keep a local recovery decision visible while the same rollout error
        // is still present in the file; a status poll must not erase 3/3 stop.
        if (old && old.lastError && item.lastError === old.lastError && old.recovery !== 'healthy') {
          return { ...item, recovery: old.recovery, retryCount: old.retryCount }
        }
        return item
      })
    })
    setSelected((current) => {
      const next = mapped.find((item) => item.id === current?.id) || mapped[0] || null
      if (!next || !current || current.id !== next.id) return next
      const preserveGuard = (current.recovery === 'paused-balance' || current.recovery === 'stopped')
        && (current.lastError === next.lastError || !next.lastError)
      return preserveGuard
        ? { ...next, recovery: current.recovery, retryCount: current.retryCount, lastError: current.lastError }
        : next
    })
    if (announce && mapped.length > 0) showToast(`${t('scanComplete')} ${mapped.length} ${t('sessionUnit')}`)
    handleRuntimeFailures(records.map((record) => ({
      sessionId: record.id,
      running: record.running === true,
      lastError: record.lastError,
      failureKey: record.failureKey,
      lastEventAtMs: record.lastEventAtMs,
    })))
  }

  const syncRuntimeSessions = (records: RunningCodexSession[]) => {
    const byId = new Map(records.map((record) => [record.sessionId, record]))
    const mergeRuntime = (item: Session): Session => {
      const runtime = byId.get(item.id)
      if (!runtime) {
        // A process disappearing is expected after a completed turn. Keep a
        // blocked incident visible until a fresh rollout event clears it.
        return item.processIds?.length
          ? {
              ...item,
              status: 'idle',
              liveState: 'idle',
              processIds: [],
              statusSource: item.statusSource || 'rollout',
              foreground: false,
            }
          : item
      }
      const balanceFailure = runtime.lastError
        && classifyCodexFailure(runtime.lastError) === 'insufficient-balance'
        && !hasPositiveCurrentBalance()
        && hasKnownEmptyCurrentBalance()
        ? 'paused-balance'
        : null
      let recovery: RecoveryState = balanceFailure
        || (runtime.requiresAttention ? 'watching' : item.recovery === 'retrying' ? 'retrying' : 'healthy')
      const sameFailure = Boolean(runtime.lastError && (
        runtime.lastError === item.lastError || (runtime.failureKey && runtime.failureKey === item.failureKey)
      ))
      if (item.recovery === 'paused-balance'
        && (sameFailure || runtime.state === 'failed' || runtime.state === 'waiting')) {
        recovery = 'paused-balance'
      }
      if (item.recovery === 'stopped'
        && (sameFailure || runtime.state === 'failed' || runtime.state === 'waiting')) {
        recovery = 'stopped'
      }
      const nextError = runtime.lastError || (
        recovery === 'paused-balance' || recovery === 'stopped' ? item.lastError : undefined
      )
      return {
        ...item,
        status: 'active',
        liveState: runtime.state,
        processIds: runtime.processIds,
        statusSource: runtime.statusSource,
        requiresAttention: runtime.requiresAttention,
        lastEventAtMs: runtime.lastEventAtMs || item.lastEventAtMs,
        timestamp: Math.max(item.timestamp, runtime.lastEventAtMs || 0),
        updated: runtime.lastEventAtMs ? relativeUpdated(runtime.lastEventAtMs) : item.updated,
        recovery,
        lastError: nextError,
        lastOutput: runtime.lastOutput || item.lastOutput,
        foreground: runtime.foreground,
      }
    }
    setSessionItems((current) => current.map((item) => clearRecoveredBalancePause(mergeRuntime(item))))
    setSelected((current) => current ? clearRecoveredBalancePause(mergeRuntime(current)) : current)
    handleRuntimeFailures(records.map((record) => ({
      sessionId: record.sessionId,
      running: true,
      lastError: record.lastError,
      failureKey: record.failureKey,
      lastEventAtMs: record.lastEventAtMs,
    })))
  }
  // Native runtime events arrive outside React's render cycle. Keep a ref to
  // the newest merger so the listener never captures stale session state.
  runtimeSyncRef.current = syncRuntimeSessions

  const scanSessions = async (announce = true) => {
    if (sessionItems.length === 0) setSessionLoadState('loading')
    const records = await listCodexSessions()
    if (!records) {
      setSessionLoadState('error')
      if (announce) showToast(desktopPlatform === 'browser' ? t('browserPreviewSessions') : `${t('commandFailed')}: ${t('noReadableSessions')}`)
      return false
    }
    setSessionLoadState('ready')
    syncSessionRecords(records, announce)
    if (announce && records.length === 0) showToast(desktopPlatform === 'browser' ? t('browserPreviewSessions') : t('noReadableSessions'))
    return true
  }

  useEffect(() => {
    void scanSessions(false)
    void getCodexInfo().then((info) => {
      if (info?.version) setCodexVersion(info.version)
      else if (desktopPlatform === 'browser') setCodexVersion('browser preview')
      setCodexProvider(info?.providerName || info?.modelProvider || '')
      // Replace legacy demo defaults with the model Codex actually uses. A
      // non-empty custom preference remains under user control.
      const legacyDefaults = new Set(['', 'gpt-5-codex', 'gpt-5', 'gpt-4.1', 'model not detected'])
      if (info?.model && legacyDefaults.has(storedDefaultModelRef.current)) {
        setDefaultModel(info.model)
      }
      if (info?.reasoningEffort) setDefaultReasoningEffort(info.reasoningEffort)
    })
    void getMobileBridgeConfig().then((config) => setMobileBridge(config))
    void getServerTunnelStatus().then((status) => setServerTunnel(status))
    void listInstalledSkills().then((items) => {
      if (items) {
        setInstalledSkills(items)
        setSkillsLoadState('ready')
      } else {
        setSkillsLoadState('error')
      }
    })
    if (desktopPlatform === 'browser') return
    let disposed = false
    const refreshRuntime = async () => {
      const records = await listRunningCodexSessions()
      if (!disposed && records) syncRuntimeSessions(records)
    }
    const refreshIndex = async () => {
      const records = await listCodexSessions()
      if (!disposed && records) syncSessionRecords(records)
    }
    void refreshRuntime()
    const runtimeTimer = window.setInterval(() => void refreshRuntime(), 2000)
    const indexTimer = autoScan ? window.setInterval(() => void refreshIndex(), 30_000) : null
    return () => {
      disposed = true
      window.clearInterval(runtimeTimer)
      if (indexTimer !== null) window.clearInterval(indexTimer)
    }
  }, [autoScan, desktopPlatform])

  useEffect(() => {
    const normalized = query.trim()
    if (!normalized) {
      if (desktopPlatform !== 'browser') {
        void listCodexSessions().then((records) => {
          if (!records) return
          syncSessionRecords(records)
        })
      }
      return
    }
    let disposed = false
    const timer = window.setTimeout(() => {
      void searchCodexSessions(normalized).then((records) => {
        if (disposed || !records) return
        syncSessionRecords(records)
      })
    }, 240)
    return () => {
      disposed = true
      window.clearTimeout(timer)
    }
  }, [query])

  useEffect(() => {
    let disposed = false
    const pollBalance = async () => {
      const providerResults = await getCcSwitchProviderBalances()
      if (providerResults && providerResults.length > 0) {
        const current = providerResults[0]
        const previous = previousBalanceRef.current
        const currentRemaining = current.remaining
        const authoritative = current.success && currentRemaining !== undefined && Number.isFinite(currentRemaining)
        const becameEmpty = authoritative && currentRemaining! <= 0 && (previous === undefined || previous > 0)
        currentProviderBalanceRef.current = current
        if (authoritative) previousBalanceRef.current = currentRemaining
        setProviderBalances(providerResults.map((item) => ({
          name: item.name,
          model: item.model || 'Codex',
          balance: item.remaining === undefined ? (item.error || 'unavailable') : `${item.remaining} ${item.unit || 'USD'}`,
          balanceValue: item.remaining ?? 0,
          currency: item.unit || 'USD',
          latency: 'native',
          status: item.success && (item.remaining === undefined || item.remaining > 5) ? 'healthy' : item.success ? 'warning' : 'offline',
          updated: 'just now',
        })))
        const shouldPause = authoritative && currentRemaining! <= 0
        setSessionItems((currentItems) => currentItems.map((item) => {
          if (shouldPause && currentProviderMatchesSession(item)) {
            return { ...item, recovery: 'paused-balance' as const, lastError: 'CC Switch 当前 Codex 供应商余额为零' }
          }
          return clearRecoveredBalancePause(item)
        }))
        if (becameEmpty) {
          balanceResumeSentRef.current.clear()
          notifyDesktop('Codex Atlas recovery guard', t('providerBalanceLow'))
        }
        let resumedCount = 0
        if (authoritative && currentRemaining! > 0 && autoResumeOnBalanceRef.current) {
          const toResume = sessionItemsRef.current.filter((item) => currentProviderMatchesSession(item) && item.recovery === 'paused-balance')
          for (const item of toResume) {
            const marker = `${item.id}:${item.failureKey || item.lastError || 'balance-recovered'}`
            if (balanceResumeSentRef.current.has(marker)) continue
            balanceResumeSentRef.current.add(marker)
            resumedCount += 1
            void inputCodexContinue(item.id).then((ok) => {
              if (!ok) {
                showToast(language === 'zh' ? `余额已恢复，但无法继续“${item.title}”` : `Balance recovered, but could not continue “${item.title}”`)
              }
            })
          }
        }
        if (resumedCount > 0) {
          showToast(language === 'zh' ? `余额已恢复，正在继续 ${resumedCount} 个会话` : `Balance recovered; continuing ${resumedCount} session(s)`)
        }
        setCcSwitchCheckedAt('just now')
        return
      }
      if (providerResults) {
        currentProviderBalanceRef.current = null
        setProviderBalances([])
      }
      setCcSwitchCheckedAt('just now')
    }
    void pollBalance()
    const timer = window.setInterval(() => void pollBalance(), 30_000)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [])

  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null
    let unlistenOutput: (() => void) | null = null
    let unlistenRuntime: (() => void) | null = null
    let unlistenAppServer: (() => void) | null = null
    void listenDesktopEvent<RunningCodexSession[]>('codex_runtime', (records) => {
      if (disposed || !Array.isArray(records)) return
      runtimeSyncRef.current?.(records)
    }).then((cleanup) => { unlistenRuntime = cleanup })
    void listenDesktopEvent<DesktopFailureEvent>('codex_failure', (event) => {
      if (disposed) return
      if (event.action === 'pause-balance' && !hasKnownEmptyCurrentBalance()) {
        const marker = `${event.sessionId}:${event.error}`
        const shouldResume = hasPositiveCurrentBalance() && autoResumeOnBalanceRef.current && !balanceResumeSentRef.current.has(marker)
        if (shouldResume) {
          balanceResumeSentRef.current.add(marker)
          void inputCodexContinue(event.sessionId)
        }
        setSessionItems((current) => current.map((item) => item.id === event.sessionId
          ? { ...item, recovery: shouldResume ? 'retrying' : 'watching', retryCount: event.attempt, lastError: event.error }
          : item))
        return
      }
      setSessionItems((current) => current.map((item) => {
        if (item.id !== event.sessionId) return item
        const recovery: RecoveryState = event.action === 'pause-balance' ? 'paused-balance' : event.action === 'stop' ? 'stopped' : event.action === 'continue' ? 'retrying' : 'watching'
        return { ...item, recovery, retryCount: event.attempt, lastError: event.error }
      }))
      const message = event.action === 'pause-balance'
        ? t('providerBalanceLow')
        : event.action === 'stop'
          ? interpolate(t('retriesStopped'), { count: event.attempt || event.maxAttempts })
          : event.action === 'continue'
            ? interpolate(t('continueSent'), { attempt: event.attempt, max: event.maxAttempts })
            : t('waitingManual')
      if (event.action === 'stop') notifyDesktop('Codex Atlas recovery guard', message)
      showToast(message)
    }).then((cleanup) => { unlisten = cleanup })
    void listenDesktopEvent<DesktopOutputEvent>('codex_output', (event) => {
      if (disposed || !event.sessionId) return
      const output = event.lastOutput || event.line
      if (!output) return
      setSessionItems((current) => current.map((item) => item.id === event.sessionId
        ? { ...item, lastOutput: output, status: item.status === 'done' ? 'active' : item.status, liveState: item.liveState === 'idle' ? 'working' : item.liveState }
        : item))
    }).then((cleanup) => { unlistenOutput = cleanup })
    void listenDesktopEvent<CodexAppServerEvent>('codex_app_server', (event) => {
      if (disposed) return
      const sessionId = event.params?.threadId
      if (!sessionId) return
      setSessionItems((current) => current.map((item) => item.id === sessionId
        ? applyAppServerSessionPatch(item, event, streamedRepliesRef.current)
        : item))
    }).then((cleanup) => { unlistenAppServer = cleanup })
    return () => {
      disposed = true
      unlisten?.()
      unlistenOutput?.()
      unlistenRuntime?.()
      unlistenAppServer?.()
    }
  }, [])

  const filteredSessions = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return sessionItems
      .filter((session) => {
        const haystack = [session.title, session.preview, session.searchText, session.branch, session.folder, ...session.tags].join(' ').toLowerCase()
        const matchesQuery = !normalized || haystack.includes(normalized)
        const matchesFilter = filter === 'all' || session.status === filter
        return matchesQuery && matchesFilter
      })
      .sort((a, b) => sortNewest ? b.timestamp - a.timestamp : a.timestamp - b.timestamp)
  }, [query, filter, sortNewest, sessionItems])

  const recentSessions = filteredSessions.slice(0, 5)
  const runningSessionCount = sessionItems.filter((item) => (item.processIds?.length || 0) > 0).length

  const activateSession = (session: Session) => {
    const recoveryNotice = session.recovery === 'paused-balance'
      ? (language === 'zh' ? '余额不足，自动继续已暂停；正在激活会话' : 'Balance is low; automatic continue is paused. Activating the session')
      : session.recovery === 'stopped'
        ? (language === 'zh' ? '自动继续已停止；正在激活会话' : 'Automatic continue is stopped. Activating the session')
        : ''
    void resumeCodexSession(session.id).then((launched) => {
      const result = launched
        ? ((session.processIds?.length || 0) > 0
          ? (language === 'zh' ? `已激活“${session.title}”` : `Activated “${session.title}”`)
          : interpolate(t('resumeOpened'), { title: session.title }))
        : `${t('resumeUnavailable')}: ${session.title}`
      showToast(recoveryNotice ? `${recoveryNotice} · ${launched ? (language === 'zh' ? '已启动' : 'started') : (language === 'zh' ? '等待桌面壳' : 'waiting for desktop shell')}` : result)
    })
    setSelected(session)
  }

  const inputContinue = (session: Session) => {
    // The native side can match the active Windows Terminal by workspace
    // title even while its process-tree poll is catching up. Do not reject a
    // manual continue solely because the cached process list is briefly empty.
    void inputCodexContinue(session.id).then((continued) => {
      showToast(continued
        ? (language === 'zh' ? `已向“${session.title}”输入继续` : `Continue sent to “${session.title}”`)
        : (language === 'zh' ? '无法定位对应的运行中终端' : 'The running terminal could not be located'))
    })
  }

  const openSessionWorkspace = (session: Session) => {
    if (!session.cwd) {
      showToast(language === 'zh' ? '该会话没有可用的工作目录' : 'This session has no readable workspace path')
      return
    }
    void openWorkspacePath(session.cwd).then((opened) => {
      if (!opened) showToast(language === 'zh' ? '无法打开工作区目录' : 'Unable to open the workspace directory')
    })
  }

  const createSession = async (request: NewCodexSessionRequest) => {
    const created = await createCodexSession(request)
    if (created) {
      setNewSessionOpen(false)
      showToast(t('sessionCreated'))
      window.setTimeout(() => { void scanSessions(false) }, 1200)
    } else {
      showToast(t('sessionCreateFailed'))
    }
  }

  const openExternalTool = (tool: 'paseo' | 'ccswitch') => {
    if (tool === 'paseo') {
      void launchPaseo('paseo').then((launched) => showToast(launched ? t('paseoStarted') : t('paseoUnavailable')))
      return
    }
    showToast(t('connectingCc'))
  }

  return (
    <div className="app-shell">
      <header className="topbar" data-tauri-drag-region onMouseDown={beginWindowDrag}>
        <DesktopWindowControls language={language} onMessage={showToast} />
        <div className="brand-lockup">
          <div className="brand-mark"><img src="/codex-atlas-icon.svg" alt="" /></div>
          <div>
            <div className="brand-name">CODEX ATLAS <span className="brand-version">v{ATLAS_VERSION}</span></div>
          </div>
        </div>
        <div className="topbar-center"><span className="pulse-dot" /> {runningSessionCount > 0 ? `${runningSessionCount} ${t('runningSessions')}` : t('localIndexSynced')}</div>
        <div className="topbar-actions">
          <button className="language-toggle" aria-label={t('switchLanguage')} title={t('switchLanguage')} onClick={() => setLanguage((value) => value === 'zh' ? 'en' : 'zh')}>{language === 'zh' ? '中文' : 'EN'}</button>
          <button className={`status-pill ${notifyEnabled ? 'on' : ''}`} onClick={() => { setNotifyEnabled(!notifyEnabled); showToast(notifyEnabled ? t('desktopNotificationsOff') : t('desktopNotificationsOn')) }}><Bell size={14} /> {notifyEnabled ? t('alertsOn') : t('alertsOff')}</button>
          <button className="icon-button" aria-label={t('refreshIndex')} title={t('refreshIndex')} onClick={() => void scanSessions(true)}><RefreshCw size={16} /></button>
          <button className="icon-button" aria-label={t('openSettings')} title={t('openSettings')} onClick={() => setActiveNav('runtime')}><Settings2 size={16} /></button>
        </div>
      </header>

      <div className="workspace">
        <div className="content-pane">
          <nav className="floating-main-nav" aria-label={t('navigate')}>
            {navItems.map(({ id, icon: Icon }) => (
              <button
                key={id}
                className={`nav-item ${activeNav === id ? 'active' : ''}`}
                aria-current={activeNav === id ? 'page' : undefined}
                aria-label={tr(language, id)}
                title={tr(language, id)}
                onClick={() => setActiveNav(id)}
              >
                <Icon size={15} />
                <span>{tr(language, id)}</span>
              </button>
            ))}
          </nav>

          <main className="main-content" ref={mainContentRef}>
          {activeNav === 'overview' && <OverviewView
            language={language}
            query={query}
            setQuery={setQuery}
            filter={filter}
            setFilter={setFilter}
            sortNewest={sortNewest}
            setSortNewest={setSortNewest}
            recentSessions={recentSessions}
            selected={selected}
            setSelected={setSelected}
            activateSession={activateSession}
            inputContinue={inputContinue}
            showToast={showToast}
            onViewAll={() => setActiveNav('sessions')}
            onScan={() => void scanSessions(true)}
            loadState={sessionLoadState}
            onCreate={() => setNewSessionOpen(true)}
          />}
          {activeNav === 'sessions' && <SessionsView language={language} sessions={filteredSessions} query={query} setQuery={setQuery} selected={selected} setSelected={setSelected} activateSession={activateSession} inputContinue={inputContinue} openWorkspace={openSessionWorkspace} loadState={sessionLoadState} />}
          {activeNav === 'monitor' && <MonitorView language={language} sessions={sessionItems} autoContinue={autoContinue} setAutoContinue={updateAutoContinue} autoResumeOnBalance={autoResumeOnBalance} setAutoResumeOnBalance={setAutoResumeOnBalance} notifyEnabled={notifyEnabled} setNotifyEnabled={setNotifyEnabled} showToast={showToast} activateSession={activateSession} inputContinue={inputContinue} />}
          {activeNav === 'integrations' && <IntegrationsView language={language} providerBalances={providerBalances} sessionTotal={sessionItems.length} ccSwitchCheckedAt={ccSwitchCheckedAt} setCcSwitchCheckedAt={setCcSwitchCheckedAt} paseoImportedCount={paseoImportedCount} setPaseoImportedCount={setPaseoImportedCount} showToast={showToast} openExternalTool={openExternalTool} />}
          {activeNav === 'skills' && <SkillsView language={language} skills={installedSkills} setSkills={setInstalledSkills} loadState={skillsLoadState} setLoadState={setSkillsLoadState} showToast={showToast} />}
          {activeNav === 'floating' && <FloatingView language={language} sessions={sessionItems} providerBalances={providerBalances} floatingEnabled={floatingEnabled} onToggleFloating={() => void toggleFloatingWindow()} floatingLaunchOnStart={floatingLaunchOnStart} setFloatingLaunchOnStart={setFloatingLaunchOnStart} notifyEnabled={notifyEnabled} setNotifyEnabled={setNotifyEnabled} activateSession={activateSession} inputContinue={inputContinue} showToast={showToast} />}
          {activeNav === 'runtime' && <RuntimeView language={language} codexVersion={codexVersion} setCodexVersion={setCodexVersion} codexProvider={codexProvider} defaultModel={defaultModel} setDefaultModel={setDefaultModel} setDefaultPermission={setDefaultPermission} defaultPermission={defaultPermission} defaultReasoningEffort={defaultReasoningEffort} setDefaultReasoningEffort={setDefaultReasoningEffort} autoScan={autoScan} setAutoScan={setAutoScan} hookStatus={hookStatus} setHookStatus={setHookStatus} mobileBridge={mobileBridge} setMobileBridge={setMobileBridge} serverTunnel={serverTunnel} setServerTunnel={setServerTunnel} showToast={showToast} />}
          </main>
        </div>

        {selected && !['skills', 'runtime', 'monitor', 'integrations', 'floating'].includes(activeNav) && <SessionInspector language={language} session={selected} onClose={() => setSelected(null)} onActivate={() => activateSession(selected)} onInputContinue={() => inputContinue(selected)} onOpenWorkspace={() => openSessionWorkspace(selected)} />}
      </div>
      {toast && <div className="toast"><Check size={15} />{toast}</div>}
      {newSessionOpen && <NewSessionDialog language={language} defaultModel={defaultModel} defaultPermission={defaultPermission} onClose={() => setNewSessionOpen(false)} onCreate={createSession} />}
    </div>
  )
}

type OverviewProps = {
  language: 'zh' | 'en'
  query: string
  setQuery: (value: string) => void
  filter: 'all' | Session['status']
  setFilter: (value: 'all' | Session['status']) => void
  sortNewest: boolean
  setSortNewest: (value: boolean) => void
  recentSessions: Session[]
  selected: Session | null
  setSelected: (session: Session) => void
  activateSession: (session: Session) => void
  inputContinue: (session: Session) => void
  showToast: (message: string) => void
  onViewAll: () => void
  onScan: () => void
  onCreate: () => void
  loadState: 'loading' | 'ready' | 'error'
}

function ContentState({ language, state, emptyLabel, onRetry }: { language: UiLanguage; state: 'loading' | 'ready' | 'error'; emptyLabel: string; onRetry?: () => void }) {
  if (state === 'loading') {
    return <div className="content-state loading" role="status"><span className="content-state-icon"><LoaderCircle size={18} /></span><strong>{language === 'zh' ? '正在读取…' : 'Loading…'}</strong></div>
  }
  if (state === 'error') {
    return <div className="content-state error" role="alert"><span className="content-state-icon"><CircleAlert size={18} /></span><strong>{language === 'zh' ? '暂时无法读取' : 'Could not load'}</strong>{onRetry && <button className="secondary-button" onClick={onRetry}><RefreshCw size={14} />{language === 'zh' ? '重试' : 'Retry'}</button>}</div>
  }
  return <div className="content-state empty"><span className="content-state-icon"><Search size={18} /></span><strong>{emptyLabel}</strong></div>
}

function NewSessionDialog({ language, defaultModel, defaultPermission, onClose, onCreate }: { language: UiLanguage; defaultModel: string; defaultPermission: string; onClose: () => void; onCreate: (request: NewCodexSessionRequest) => Promise<void> }) {
  const [draft, setDraft] = useState<NewCodexSessionRequest>({
    cwd: '',
    prompt: '',
    model: defaultModel === 'model not detected' ? '' : defaultModel,
    permission: defaultPermission,
  })
  const [busy, setBusy] = useState(false)
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!draft.cwd.trim() || busy) return
    setBusy(true)
    await onCreate({ ...draft, cwd: draft.cwd.trim(), prompt: draft.prompt.trim(), model: draft.model.trim() })
    setBusy(false)
  }
  return <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose() }}>
    <form className="confirm-dialog new-session-dialog" onSubmit={(event) => void submit(event)} role="dialog" aria-modal="true" aria-labelledby="new-session-title">
      <div className="dialog-icon"><Plus size={19} /></div>
      <h2 id="new-session-title">{tr(language, 'newSession')}</h2>
      <p>{language === 'zh' ? '在指定工作目录打开一个真正的 Codex CLI 交互会话。' : 'Open a real interactive Codex CLI session in the selected workspace.'}</p>
      <div className="dialog-form">
        <label className="field-label">{tr(language, 'workingDirectory')}<input className="text-input" value={draft.cwd} onChange={(event) => setDraft({ ...draft, cwd: event.target.value })} placeholder={tr(language, 'directoryHint')} autoFocus spellCheck={false} /></label>
        <label className="field-label">{tr(language, 'initialPrompt')}<textarea className="text-input dialog-textarea" value={draft.prompt} onChange={(event) => setDraft({ ...draft, prompt: event.target.value })} placeholder={tr(language, 'promptHint')} rows={4} /></label>
        <div className="dialog-form-row"><label className="field-label">{tr(language, 'model')}<input className="text-input" value={draft.model} onChange={(event) => setDraft({ ...draft, model: event.target.value })} placeholder="Codex default" spellCheck={false} /></label><label className="field-label">{tr(language, 'permission')}<select value={draft.permission} onChange={(event) => setDraft({ ...draft, permission: event.target.value })}><option value="Workspace write">{language === 'zh' ? '工作区写入' : 'Workspace write'}</option><option value="Read only">{language === 'zh' ? '只读' : 'Read only'}</option><option value="Full access">{language === 'zh' ? '完全访问' : 'Full access'}</option></select></label></div>
      </div>
      <div className="dialog-actions"><button type="button" className="secondary-button" onClick={onClose} disabled={busy}>{language === 'zh' ? '取消' : 'Cancel'}</button><button type="submit" className="primary-button" disabled={busy || !draft.cwd.trim()}>{busy ? <LoaderCircle className="spin" size={15} /> : <Play size={15} fill="currentColor" />}{busy ? tr(language, 'creatingSession') : tr(language, 'createSession')}</button></div>
    </form>
  </div>
}

function OverviewView({ language, query, setQuery, filter, setFilter, sortNewest, setSortNewest, recentSessions, selected, setSelected, activateSession, inputContinue, onViewAll, onScan, onCreate, loadState }: OverviewProps) {
  return <>
    <section className="page-heading">
      <div>
        <div className="eyebrow accent-text">{language === 'zh' ? '本地会话索引' : 'LOCAL SESSION INDEX'} <span className="heading-line" /></div>
        <h1>{language === 'zh' ? '最近会话' : 'Recent sessions'}</h1>
      </div>
      <div className="page-actions"><button className="secondary-button new-session-button" onClick={onCreate}><Plus size={16} /> {tr(language, 'newSession')}</button><button className="primary-button" onClick={onScan}><RefreshCw size={16} /> {language === 'zh' ? '扫描会话' : 'Scan sessions'} <span className="button-key">⌘ ↵</span></button></div>
    </section>

    <section className="section-block">
      <div className="section-head">
        <div>
          <div className="eyebrow">{language === 'zh' ? '会话流' : 'SESSION STREAM'}</div>
          <h2>{language === 'zh' ? '可继续的会话' : 'Ready to resume'} <span className="muted-count">/ {recentSessions.length} {language === 'zh' ? '个' : 'shown'}</span></h2>
        </div>
        <div className="section-actions">
          <div className="search-box"><Search size={15} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={language === 'zh' ? '搜索会话、分支或内容…' : 'Search sessions, branches, content…'} /><span className="search-key">/</span></div>
          <div className="segmented-control">
            {(['all', 'active', 'done'] as const).map((value) => <button key={value} className={filter === value ? 'selected' : ''} onClick={() => setFilter(value)}>{value === 'all' ? (language === 'zh' ? '全部' : 'All') : value === 'active' ? (language === 'zh' ? '运行中' : 'Active') : (language === 'zh' ? '已完成' : 'Done')}</button>)}
          </div>
          <button className="icon-button" aria-label={language === 'zh' ? '切换排序' : 'Toggle sort'} title={language === 'zh' ? '切换排序' : 'Toggle sort'} onClick={() => setSortNewest(!sortNewest)}><ListFilter size={16} /></button>
        </div>
      </div>
      <div className="session-table">
        <div className="table-head"><span>{language === 'zh' ? '会话' : 'SESSION'}</span><span>{language === 'zh' ? '工作区 / 分支' : 'WORKSPACE / BRANCH'}</span><span>{language === 'zh' ? '模型' : 'MODEL'}</span><span>{language === 'zh' ? '更新时间' : 'UPDATED'}</span><span /></div>
        {recentSessions.map((session, index) => <SessionRow key={session.id} language={language} session={session} index={index} selected={selected?.id === session.id} onSelect={() => setSelected(session)} onActivate={() => activateSession(session)} onInputContinue={() => inputContinue(session)} />)}
        {recentSessions.length === 0 && <ContentState language={language} state={loadState} emptyLabel={query.trim() ? tr(language, 'noSearchResults') : tr(language, 'noReadableSessions')} onRetry={onScan} />}
      </div>
      <button className="text-button" onClick={onViewAll}><span>{language === 'zh' ? '查看全部会话' : 'View all sessions'}</span><ArrowUpRight size={15} /></button>
    </section>

  </>
}

function Metric({ label, value, delta, icon, tone }: { label: string; value: string; delta: string; icon: React.ReactNode; tone: string }) {
  return <div className="metric-card"><div className={`metric-icon ${tone}`}>{icon}</div><div className="metric-label">{label}</div><div className="metric-value">{value}</div><div className="metric-delta">{delta}</div></div>
}

function SessionRow({ language, session, index, selected, onSelect, onActivate, onInputContinue, onOpenWorkspace }: { language: UiLanguage; session: Session; index: number; selected: boolean; onSelect: () => void; onActivate: () => void; onInputContinue: () => void; onOpenWorkspace?: () => void }) {
  return <div className={`session-row ${selected ? 'selected' : ''}`} style={{ animationDelay: `${index * 45}ms` }} onClick={onSelect}>
    <div className="session-main"><span className={`status-dot ${session.status}`} /><div><strong>{localizeSessionValue(session.title, language)}</strong><small>{localizeSessionValue(session.preview, language)}</small><div className="tag-line">{session.tags.map((tag) => <span key={tag}>#{tag}</span>)}</div></div></div>
    <div className="workspace-cell"><strong>{session.folder}</strong><small><GitBranch size={12} /> {session.branch}</small></div>
    <div className="model-cell"><span className="model-chip">{localizeSessionValue(session.model, language)}</span><small>{formatPermission(session.permission, language)}</small></div>
    <div className="updated-cell"><Clock3 size={13} />{formatSessionUpdated(session, language)}</div>
    <div className="row-actions session-actions">{onOpenWorkspace && <button className="icon-button session-workspace-button" aria-label={language === 'zh' ? '打开工作区' : 'Open workspace'} title={language === 'zh' ? '打开工作区' : 'Open workspace'} disabled={!session.cwd} onClick={(event) => { event.stopPropagation(); onOpenWorkspace() }}><FolderOpen size={15} /></button>}<button className="resume-button" onClick={(event) => { event.stopPropagation(); onActivate() }}><Play size={13} fill="currentColor" /> {tr(language, 'activateSession')}</button><button className="resume-button input-continue-button" onClick={(event) => { event.stopPropagation(); onInputContinue() }}><TerminalSquare size={13} /> {tr(language, 'inputContinue')}</button></div>
  </div>
}

function SessionInspector({ language, session, onClose, onActivate, onInputContinue, onOpenWorkspace }: { language: UiLanguage; session: Session; onClose: () => void; onActivate: () => void; onInputContinue: () => void; onOpenWorkspace?: () => void }) {
  return <aside className="inspector">
    <div className="inspector-head"><span className="eyebrow">{tr(language, 'session')}</span><button className="icon-button small" aria-label={tr(language, 'closeDetail')} title={tr(language, 'closeDetail')} onClick={onClose}><X size={16} /></button></div>
    <div className="inspector-title"><span className={`status-dot ${session.status}`} /><h2>{localizeSessionValue(session.title, language)}</h2></div>
    <p className="inspector-preview">{localizeSessionValue(session.preview, language)}</p>
    <div className="inspector-actions"><button className="inspector-resume" onClick={onActivate}><Play size={15} fill="currentColor" /> {tr(language, 'activateSession')}</button><button className="inspector-resume input-continue-button" onClick={onInputContinue}><TerminalSquare size={15} /> {tr(language, 'inputContinue')}</button>{onOpenWorkspace && <button className="inspector-resume workspace-open-action" onClick={onOpenWorkspace} disabled={!session.cwd}><FolderOpen size={15} /> {language === 'zh' ? '打开工作区' : 'Open workspace'}</button>}</div>
    <div className="detail-block"><div className="eyebrow">{tr(language, 'workspace')}</div><div className="detail-row"><FolderOpen size={14} /><span>{tr(language, 'folder')}</span><strong>{session.folder}</strong></div><div className="detail-row workspace-path-row" title={session.cwd || undefined}><FolderOpen size={14} /><span>{language === 'zh' ? '完整路径' : 'Full path'}</span><strong>{session.cwd || (language === 'zh' ? '未读取' : 'Unavailable')}</strong></div><div className="detail-row"><GitBranch size={14} /><span>{tr(language, 'branch')}</span><strong>{session.branch}</strong></div></div>
    <div className="detail-block"><div className="eyebrow">{tr(language, 'runtime')}</div><div className="detail-row"><Cpu size={14} /><span>{tr(language, 'model')}</span><strong>{localizeSessionValue(session.model, language)}</strong></div><div className="detail-row"><ShieldCheck size={14} /><span>{tr(language, 'permission')}</span><strong>{formatPermission(session.permission, language)}</strong></div><div className="detail-row"><Clock3 size={14} /><span>{tr(language, 'updated')}</span><strong>{formatSessionUpdated(session, language)}</strong></div></div>
  </aside>
}

function SessionsView({ language, sessions: items, query, setQuery, selected, setSelected, activateSession, inputContinue, openWorkspace, loadState }: { language: UiLanguage; sessions: Session[]; query: string; setQuery: (value: string) => void; selected: Session | null; setSelected: (value: Session) => void; activateSession: (value: Session) => void; inputContinue: (value: Session) => void; openWorkspace: (value: Session) => void; loadState: 'loading' | 'ready' | 'error' }) {
  return <><section className="page-heading compact"><div><div className="eyebrow accent-text">{tr(language, 'sessionArchive')} <span className="heading-line" /></div><h1>{tr(language, 'allSessions')}</h1></div></section><div className="archive-toolbar"><div className="search-box wide"><Search size={15} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={tr(language, 'searchArchive')} /></div><div className="archive-stat"><span className="pulse-dot" /> {items.length} {tr(language, 'matchingRecords')}</div></div><div className="archive-list">{items.map((session, index) => <SessionRow key={session.id} language={language} session={session} index={index} selected={selected?.id === session.id} onSelect={() => setSelected(session)} onActivate={() => activateSession(session)} onInputContinue={() => inputContinue(session)} onOpenWorkspace={() => openWorkspace(session)} />)}{items.length === 0 && <ContentState language={language} state={loadState} emptyLabel={query.trim() ? tr(language, 'noSearchResults') : tr(language, 'noReadableSessions')} />}</div></>
}

function SkillsView({ language, skills, setSkills, loadState, setLoadState, showToast }: { language: UiLanguage; skills: SkillRecord[]; setSkills: React.Dispatch<React.SetStateAction<SkillRecord[]>>; loadState: 'loading' | 'ready' | 'error'; setLoadState: React.Dispatch<React.SetStateAction<'loading' | 'ready' | 'error'>>; showToast: (message: string) => void }) {
  const [query, setQuery] = useState('')
  const [selectedPaths, setSelectedPaths] = useState<string[]>([])
  const [detail, setDetail] = useState<SkillDetail | null>(null)
  const [detailTarget, setDetailTarget] = useState<SkillRecord | null>(null)
  const [detailError, setDetailError] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [busy, setBusy] = useState<'refresh' | 'check' | 'update' | 'enable' | 'disable' | 'delete' | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const normalized = query.trim().toLowerCase()
  const visibleSkills = useMemo(() => skills.filter((skill) => !normalized || [skill.name, skill.description, skill.source, skill.repository || ''].join(' ').toLowerCase().includes(normalized)), [skills, normalized])
  const selectable = visibleSkills.filter((skill) => !skill.protected)
  const selected = skills.filter((skill) => selectedPaths.includes(skill.path) && !skill.protected)
  const allSelected = selectable.length > 0 && selectable.every((skill) => selectedPaths.includes(skill.path))

  const refresh = async (detectUpdates = false) => {
    setBusy(detectUpdates ? 'check' : 'refresh')
    if (skills.length === 0) setLoadState('loading')
    const paths = skills.filter((skill) => !skill.protected).map((skill) => skill.path)
    const next = detectUpdates ? await checkSkillUpdates(paths) : await listInstalledSkills()
    if (next) {
      setSkills(next)
      setLoadState('ready')
      showToast(detectUpdates
        ? (language === 'zh' ? `检测完成 · ${next.filter((skill) => skill.updateAvailable).length} 个可更新` : `Checked · ${next.filter((skill) => skill.updateAvailable).length} updates`)
        : (language === 'zh' ? '技能列表已刷新' : 'Skills refreshed'))
    } else {
      setLoadState('error')
    }
    setBusy(null)
  }

  const reloadAfterAction = async () => {
    const next = await listInstalledSkills()
    if (next) setSkills(next)
    setSelectedPaths([])
    setDetail(null)
    setDetailTarget(null)
    setDetailError('')
  }

  const summarize = (results: Awaited<ReturnType<typeof updateSkills>>, action: string) => {
    if (!results) {
      showToast(language === 'zh' ? `${action}失败` : `${action} failed`)
      return
    }
    const succeeded = results.filter((result) => result.success).length
    const failed = results.length - succeeded
    showToast(language === 'zh' ? `${action}：${succeeded} 成功${failed ? `，${failed} 失败` : ''}` : `${action}: ${succeeded} succeeded${failed ? `, ${failed} failed` : ''}`)
  }

  const runEnabled = async (enabled: boolean, paths = selected.map((skill) => skill.path)) => {
    if (paths.length === 0) return
    setBusy(enabled ? 'enable' : 'disable')
    const results = await setSkillsEnabled(paths, enabled)
    summarize(results, enabled ? (language === 'zh' ? '启用' : 'Enable') : (language === 'zh' ? '停用' : 'Disable'))
    await reloadAfterAction()
    setBusy(null)
  }

  const runUpdate = async (requestedPaths?: string[]) => {
    const paths = requestedPaths || selected.filter((skill) => skill.managed).map((skill) => skill.path)
    if (paths.length === 0) return
    setBusy('update')
    const results = await updateSkills(paths)
    summarize(results, language === 'zh' ? '更新' : 'Update')
    await reloadAfterAction()
    setBusy(null)
  }

  const runDelete = async () => {
    const paths = selected.map((skill) => skill.path)
    if (paths.length === 0) return
    setConfirmDelete(false)
    setBusy('delete')
    const results = await deleteSkills(paths)
    summarize(results, language === 'zh' ? '删除' : 'Delete')
    await reloadAfterAction()
    setBusy(null)
  }

  const openDetail = async (skill: SkillRecord) => {
    setDetailTarget(skill)
    setDetail(null)
    setDetailError('')
    setDetailLoading(true)
    const result = await getSkillDetail(skill.path)
    setDetail(result.detail)
    setDetailError(result.error || (!result.detail ? (language === 'zh' ? '未能读取技能详情' : 'Could not load skill details') : ''))
    setDetailLoading(false)
  }

  const closeDetail = () => {
    setDetail(null)
    setDetailTarget(null)
    setDetailError('')
  }

  const toggleSelection = (path: string) => setSelectedPaths((current) => current.includes(path) ? current.filter((item) => item !== path) : [...current, path])
  const toggleAll = () => setSelectedPaths((current) => allSelected ? current.filter((path) => !selectable.some((skill) => skill.path === path)) : Array.from(new Set([...current, ...selectable.map((skill) => skill.path)])))
  const busyLabel = language === 'zh' ? '处理中…' : 'Working…'
  const updateStatus = (skill: SkillRecord) => skill.updateAvailable
    ? (language === 'zh' ? '可更新' : 'Update')
    : skill.updateStatus === 'current'
      ? (language === 'zh' ? '已是最新' : 'Current')
      : skill.protected
        ? (language === 'zh' ? '随 Codex 更新' : 'With Codex')
        : (language === 'zh' ? '本地' : 'Local')

  return <>
    <section className="page-heading compact"><div><div className="eyebrow accent-text">{tr(language, 'extensions')} <span className="heading-line" /></div><h1>{tr(language, 'installedSkills')}</h1></div><div className="page-actions"><button className="secondary-button" onClick={() => void refresh(false)} disabled={busy !== null}>{busy === 'refresh' ? <LoaderCircle className="spin" size={16} /> : <RefreshCw size={16} />}{busy === 'refresh' ? busyLabel : (language === 'zh' ? '刷新' : 'Refresh')}</button><button className="primary-button" onClick={() => void refresh(true)} disabled={busy !== null || skills.length === 0}>{busy === 'check' ? <LoaderCircle className="spin" size={16} /> : <GitBranch size={16} />}{busy === 'check' ? busyLabel : (language === 'zh' ? '检测更新' : 'Check updates')}</button></div></section>
    <div className={`skills-workspace ${detail || detailLoading || detailError ? 'with-detail' : ''}`}>
      <section className="skills-manager">
        <div className="skills-toolbar">
          <div className="search-box wide"><Search size={16} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={language === 'zh' ? '搜索技能' : 'Search skills'} /></div>
          <span className="skills-count">{skills.filter((skill) => skill.enabled).length}/{skills.length}</span>
        </div>
        <div className="bulk-bar">
          <button className={`select-control ${allSelected ? 'selected' : ''}`} onClick={toggleAll} disabled={selectable.length === 0 || busy !== null} aria-label={language === 'zh' ? '全选' : 'Select all'}>{allSelected ? <Check size={15} /> : <Square size={15} />}</button>
          <span>{selected.length > 0 ? (language === 'zh' ? `已选 ${selected.length} 项` : `${selected.length} selected`) : (language === 'zh' ? '选择技能进行批量操作' : 'Select skills for bulk actions')}</span>
          <div className="bulk-actions">
            <button onClick={() => void runUpdate()} disabled={busy !== null || !selected.some((skill) => skill.managed)}>{busy === 'update' ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />}{language === 'zh' ? '更新' : 'Update'}</button>
            <button onClick={() => void runEnabled(true)} disabled={busy !== null || !selected.some((skill) => !skill.enabled)}><Power size={14} />{language === 'zh' ? '启用' : 'Enable'}</button>
            <button onClick={() => void runEnabled(false)} disabled={busy !== null || !selected.some((skill) => skill.enabled)}><Pause size={14} />{language === 'zh' ? '停用' : 'Disable'}</button>
            <button className="danger" onClick={() => setConfirmDelete(true)} disabled={busy !== null || selected.length === 0}><Trash2 size={14} />{language === 'zh' ? '删除' : 'Delete'}</button>
          </div>
        </div>
        <div className="skills-list">
          {visibleSkills.map((skill) => <div className={`skill-row ${detailTarget?.path === skill.path ? 'active' : ''} ${!skill.enabled ? 'disabled-skill' : ''}`} key={skill.path} onClick={() => void openDetail(skill)}>
            <button className={`select-control ${selectedPaths.includes(skill.path) ? 'selected' : ''}`} disabled={skill.protected || busy !== null} aria-label={language === 'zh' ? `选择 ${skill.name}` : `Select ${skill.name}`} onClick={(event) => { event.stopPropagation(); toggleSelection(skill.path) }}>{selectedPaths.includes(skill.path) ? <Check size={15} /> : <Square size={15} />}</button>
            <div className="skill-symbol"><Sparkles size={17} /></div>
            <button className="skill-copy" onClick={(event) => { event.stopPropagation(); void openDetail(skill) }}><strong>{skill.name}</strong><small>{language === 'zh' ? (skill.descriptionZh || `用于 Codex 的“${skill.name}”技能。`) : skill.description}</small></button>
            <span className={`skill-update-state ${skill.updateAvailable ? 'available' : ''}`}>{updateStatus(skill)}</span>
            <span className="skill-version">{skill.version === 'local' ? 'local' : `v${skill.version}`}</span>
            <button className={`toggle ${skill.enabled ? 'on' : ''}`} disabled={skill.protected || busy !== null} aria-label={`${tr(language, 'skillOptions')}: ${skill.name}`} onClick={(event) => { event.stopPropagation(); void runEnabled(!skill.enabled, [skill.path]) }}><span /></button>
            <button className="skill-detail-open" aria-label={language === 'zh' ? `查看 ${skill.name}` : `View ${skill.name}`} onClick={(event) => { event.stopPropagation(); void openDetail(skill) }}><ChevronRight size={16} /></button>
          </div>)}
          {visibleSkills.length === 0 && <ContentState language={language} state={loadState} emptyLabel={query ? (language === 'zh' ? '没有匹配的技能' : 'No matching skills') : (language === 'zh' ? '未安装技能' : 'No skills installed')} onRetry={() => void refresh(false)} />}
        </div>
      </section>
      {(detail || detailLoading || detailError) && <aside className="skill-detail">
        {detailLoading && <div className="detail-loading"><LoaderCircle className="spin" size={20} /><span>{language === 'zh' ? '正在读取详情…' : 'Loading details…'}</span></div>}
        {detailError && !detailLoading && <div className="detail-error" role="alert"><div className="skill-detail-head"><div className="skill-symbol error"><CircleAlert size={18} /></div><div><h2>{detailTarget?.name || (language === 'zh' ? '技能详情' : 'Skill details')}</h2><span>{language === 'zh' ? '读取失败' : 'Could not load'}</span></div><button className="icon-button" onClick={closeDetail} aria-label={language === 'zh' ? '关闭详情' : 'Close details'}><X size={17} /></button></div><p>{detailError}</p><button className="secondary-button" onClick={() => detailTarget && void openDetail(detailTarget)}><RefreshCw size={14} />{language === 'zh' ? '重试' : 'Retry'}</button></div>}
        {detail && !detailLoading && <>
          <div className="skill-detail-head"><div className="skill-symbol"><Sparkles size={18} /></div><div><h2>{detail.skill.name}</h2><span>{updateStatus(detail.skill)}</span></div><button className="icon-button" onClick={closeDetail} aria-label={language === 'zh' ? '关闭详情' : 'Close details'}><X size={17} /></button></div>
          <div className="skill-detail-description-block"><span className="detail-label">{language === 'zh' ? '中文摘要' : 'Summary'}</span><p className="skill-detail-description">{language === 'zh' ? (detail.skill.descriptionZh || `用于 Codex 的“${detail.skill.name}”技能。`) : detail.skill.description}</p><span className="detail-label">{language === 'zh' ? '原始描述' : 'Original description'}</span><p className="skill-detail-description original">{detail.skill.description}</p></div>
          <dl className="skill-meta"><div><dt>{language === 'zh' ? '状态' : 'Status'}</dt><dd>{detail.skill.enabled ? (language === 'zh' ? '已启用' : 'Enabled') : (language === 'zh' ? '已停用' : 'Disabled')}</dd></div><div><dt>{language === 'zh' ? '版本' : 'Version'}</dt><dd>{detail.skill.version}</dd></div><div><dt>{language === 'zh' ? '文件' : 'Files'}</dt><dd>{detail.files?.length ?? 0}</dd></div></dl>
          <div className="skill-repository-panel"><div><span className="detail-label">{language === 'zh' ? '技能仓库' : 'Repository'}</span><strong title={detail.skill.repository || undefined}>{detail.skill.repository || (language === 'zh' ? '未在技能元数据中找到' : 'No repository metadata found')}</strong></div><button className="secondary-button" onClick={() => { const url = detail.skill.repository || `https://github.com/search?q=${encodeURIComponent(detail.skill.name)}&type=repositories`; void openExternalUrl(url).then((opened) => { if (!opened) showToast(language === 'zh' ? '无法打开仓库搜索' : 'Could not open repository search') }) }}><ExternalLink size={14} />{detail.skill.repository ? (language === 'zh' ? '打开仓库' : 'Open repo') : (language === 'zh' ? '查找仓库' : 'Find repo')}</button></div>
          {detail.sections && detail.sections.length > 0 && <div className="skill-detail-section parsed-skill-section"><h3><ListTree size={15} />{language === 'zh' ? '技能说明' : 'Skill guide'}</h3>{detail.sections.slice(0, 12).map((section) => <article key={section.heading}><h4>{section.heading}</h4><p>{section.content || (language === 'zh' ? '此部分没有正文。' : 'No content in this section.')}</p></article>)}</div>}
          <div className="skill-detail-section"><details className="skill-source-details"><summary><FileText size={15} />{language === 'zh' ? '查看 SKILL.md 原文' : 'View raw SKILL.md'}</summary><pre>{detail.content}</pre></details></div>
          <div className="skill-detail-actions"><button className="secondary-button" onClick={() => void runEnabled(!detail.skill.enabled, [detail.skill.path])} disabled={detail.skill.protected || busy !== null}>{detail.skill.enabled ? <Pause size={14} /> : <Power size={14} />}{detail.skill.enabled ? (language === 'zh' ? '停用' : 'Disable') : (language === 'zh' ? '启用' : 'Enable')}</button><button className="primary-button" onClick={() => void runUpdate([detail.skill.path])} disabled={!detail.skill.managed || busy !== null}><RefreshCw size={14} />{language === 'zh' ? '更新' : 'Update'}</button></div>
        </>}
      </aside>}
    </div>
    {confirmDelete && <div className="dialog-backdrop" role="presentation" onMouseDown={() => setConfirmDelete(false)}><div className="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-skills-title" onMouseDown={(event) => event.stopPropagation()}><div className="dialog-icon"><Trash2 size={18} /></div><h2 id="delete-skills-title">{language === 'zh' ? `删除 ${selected.length} 个技能？` : `Delete ${selected.length} skills?`}</h2><p>{language === 'zh' ? '对应的本地技能目录会被永久删除。' : 'The corresponding local skill folders will be permanently removed.'}</p><div><button className="secondary-button" onClick={() => setConfirmDelete(false)}>{language === 'zh' ? '取消' : 'Cancel'}</button><button className="danger-button" onClick={() => void runDelete()}><Trash2 size={14} />{language === 'zh' ? '确认删除' : 'Delete'}</button></div></div></div>}
  </>
}

function MonitorView({ language, sessions: items, autoContinue, setAutoContinue, autoResumeOnBalance, setAutoResumeOnBalance, notifyEnabled, setNotifyEnabled, showToast, activateSession, inputContinue }: { language: UiLanguage; sessions: Session[]; autoContinue: boolean; setAutoContinue: (value: boolean) => void; autoResumeOnBalance: boolean; setAutoResumeOnBalance: (value: boolean) => void; notifyEnabled: boolean; setNotifyEnabled: (value: boolean) => void; showToast: (message: string) => void; activateSession: (session: Session) => void; inputContinue: (session: Session) => void }) {
  const watched = items.filter((item) => item.recovery !== 'healthy')
  const running = items.filter((item) => (item.processIds?.length || 0) > 0).sort((left, right) => Number(right.foreground) - Number(left.foreground) || (right.lastEventAtMs || 0) - (left.lastEventAtMs || 0))
  const latestEventAt = items.reduce((latest, item) => Math.max(latest, item.lastEventAtMs || 0), 0)
  const latestEvent = latestEventAt > 100_000_000_000 ? relativeUpdated(latestEventAt, language) : (language === 'zh' ? '暂无事件' : 'No events yet')
  const incidentCount = language === 'zh' ? `${watched.length} ${tr(language, 'sessionsNeedAttention')}` : `${watched.length} ${tr(language, 'sessionsNeedAttention')}`
  const incidentBadge = (item: Session) => item.recovery === 'paused-balance'
    ? tr(language, 'balance')
    : item.recovery === 'stopped'
      ? `${item.retryCount}/3 ${tr(language, 'failed')}`
      : `${item.retryCount}/3`
  return <>
    <section className="page-heading compact"><div><div className="eyebrow accent-text">{tr(language, 'errorRecovery')} <span className="heading-line" /></div><h1>{tr(language, 'recoveryMonitor')}</h1></div><div className="monitor-live"><span className="pulse-dot" /> {tr(language, 'liveWatcher')}</div></section>
    <div className="guardrail-banner"><div className="guardrail-icon"><ShieldCheck size={18} /></div><div><strong>{tr(language, 'guardrailsActive')}</strong><span>{tr(language, 'balanceBlocked')}</span></div><button className={`toggle ${autoContinue ? 'on' : ''}`} onClick={() => setAutoContinue(!autoContinue)} aria-label={language === 'zh' ? '切换自动继续' : 'Toggle automatic continue'}><span /></button></div>
    {running.length > 0 && <section className="settings-panel running-sessions-panel"><div className="panel-head"><div><div className="eyebrow">{tr(language, 'runningNow')}</div><h3>{running.length}</h3></div><span className="monitor-live"><span className="pulse-dot" /> {tr(language, 'liveWatcher')}</span></div><div className="running-session-list">{running.map((item) => <div className="running-session-row" key={item.id}><span className="status-dot active" /><div><strong>{item.title}</strong><small>{item.folder} · {item.liveState || 'idle'}{item.foreground ? (language === 'zh' ? ' · 当前窗口' : ' · foreground') : ''}</small></div><div className="compact-session-actions"><button className="text-button" onClick={() => activateSession(item)}><Play size={13} fill="currentColor" /> {tr(language, 'activateSession')}</button><button className="text-button" onClick={() => inputContinue(item)}><TerminalSquare size={13} /> {tr(language, 'inputContinue')}</button></div></div>)}</div></section>}
    <div className="monitor-grid">
      <div className="settings-panel monitor-rules"><div className="panel-head"><div><div className="eyebrow">{tr(language, 'decisionRules')}</div><h3>{tr(language, 'automaticContinuePolicy')}</h3></div><RotateCcw size={17} className="teal-icon" /></div>
        <div className="rule-row"><span className="rule-number">01</span><div><strong>{tr(language, 'balanceRule')}</strong><small>{tr(language, 'balanceRuleDetail')}</small></div><span className="rule-result stop">{tr(language, 'stop')}</span></div>
        <div className="rule-row"><span className="rule-number">02</span><div><strong>{tr(language, 'transientRule')}</strong><small>{tr(language, 'transientRuleDetail')}</small></div><span className="rule-result retry">{tr(language, 'retry')}</span></div>
        <div className="rule-row"><span className="rule-number">03</span><div><strong>{tr(language, 'threeFailuresRule')}</strong><small>{tr(language, 'threeFailuresDetail')}</small></div><span className="rule-result stop">{tr(language, 'stop')}</span></div>
        <div className="switch-row monitor-switch"><span><strong>{tr(language, 'autoResumeBalance')}</strong><small>{tr(language, 'autoResumeBalanceDescription')}</small></span><button className={`toggle ${autoResumeOnBalance ? 'on' : ''}`} onClick={() => setAutoResumeOnBalance(!autoResumeOnBalance)} aria-label={tr(language, 'autoResumeBalance')}><span /></button></div>
        <div className="switch-row monitor-switch"><span><strong>{tr(language, 'desktopNotifications')}</strong><small>{tr(language, 'notifyOnStop')}</small></span><button className={`toggle ${notifyEnabled ? 'on' : ''}`} onClick={() => setNotifyEnabled(!notifyEnabled)} aria-label={language === 'zh' ? '切换桌面通知' : 'Toggle desktop notifications'}><span /></button></div>
      </div>
      <div className="settings-panel incidents-panel"><div className="panel-head"><div><div className="eyebrow">{tr(language, 'liveIncidents')}</div><h3>{incidentCount}</h3></div><BellRing size={17} className="orange-icon" /></div><div className="incident-list">{watched.map((item) => <div className="incident-row" key={item.id}><span className={`incident-light ${item.recovery}`} /><div><strong>{item.title}</strong><small>{item.lastError || tr(language, 'watchingRecoverable')}</small></div><span className={`incident-badge ${item.recovery}`}>{incidentBadge(item)}</span><button className="icon-button tiny" aria-label={tr(language, 'inspectIncident')} title={tr(language, 'inspectIncident')} onClick={() => showToast(`${item.title} · ${item.lastError || tr(language, 'watchingRecoverable')}`)}><ArrowUpRight size={14} /></button><div className="compact-session-actions"><button className="text-button incident-action" onClick={() => activateSession(item)}><Play size={12} fill="currentColor" /> {tr(language, 'activateSession')}</button><button className="text-button incident-action" onClick={() => inputContinue(item)}><TerminalSquare size={12} /> {tr(language, 'inputContinue')}</button></div></div>)}{watched.length === 0 && <div className="incident-empty"><span className="health-light green" /><strong>{language === 'zh' ? '暂无异常' : 'No incidents'}</strong><small>{tr(language, 'watcherHealthy')}</small></div>}</div><div className="monitor-foot"><span>{tr(language, 'lastEvent')}</span><strong>{latestEvent}</strong><span className="monitor-foot-status"><span className="pulse-dot" /> {tr(language, 'watcherHealthy')}</span></div></div>
    </div>
  </>
}

function IntegrationsView({ language, providerBalances, sessionTotal, ccSwitchCheckedAt, setCcSwitchCheckedAt, paseoImportedCount, setPaseoImportedCount, showToast, openExternalTool }: { language: UiLanguage; providerBalances: Provider[]; sessionTotal: number; ccSwitchCheckedAt: string; setCcSwitchCheckedAt: (value: string) => void; paseoImportedCount: number; setPaseoImportedCount: (value: number) => void; showToast: (message: string) => void; openExternalTool: (tool: 'paseo' | 'ccswitch') => void }) {
  const checkCcSwitchBalance = async () => {
    const providerResults = await getCcSwitchProviderBalances()
    if (providerResults && providerResults.length > 0) {
      setCcSwitchCheckedAt('just now')
      const exhausted = providerResults.filter((item) => item.success && item.remaining !== undefined && item.remaining <= 0)
      const low = providerResults.filter((item) => item.success && item.remaining !== undefined && item.remaining > 0 && item.remaining <= 5)
      const healthy = providerResults.filter((item) => item.success && (item.remaining === undefined || item.remaining > 5)).length
      const unavailable = providerResults.filter((item) => !item.success)
      showToast(exhausted.length > 0
        ? (language === 'zh' ? `${exhausted.length} 个供应商余额为零，自动继续已暂停` : `${exhausted.length} provider balances are empty; automatic continue is paused`)
        : unavailable.length > 0
          ? (language === 'zh' ? `${unavailable.length} 个供应商余额接口不可用，未判定为余额不足` : `${unavailable.length} provider balance endpoints are unavailable; no low-balance pause applied`)
        : low.length > 0
          ? (language === 'zh' ? `${low.length} 个供应商余额偏低，但仍可用` : `${low.length} provider balances are low but still available`)
          : (language === 'zh' ? `余额正常 · ${healthy}/${providerResults.length} 个供应商可用` : `Balances healthy · ${healthy}/${providerResults.length} providers available`))
      return
    }
    const result = await getCcSwitchBalance({ baseUrl: 'http://127.0.0.1:15721', apiKey: '' })
    setCcSwitchCheckedAt(result ? 'just now' : 'preview mode · IPC unavailable')
    if (!result) { showToast(language === 'zh' ? '预览模式：桌面壳接入后将调用 CC Switch 余额接口' : 'Preview mode: CC Switch balance API is available after the desktop shell connects'); return }
    if (!result.success) { showToast(result.error || (language === 'zh' ? 'CC Switch 余额查询失败' : 'CC Switch balance check failed')); return }
    const amount = result.remaining ?? 0
    showToast(amount <= 0
      ? (language === 'zh' ? `余额为零 · ${amount} ${result.unit || 'USD'}，自动继续已暂停` : `Balance is empty · ${amount} ${result.unit || 'USD'}; automatic continue is paused`)
      : amount <= 5
        ? (language === 'zh' ? `余额偏低但仍可用 · ${amount} ${result.unit || 'USD'}` : `Balance is low but available · ${amount} ${result.unit || 'USD'}`)
        : (language === 'zh' ? `余额正常 · ${amount} ${result.unit || 'USD'}` : `Balance healthy · ${amount} ${result.unit || 'USD'}`))
  }
  const importAllToPaseo = async () => {
    const imported = await importAllPaseoSessions()
    if (!imported) {
      showToast(language === 'zh' ? '预览模式：桌面壳接入后将执行 Paseo 批量导入' : 'Preview mode: Paseo bulk import is available after the desktop shell connects')
      return
    }
    setPaseoImportedCount(imported.imported)
    showToast(imported.failed === 0 ? (language === 'zh' ? `已导入 Paseo · ${imported.imported}/${imported.total}` : `Imported to Paseo · ${imported.imported}/${imported.total}`) : (language === 'zh' ? `Paseo 导入完成 · ${imported.imported}/${imported.total}，${imported.failed} 个失败` : `Paseo import finished · ${imported.imported}/${imported.total}; ${imported.failed} failed`))
  }
  const checkedLabel = ccSwitchCheckedAt === 'not checked'
    ? tr(language, 'balanceRegistry')
    : `${tr(language, 'lastChecked')} ${language === 'zh' && ccSwitchCheckedAt === 'just now' ? '刚刚' : ccSwitchCheckedAt}`
  const connectedLabel = providerBalances.length
    ? `${providerBalances.length} ${tr(language, 'providersConnected')}`
    : tr(language, 'readyToCheck')
  return <>
    <section className="page-heading compact"><div><div className="eyebrow accent-text">{tr(language, 'connections')} <span className="heading-line" /></div><h1>{tr(language, 'localTools')}</h1></div><button className="primary-button" onClick={() => { void checkCcSwitchBalance(); showToast(language === 'zh' ? '连接状态已刷新' : 'Connection status refreshed') }}><RefreshCw size={16} /> {tr(language, 'refresh')}</button></section>
    <div className="integration-grid">
      <section className="settings-panel integration-card">
        <div className="integration-head"><div className="integration-logo cc">CC</div><div><h3>CC Switch</h3><span className="integration-status"><span className="pulse-dot" /> {tr(language, 'providerBalanceMonitor')}</span></div></div>
        <div className="connection-summary"><span className="health-light green" /><div><strong>{connectedLabel}</strong><small>{checkedLabel}</small></div><button className="secondary-button" onClick={() => void checkCcSwitchBalance()}><WalletCards size={14} /> {tr(language, 'check')}</button></div>
        <div className="provider-list">{providerBalances.map((provider) => <div className="provider-row" key={provider.name}><span className={`provider-status ${provider.status}`} /><div><strong>{provider.name}</strong><small>{provider.model} · {provider.latency}</small></div><div className="provider-balance"><strong>{provider.balance}</strong><small>{language === 'zh' && provider.updated === 'just now' ? '刚刚' : provider.updated}</small></div><button className="icon-button tiny" aria-label={tr(language, 'refreshProviderBalance')} title={tr(language, 'refreshProviderBalance')} onClick={() => void checkCcSwitchBalance()}><RefreshCw size={13} /></button></div>)}{providerBalances.length === 0 && <div className="provider-empty"><span className="provider-status warning" /><strong>{language === 'zh' ? '等待余额检查' : 'Waiting for balance check'}</strong><small>{tr(language, 'providerBalanceMonitor')}</small></div>}</div>
        {providerBalances.some((provider) => provider.status === 'warning' && provider.balanceValue <= 0) && <div className="balance-warning"><ShieldCheck size={15} /><span>{tr(language, 'insufficientBalance')}</span></div>}
      </section>
      <section className="settings-panel integration-card paseo-card">
        <div className="integration-head"><div className="integration-logo paseo">P</div><div><h3>Paseo</h3><span className="integration-status"><span className="pulse-dot" /> {tr(language, 'sessionCompanion')}</span></div></div>
        <div className="paseo-actions"><button className="primary-button" onClick={() => openExternalTool('paseo')}><Play size={15} fill="currentColor" /> {tr(language, 'launch')}</button><button className="secondary-button" onClick={() => void importAllToPaseo()}><Upload size={15} /> {tr(language, 'importAll')}</button></div>
        <div className="paseo-health"><div className="health-line"><span className="health-light green" /><span><strong>{tr(language, 'sessionBridge')}</strong><small>{tr(language, 'readySync')}</small></span><Check size={15} /></div><div className="health-line"><span className="health-light yellow" /><span><strong>{tr(language, 'recentRepair')}</strong><small>{paseoImportedCount < sessionTotal ? `${paseoImportedCount} / ${sessionTotal} ${tr(language, 'synced')}` : tr(language, 'allSynced')}</small></span><button className="text-button small-text" onClick={() => { setPaseoImportedCount(sessionTotal); showToast(language === 'zh' ? '最近会话索引已修复' : 'Recent session index repaired') }}><RotateCcw size={13} /> {tr(language, 'repair')}</button></div></div>
      </section>
    </div>
  </>
}

function FloatingView({ language, sessions: items, providerBalances, floatingEnabled, onToggleFloating, floatingLaunchOnStart, setFloatingLaunchOnStart, notifyEnabled, setNotifyEnabled, activateSession, inputContinue, showToast }: { language: UiLanguage; sessions: Session[]; providerBalances: Provider[]; floatingEnabled: boolean; onToggleFloating: () => void; floatingLaunchOnStart: boolean; setFloatingLaunchOnStart: (value: boolean) => void; notifyEnabled: boolean; setNotifyEnabled: (value: boolean) => void; activateSession: (session: Session) => void; inputContinue: (session: Session) => void; showToast: (message: string) => void }) {
  const [alwaysOnTop, setAlwaysOnTop] = useState(() => readStored('floatingAlwaysOnTop', true))
  const [showOutput, setShowOutput] = useState(() => readStored('floatingShowOutput', true))
  const [autoPickSession, setAutoPickSession] = useState(() => readStored('floatingAutoPickSession', true))
  const [rightClickMenu, setRightClickMenu] = useState(() => readStored('floatingRightClickMenu', true))
  const [floatingSkin, setFloatingSkin] = useState<FloatingSkin>(() => normalizeFloatingSkin(readStored('floatingSkin', 'classic')))
  const [floatingSize, setFloatingSize] = useState(() => {
    const stored = Number(readStored('floatingSize', 252))
    return Number.isFinite(stored) ? Math.max(180, Math.min(720, stored)) : 252
  })
  const [floatingFontScale, setFloatingFontScale] = useState(() => {
    const stored = Number(readStored('floatingFontScale', 100))
    return Number.isFinite(stored) ? Math.max(75, Math.min(160, stored)) : 100
  })
  const [floatingOpacity, setFloatingOpacity] = useState(() => readStored('floatingOpacity', 100))
  const active = items.filter((item) => item.status === 'active').length
  const waiting = items.filter((item) => item.recovery === 'watching' || item.recovery === 'retrying').length
  const blocked = items.filter((item) => item.recovery === 'paused-balance' || item.recovery === 'stopped').length
  const state = blocked > 0 ? 'blocked' : waiting > 0 ? 'warning' : active > 0 ? 'active' : 'idle'
  const count = blocked || waiting || active
  const activeSession = items
    .filter((item) => item.status === 'active')
    .sort((left, right) => Number(right.foreground) - Number(left.foreground) || (right.lastEventAtMs || right.timestamp) - (left.lastEventAtMs || left.timestamp))[0]
  const balance = providerBalances[0]
  const toggleLaunchPreference = () => {
    const next = !floatingLaunchOnStart
    setFloatingLaunchOnStart(next)
    showToast(tr(language, next ? 'launchPreferenceOn' : 'launchPreferenceOff'))
  }
  const toggleNotifications = () => {
    const next = !notifyEnabled
    setNotifyEnabled(next)
    showToast(tr(language, next ? 'desktopNotificationsOn' : 'desktopNotificationsOff'))
  }
  const toggleAlwaysOnTop = async () => {
    const next = !alwaysOnTop
    setAlwaysOnTop(next)
    writeStored('floatingAlwaysOnTop', next)
    const handled = await setFloatingAlwaysOnTop(next)
    if (handled === null) showToast(language === 'zh' ? '桌面壳暂不可用' : 'Desktop shell unavailable')
  }
  const toggleFloatingOption = (key: string, value: boolean, setter: (value: boolean) => void) => {
    setter(value)
    writeStored(key, value)
  }
  const updateFloatingSize = (value: number) => {
    const next = Math.max(180, Math.min(720, Math.round(value)))
    setFloatingSize(next)
    writeStored('floatingSize', next)
    void setFloatingWindowSize(next)
  }
  const updateFloatingOpacity = (value: number) => {
    setFloatingOpacity(value)
    writeStored('floatingOpacity', value)
  }
  const updateFloatingFontScale = (value: number) => {
    const next = Math.max(75, Math.min(160, Math.round(value)))
    setFloatingFontScale(next)
    writeStored('floatingFontScale', next)
  }
  const updateFloatingSkin = (skin: FloatingSkin) => {
    setFloatingSkin(skin)
    writeStored('floatingSkin', skin)
    void setFloatingWindowShape(skin)
  }
  return <>
    <section className="page-heading compact floating-page-heading">
      <div><div className="eyebrow accent-text">{tr(language, 'floating')} <span className="heading-line" /></div><h1>{tr(language, 'floatingTitle')}</h1></div>
      <button className={floatingEnabled ? 'secondary-button floating-visibility-button' : 'primary-button'} onClick={onToggleFloating}>{floatingEnabled ? <X size={15} /> : <PictureInPicture2 size={15} />}{tr(language, floatingEnabled ? 'hideWidget' : 'showWidget')}</button>
    </section>
    <div className="floating-settings-layout">
      <section className="floating-preview-panel">
        <div className="floating-preview-head"><div><span className="eyebrow">{tr(language, 'widgetPreview')}</span><strong>{tr(language, floatingEnabled ? 'widgetVisible' : 'widgetHidden')}</strong><small>{tr(language, 'widgetPreviewDescription')}</small></div><span className={`floating-visibility-state ${floatingEnabled ? 'visible' : ''}`}><i />{tr(language, floatingEnabled ? 'on' : 'off')}</span></div>
        <div className="floating-preview-stage">
          <div className={`floating-crt-preview ${state} skin-${floatingSkin}`} style={{ '--floating-font-scale': String(floatingSize / 252 * floatingFontScale / 100) } as React.CSSProperties} aria-label={tr(language, 'floatingCrtPreview')}>
            <div className="floating-crt-preview-top"><strong>ATLAS</strong><span><i className="red" /><i className="yellow" /><i className="green" /></span></div>
             <div className="floating-crt-preview-body"><div className="floating-crt-preview-screen"><div><i className="floating-crt-preview-dot" />{active > 0 ? (language === 'zh' ? '执行中' : 'Working') : (language === 'zh' ? '空闲' : 'Idle')}<b>{count || 0}</b></div><strong>{activeSession?.title || (language === 'zh' ? '未选择会话' : 'No session selected')}</strong><small>{activeSession ? `${activeSession.folder} · ${activeSession.model}` : tr(language, 'floatingCrtPreviewDescription')}</small><div className="floating-crt-preview-balance">{balance ? `${balance.name} · ${balance.balance}` : (language === 'zh' ? '余额未检查' : 'Balance not checked')}</div><p>{showOutput ? (activeSession?.lastOutput || activeSession?.lastError || (language === 'zh' ? '暂无最新输出' : 'No recent output')) : (language === 'zh' ? '输出已隐藏' : 'Output hidden')}</p></div></div>
            <div className="floating-crt-preview-footer"><button aria-label={language === 'zh' ? '上一个会话' : 'Previous session'}><ChevronLeft size={13} /></button><span>{count > 0 ? `${count} ${language === 'zh' ? '个会话' : 'sessions'}` : tr(language, 'floatingIdle')}</span><button aria-label={tr(language, 'activateSession')}><Play size={11} fill="currentColor" /></button><button aria-label={tr(language, 'inputContinue')}><TerminalSquare size={11} /></button><button aria-label={language === 'zh' ? '下一个会话' : 'Next session'}><ChevronRight size={13} /></button></div>
          </div>
        </div>
        <div className="floating-stat-strip"><div><i className="green" /><strong>{active}</strong><span>{tr(language, 'activeSession')}</span></div><div><i className="yellow" /><strong>{waiting}</strong><span>{tr(language, 'waiting')}</span></div><div><i className="red" /><strong>{blocked}</strong><span>{tr(language, 'blocked')}</span></div></div>
      </section>
      <section className="settings-panel floating-controls-panel">
        <div className="panel-head"><div><h3>{tr(language, 'floatingControls')}</h3><span className="panel-subtitle">Atlas Mini</span></div><PictureInPicture2 size={17} className="teal-icon" /></div>
        <div className="floating-control-list">
          <div className="switch-row"><span><strong>{tr(language, 'floatingWidget')}</strong><small>{tr(language, 'floatingWidgetDescription')}</small></span><button className={`toggle ${floatingEnabled ? 'on' : ''}`} onClick={onToggleFloating} aria-label={tr(language, 'floatingWidget')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'showOnLaunch')}</strong><small>{tr(language, 'showOnLaunchDescription')}</small></span><button className={`toggle ${floatingLaunchOnStart ? 'on' : ''}`} onClick={toggleLaunchPreference} aria-label={tr(language, 'showOnLaunch')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'floatingNotifications')}</strong><small>{tr(language, 'floatingNotificationsDescription')}</small></span><button className={`toggle ${notifyEnabled ? 'on' : ''}`} onClick={toggleNotifications} aria-label={tr(language, 'floatingNotifications')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'alwaysOnTop')}</strong><small>{tr(language, 'alwaysOnTopDescription')}</small></span><button className={`toggle ${alwaysOnTop ? 'on' : ''}`} onClick={() => void toggleAlwaysOnTop()} aria-label={tr(language, 'alwaysOnTop')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'showOutput')}</strong><small>{tr(language, 'showOutputDescription')}</small></span><button className={`toggle ${showOutput ? 'on' : ''}`} onClick={() => toggleFloatingOption('floatingShowOutput', !showOutput, setShowOutput)} aria-label={tr(language, 'showOutput')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'autoPickSession')}</strong><small>{tr(language, 'autoPickSessionDescription')}</small></span><button className={`toggle ${autoPickSession ? 'on' : ''}`} onClick={() => toggleFloatingOption('floatingAutoPickSession', !autoPickSession, setAutoPickSession)} aria-label={tr(language, 'autoPickSession')}><span /></button></div>
          <div className="switch-row"><span><strong>{tr(language, 'rightClickMenu')}</strong><small>{tr(language, 'rightClickMenuDescription')}</small></span><button className={`toggle ${rightClickMenu ? 'on' : ''}`} onClick={() => toggleFloatingOption('floatingRightClickMenu', !rightClickMenu, setRightClickMenu)} aria-label={tr(language, 'rightClickMenu')}><span /></button></div>
        </div>
        <div className="floating-skin-picker">
          <div><strong>{tr(language, 'floatingSkin')}</strong><small>{tr(language, 'floatingSkinDescription')}</small></div>
          <div className="floating-skin-options" role="radiogroup" aria-label={tr(language, 'floatingSkin')}>
            {floatingSkinOptions.map((option) => <button key={option.id} type="button" role="radio" aria-checked={floatingSkin === option.id} className={`floating-skin-option skin-${option.id} ${floatingSkin === option.id ? 'selected' : ''}`} onClick={() => updateFloatingSkin(option.id)} title={language === 'zh' ? option.labelZh : option.labelEn}><i aria-hidden="true" /><span>{language === 'zh' ? option.labelZh : option.labelEn}</span></button>)}
          </div>
        </div>
        <div className="floating-appearance-grid"><label><span>{tr(language, 'floatingScale')} · {floatingSize}px</span><input type="range" min="180" max="720" step="1" value={floatingSize} onChange={(event) => updateFloatingSize(Number(event.target.value))} /></label><label><span>{language === 'zh' ? '悬浮窗字号' : 'Widget font size'} · {floatingFontScale}%</span><input type="range" min="75" max="160" step="1" value={floatingFontScale} onChange={(event) => updateFloatingFontScale(Number(event.target.value))} /></label><label><span>{tr(language, 'floatingOpacity')} · {floatingOpacity}%</span><input type="range" min="65" max="100" step="5" value={floatingOpacity} onChange={(event) => updateFloatingOpacity(Number(event.target.value))} /></label></div>
        <div className="floating-quick-actions"><span className="eyebrow">{tr(language, 'quickActions')}</span><div className="floating-action-pair"><button className="secondary-button" onClick={() => activeSession && activateSession(activeSession)} disabled={!activeSession}><Play size={14} fill="currentColor" />{activeSession ? tr(language, 'activateSession') : tr(language, 'noActiveSession')}</button><button className="secondary-button" onClick={() => activeSession && inputContinue(activeSession)} disabled={!activeSession}><TerminalSquare size={14} />{tr(language, 'inputContinue')}</button></div></div>
      </section>
    </div>
  </>
}

function RuntimeView({ language, codexVersion, setCodexVersion, codexProvider, defaultModel, setDefaultModel, defaultPermission, setDefaultPermission, defaultReasoningEffort, setDefaultReasoningEffort, autoScan, setAutoScan, hookStatus, setHookStatus, mobileBridge, setMobileBridge, serverTunnel, setServerTunnel, showToast }: { language: 'zh' | 'en'; codexVersion: string; setCodexVersion: (value: string) => void; codexProvider: string; defaultModel: string; setDefaultModel: (value: string) => void; defaultPermission: string; setDefaultPermission: (value: string) => void; defaultReasoningEffort: string; setDefaultReasoningEffort: (value: string) => void; autoScan: boolean; setAutoScan: (value: boolean) => void; hookStatus: CodexHookStatus | null; setHookStatus: (value: CodexHookStatus | null) => void; mobileBridge: MobileBridgeConfig | null; setMobileBridge: (value: MobileBridgeConfig | null) => void; serverTunnel: ServerTunnelStatus | null; setServerTunnel: (value: ServerTunnelStatus | null) => void; showToast: (message: string) => void }) {
  const [saving, setSaving] = useState(false)
  const [updatingCodex, setUpdatingCodex] = useState(false)
  const [desktopUpdate, setDesktopUpdate] = useState<DesktopUpdateInfo | null>(null)
  const [desktopUpdateBusy, setDesktopUpdateBusy] = useState<'check' | 'download' | 'install' | null>(null)
  const [desktopUpdateProgress, setDesktopUpdateProgress] = useState<DesktopUpdateProgress | null>(null)
  const [desktopUpdateError, setDesktopUpdateError] = useState('')
  const [bridgeSaving, setBridgeSaving] = useState(false)
  const [tunnelBusy, setTunnelBusy] = useState(false)
  const [bridgeDraft, setBridgeDraft] = useState<MobileBridgeSettings>({ tunnelUrl: '', cloudflaredPath: '', tunnelToken: '', tunnelName: '', preferTunnel: false, autoStartTunnel: false })
  const [serverDraft, setServerDraft] = useState<ServerTunnelInstallRequest>({ host: '', port: 22, username: '', password: '', cloudflareToken: '', tunnelUrl: '', remotePort: 15730, autoStart: true, rememberPassword: false, identityFile: '' })
  const [serverBusy, setServerBusy] = useState(false)
  const [serverProgress, setServerProgress] = useState<ServerTunnelProgress | null>(null)
  const [serverPasswordVisible, setServerPasswordVisible] = useState(false)
  const [bridgeAdvanced, setBridgeAdvanced] = useState(false)
  const [serverAdvanced, setServerAdvanced] = useState(false)
  const [voiceStatus, setVoiceStatus] = useState<VoiceServiceStatus | null>(null)
  const [voiceProgress, setVoiceProgress] = useState<VoiceServiceProgress | null>(null)
  const [voiceBusy, setVoiceBusy] = useState(false)
  useEffect(() => {
    let disposed = false
    void checkDesktopUpdate().then((result) => { if (!disposed && result) setDesktopUpdate(result) })
    return () => { disposed = true }
  }, [])
  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null
    void listenDesktopEvent<DesktopUpdateProgress>('desktop_update_progress', (progress) => {
      if (disposed) return
      const normalized = {
        ...progress,
        state: progress.state || (progress.complete ? 'complete' : 'downloading'),
      }
      setDesktopUpdateProgress(normalized)
      if (normalized.state === 'error') setDesktopUpdateError(normalized.error || (language === 'zh' ? '下载安装包失败' : 'Installer download failed'))
      if (normalized.state === 'complete') setDesktopUpdateError('')
    }).then((remove) => {
      if (disposed) remove?.()
      else unlisten = remove
    })
    return () => {
      disposed = true
      unlisten?.()
    }
  }, [language])
  useEffect(() => {
    const onCommandError = (event: Event) => {
      const detail = (event as CustomEvent<DesktopCommandError>).detail
      if (detail?.command !== 'download_desktop_update') return
      const error = detail.error || (language === 'zh' ? '下载安装包失败' : 'Installer download failed')
      setDesktopUpdateError(error)
      setDesktopUpdateProgress((current) => ({
        state: 'error',
        downloadedBytes: current?.downloadedBytes || 0,
        totalBytes: current?.totalBytes || desktopUpdate?.assetSize || null,
        bytesPerSecond: null,
        attempt: current?.attempt || 0,
        transport: current?.transport || 'none',
        complete: false,
        error,
      }))
    }
    window.addEventListener('codex-atlas:command-error', onCommandError)
    return () => window.removeEventListener('codex-atlas:command-error', onCommandError)
  }, [desktopUpdate?.assetSize, language])
  useEffect(() => {
    if (!serverTunnel) return
    setServerDraft((current) => ({ ...current, host: serverTunnel.host || current.host, port: serverTunnel.port || current.port, username: serverTunnel.username || current.username, remotePort: serverTunnel.remotePort || current.remotePort, tunnelUrl: serverTunnel.tunnelUrl || current.tunnelUrl, autoStart: serverTunnel.autoStart, identityFile: serverTunnel.keyPath || current.identityFile }))
  }, [serverTunnel])
  useEffect(() => {
    if (!mobileBridge) return
    setBridgeDraft((current) => ({
      ...current,
      preferTunnel: mobileBridge.preferTunnel ?? current.preferTunnel,
      autoStartTunnel: mobileBridge.autoStartTunnel ?? current.autoStartTunnel,
      tunnelUrl: mobileBridge.tunnelUrl || current.tunnelUrl,
    }))
  }, [mobileBridge])
  useEffect(() => {
    let disposed = false
    const refresh = async () => {
      const status = await getVoiceServiceStatus()
      if (!disposed && status) setVoiceStatus(status)
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), 5000)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [])
  useEffect(() => {
    if (!voiceBusy) return
    let disposed = false
    const poll = async () => {
      const progress = await getVoiceServiceProgress()
      if (!disposed && progress) setVoiceProgress(progress)
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 350)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [voiceBusy])
  useEffect(() => {
    if (!serverBusy) return
    let disposed = false
    const poll = async () => {
      const progress = await getServerTunnelProgress()
      if (!disposed && progress) setServerProgress(progress)
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 350)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [serverBusy])
  const refreshHookStatus = async () => {
    const status = await getCodexHookStatus()
    if (status) setHookStatus(status)
    return status
  }
  const installHook = async () => {
    const status = await installCodexHook()
    if (status) {
      setHookStatus(status)
      showToast(language === 'zh' ? '状态 hook 已安装；在新 Codex 会话中用 /hooks 确认一次' : 'Status hook installed; approve it once with /hooks in a new Codex session')
    } else {
      showToast(language === 'zh' ? '桌面壳不可用，无法安装 hook' : 'Desktop shell unavailable; hook was not installed')
    }
  }
  const hookReady = hookStatus?.configured && hookStatus.enabled
  const saveDefaults = async () => {
    setSaving(true)
    const info = await setCodexDefaults(defaultModel, defaultPermission, defaultReasoningEffort)
    setSaving(false)
    if (info?.model) {
      setDefaultModel(info.model)
      showToast(language === 'zh' ? '已写入 Codex 配置' : 'Saved to Codex configuration')
    }
  }
  const applyDefaultsImmediately = (model: string, permission: string, reasoning: string) => {
    if (!model.trim()) return
    void setCodexDefaults(model, permission, reasoning).then((info) => {
      if (info?.model) setDefaultModel(info.model)
      if (info?.reasoningEffort) setDefaultReasoningEffort(info.reasoningEffort)
      writeStored('defaultModel', info?.model || model)
      writeStored('defaultPermission', permission)
      writeStored('defaultReasoningEffort', info?.reasoningEffort || reasoning)
    })
  }
  const updateInstalledCodex = async () => {
    setUpdatingCodex(true)
    const info = await updateCodex()
    setUpdatingCodex(false)
    if (info?.version) {
      setCodexVersion(info.version)
      showToast(language === 'zh' ? `Codex 已更新到 ${info.version}` : `Codex updated to ${info.version}`)
      return
    }
    showToast(language === 'zh' ? 'Codex 更新失败，请检查 npm 和网络' : 'Codex update failed; check npm and network')
  }
  const checkAtlasUpdate = async () => {
    setDesktopUpdateBusy('check')
    const result = await checkDesktopUpdate()
    if (result) {
      setDesktopUpdate(result)
      setDesktopUpdateError('')
      if (!result.downloaded) setDesktopUpdateProgress(null)
      showToast(result.available ? (language === 'zh' ? `发现 Atlas ${result.latestVersion}` : `Atlas ${result.latestVersion} is available`) : (language === 'zh' ? '当前已是最新版本' : 'Atlas is up to date'))
    } else {
      showToast(language === 'zh' ? '检查更新失败，请检查网络后重试' : 'Update check failed. Check your connection and try again.')
    }
    setDesktopUpdateBusy(null)
  }
  const downloadAtlasUpdate = async () => {
    if (!desktopUpdate?.available) return
    setDesktopUpdateError('')
    setDesktopUpdateProgress({
      state: 'connecting',
      downloadedBytes: 0,
      totalBytes: desktopUpdate.assetSize || null,
      bytesPerSecond: null,
      attempt: 1,
      transport: 'native',
      complete: false,
      error: null,
    })
    setDesktopUpdateBusy('download')
    try {
      const result = await downloadDesktopUpdate(desktopUpdate)
      if (result?.downloaded) {
        setDesktopUpdate(result)
        showToast(language === 'zh' ? '安装包已下载，可立即安装' : 'Installer downloaded and ready to install')
      } else {
        setDesktopUpdateProgress((current) => current?.state === 'error' ? current : {
          state: 'error',
          downloadedBytes: current?.downloadedBytes || 0,
          totalBytes: current?.totalBytes || desktopUpdate.assetSize || null,
          bytesPerSecond: null,
          attempt: current?.attempt || 0,
          transport: current?.transport || 'none',
          complete: false,
          error: language === 'zh' ? '下载安装包失败，请重试' : 'Installer download failed. Try again.',
        })
        setDesktopUpdateError((current) => current || (language === 'zh' ? '下载安装包失败，请重试' : 'Installer download failed. Try again.'))
      }
    } finally {
      setDesktopUpdateBusy(null)
    }
  }
  const installAtlasUpdate = async () => {
    const path = desktopUpdate?.downloadedPath
    if (!path) return
    setDesktopUpdateBusy('install')
    const ok = await installDesktopUpdate(path)
    setDesktopUpdateBusy(null)
    if (!ok) showToast(language === 'zh' ? '无法启动安装程序' : 'Could not launch installer')
  }
  const installVoice = async () => {
    setVoiceBusy(true)
    setVoiceProgress({ state: 'installing', step: 1, total: 6, message: tr(language, 'voiceServiceInstalling'), downloadedBytes: 0, startedAtMs: Date.now(), finishedAtMs: null })
    try {
      const status = await installVoiceService()
      if (status) {
        setVoiceStatus(status)
        setVoiceProgress((current) => current ? { ...current, state: status.ready && status.daemonRunning ? 'ready' : 'error', step: status.ready && status.daemonRunning ? current.total : current.step, message: status.ready && status.daemonRunning ? tr(language, 'voiceServiceReady') : (status.error || tr(language, 'voiceServiceInstallFailed')), finishedAtMs: Date.now() } : current)
        showToast(status.ready && status.daemonRunning ? tr(language, 'voiceServiceReady') : (status.error || tr(language, 'voiceServiceInstallFailed')))
      } else {
        showToast(tr(language, 'voiceServiceInstallFailed'))
      }
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error)
      setVoiceProgress((current) => current ? { ...current, state: 'error', message: detail, finishedAtMs: Date.now() } : current)
      showToast(`${tr(language, 'voiceServiceInstallFailed')}: ${detail}`)
    } finally {
      setVoiceBusy(false)
      const [status, progress] = await Promise.all([getVoiceServiceStatus(), getVoiceServiceProgress()])
      if (status) setVoiceStatus(status)
      if (progress) setVoiceProgress(progress)
    }
  }
  const openProjectLink = async (url: string) => {
    const opened = await openExternalUrl(url)
    if (!opened) showToast(language === 'zh' ? '无法打开链接' : 'Could not open link')
  }
  const copyMobileBridge = async () => {
    if (!mobileBridge) return
    const text = mobileBridge.pairingUri || `${mobileBridge.activeUrl || mobileBridge.url}\nToken: ${mobileBridge.token}`
    try {
      await navigator.clipboard.writeText(text)
      showToast(tr(language, 'bridgeCopied'))
    } catch {
      showToast(text)
    }
  }
  const saveBridgeSettings = async () => {
    setBridgeSaving(true)
    const config = await configureMobileBridge(bridgeDraft)
    setBridgeSaving(false)
    if (config) {
      setMobileBridge(config)
      showToast(language === 'zh' ? '连接配置已保存' : 'Connection settings saved')
    }
  }
  const toggleTunnel = async () => {
    setTunnelBusy(true)
    const config = mobileBridge?.tunnelRunning ? await stopMobileBridgeTunnel() : await startMobileBridgeTunnel()
    setTunnelBusy(false)
    if (config) {
      setMobileBridge(config)
      showToast(language === 'zh' ? (config.tunnelRunning ? '固定隧道已启动' : '固定隧道已停止') : (config.tunnelRunning ? 'Fixed tunnel started' : 'Fixed tunnel stopped'))
    }
  }
  const installServer = async () => {
    setServerBusy(true)
    setServerProgress({ state: 'connecting', step: 1, total: 7, message: language === 'zh' ? '正在连接服务器' : 'Connecting to server', startedAtMs: Date.now(), finishedAtMs: null })
    const progressTimer = window.setInterval(() => {
      void getServerTunnelProgress().then((progress) => { if (progress) setServerProgress(progress) })
    }, 500)
    try {
      const status = await installServerTunnel(serverDraft)
      if (status) {
        setServerTunnel(status)
        const config = await getMobileBridgeConfig()
        if (config) setMobileBridge(config)
        showToast(language === 'zh' ? '服务器通道已部署并连接' : 'Server tunnel deployed and connected')
      } else {
        const progress = await getServerTunnelProgress()
        if (progress) setServerProgress(progress)
        showToast(language === 'zh' ? '服务器通道部署失败，请查看进度详情' : 'Server tunnel deployment failed; inspect the progress details')
      }
    } finally {
      window.clearInterval(progressTimer)
      setServerBusy(false)
      const progress = await getServerTunnelProgress()
      if (progress) setServerProgress(progress)
    }
  }
  const toggleServer = async () => {
    setServerBusy(true)
    setServerProgress({ state: 'starting', step: 6, total: 7, message: language === 'zh' ? '正在启动反向 SSH 通道' : 'Starting reverse SSH tunnel', startedAtMs: Date.now(), finishedAtMs: null })
    try {
      const status = serverTunnel?.running ? await stopServerTunnel() : await startServerTunnel()
      if (status) {
        setServerTunnel(status)
        showToast(status.running
          ? (language === 'zh' ? '服务器通道已启动' : 'Server tunnel started')
          : (language === 'zh' ? '服务器通道已停止' : 'Server tunnel stopped'))
      } else {
        const progress = await getServerTunnelProgress()
        if (progress) setServerProgress(progress)
      }
    } finally {
      setServerBusy(false)
      const progress = await getServerTunnelProgress()
      if (progress) setServerProgress(progress)
      const status = await getServerTunnelStatus()
      if (status) setServerTunnel(status)
    }
  }
  const serverProgressLabel = (state: string) => {
    const labels: Record<string, [string, string]> = {
      connecting: ['连接服务器', 'Connecting'], 'generating-key': ['准备密钥', 'Preparing key'], authorizing: ['授权密钥', 'Authorizing'], 'checking-root': ['检查权限', 'Checking permissions'], installing: ['安装通道', 'Installing'], starting: ['启动通道', 'Starting'], ready: ['已就绪', 'Ready'], error: ['部署失败', 'Failed'], idle: ['尚未部署', 'Not started'],
    }
    return labels[state]?.[language === 'zh' ? 0 : 1] || state
  }
  const voiceReady = Boolean(voiceStatus?.ready && voiceStatus.daemonRunning)
  const voiceConfigured = Boolean(voiceStatus?.serviceInstalled || voiceStatus?.sttReady || voiceStatus?.ttsReady)
  const voiceTone = voiceReady ? 'green' : 'yellow'
  const voiceHeadline = voiceBusy
    ? tr(language, 'voiceServiceInstalling')
    : voiceReady
      ? tr(language, 'voiceServiceReady')
      : !voiceStatus
        ? tr(language, 'voiceServiceChecking')
        : !voiceStatus.serviceInstalled || !voiceStatus.sttReady || !voiceStatus.ttsReady
          ? tr(language, 'voiceServiceMissing')
          : tr(language, 'voiceServiceDaemonStopped')
  const voiceProgressStep = voiceProgress ? Math.min(voiceProgress.step, voiceProgress.total || 6) : 0
  const voiceDownloadPercent = voiceProgress?.totalBytes && voiceProgress.totalBytes > 0
    ? Math.min(100, Math.round((voiceProgress.downloadedBytes / voiceProgress.totalBytes) * 100))
    : null
  const desktopDownloadPercent = desktopUpdateProgress?.totalBytes && desktopUpdateProgress.totalBytes > 0
    ? Math.min(100, Math.round((desktopUpdateProgress.downloadedBytes / desktopUpdateProgress.totalBytes) * 100))
    : null
  const desktopProgressLabel = desktopUpdateProgress
    ? desktopUpdateProgress.state === 'connecting'
      ? (language === 'zh' ? '正在连接 GitHub' : 'Connecting to GitHub')
      : desktopUpdateProgress.state === 'retrying'
        ? desktopUpdateProgress.transport === 'system'
          ? (language === 'zh' ? '正在切换系统下载器' : 'Switching to the system downloader')
          : (language === 'zh' ? `网络中断，正在重试 ${desktopUpdateProgress.attempt}/3` : `Connection interrupted, retrying ${desktopUpdateProgress.attempt}/3`)
        : desktopUpdateProgress.state === 'finalizing'
          ? (language === 'zh' ? '正在校验安装包' : 'Verifying installer')
          : desktopUpdateProgress.state === 'complete'
            ? (language === 'zh' ? '安装包已就绪' : 'Installer is ready')
            : desktopUpdateProgress.state === 'error'
              ? (language === 'zh' ? '下载失败' : 'Download failed')
              : (language === 'zh' ? '正在下载安装包' : 'Downloading installer')
    : ''
  const desktopProgressSize = desktopUpdateProgress
    ? `${formatByteCount(desktopUpdateProgress.downloadedBytes, language)}${desktopUpdateProgress.totalBytes ? ` / ${formatByteCount(desktopUpdateProgress.totalBytes, language)}` : ''}${desktopUpdateProgress.bytesPerSecond ? ` · ${formatByteCount(desktopUpdateProgress.bytesPerSecond, language)}/s` : ''}`
    : ''
  return <>
    <section className="page-heading compact"><div><div className="eyebrow accent-text">{tr(language, 'settings')} <span className="heading-line" /></div><h1>{tr(language, 'runtimeTitle')}</h1></div><button className="primary-button" onClick={() => void saveDefaults()} disabled={saving || !defaultModel.trim()}>{saving ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />} {saving ? (language === 'zh' ? '保存中…' : 'Saving…') : tr(language, 'save')}</button></section>
    <div className="runtime-grid">
      <section className="settings-panel">
        <div className="panel-head"><div><h3>{tr(language, 'sessionDefaults')}</h3><span className="panel-subtitle">{tr(language, 'appliedNewResumes')}</span></div><Wrench size={17} className="teal-icon" /></div>
        <label className="field-label">{tr(language, 'model')}<input className="text-input" value={defaultModel} onChange={(event) => setDefaultModel(event.target.value)} onBlur={() => applyDefaultsImmediately(defaultModel, defaultPermission, defaultReasoningEffort)} spellCheck={false} /><span className="field-hint">{codexProvider ? `${language === 'zh' ? '当前供应商' : 'Provider'} · ${codexProvider}` : (language === 'zh' ? '读取自 Codex 配置' : 'Read from Codex configuration')}</span></label>
        <label className="field-label">{tr(language, 'permissionField')}<select value={defaultPermission} onChange={(event) => { const next = event.target.value; setDefaultPermission(next); applyDefaultsImmediately(defaultModel, next, defaultReasoningEffort) }}><option value="Workspace write">{language === 'zh' ? '工作区写入' : 'Workspace write'}</option><option value="Read only">{language === 'zh' ? '只读' : 'Read only'}</option><option value="Full access">{language === 'zh' ? '完全访问' : 'Full access'}</option></select></label>
        <label className="field-label">{language === 'zh' ? '思考程度' : 'Reasoning effort'}<select value={defaultReasoningEffort} onChange={(event) => { const next = event.target.value; setDefaultReasoningEffort(next); applyDefaultsImmediately(defaultModel, defaultPermission, next) }}><option value="low">{language === 'zh' ? '低' : 'Low'}</option><option value="medium">{language === 'zh' ? '中' : 'Medium'}</option><option value="high">{language === 'zh' ? '高' : 'High'}</option><option value="xhigh">{language === 'zh' ? '极高' : 'Extra high'}</option></select></label>
        <label className="switch-row"><span><strong>{tr(language, 'scanOnLaunch')}</strong><small>{tr(language, 'refreshOnLaunch')}</small></span><button className={`toggle ${autoScan ? 'on' : ''}`} onClick={() => setAutoScan(!autoScan)} aria-label={tr(language, 'scanOnLaunch')}><span /></button></label>
        <label className="switch-row"><span><strong>{tr(language, 'recoveryGuardrails')}</strong><small>{tr(language, 'pauseBalance')}</small></span><span className="guardrail-chip"><ShieldCheck size={13} /> {tr(language, 'on')}</span></label>
      </section>
      <section className="settings-panel">
        <div className="panel-head"><div><h3>{tr(language, 'codexVersion')}</h3><span className="panel-subtitle">{tr(language, 'detectedCli')}</span></div><GitBranch size={17} className="violet-icon" /></div>
        <div className="version-card current"><div><span className="version-badge">{tr(language, 'installedBadge')}</span><strong>Codex {codexVersion}</strong><small>{codexVersion === 'detecting…' ? tr(language, 'detecting') : tr(language, 'readyResume')}</small></div><Check size={17} /></div>
        <button className="secondary-button version-help" onClick={() => void updateInstalledCodex()} disabled={updatingCodex}>{updatingCodex ? <LoaderCircle className="spin" size={14} /> : <Download size={14} />} {updatingCodex ? (language === 'zh' ? '更新中…' : 'Updating…') : tr(language, 'checkUpdates')}</button>
        <div className="desktop-update-row">
          <div>
            <strong>{language === 'zh' ? 'Atlas 桌面版本' : 'Atlas desktop version'}</strong>
            <small>{desktopUpdate ? (desktopUpdate.available ? `${language === 'zh' ? '可更新至' : 'Available'} ${desktopUpdate.latestVersion}` : (language === 'zh' ? '已是最新' : 'Up to date')) : (language === 'zh' ? '检查中…' : 'Checking…')}</small>
          </div>
          <div className="desktop-update-actions">
            <button className="icon-button tiny" onClick={() => void checkAtlasUpdate()} disabled={desktopUpdateBusy !== null} aria-label={language === 'zh' ? '检查 Atlas 更新' : 'Check Atlas updates'} title={language === 'zh' ? '检查 Atlas 更新' : 'Check Atlas updates'}>{desktopUpdateBusy === 'check' ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />}</button>
            {desktopUpdate?.downloadedPath
              ? <button className="primary-button compact-button" onClick={() => void installAtlasUpdate()} disabled={desktopUpdateBusy !== null}>{desktopUpdateBusy === 'install' ? <LoaderCircle className="spin" size={13} /> : <PackageCheck size={13} />}{language === 'zh' ? '安装' : 'Install'}</button>
              : desktopUpdate?.available
                ? <button className="primary-button compact-button" onClick={() => void downloadAtlasUpdate()} disabled={desktopUpdateBusy !== null}>{desktopUpdateBusy === 'download' ? <LoaderCircle className="spin" size={13} /> : <Download size={13} />}{desktopUpdateBusy === 'download' ? (desktopDownloadPercent !== null ? `${desktopDownloadPercent}%` : (language === 'zh' ? '下载中…' : 'Downloading…')) : desktopUpdateError ? (language === 'zh' ? '重试' : 'Retry') : (language === 'zh' ? '下载' : 'Download')}</button>
                : null}
          </div>
        </div>
        {desktopUpdateProgress && <div className={`desktop-update-progress ${desktopUpdateProgress.state}`} role="status" aria-live="polite">
          <div><strong>{desktopProgressLabel}</strong><span>{desktopDownloadPercent !== null ? `${desktopDownloadPercent}%` : desktopProgressSize}</span></div>
          {desktopUpdateProgress.totalBytes ? <progress max={desktopUpdateProgress.totalBytes} value={Math.min(desktopUpdateProgress.downloadedBytes, desktopUpdateProgress.totalBytes)} /> : <progress />}
          <small>{desktopUpdateError || desktopUpdateProgress.error || desktopProgressSize}</small>
        </div>}
      </section>
      <section className="settings-panel project-panel">
        <div className="panel-head"><div><h3>{tr(language, 'atlasProject')}</h3><span className="panel-subtitle">{tr(language, 'atlasProjectDescription')}</span></div><ExternalLink size={17} className="teal-icon" /></div>
        <code className="project-repository">{ATLAS_GITHUB_REPOSITORY}</code>
        <div className="project-actions">
          <button className="secondary-button" onClick={() => void openProjectLink(ATLAS_GITHUB_URL)}><ExternalLink size={14} />{tr(language, 'openProject')}</button>
          <button className="primary-button" onClick={() => void openProjectLink(ATLAS_RELEASES_URL)}><Download size={14} />{tr(language, 'openRelease')}</button>
        </div>
      </section>
      <section className="settings-panel hook-panel">
        <div className="panel-head"><div><h3>{tr(language, 'codexStatusHook')}</h3><span className="panel-subtitle">{tr(language, 'officialEvents')}</span></div><RadioTower size={17} className="teal-icon" /></div>
        <div className="hook-status-line"><span className={`health-light ${hookReady ? (hookStatus?.connected ? 'green' : 'yellow') : 'yellow'}`} /><div><strong>{hookReady ? (hookStatus?.connected ? tr(language, 'connected') : tr(language, 'configuredWaiting')) : tr(language, 'notConfigured')}</strong><small>{hookStatus?.sessionCount || 0} {tr(language, 'sessionEvents')}{hookStatus?.error ? ` · ${hookStatus.error}` : ''}</small></div><button className="icon-button tiny" aria-label={tr(language, 'refreshHook')} title={tr(language, 'refreshHook')} onClick={() => void refreshHookStatus()}><RefreshCw size={13} /></button></div>
        <button className="secondary-button version-help" onClick={() => void installHook()}>{hookReady ? <RefreshCw size={14} /> : <PlugZap size={14} />}{hookReady ? tr(language, 'repairHook') : tr(language, 'installHook')}</button>
      </section>
      <section className={`settings-panel voice-service-panel ${voiceProgress?.state === 'error' ? 'has-error' : ''}`}>
        <div className="panel-head"><div><h3>{tr(language, 'voiceService')}</h3><span className="panel-subtitle">{tr(language, 'voiceServiceDescription')}</span></div><Activity size={17} className="teal-icon" /></div>
        <div className="voice-service-status">
          <span className={`health-light ${voiceTone}`} />
          <div className="voice-service-status-copy"><strong>{voiceHeadline}</strong><small>{voiceStatus?.serviceVersion ? `Atlas ${voiceStatus.serviceVersion}` : tr(language, 'voiceServiceDaemon')} · {voiceStatus?.daemonRunning ? tr(language, 'connected') : tr(language, 'notConfigured')}</small></div>
          <button className="secondary-button compact-button" onClick={() => void installVoice()} disabled={voiceBusy}>{voiceBusy ? <LoaderCircle className="spin" size={14} /> : voiceConfigured ? <RefreshCw size={14} /> : <Download size={14} />}{voiceBusy ? tr(language, 'voiceServiceInstalling') : voiceConfigured ? tr(language, 'repairVoiceService') : tr(language, 'installVoiceService')}</button>
        </div>
        <div className="voice-service-meta"><span><strong>{tr(language, 'voiceServiceModels')}</strong><small>{voiceStatus?.sttReady ? 'STT ✓' : 'STT —'} · {voiceStatus?.ttsReady ? 'TTS ✓' : 'TTS —'}</small></span><span><strong>{tr(language, 'voiceServiceProvider')}</strong><small>{tr(language, 'voiceServiceDaemon')}</small></span></div>
        {voiceProgress && (voiceBusy || voiceProgress.state === 'error' || voiceProgress.state === 'ready') && <div className={`voice-install-progress ${voiceProgress.state}`} role="status" aria-live="polite"><div><strong>{voiceProgress.message}</strong><span>{voiceDownloadPercent !== null ? `${voiceDownloadPercent}%` : `${voiceProgressStep} / ${voiceProgress.total || 6}`}</span></div><progress max={voiceProgress.total || 6} value={voiceProgressStep} /><small>{voiceProgress.state === 'error' ? tr(language, 'voiceServiceInstallFailed') : voiceProgress.state === 'ready' ? tr(language, 'voiceServiceReady') : `${tr(language, 'voiceServiceModels')} · ${voiceProgressStep} / ${voiceProgress.total || 6}`}</small></div>}
      </section>
      <section className="settings-panel mobile-bridge-panel connection-panel">
        <div className="panel-head"><div><h3>{tr(language, 'mobileBridge')}</h3><span className="panel-subtitle">{language === 'zh' ? '扫描一次，手机卡片自动同步' : 'Scan once; the phone card stays in sync'}</span></div><PictureInPicture2 size={17} className="teal-icon" /></div>
        {mobileBridge ? <>
          <div className="mobile-bridge-status connection-status"><span className={`health-light ${mobileBridge.tunnelRunning ? 'green' : mobileBridge.tunnelConfigured ? 'yellow' : 'green'}`} /><div><strong>{mobileBridge.deviceName || (language === 'zh' ? '桌面 Bridge 已就绪' : 'Desktop Bridge is ready')}</strong><small>{mobileBridge.deviceKind ? `${mobileBridge.deviceKind} · ` : ''}{mobileBridge.tunnelRunning ? tr(language, 'tunnelRunning') : (mobileBridge.tunnelConfigured ? (language === 'zh' ? '局域网优先 · 固定通道备用' : 'LAN first · fixed route as fallback') : (language === 'zh' ? '局域网连接' : 'LAN connection'))}</small></div><button className="secondary-button compact-button" onClick={() => void toggleTunnel()} disabled={tunnelBusy || !mobileBridge.tunnelConfigured}>{tunnelBusy ? <LoaderCircle className="spin" size={13} /> : <Power size={13} />}{mobileBridge.tunnelRunning ? tr(language, 'stopTunnel') : tr(language, 'startTunnel')}</button></div>
          {mobileBridge.tunnelError && <div className="field-hint error-text">{mobileBridge.tunnelError}</div>}
          <div className="mobile-bridge-pairing pairing-card"><div className="pairing-copy"><strong>{language === 'zh' ? '用手机扫描二维码' : 'Scan with your phone'}</strong><small>{language === 'zh' ? '也可以复制链接，在手机上粘贴后自动连接' : 'You can also copy the link and paste it on your phone'}</small><code className="mobile-bridge-pairing-link" title={mobileBridge.pairingUri}>{mobileBridge.pairingUri}</code><div className="mobile-bridge-actions"><button className="primary-button" onClick={() => void copyMobileBridge()}><Upload size={14} />{language === 'zh' ? '复制配对链接' : 'Copy pairing link'}</button><button className="secondary-button version-help" onClick={() => void saveBridgeSettings()} disabled={bridgeSaving}>{bridgeSaving ? <LoaderCircle className="spin" size={14} /> : <Check size={14} />}{tr(language, 'saveBridge')}</button></div></div><QRCodeSVG className="pairing-qr" value={mobileBridge.pairingUri} size={220} level="L" includeMargin /></div>
          <button className="advanced-toggle" onClick={() => setBridgeAdvanced((value) => !value)} aria-expanded={bridgeAdvanced}><span>{language === 'zh' ? '高级连接设置' : 'Advanced connection settings'}</span><ChevronDown size={15} className={bridgeAdvanced ? 'rotated' : ''} /></button>
          {bridgeAdvanced && <div className="advanced-panel"><div className="mobile-bridge-form"><label className="field-label">{tr(language, 'tunnelUrl')}<input className="text-input" value={bridgeDraft.tunnelUrl} onChange={(event) => setBridgeDraft({ ...bridgeDraft, tunnelUrl: event.target.value })} placeholder="https://atlas.example.com" spellCheck={false} /></label><label className="field-label">{tr(language, 'tunnelToken')}<input className="text-input" type="password" value={bridgeDraft.tunnelToken} onChange={(event) => setBridgeDraft({ ...bridgeDraft, tunnelToken: event.target.value })} /></label><label className="field-label">{tr(language, 'tunnelName')}<input className="text-input" value={bridgeDraft.tunnelName} onChange={(event) => setBridgeDraft({ ...bridgeDraft, tunnelName: event.target.value })} /></label><label className="field-label">{tr(language, 'cloudflaredPath')}<input className="text-input" value={bridgeDraft.cloudflaredPath} onChange={(event) => setBridgeDraft({ ...bridgeDraft, cloudflaredPath: event.target.value })} placeholder="cloudflared" /></label></div><div className="mobile-bridge-preferences"><label className="switch-row"><span>{tr(language, 'bridgeModeTunnel')}</span><button className={`toggle ${bridgeDraft.preferTunnel ? 'on' : ''}`} onClick={() => setBridgeDraft({ ...bridgeDraft, preferTunnel: !bridgeDraft.preferTunnel })} aria-label={tr(language, 'bridgeModeTunnel')}><span /></button></label><label className="switch-row"><span>{tr(language, 'tunnelAutoStart')}</span><button className={`toggle ${bridgeDraft.autoStartTunnel ? 'on' : ''}`} onClick={() => setBridgeDraft({ ...bridgeDraft, autoStartTunnel: !bridgeDraft.autoStartTunnel })} aria-label={tr(language, 'tunnelAutoStart')}><span /></button></label></div><div className="mobile-bridge-route-grid"><div><span>{tr(language, 'bridgeLan')}</span><code>{mobileBridge.lanUrl || mobileBridge.url}</code></div><div><span>{tr(language, 'bridgeTunnel')}</span><code>{mobileBridge.tunnelUrl || tr(language, 'tunnelNotConfigured')}</code></div><div><span>{tr(language, 'bridgeActive')}</span><strong>{mobileBridge.activeUrl || mobileBridge.url}</strong></div></div></div>}
        </> : <div className="content-state empty"><CircleAlert size={18} /><span>{tr(language, 'bridgeOffline')}</span></div>}
      </section>
      <section className="settings-panel mobile-bridge-panel server-tunnel-panel connection-panel">
        <div className="panel-head"><div><h3>{tr(language, 'serverTunnel')}</h3><span className="panel-subtitle">{language === 'zh' ? '可选：为离开局域网的手机提供固定入口' : 'Optional fixed route for phones outside your LAN'}</span></div><PlugZap size={17} className="teal-icon" /></div>
        <div className="server-tunnel-status"><span className={`health-light ${serverTunnel?.running ? 'green' : serverTunnel?.configured ? 'yellow' : 'yellow'}`} /><div><strong>{serverTunnel?.running ? tr(language, 'serverRunning') : serverTunnel?.configured ? tr(language, 'serverStopped') : (language === 'zh' ? '尚未连接服务器' : 'No server connected')}</strong>{serverTunnel?.publicUrl && <code>{serverTunnel.publicUrl}</code>}{serverTunnel?.error && <small className="error-text">{serverTunnel.error}</small>}</div>{serverTunnel?.configured && <button className="secondary-button compact-button" onClick={() => void toggleServer()} disabled={serverBusy}>{serverBusy ? <LoaderCircle className="spin" size={13} /> : <Power size={13} />}{serverTunnel.running ? tr(language, 'stopTunnel') : tr(language, 'startTunnel')}</button>}</div>
        {!serverTunnel?.configured && <div className="mobile-bridge-form server-tunnel-form server-quick-form"><label className="field-label">{tr(language, 'serverHost')}<input className="text-input" value={serverDraft.host} onChange={(event) => setServerDraft({ ...serverDraft, host: event.target.value })} placeholder="203.0.113.10" /></label><label className="field-label">{tr(language, 'serverUsername')}<input className="text-input" value={serverDraft.username} onChange={(event) => setServerDraft({ ...serverDraft, username: event.target.value })} placeholder="root" /></label><label className="field-label">{tr(language, 'serverPassword')}<span className="password-input"><input className="text-input" type={serverPasswordVisible ? 'text' : 'password'} value={serverDraft.password} onChange={(event) => setServerDraft({ ...serverDraft, password: event.target.value })} /><button type="button" className="password-toggle" onClick={() => setServerPasswordVisible((value) => !value)} aria-label={language === 'zh' ? (serverPasswordVisible ? '隐藏密码' : '显示密码') : (serverPasswordVisible ? 'Hide password' : 'Show password')} title={language === 'zh' ? (serverPasswordVisible ? '隐藏密码' : '显示密码') : (serverPasswordVisible ? 'Hide password' : 'Show password')}>{serverPasswordVisible ? <EyeOff size={15} /> : <Eye size={15} />}</button></span></label></div>}
        <button className="advanced-toggle" onClick={() => setServerAdvanced((value) => !value)} aria-expanded={serverAdvanced}><span>{language === 'zh' ? '高级服务器设置' : 'Advanced server settings'}</span><ChevronDown size={15} className={serverAdvanced ? 'rotated' : ''} /></button>
        {serverAdvanced && <div className="advanced-panel"><div className="mobile-bridge-form server-tunnel-form"><label className="field-label">{tr(language, 'serverPort')}<input className="text-input" type="number" min="1" value={serverDraft.port} onChange={(event) => setServerDraft({ ...serverDraft, port: Number(event.target.value) || 22 })} /></label><label className="field-label">{tr(language, 'cloudflareToken')}<input className="text-input" type="password" value={serverDraft.cloudflareToken} onChange={(event) => setServerDraft({ ...serverDraft, cloudflareToken: event.target.value })} /></label><label className="field-label">{language === 'zh' ? '固定访问地址（可选）' : 'Public URL (optional)'}<input className="text-input" value={serverDraft.tunnelUrl} onChange={(event) => setServerDraft({ ...serverDraft, tunnelUrl: event.target.value })} placeholder="https://atlas.example.com" /></label></div><div className="mobile-bridge-preferences"><label className="switch-row"><span>{tr(language, 'rememberPassword')}</span><button className={`toggle ${serverDraft.rememberPassword ? 'on' : ''}`} onClick={() => setServerDraft({ ...serverDraft, rememberPassword: !serverDraft.rememberPassword })} aria-label={tr(language, 'rememberPassword')}><span /></button></label><label className="switch-row"><span>{tr(language, 'tunnelAutoStart')}</span><button className={`toggle ${serverDraft.autoStart ? 'on' : ''}`} onClick={() => setServerDraft({ ...serverDraft, autoStart: !serverDraft.autoStart })} aria-label={tr(language, 'tunnelAutoStart')}><span /></button></label></div></div>}
        <div className="mobile-bridge-actions"><button className="primary-button" onClick={() => void installServer()} disabled={serverBusy || !serverDraft.host.trim() || !serverDraft.username.trim()}>{serverBusy ? <LoaderCircle className="spin" size={14} /> : <Power size={14} />}{serverBusy ? tr(language, 'serverInstalling') : tr(language, 'installAndConnect')}</button></div>
        {serverProgress && serverProgress.state !== 'idle' && <div className={`server-install-progress ${serverProgress.state}`} role="status" aria-live="polite"><div><strong>{serverProgressLabel(serverProgress.state)}</strong><span>{Math.min(serverProgress.step, serverProgress.total)} / {serverProgress.total}</span></div><progress max={serverProgress.total} value={Math.min(serverProgress.step, serverProgress.total)} /><small>{serverProgress.message}</small></div>}
      </section>
    </div>
  </>
}

type PrototypeId = 'clear' | 'mist' | 'paper' | 'signal'
type GalleryLanguage = 'zh' | 'en'

type GalleryCopy = Record<string, string>

const galleryText: Record<GalleryLanguage, GalleryCopy> = {
  zh: {
    eyebrow: 'Codex Atlas 原型',
    title: '选择首页布局',
    subtitle: '三套布局共用同一套视觉语言，只比较会话、详情和状态之间的关系。',
    language: '语言',
    prototype: '原型',
    preview: '查看大图',
    choose: '选择此方案',
    chosen: '当前选择',
    selectedHint: '点击卡片切换，或打开大图仔细看看。',
    confirm: '确认视觉方向',
    pending: '尚未确认。你选定后我再开始完整软件实现。',
    close: '关闭预览',
    home: '首页',
    sessions: '全部会话',
    monitor: '恢复监控',
    integrations: '连接',
    settings: '设置',
    recent: '最近会话',
    active: '运行中',
    waiting: '待处理',
    blocked: '已阻止',
    resume: '继续',
    search: '搜索会话内容…',
    balance: '供应商余额',
    floating: '桌面悬浮窗',
    cleanName: '会话流',
    cleanDesc: '左侧导航、中间最近会话、右侧恢复状态，信息平衡且完整。',
    mistName: '专注分屏',
    mistDesc: '极窄工具栏配宽会话列表，选中会话后在右侧展开完整详情。',
    paperName: '状态底座',
    paperDesc: '顶部导航配全宽会话流，恢复、余额和 Paseo 状态收在底部。',
    signalName: '信号紧凑',
    signalDesc: '冷白高密度布局，状态和快捷操作一眼可见。',
    cleanTag: '导航 + 会话流 + 状态轨',
    mistTag: '宽列表 + 详情面板',
    paperTag: '顶部导航 + 底部状态',
    signalTag: '高密度 · 快捷优先',
    mockWorkspace: '工作区 / 分支',
    mockModel: '模型',
    mockUpdated: '更新时间',
    mockSynced: '索引已同步',
    mockCount: '128 个会话',
    mockGuard: '恢复保护已开启',
    mockBalance: '余额正常',
    mockRetry: '等待重试',
    mockAttention: '需要处理',
    mockCommand: '快速操作',
    mockNew: '新建会话',
    mockDefaults: '默认设置',
    mockSkills: '管理 Skills',
    mockStatus: '状态概览',
  },
  en: {
    eyebrow: 'CODEX ATLAS PROTOTYPES',
    title: 'Choose the home layout',
    subtitle: 'Three layouts share one visual system. Compare only how sessions, details, and status relate.',
    language: 'Language',
    prototype: 'Prototype',
    preview: 'Open preview',
    choose: 'Choose this style',
    chosen: 'Selected',
    selectedHint: 'Click a card to switch, or open the larger preview to inspect it.',
    confirm: 'Confirm visual direction',
    pending: 'Nothing is confirmed yet. I will implement the complete app after your choice.',
    close: 'Close preview',
    home: 'Overview',
    sessions: 'All sessions',
    monitor: 'Recovery monitor',
    integrations: 'Integrations',
    settings: 'Settings',
    recent: 'Recent sessions',
    active: 'Active',
    waiting: 'Waiting',
    blocked: 'Blocked',
    resume: 'Resume',
    search: 'Search session content…',
    balance: 'Provider balance',
    floating: 'Desktop mini window',
    cleanName: 'Session Flow',
    cleanDesc: 'Navigation left, recent sessions centered, and recovery status on the right.',
    mistName: 'Focus Split',
    mistDesc: 'A slim tool rail, a wide session list, and a full detail panel for selection.',
    paperName: 'Status Dock',
    paperDesc: 'Top navigation, a full-width session stream, and runtime status along the bottom.',
    signalName: 'Signal Compact',
    signalDesc: 'Cool white, dense rows, and fast status actions for power users.',
    cleanTag: 'Navigation + stream + status rail',
    mistTag: 'Wide list + detail panel',
    paperTag: 'Top navigation + status dock',
    signalTag: 'Dense · action first',
    mockWorkspace: 'Workspace / branch',
    mockModel: 'Model',
    mockUpdated: 'Updated',
    mockSynced: 'Index synced',
    mockCount: '128 sessions',
    mockGuard: 'Recovery guard on',
    mockBalance: 'Balance healthy',
    mockRetry: 'Retry waiting',
    mockAttention: 'Needs attention',
    mockCommand: 'Quick actions',
    mockNew: 'New session',
    mockDefaults: 'Manage defaults',
    mockSkills: 'Manage Skills',
    mockStatus: 'Status overview',
  },
}

const prototypeMeta: Array<{ id: PrototypeId; nameKey: string; descKey: string; tagKey: string }> = [
  { id: 'clear', nameKey: 'cleanName', descKey: 'cleanDesc', tagKey: 'cleanTag' },
  { id: 'mist', nameKey: 'mistName', descKey: 'mistDesc', tagKey: 'mistTag' },
  { id: 'paper', nameKey: 'paperName', descKey: 'paperDesc', tagKey: 'paperTag' },
]

function galleryT(language: GalleryLanguage, key: string) {
  return galleryText[language][key] || galleryText.en[key] || key
}

function PrototypeCanvas({ id, language }: { id: PrototypeId; language: GalleryLanguage }) {
  const t = (key: string) => galleryT(language, key)
  const [floatingVisible, setFloatingVisible] = useState(true)
  const [windowMessage, setWindowMessage] = useState('')
  const rows: Array<{ title: string; preview: string; folder: string; branch: string; model: string; time: string; state: 'active' | 'waiting' | 'done' | 'blocked' }> = language === 'zh'
    ? [
      { title: '认证中间件重构', preview: '刷新令牌轮换与 Cookie 边界', folder: 'api-gateway', branch: 'feat/auth-rotation', model: 'gpt-5.6-sol', time: '2 分钟', state: 'active' },
      { title: '排查 flaky e2e', preview: '定位结算流程的偶发超时', folder: 'web-console', branch: 'test/checkout-retries', model: 'gpt-5.6-sol', time: '18 分钟', state: 'waiting' },
      { title: '设计令牌迁移', preview: '旧色板映射为语义化 Token', folder: 'design-system', branch: 'chore/token-map', model: 'gpt-5-codex', time: '1 小时', state: 'done' },
      { title: '发布清单文档', preview: '整理平台发布检查步骤', folder: 'platform-docs', branch: 'docs/release-playbook', model: 'gpt-5-codex', time: '昨天', state: 'done' },
      { title: '查询计划回归', preview: '供应商余额不足，自动继续已暂停', folder: 'data-core', branch: 'perf/query-plan', model: 'gpt-5.6-sol', time: '昨天', state: 'blocked' },
    ]
    : [
      { title: 'Refactor auth middleware', preview: 'Rotate refresh tokens and tighten cookie boundaries', folder: 'api-gateway', branch: 'feat/auth-rotation', model: 'gpt-5.6-sol', time: '2 min', state: 'active' },
      { title: 'Triage flaky e2e', preview: 'Trace the intermittent checkout timeout', folder: 'web-console', branch: 'test/checkout-retries', model: 'gpt-5.6-sol', time: '18 min', state: 'waiting' },
      { title: 'Design token migration', preview: 'Map the legacy palette to semantic tokens', folder: 'design-system', branch: 'chore/token-map', model: 'gpt-5-codex', time: '1 hr', state: 'done' },
      { title: 'Document release checklist', preview: 'Organize the platform release checks', folder: 'platform-docs', branch: 'docs/release-playbook', model: 'gpt-5-codex', time: 'Yesterday', state: 'done' },
      { title: 'Query plan regression', preview: 'Insufficient balance. Auto-continue paused', folder: 'data-core', branch: 'perf/query-plan', model: 'gpt-5.6-sol', time: 'Yesterday', state: 'blocked' },
    ]
  const nav = [
    { label: t('home'), icon: LayoutDashboard },
    { label: t('sessions'), icon: Layers3, count: '128' },
    { label: t('monitor'), icon: RadioTower, count: '3' },
    { label: t('integrations'), icon: PlugZap },
    { label: language === 'zh' ? '技能' : 'Skills', icon: Sparkles },
  ]
  const showWindowMessage = (message: string) => {
    setWindowMessage(message)
    window.setTimeout(() => setWindowMessage(''), 2200)
  }
  const handleFloatingToggle = async () => {
    const next = !floatingVisible
    setFloatingVisible(next)
    const handled = await setFloatingWindowVisible(next)
    if (handled === null) showWindowMessage(next ? '悬浮窗已开启（桌面壳连接后置顶显示）' : '悬浮窗已隐藏')
  }
  return <div className={`atlas-canvas atlas-layout-${id}`}>
    <header className="atlas-bar">
      <div className="atlas-brand"><span><img src="/codex-atlas-icon.svg" alt="" /></span><strong>Codex Atlas</strong></div>
      {id !== 'mist' && <nav className="atlas-top-nav">{nav.slice(0, 4).map(({ label, icon: Icon }, index) => <button className={index === 0 ? 'active' : ''} key={label}><Icon size={13} />{label}</button>)}</nav>}
      {id !== 'mist' && <div className="atlas-global-search"><Search size={14} /><span>{t('search')}</span><kbd>Ctrl K</kbd></div>}
      <div className="atlas-bar-actions"><span className="atlas-sync"><i />{t('mockSynced')}</span><button className={`atlas-window-button ${floatingVisible ? 'is-active' : ''}`} aria-label={floatingVisible ? (language === 'zh' ? '隐藏桌面悬浮窗' : 'Hide floating window') : (language === 'zh' ? '显示桌面悬浮窗' : 'Show floating window')} aria-pressed={floatingVisible} title={floatingVisible ? (language === 'zh' ? '隐藏悬浮窗' : 'Hide floating window') : (language === 'zh' ? '显示悬浮窗' : 'Show floating window')} onClick={() => void handleFloatingToggle()}><PictureInPicture2 size={15} /></button><button aria-label={language === 'zh' ? '通知' : 'Notifications'} title={language === 'zh' ? '通知' : 'Notifications'}><Bell size={15} /></button><DesktopWindowControls language={language} onMessage={showWindowMessage} /></div>
    </header>
    {windowMessage && <div className="atlas-window-message" role="status">{windowMessage}</div>}
    <div className="atlas-body">
      <nav className="atlas-side-nav"><div className="atlas-nav-group">{nav.map(({ label, icon: Icon, count }, index) => <button className={index === 0 ? 'active' : ''} key={label} title={label}><Icon size={16} /><span>{label}</span>{count && <b>{count}</b>}</button>)}</div><div className="atlas-nav-bottom"><button><Settings2 size={16} /><span>{t('settings')}</span></button><small><i /> {t('mockGuard')}</small></div></nav>
      <main className="atlas-main">
        <div className="atlas-page-head"><div><span>{language === 'zh' ? '今天' : 'Today'}</span><h2>{t('recent')}</h2><p>{language === 'zh' ? '所有 Codex 会话都已索引并可继续' : 'Every Codex session is indexed and ready to resume'}</p></div><button className="atlas-scan"><RefreshCw size={14} />{language === 'zh' ? '扫描会话' : 'Scan sessions'}</button></div>
        <div className="atlas-list-tools"><div className="atlas-local-search"><Search size={14} /><span>{t('search')}</span></div><div className="atlas-filters"><button className="active">{language === 'zh' ? '全部' : 'All'}</button><button>{t('active')}</button><button>{language === 'zh' ? '已完成' : 'Done'}</button></div><button className="atlas-tool-icon" aria-label={language === 'zh' ? '筛选' : 'Filter'}><ListFilter size={14} /></button></div>
        <div className="atlas-list-head"><span>{language === 'zh' ? '会话' : 'Session'}</span><span>{language === 'zh' ? '工作区与分支' : 'Workspace & branch'}</span><span>{t('mockModel')}</span><span>{t('mockUpdated')}</span><span /></div>
        <div className="atlas-session-list">{rows.map((row, index) => <div className={`atlas-session-row ${index === 0 ? 'selected' : ''}`} key={row.title}><div className="atlas-session-copy"><i className={row.state} /><span><strong>{row.title}</strong><small>{row.preview}</small></span></div><div className="atlas-workspace"><strong>{row.folder}</strong><small><GitBranch size={11} />{row.branch}</small></div><span className="atlas-model">{row.model}</span><span className="atlas-time">{row.time}</span><button className="atlas-resume" aria-label={`${t('resume')} ${row.title}`}><Play size={12} fill="currentColor" /></button></div>)}</div>
      </main>
      <aside className="atlas-context">
        <div className="atlas-context-head"><div><span>{id === 'mist' ? (language === 'zh' ? '会话详情' : 'Session detail') : (language === 'zh' ? '实时状态' : 'Live status')}</span><h3>{id === 'mist' ? rows[0].title : (language === 'zh' ? '运行正常' : 'Running normally')}</h3></div></div>
        <div className="atlas-detail-copy"><span className="atlas-detail-state"><i />{t('active')}</span><p>{rows[0].preview}</p></div>
        <div className="atlas-context-section"><span>{language === 'zh' ? '恢复保护' : 'Recovery guard'}</span><div className="atlas-status-line"><i className="green" /><span><strong>{language === 'zh' ? '自动继续已开启' : 'Auto-continue enabled'}</strong><small>{language === 'zh' ? '最多连续尝试 3 次' : 'Up to 3 consecutive attempts'}</small></span><b>{id === 'mist' ? '0/3' : 'ON'}</b></div><div className="atlas-status-line"><i className="yellow" /><span><strong>CC Switch</strong><small>Codex2API · gpt-5.6-sol</small></span><b>$18.42</b></div><div className="atlas-status-line"><i className="green" /><span><strong>Paseo</strong><small>{language === 'zh' ? '128 个会话已同步' : '128 sessions synced'}</small></span><Check size={14} /></div></div>
        <div className="atlas-context-section atlas-error"><span>{language === 'zh' ? '最近事件' : 'Latest event'}</span><strong>403 Forbidden</strong><p>{language === 'zh' ? '检测到余额不足时不会自动输入继续。' : 'Continue is never injected when balance is insufficient.'}</p></div>
        <button className="atlas-context-action"><Play size={13} fill="currentColor" />{t('resume')}</button>
      </aside>
    </div>
    <footer className="atlas-status-dock"><div><i className="green" /><span><strong>{language === 'zh' ? '3 个会话运行中' : '3 sessions active'}</strong><small>{language === 'zh' ? '恢复监控正常' : 'Recovery watcher healthy'}</small></span></div><div><i className="yellow" /><span><strong>Codex2API · $18.42</strong><small>{language === 'zh' ? '余额正常' : 'Balance healthy'}</small></span></div><div><i className="green" /><span><strong>Paseo · 128</strong><small>{language === 'zh' ? '全部会话已同步' : 'All sessions synced'}</small></span></div><button><Play size={13} fill="currentColor" />{t('resume')}</button></footer>
  </div>
}

function PrototypeGallery() {
  const [language, setLanguage] = useState<GalleryLanguage>('zh')
  const [selected, setSelected] = useState<PrototypeId>('mist')
  const [confirmed, setConfirmed] = useState(false)
  const [windowMessage, setWindowMessage] = useState('')
  const t = (key: string) => galleryT(language, key)
  const selectedMeta = prototypeMeta.find((item) => item.id === selected) || prototypeMeta[0]
  const selectedIndex = prototypeMeta.findIndex((item) => item.id === selected)
  const showWindowMessage = (message: string) => {
    setWindowMessage(message)
    window.setTimeout(() => setWindowMessage(''), 2200)
  }
  return <div className="immersive-picker">
    <header className="picker-header">
      <div className="picker-brand"><span><img src="/codex-atlas-icon.svg" alt="" /></span><strong>Codex Atlas</strong><small>{language === 'zh' ? '布局原型' : 'Layout prototypes'}</small></div>
      <div className="picker-header-actions"><span>{t('language')}</span><div className="picker-language"><button className={language === 'zh' ? 'active' : ''} onClick={() => setLanguage('zh')}>中文</button><button className={language === 'en' ? 'active' : ''} onClick={() => setLanguage('en')}>EN</button></div><DesktopWindowControls language={language} onMessage={showWindowMessage} /></div>
    </header>
    {windowMessage && <div className="picker-window-message" role="status">{windowMessage}</div>}
    <main className="picker-workspace">
      <aside className="picker-sidebar"><div className="picker-intro"><span>{t('eyebrow')}</span><h1>{t('title')}</h1><p>{t('subtitle')}</p></div><nav className="picker-options">{prototypeMeta.map((meta, index) => <button className={selected === meta.id ? 'active' : ''} key={meta.id} onClick={() => { setSelected(meta.id); setConfirmed(false) }}><span className="picker-option-number">0{index + 1}</span><span><strong>{t(meta.nameKey)}</strong><small>{t(meta.tagKey)}</small></span>{selected === meta.id ? <Check size={15} /> : <ArrowUpRight size={15} />}</button>)}</nav><div className="picker-selection"><span>{confirmed ? (language === 'zh' ? '已记录你的选择' : 'Selection recorded') : t('chosen')}</span><strong>{t(selectedMeta.nameKey)}</strong><p>{t(selectedMeta.descKey)}</p><button className={confirmed ? 'confirmed' : ''} onClick={() => setConfirmed(true)}>{confirmed ? <Check size={15} /> : <Palette size={15} />}{confirmed ? (language === 'zh' ? '已选择' : 'Selected') : t('choose')}</button></div></aside>
      <section className="picker-stage"><div className="picker-stage-head"><div><span>0{selectedIndex + 1} / 0{prototypeMeta.length}</span><strong>{t(selectedMeta.nameKey)}</strong></div><span>{t(selectedMeta.tagKey)}</span></div><PrototypeCanvas id={selected} language={language} /></section>
    </main>
  </div>
}

function FloatingMini() {
  const [language, setLanguage] = useState<UiLanguage>(() => readStored('language', 'zh'))
  const [floatingOpacity, setFloatingOpacity] = useState(() => readStored('floatingOpacity', 100))
  const [floatingSkin, setFloatingSkin] = useState<FloatingSkin>(() => normalizeFloatingSkin(readStored('floatingSkin', 'classic')))
  const [floatingSize, setFloatingSize] = useState(() => {
    const stored = Number(readStored('floatingSize', 252))
    return Number.isFinite(stored) ? Math.max(180, Math.min(720, stored)) : 252
  })
  const [floatingFontScale, setFloatingFontScale] = useState(() => {
    const stored = Number(readStored('floatingFontScale', 100))
    return Number.isFinite(stored) ? Math.max(75, Math.min(160, stored)) : 100
  })
  const [showOutput, setShowOutput] = useState(() => readStored('floatingShowOutput', true))
  const [autoPickSession, setAutoPickSession] = useState(() => readStored('floatingAutoPickSession', true))
  const [rightClickMenu, setRightClickMenu] = useState(() => readStored('floatingRightClickMenu', true))
  const [defaultModel, setDefaultModel] = useState(() => readStored('defaultModel', ''))
  const [defaultPermission, setDefaultPermission] = useState(() => readStored('defaultPermission', 'Workspace write'))
  const [defaultReasoningEffort, setDefaultReasoningEffort] = useState(() => readStored('defaultReasoningEffort', 'medium'))
  const t = (key: string) => tr(language, key)
  const [items, setItems] = useState<Session[]>([])
  const [codexModels, setCodexModels] = useState<CodexModelOption[]>([])
  const [modelBusy, setModelBusy] = useState(false)
  const [currentBalance, setCurrentBalance] = useState<CcSwitchProviderBalance | null>(null)
  const currentBalanceRef = useRef<CcSwitchProviderBalance | null>(null)
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [actionBusy, setActionBusy] = useState<'activate' | 'input' | null>(null)
  const [quickInputOpen, setQuickInputOpen] = useState(false)
  const [quickInput, setQuickInput] = useState('')
  const [quickInputMode, setQuickInputMode] = useState<FloatingInputMode>('queue')
  const [quickAttachments, setQuickAttachments] = useState<FloatingAttachment[]>([])
  const [carouselPage, setCarouselPage] = useState(0)
  const [carouselPaused, setCarouselPaused] = useState(false)
  const quickFileInputRef = useRef<HTMLInputElement | null>(null)
  const quickInputRef = useRef<HTMLTextAreaElement | null>(null)
  const [approvalBusy, setApprovalBusy] = useState<string | null>(null)
  const [approvalOther, setApprovalOther] = useState('')
  const approvalOtherInputRef = useRef<HTMLInputElement | null>(null)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null)
  const sessionTargetRef = useRef(new FloatingSessionTargetLock())
  const streamedRepliesRef = useRef(new Map<string, string>())
  const eventTime = (item: Session) => item.lastEventAtMs || item.timestamp || 0
  const selectLatest = (mapped: Session[]) => {
    const lockedSessionId = sessionTargetRef.current.lockedSessionId
    if (lockedSessionId) {
      // Keep the target stable while the editor is open. The record itself is
      // still refreshed below by the normal runtime/index polling.
      setSelectedSessionId(lockedSessionId)
      return
    }
    const currentSessionId = sessionTargetRef.current.selectedSessionId
    if (!autoPickSession && currentSessionId && mapped.some((item) => item.id === currentSessionId)) return
    const running = mapped.filter((item) => (item.processIds?.length || 0) > 0)
    const selected = running.find((item) => item.foreground)
      || running.reduce<Session | null>((best, item) => !best || eventTime(item) > eventTime(best) ? item : best, null)
      || mapped[0]
    if (!selected) {
      sessionTargetRef.current.selectAutomatically(null)
      setSelectedSessionId(null)
      return
    }
    setSelectedSessionId(sessionTargetRef.current.selectAutomatically(selected.id))
  }
  const applyRuntimeRecords = (records: RunningCodexSession[]) => {
    const byId = new Map(records.map((record) => [record.sessionId, record]))
    const foreground = records.find((record) => record.foreground)
    const latest = foreground || records.reduce<RunningCodexSession | null>((best, record) => !best || record.lastEventAtMs > best.lastEventAtMs ? record : best, null)
    setItems((current) => current.map((item) => {
      const runtime = byId.get(item.id)
      if (!runtime) {
        return item.processIds?.length
          ? { ...item, status: 'idle', liveState: 'idle', processIds: [], foreground: false }
          : { ...item, foreground: false }
      }
      const nextError = runtime.lastError || item.lastError
      const recovery: RecoveryState = runtime.lastError && classifyCodexFailure(runtime.lastError) === 'insufficient-balance'
        && currentBalanceRef.current?.success === true
        && currentBalanceRef.current.remaining !== undefined
        && currentBalanceRef.current.remaining <= 0
        ? 'paused-balance'
        : runtime.requiresAttention ? 'watching' : item.recovery
      return {
        ...item,
        status: 'active',
        liveState: runtime.state,
        processIds: runtime.processIds,
        statusSource: runtime.statusSource,
        requiresAttention: runtime.requiresAttention,
        lastEventAtMs: runtime.lastEventAtMs || item.lastEventAtMs,
        timestamp: Math.max(item.timestamp, runtime.lastEventAtMs || 0),
        updated: runtime.lastEventAtMs ? relativeUpdated(runtime.lastEventAtMs) : item.updated,
        recovery,
        lastError: nextError,
        lastOutput: runtime.lastOutput || item.lastOutput,
        foreground: runtime.foreground,
      }
    }))
    if (sessionTargetRef.current.lockedSessionId) {
      setSelectedSessionId(sessionTargetRef.current.lockedSessionId)
      return
    }
    const runningIds = new Set(records.map((record) => record.sessionId))
    const currentSessionId = sessionTargetRef.current.selectedSessionId
    if (autoPickSession && latest && (foreground || !currentSessionId || !runningIds.has(currentSessionId))) {
      setSelectedSessionId(sessionTargetRef.current.selectAutomatically(latest.sessionId))
    } else if (!latest) {
      sessionTargetRef.current.selectAutomatically(null)
      setSelectedSessionId(null)
    }
  }
  useEffect(() => {
    const syncLanguage = (event: StorageEvent) => {
      if (event.key !== 'codex-atlas:language' || !event.newValue) return
      try {
        const next = JSON.parse(event.newValue)
        if (next === 'zh' || next === 'en') setLanguage(next)
      } catch {
        // Ignore malformed external storage updates.
      }
    }
    window.addEventListener('storage', syncLanguage)
    return () => window.removeEventListener('storage', syncLanguage)
  }, [])
  useEffect(() => {
    let disposed = false
    const syncModels = () => void getCodexModels().then((models) => {
      if (!disposed && models) setCodexModels(models)
    })
    syncModels()
    const modelTimer = window.setInterval(syncModels, 60_000)
    void getCodexInfo().then((info) => {
      if (disposed || !info) return
      if (info.model && !defaultModel.trim()) setDefaultModel(info.model)
      if (info.reasoningEffort) setDefaultReasoningEffort(info.reasoningEffort)
    })
    return () => { disposed = true; window.clearInterval(modelTimer) }
  }, [])
  useEffect(() => {
    let disposed = false
    const syncBalance = async () => {
      const result = await getCcSwitchProviderBalances()
      if (!disposed) {
        const next = result?.[0] || null
        currentBalanceRef.current = next
        setCurrentBalance(next)
      }
    }
    void syncBalance()
    const timer = window.setInterval(() => void syncBalance(), 30_000)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [])
  useEffect(() => {
    const syncSettings = (event: StorageEvent) => {
      if (!event.key?.startsWith('codex-atlas:')) return
      if (event.key === 'codex-atlas:floatingOpacity') setFloatingOpacity(readStored('floatingOpacity', 100))
      if (event.key === 'codex-atlas:floatingSkin') setFloatingSkin(normalizeFloatingSkin(readStored('floatingSkin', 'classic')))
      if (event.key === 'codex-atlas:floatingSize') setFloatingSize(Math.max(180, Math.min(720, Number(readStored('floatingSize', 252)) || 252)))
      if (event.key === 'codex-atlas:floatingFontScale') {
        const next = Number(readStored('floatingFontScale', 100))
        setFloatingFontScale(Number.isFinite(next) ? Math.max(75, Math.min(160, next)) : 100)
      }
      if (event.key === 'codex-atlas:floatingShowOutput') setShowOutput(readStored('floatingShowOutput', true))
      if (event.key === 'codex-atlas:floatingAutoPickSession') setAutoPickSession(readStored('floatingAutoPickSession', true))
      if (event.key === 'codex-atlas:floatingRightClickMenu') setRightClickMenu(readStored('floatingRightClickMenu', true))
      if (event.key === 'codex-atlas:defaultModel') setDefaultModel(readStored('defaultModel', ''))
      if (event.key === 'codex-atlas:defaultPermission') setDefaultPermission(readStored('defaultPermission', 'Workspace write'))
      if (event.key === 'codex-atlas:defaultReasoningEffort') setDefaultReasoningEffort(readStored('defaultReasoningEffort', 'medium'))
    }
    window.addEventListener('storage', syncSettings)
    return () => window.removeEventListener('storage', syncSettings)
  }, [])
  useEffect(() => {
    const onCommandError = (event: Event) => {
      const detail = (event as CustomEvent<DesktopCommandError>).detail
      if (!detail || !['send_floating_message', 'send_session_input', 'send_terminal_input'].includes(detail.command)) return
      setMessage(detail.error || (language === 'zh' ? '消息提交失败' : 'Message could not be delivered'))
      window.setTimeout(() => setMessage(''), 4500)
    }
    window.addEventListener('codex-atlas:command-error', onCommandError)
    return () => window.removeEventListener('codex-atlas:command-error', onCommandError)
  }, [language])
  useEffect(() => {
    document.documentElement.classList.add('floating-window')
    document.body.classList.add('floating-window')
    document.documentElement.style.setProperty('--floating-size', `${floatingSize}px`)
    document.documentElement.style.setProperty('--floating-font-scale', String(floatingSize / 252 * floatingFontScale / 100))
    return () => {
      document.documentElement.classList.remove('floating-window')
      document.body.classList.remove('floating-window')
      document.documentElement.style.removeProperty('--floating-size')
      document.documentElement.style.removeProperty('--floating-font-scale')
    }
  }, [floatingSize, floatingFontScale])
  useEffect(() => {
    const heartbeat = () => void floatingWindowHeartbeat()
    heartbeat()
    const timer = window.setInterval(heartbeat, 5_000)
    const onVisibilityChange = () => {
      if (!document.hidden) heartbeat()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [])
  useEffect(() => {
    const syncNativeShape = () => void setFloatingWindowShape(floatingSkin)
    syncNativeShape()
    window.addEventListener('resize', syncNativeShape)
    return () => window.removeEventListener('resize', syncNativeShape)
  }, [floatingSkin])
  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null
    let unlistenOutput: (() => void) | null = null
    let unlistenRuntime: (() => void) | null = null
    let unlistenAppServer: (() => void) | null = null
    const syncIndex = () => listCodexSessions().then((records) => {
      if (disposed || !records) return
      const mapped = records.map(mapDesktopSession)
      setItems(mapped)
      selectLatest(mapped)
    })
    const syncRuntime = () => listRunningCodexSessions().then((records) => {
      if (disposed || !records) return
      applyRuntimeRecords(records)
    })
    void syncIndex()
    void syncRuntime()
    const runtimeTimer = window.setInterval(() => void syncRuntime(), 2000)
    const indexTimer = window.setInterval(() => void syncIndex(), 30_000)
    void listenDesktopEvent<RunningCodexSession[]>('codex_runtime', (records) => {
      if (disposed || !Array.isArray(records)) return
      applyRuntimeRecords(records)
    }).then((cleanup) => { unlistenRuntime = cleanup })
    void listenDesktopEvent<DesktopFailureEvent>('codex_failure', (event) => {
      if (disposed) return
      setItems((current) => current.map((item) => {
        if (item.id !== event.sessionId) return item
        const confirmedEmpty = currentBalanceRef.current?.success === true
          && currentBalanceRef.current.remaining !== undefined
          && currentBalanceRef.current.remaining <= 0
        const recovery: RecoveryState = event.action === 'pause-balance'
          ? (confirmedEmpty ? 'paused-balance' : 'watching')
          : event.action === 'stop' ? 'stopped' : event.action === 'continue' ? 'retrying' : 'watching'
        return { ...item, recovery, retryCount: event.attempt, lastError: event.error, lastEventAtMs: Date.now(), timestamp: Date.now(), updated: relativeUpdated(Date.now()) }
      }))
    }).then((cleanup) => { unlisten = cleanup })
    void listenDesktopEvent<DesktopOutputEvent>('codex_output', (event) => {
      if (disposed || !event.sessionId) return
      const output = event.lastOutput || event.line
      if (!output) return
      const now = Date.now()
      setItems((current) => current.map((item) => item.id === event.sessionId
        ? { ...item, lastOutput: output, liveState: 'working', status: 'active', lastEventAtMs: now, timestamp: now, updated: relativeUpdated(now) }
        : item))
    }).then((cleanup) => { unlistenOutput = cleanup })
    void listenDesktopEvent<CodexAppServerEvent>('codex_app_server', (event) => {
      if (disposed) return
      const sessionId = event.params?.threadId
      if (!sessionId) return
      setItems((current) => current.map((item) => item.id === sessionId
        ? applyAppServerSessionPatch(item, event, streamedRepliesRef.current)
        : item))
    }).then((cleanup) => { unlistenAppServer = cleanup })
    return () => {
      disposed = true
      window.clearInterval(runtimeTimer)
      window.clearInterval(indexTimer)
      unlisten?.()
      unlistenOutput?.()
      unlistenRuntime?.()
      unlistenAppServer?.()
    }
  }, [])
  const orderedItems = useMemo(() => [...items]
    .sort((left, right) => Number(right.foreground) - Number(left.foreground) || eventTime(right) - eventTime(left)), [items])
  const selectedIndexInOrder = selectedSessionId
    ? orderedItems.findIndex((item) => item.id === selectedSessionId)
    : -1
  const selectedIndex = selectedIndexInOrder >= 0 ? selectedIndexInOrder : 0
  // Do not silently fall back to another session while a locked target is
  // being refreshed or removed. Showing another session would make a failed
  // submission look as though it was sent to the wrong conversation.
  const selectedItem = selectedSessionId && selectedIndexInOrder < 0
    ? undefined
    : orderedItems[selectedIndex] || orderedItems[0]
  const openQuickInput = (sessionId?: string) => {
    const target = sessionTargetRef.current.openInput(sessionId || selectedItem?.id)
    if (!target) return
    setSelectedSessionId(target)
    setQuickInputOpen(true)
  }
  const closeQuickInput = (clearDraft = true) => {
    sessionTargetRef.current.closeInput()
    setQuickInputOpen(false)
    if (clearDraft) {
      setQuickInput('')
      setQuickAttachments([])
    }
  }
  const actionItem = sessionTargetRef.current.lockedSessionId
    ? orderedItems.find((item) => item.id === sessionTargetRef.current.lockedSessionId) || selectedItem
    : selectedItem
  useEffect(() => {
    const observed = selectedItem?.model?.trim()
    if (!observed) return
    setCodexModels((current) => current.some((model) => model.slug === observed)
      ? current
      : [...current, { slug: observed, displayName: observed, official: false, source: 'current-config' }])
  }, [selectedItem?.model])
  const phase = selectedItem?.liveState?.toLowerCase() || ''
  const outputLower = selectedItem?.lastOutput?.toLowerCase() || ''
  const isWorking = selectedItem?.status === 'active'
    && (phase === 'working' || phase === 'thinking' || phase === 'tool' || outputLower.includes('thinking') || outputLower.includes('running tool') || outputLower.includes('正在思考') || outputLower.includes('执行工具'))
  const state = selectedItem?.recovery === 'paused-balance' || selectedItem?.recovery === 'stopped'
    ? 'blocked'
    : selectedItem?.recovery === 'watching' || selectedItem?.recovery === 'retrying' || selectedItem?.requiresAttention
      ? 'warning'
      : isWorking ? 'working' : selectedItem?.status === 'active' ? 'active' : 'idle'
  const approvalRequest = parseApprovalRequest(selectedItem, language)
  const phaseLabel = approvalRequest
    ? (language === 'zh' ? '需要审批' : 'Approval')
    : selectedItem?.recovery === 'paused-balance'
    ? (language === 'zh' ? '余额不足' : 'Balance low')
    : selectedItem?.recovery === 'stopped'
      ? (language === 'zh' ? '已停止' : 'Stopped')
      : outputLower.startsWith('thinking') || outputLower.includes('正在思考')
        ? (language === 'zh' ? '思考中' : 'Thinking')
        : outputLower.startsWith('running tool') || outputLower.includes('执行工具')
          ? (language === 'zh' ? '执行工具' : 'Tool')
          : phase === 'working'
            ? (language === 'zh' ? '执行中' : 'Working')
            : phase === 'waiting' || selectedItem?.recovery === 'watching'
              ? (language === 'zh' ? '等待输入' : 'Waiting')
              : phase === 'failed'
                ? (language === 'zh' ? '异常' : 'Error')
                : selectedItem?.status === 'active'
                  ? (language === 'zh' ? '空闲' : 'Idle')
                  : (language === 'zh' ? '已退出' : 'Exited')
  const statusLabel = selectedItem ? `${phaseLabel} · ${selectedItem.title}` : t('floatingIdle')
  const displayOutput = selectedItem?.lastOutput || selectedItem?.lastError || (language === 'zh' ? '暂无输出' : 'No output yet')
  const replyPages = useMemo(() => splitFloatingReply(displayOutput), [displayOutput])
  const outputPhase = selectedItem?.liveState?.toLowerCase() || ''
  const isIdleReply = Boolean(selectedItem
    && !approvalRequest
    && !isWorking
    && !selectedItem.requiresAttention
    && !['waiting', 'failed', 'blocked'].includes(outputPhase)
    && (outputPhase === 'idle' || outputPhase === 'completed' || selectedItem.status === 'active'))
  const shouldCarouselReply = isIdleReply && replyPages.length > 1 && !quickInputOpen
  const prefersReducedMotion = useMemo(() => {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches
    } catch {
      return false
    }
  }, [])
  useEffect(() => {
    setCarouselPage(0)
    setCarouselPaused(false)
  }, [selectedItem?.id, displayOutput])
  useEffect(() => {
    if (!shouldCarouselReply || carouselPaused || prefersReducedMotion) return
    const timer = window.setInterval(() => {
      setCarouselPage((current) => (current + 1) % replyPages.length)
    }, 6500)
    return () => window.clearInterval(timer)
  }, [carouselPaused, prefersReducedMotion, replyPages.length, shouldCarouselReply])
  const visibleReply = replyPages[carouselPage] || displayOutput
  const dragLabel = language === 'zh' ? '拖动悬浮组件' : 'Drag widget'
  const sessionLabel = selectedItem
    ? `${selectedIndex + 1}/${orderedItems.length} · ${selectedItem.folder}`
    : '0/0'
  const switchSession = (direction: -1 | 1) => {
    if (orderedItems.length < 2) return
    const currentIndex = orderedItems.findIndex((item) => item.id === sessionTargetRef.current.selectedSessionId)
    const index = currentIndex < 0 ? 0 : (currentIndex + direction + orderedItems.length) % orderedItems.length
    const next = orderedItems[index]
    setSelectedSessionId(sessionTargetRef.current.selectManually(next.id))
  }
  const activateSelected = () => {
    if (!actionItem) return
    setActionBusy('activate')
    void resumeCodexSession(actionItem.id).then((ok) => {
      setMessage(ok
        ? (language === 'zh' ? '会话已激活' : 'Session activated')
        : (language === 'zh' ? '无法激活会话' : 'Could not activate session'))
      setActionBusy(null)
      window.setTimeout(() => setMessage(''), 2200)
    })
  }
  const inputContinueSelected = () => {
    if (!actionItem) return
    setActionBusy('input')
    // Queue through Codex itself so the floating widget never steals focus
    // from the user's active application.
    void sendCodexContinue(actionItem.id, false).then((ok) => {
      setMessage(ok
        ? (language === 'zh' ? '已在后台提交“继续”' : 'Continue queued in the background')
        : (language === 'zh' ? '无法提交到该会话' : 'Could not queue the message'))
      setActionBusy(null)
      window.setTimeout(() => setMessage(''), 2200)
    })
  }
  const submitQuickInput = (event?: React.FormEvent) => {
    event?.preventDefault()
    const value = quickInput.trim()
    const targetSessionId = sessionTargetRef.current.submissionTarget(selectedItem?.id) || ''
    if (!targetSessionId || (!value && quickAttachments.length === 0) || actionBusy || modelBusy) return
    setActionBusy('input')
    void sendFloatingMessage(targetSessionId, value, quickInputMode, quickAttachments).then((ok) => {
      setMessage(ok
        ? (quickInputMode === 'queue'
          ? (language === 'zh' ? '消息已加入队列' : 'Message added to queue')
          : (language === 'zh' ? '已打断思考并发送' : 'Thinking interrupted and message sent'))
        : (language === 'zh' ? '消息提交失败，请检查会话终端' : 'Message could not be delivered; check the session terminal'))
      if (ok) {
        closeQuickInput()
      }
      setActionBusy(null)
      window.setTimeout(() => setMessage(''), 2200)
    }).catch(() => {
      setActionBusy(null)
      setMessage(language === 'zh' ? '消息提交失败' : 'Message could not be queued')
      window.setTimeout(() => setMessage(''), 2200)
    })
  }
  const addQuickFiles = (files: FileList | null) => {
    if (!files) return
    const next = Array.from(files).map((file) => {
      const candidate = file as File & { path?: string }
      const path = candidate.path || file.webkitRelativePath || ''
      return { kind: file.type.startsWith('image/') ? 'image' : 'file', name: file.name, path } satisfies FloatingAttachment
    })
    setQuickAttachments((current) => [...current, ...next].slice(0, 8))
  }
  const saveRuntimeSetting = (nextModel: string, nextPermission: string, nextReasoningEffort = defaultReasoningEffort) => {
    if (!nextModel.trim()) {
      setMessage(language === 'zh' ? '尚未读取 Codex 模型' : 'Codex model is not available yet')
      window.setTimeout(() => setMessage(''), 2200)
      return
    }
    setModelBusy(true)
    void setCodexDefaults(nextModel, nextPermission, nextReasoningEffort).then((info) => {
      if (info?.model) {
        setDefaultModel(info.model)
        setDefaultPermission(nextPermission)
        setDefaultReasoningEffort(info.reasoningEffort || nextReasoningEffort)
        writeStored('defaultModel', info.model)
        writeStored('defaultPermission', nextPermission)
        writeStored('defaultReasoningEffort', info.reasoningEffort || nextReasoningEffort)
        setMessage(language === 'zh' ? `已切换模型：${info.model}` : `Model switched to ${info.model}`)
      } else {
        setMessage(language === 'zh' ? '模型设置失败' : 'Model setting failed')
      }
      setModelBusy(false)
      window.setTimeout(() => setMessage(''), 2200)
    }).catch(() => {
      setModelBusy(false)
      setMessage(language === 'zh' ? '模型设置失败' : 'Model setting failed')
      window.setTimeout(() => setMessage(''), 2200)
    })
  }
  const respondToApproval = (option: ApprovalOption) => {
    if (!selectedItem || approvalBusy) return
    if (option.kind === 'other') {
      const focusOtherInput = () => window.setTimeout(() => approvalOtherInputRef.current?.focus(), 60)
      if (option.value === '__other__') {
        focusOtherInput()
        setMessage(language === 'zh' ? '请输入补充内容后提交' : 'Enter the additional instruction, then submit')
        window.setTimeout(() => setMessage(''), 2200)
        return
      }
      setApprovalBusy(option.value)
      void sendTerminalInput(selectedItem.id, option.value).then((ok) => {
        setMessage(ok
          ? (language === 'zh' ? '已选择其他，请输入补充内容' : 'Other selected; enter the additional instruction')
          : (language === 'zh' ? '审批提交失败' : 'Approval could not be submitted'))
        setApprovalBusy(null)
        if (ok) focusOtherInput()
        window.setTimeout(() => setMessage(''), 2200)
      }).catch(() => {
        setApprovalBusy(null)
        setMessage(language === 'zh' ? '审批提交失败' : 'Approval could not be submitted')
        window.setTimeout(() => setMessage(''), 2200)
      })
      return
    }
    setApprovalBusy(option.value)
    void sendTerminalInput(selectedItem.id, option.value).then((ok) => {
      setMessage(ok
        ? (language === 'zh' ? `已提交：${option.label}` : `Submitted: ${option.label}`)
        : (language === 'zh' ? '审批提交失败' : 'Approval could not be submitted'))
      if (ok) setItems((current) => current.map((item) => item.id === selectedItem.id ? { ...item, requiresAttention: false, liveState: 'working', lastOutput: '' } : item))
      setApprovalBusy(null)
      window.setTimeout(() => setMessage(''), 2200)
    }).catch(() => {
      setApprovalBusy(null)
      setMessage(language === 'zh' ? '审批提交失败' : 'Approval could not be submitted')
      window.setTimeout(() => setMessage(''), 2200)
    })
  }
  const respondWithCustomApproval = () => {
    const value = approvalOther.trim()
    if (!value) return
    respondToApproval({ label: value, value })
    setApprovalOther('')
  }
  const openContextMenu = (event: React.MouseEvent<HTMLElement>) => {
    if (!rightClickMenu) return
    event.preventDefault()
    setContextMenu({ x: Math.min(event.clientX, Math.max(8, window.innerWidth - 246)), y: Math.min(event.clientY, Math.max(8, window.innerHeight - 360)) })
  }
  useEffect(() => {
    if (!contextMenu) return
    const close = () => setContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('blur', close)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('blur', close)
    }
  }, [contextMenu])
  useEffect(() => {
    if (!quickInputOpen) sessionTargetRef.current.closeInput()
  }, [quickInputOpen])
  useEffect(() => {
    if (quickInputOpen) quickInputRef.current?.focus()
  }, [quickInputOpen])
  const beginFloatingDrag = (event: React.MouseEvent<HTMLElement>) => {
    if (event.button !== 0) return
    void startDesktopWindowDrag()
  }
  const beginFloatingDragWithKeyboard = (event: React.KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Enter' || event.key === ' ') void startDesktopWindowDrag()
  }
  return <main className={`desktop-tv-widget ${state} skin-${floatingSkin}`} style={{ opacity: floatingOpacity / 100, '--floating-font-scale': String(floatingSize / 252 * floatingFontScale / 100) } as React.CSSProperties} aria-label={`${t('statusPrefix')}: ${statusLabel}`} title={statusLabel} onContextMenu={openContextMenu}>
    <div className="desktop-tv-shell">
       <div className="desktop-tv-top">
        <span className="desktop-tv-brand">ATLAS <small className="desktop-tv-version">v{ATLAS_VERSION}</small></span>
        <span className="desktop-tv-top-right"><span className="desktop-tv-top-balance" title={currentBalance?.name || (language === 'zh' ? '当前供应商余额' : 'Current provider balance')}>{currentBalance?.remaining === undefined ? '--' : currentBalance.remaining.toFixed(2)}</span><span className="desktop-tv-leds"><i className="red" /><i className="yellow" /><i className="green" /></span></span>
      </div>
      <div className="desktop-tv-body">
        <div className="desktop-tv-screen-frame">
          <div className={`desktop-tv-screen ${quickInputOpen ? 'is-inputting' : ''}`} onClick={() => { if (!approvalRequest && selectedItem) openQuickInput() }} title={selectedItem?.title || t('floatingIdle')}>
            <div className="desktop-tv-scanlines" aria-hidden="true" />
            <div className="desktop-tv-screen-head"><span><i className="desktop-tv-status-dot" />{phaseLabel}</span><b>{sessionLabel}</b></div>
              <div className="desktop-tv-session-meta" aria-hidden="true"><strong>{selectedItem?.title || (language === 'zh' ? '未选择会话' : 'No session selected')}</strong><small>{selectedItem ? `${selectedItem.folder} · ${selectedItem.model}` : ''}</small></div>
             {approvalRequest ? <div className="desktop-tv-approval" role="status" aria-live="polite" onClick={(event) => event.stopPropagation()}><strong>{language === 'zh' ? '需要审批' : 'Approval needed'}</strong><p>{approvalRequest.prompt}</p><div className="desktop-tv-approval-options">{approvalRequest.options.map((option) => <button key={`${option.value}:${option.label}`} onClick={() => respondToApproval(option)} disabled={approvalBusy !== null || !selectedItem}>{approvalBusy === option.value ? <LoaderCircle className="spin" size={10} /> : null}{option.label}</button>)}</div><div className="desktop-tv-approval-other"><input ref={approvalOtherInputRef} value={approvalOther} onChange={(event) => setApprovalOther(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') respondWithCustomApproval() }} placeholder={t('otherChoicePlaceholder')} disabled={approvalBusy !== null || !selectedItem} /><button onClick={respondWithCustomApproval} disabled={!approvalOther.trim() || approvalBusy !== null || !selectedItem}>{t('otherChoice')} · {t('submitOtherChoice')}</button></div></div> : quickInputOpen ? <form className="desktop-tv-quick-input" onSubmit={submitQuickInput} onClick={(event) => event.stopPropagation()}>
                <div className="desktop-tv-quick-modes" role="group" aria-label={language === 'zh' ? '发送方式' : 'Send mode'}><button type="button" className={quickInputMode === 'queue' ? 'selected' : ''} onClick={() => setQuickInputMode('queue')}>{language === 'zh' ? '排队' : 'Queue'}</button><button type="button" className={quickInputMode === 'interrupt' ? 'selected' : ''} onClick={() => setQuickInputMode('interrupt')}>{language === 'zh' ? '打断' : 'Interrupt'}</button></div>
                <textarea ref={quickInputRef} value={quickInput} onChange={(event) => setQuickInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') { closeQuickInput(); return } if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} placeholder={t('quickInputPlaceholder')} disabled={!actionItem || actionBusy !== null} aria-label={t('quickInputPlaceholder')} rows={2} />
                <div className="desktop-tv-quick-attachments">{quickAttachments.map((attachment, index) => <span key={`${attachment.name}:${index}`} title={attachment.path || attachment.name}><i>{attachment.kind === 'image' ? <ImageIcon size={10} /> : <FileText size={10} />}</i>{attachment.name}<button type="button" onClick={() => setQuickAttachments((current) => current.filter((_, currentIndex) => currentIndex !== index))} aria-label={language === 'zh' ? `移除 ${attachment.name}` : `Remove ${attachment.name}`}><X size={10} /></button></span>)}</div>
                <div className="desktop-tv-quick-tools"><label className="desktop-tv-file-button" title={language === 'zh' ? '添加文档或图片' : 'Attach a document or image'}><Paperclip size={12} /><input ref={quickFileInputRef} type="file" multiple accept="image/*,.txt,.md,.pdf,.json,.csv,.doc,.docx,.xls,.xlsx" onChange={(event) => { addQuickFiles(event.target.files); event.currentTarget.value = '' }} /></label><span>{quickInputMode === 'queue' ? (language === 'zh' ? '当前轮结束后发送' : 'Send after current response') : (language === 'zh' ? '立即打断并提交' : 'Interrupt and submit')}</span><button type="submit" disabled={(!quickInput.trim() && quickAttachments.length === 0) || !actionItem || actionBusy !== null} aria-label={t('quickInputSubmit')} title={t('quickInputSubmit')}>{actionBusy === 'input' ? <LoaderCircle className="spin" size={12} /> : <ArrowUpRight size={12} />}</button></div>
              </form> : showOutput && <div
                className={`desktop-tv-output${shouldCarouselReply ? ' is-carousel' : ''}`}
                aria-live={isWorking ? 'polite' : 'off'}
                onMouseEnter={() => setCarouselPaused(true)}
                onMouseLeave={() => setCarouselPaused(false)}
                onFocus={() => setCarouselPaused(true)}
                onBlur={() => setCarouselPaused(false)}
              >
                {shouldCarouselReply
                  ? <div key={`${selectedItem?.id}:${carouselPage}`} className="desktop-tv-output-page">{visibleReply}</div>
                  : visibleReply}
              </div>}
          </div>
        </div>
      </div>
      <div className="desktop-tv-footer">
        <button className="desktop-tv-action switch" aria-label={language === 'zh' ? '上一个会话' : 'Previous session'} title={language === 'zh' ? '上一个会话' : 'Previous session'} onClick={() => switchSession(-1)} disabled={orderedItems.length < 2}><ChevronLeft size={17} /></button>
        <div className="desktop-tv-drag-handle" data-tauri-drag-region role="button" tabIndex={0} aria-label={dragLabel} title={dragLabel} onMouseDown={beginFloatingDrag} onKeyDown={beginFloatingDragWithKeyboard}><GripVertical size={14} /><span>{sessionLabel}</span></div>
        <button className="desktop-tv-action resume" aria-label={t('floatingResume')} title={t('floatingResume')} onClick={activateSelected} disabled={!selectedItem || actionBusy !== null}>{actionBusy === 'activate' ? <LoaderCircle className="spin" size={13} /> : <Play size={13} fill="currentColor" />}</button>
        <button className="desktop-tv-action input" aria-label={t('floatingInputContinue')} title={t('floatingInputContinue')} onClick={inputContinueSelected} disabled={!selectedItem || actionBusy !== null}>{actionBusy === 'input' ? <LoaderCircle className="spin" size={13} /> : <TerminalSquare size={13} />}</button>
        <button className="desktop-tv-action switch" aria-label={language === 'zh' ? '下一个会话' : 'Next session'} title={language === 'zh' ? '下一个会话' : 'Next session'} onClick={() => switchSession(1)} disabled={orderedItems.length < 2}><ChevronRight size={17} /></button>
      </div>
    </div>
    {message && <span className="desktop-tv-message" role="status">{message}</span>}
    {contextMenu && <div className="desktop-tv-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} role="menu" onClick={(event) => event.stopPropagation()}>
      <button role="menuitem" onClick={() => { setContextMenu(null); activateSelected() }} disabled={!selectedItem || actionBusy !== null}><Play size={13} fill="currentColor" />{t('floatingResume')}</button>
      <button role="menuitem" onClick={() => { setContextMenu(null); inputContinueSelected() }} disabled={!selectedItem || actionBusy !== null}><TerminalSquare size={13} />{t('floatingInputContinue')}</button>
       <button role="menuitem" onClick={() => { setContextMenu(null); openQuickInput() }} disabled={!selectedItem}><Plus size={13} />{t('quickInputSubmit')}</button>
      <div className="desktop-tv-context-divider" />
      <span className="desktop-tv-context-label">{t('modelMenu')}</span>
      {codexModels.length === 0 && <span className="desktop-tv-context-empty">{language === 'zh' ? '正在读取 Codex 模型…' : 'Reading Codex models…'}</span>}
      {codexModels.map((model) => <button key={model.slug} role="menuitem" className="desktop-tv-context-choice" onClick={() => { setContextMenu(null); saveRuntimeSetting(model.slug, defaultPermission) }} disabled={modelBusy}><span><Check size={12} className={defaultModel === model.slug ? 'is-selected' : 'is-hidden'} />{model.displayName}</span><small>{model.official ? t('officialModel') : model.source === 'provider-api' ? (language === 'zh' ? '中转站' : 'Provider') : model.source === 'cc-switch' ? 'CC Switch' : t('configuredModel')}</small></button>)}
      <div className="desktop-tv-context-divider" />
      <span className="desktop-tv-context-label">{language === 'zh' ? '思考程度' : 'Reasoning effort'}</span>
      {(['low', 'medium', 'high', 'xhigh'] as const).map((effort) => <button key={effort} role="menuitem" className="desktop-tv-context-choice" onClick={() => { setContextMenu(null); saveRuntimeSetting(defaultModel || selectedItem?.model || codexModels[0]?.slug || '', defaultPermission, effort) }} disabled={modelBusy}><span><Check size={12} className={defaultReasoningEffort === effort ? 'is-selected' : 'is-hidden'} />{language === 'zh' ? (effort === 'low' ? '低' : effort === 'medium' ? '中' : effort === 'high' ? '高' : '极高') : (effort === 'xhigh' ? 'Extra high' : effort[0].toUpperCase() + effort.slice(1))}</span></button>)}
      <div className="desktop-tv-context-divider" />
      <span className="desktop-tv-context-label">{t('permissionMenu')}</span>
      {['Workspace write', 'Read only', 'Full access'].map((permission) => <button key={permission} role="menuitem" className="desktop-tv-context-choice" onClick={() => { setContextMenu(null); saveRuntimeSetting(defaultModel || selectedItem?.model || codexModels[0]?.slug || '', permission) }} disabled={modelBusy}><span><Check size={12} className={defaultPermission === permission ? 'is-selected' : 'is-hidden'} />{language === 'zh' ? (permission === 'Workspace write' ? '工作区写入' : permission === 'Read only' ? '只读' : '完全访问') : permission}</span></button>)}
      <div className="desktop-tv-context-divider" />
      <button role="menuitem" onClick={() => { setContextMenu(null); void showMainDesktopWindow() }}><ExternalLink size={13} />{t('floatingOpen')}</button>
    </div>}
  </main>
}

const showPrototypes = new URLSearchParams(window.location.search).get('view') === 'prototypes'
const showFloating = new URLSearchParams(window.location.search).get('view') === 'floating'
// Apply the floating surface before React mounts so a slow/transparent WebView
// cannot flash or remain on its platform default black background.
if (showFloating) {
  document.documentElement.classList.add('floating-window')
  document.documentElement.classList.add(navigator.userAgent.includes('Windows') ? 'floating-window-windows' : 'floating-window-transparent')
  document.body.classList.add('floating-window')
}
document.title = showPrototypes ? 'Codex Atlas · Prototypes' : showFloating ? 'Atlas Mini' : 'Codex Atlas'
createRoot(document.getElementById('root')!).render(<React.StrictMode>{showPrototypes ? <PrototypeGallery /> : showFloating ? <FloatingMini /> : <App />}</React.StrictMode>)
