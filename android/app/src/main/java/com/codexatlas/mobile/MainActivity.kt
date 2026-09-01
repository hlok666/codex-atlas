package com.codexatlas.mobile

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private var pairingFromIntent by mutableStateOf("")
    private var sessionIdFromIntent by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumePairing(intent)
        consumeSessionIntent(intent)
        setContent { AtlasTheme { AtlasMobileApp(pairingFromIntent, sessionIdFromIntent) } }
    }

    override fun onStart() {
        super.onStart()
        AtlasSyncService.setActivityVisible(this, true)
    }

    override fun onStop() {
        AtlasSyncService.setActivityVisible(this, false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePairing(intent)
        consumeSessionIntent(intent)
    }

    private fun consumePairing(intent: Intent?) {
        val uri = intent?.data ?: return
        val raw = uri.toString()
        if (parsePairing(raw) == null) return
        pairingFromIntent = raw
        parsePairing(raw)?.let { pairing ->
            BridgePreferences.saveDevice(
                this,
                AtlasDeviceProfile(
                    id = pairing.deviceId,
                    name = pairing.deviceName.ifBlank { "Codex Atlas" },
                    kind = pairing.deviceKind.ifBlank { "desktop" },
                    lanUrl = pairing.lanUrl,
                    tunnelUrl = pairing.tunnelUrl,
                    token = pairing.token,
                    preferTunnel = pairing.preferTunnel,
                    route = if (pairing.preferTunnel) "server" else "auto",
                ),
            )
        }
    }

    private fun consumeSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        if (sessionId.isNotBlank()) sessionIdFromIntent = sessionId
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.codexatlas.mobile.extra.SESSION_ID"

        fun storedPairing(context: Context): String {
            return BridgePreferences.selectedDevice(context)?.let(::pairingForProfile).orEmpty()
        }

        fun pairingForProfile(profile: AtlasDeviceProfile): String {
            val lan = profile.lanUrl.trim()
            val tunnel = profile.tunnelUrl.trim()
            val token = profile.token.trim()
            if (lan.isBlank() || token.isBlank()) return ""
            return Uri.Builder()
                .scheme("codex-atlas")
                .authority("connect")
                .appendQueryParameter("lan", lan)
                .apply { if (tunnel.isNotBlank()) appendQueryParameter("tunnel", tunnel) }
                .appendQueryParameter("token", token)
                .appendQueryParameter("deviceId", profile.id)
                .appendQueryParameter("deviceName", profile.name)
                .appendQueryParameter("deviceKind", profile.kind)
                .appendQueryParameter("preferTunnel", if (profile.preferTunnel) "1" else "0")
                .build()
                .toString()
        }

        fun parsePairing(raw: String): PairingDetails? {
            val cleaned = raw.trim().removePrefix("\uFEFF").trim()
            // Desktop builds before 0.1.4 emitted `connect&lan=...` without
            // the query marker. Accept those links so users do not need to
            // regenerate a pairing code after updating the app.
            val legacyPrefix = "codex-atlas://connect&"
            val normalized = if (cleaned.startsWith(legacyPrefix, ignoreCase = true)) {
                "codex-atlas://connect?${cleaned.substring(legacyPrefix.length)}"
            } else {
                cleaned
            }
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            if (!uri.scheme.equals("codex-atlas", ignoreCase = true) || !uri.host.equals("connect", ignoreCase = true)) return null
            val lan = (uri.getQueryParameter("lan") ?: uri.getQueryParameter("url"))?.trim().orEmpty()
            val tunnel = uri.getQueryParameter("tunnel")?.trim().orEmpty()
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (lan.isBlank() || token.isBlank()) return null
            val deviceId = uri.getQueryParameter("deviceId")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "desktop-${Integer.toUnsignedString((lan + "|" + tunnel).hashCode(), 16)}"
            val deviceName = uri.getQueryParameter("deviceName")?.trim().orEmpty()
            val deviceKind = uri.getQueryParameter("deviceKind")?.trim().orEmpty()
            val preferTunnel = uri.getQueryParameter("preferTunnel").equals("1") ||
                uri.getQueryParameter("preferTunnel").equals("true", ignoreCase = true)
            return PairingDetails(lan, tunnel, token, preferTunnel, deviceId, deviceName, deviceKind)
        }
    }
}

data class PairingDetails(
    val lanUrl: String,
    val tunnelUrl: String,
    val token: String,
    val preferTunnel: Boolean,
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceKind: String = "desktop",
)

enum class ConnectionRoute(val key: String) {
    Auto("auto"),
    Lan("lan"),
    Server("server");

    companion object {
        fun fromKey(value: String): ConnectionRoute = entries.firstOrNull { it.key == value } ?: Auto
    }
}

private enum class MobilePage {
    Home,
    Conversation,
    Queue,
    Settings,
}

fun primaryBridgeUrl(details: PairingDetails, route: ConnectionRoute): String = when (route) {
    ConnectionRoute.Auto -> if (details.preferTunnel) details.tunnelUrl else details.lanUrl
    ConnectionRoute.Lan -> details.lanUrl
    ConnectionRoute.Server -> details.tunnelUrl
}

fun fallbackBridgeUrl(details: PairingDetails, route: ConnectionRoute): String = when (route) {
    ConnectionRoute.Auto -> if (details.preferTunnel) details.lanUrl else details.tunnelUrl
    ConnectionRoute.Lan, ConnectionRoute.Server -> ""
}

private fun mergeAtlasMessages(current: List<AtlasMessage>, incoming: List<AtlasMessage>): List<AtlasMessage> {
    val acknowledgedUserMessages = incoming.filter { item ->
        item.role.equals("user", ignoreCase = true) &&
            item.kind.lowercase(Locale.ROOT) !in setOf("queued", "sending", "sent", "failed")
    }
    val merged = linkedMapOf<String, AtlasMessage>()
    (current + incoming)
        .filter { it.id.isNotBlank() || it.text.isNotBlank() }
        .filterNot { item ->
            item.kind.lowercase(Locale.ROOT) in setOf("queued", "sending", "sent") &&
                acknowledgedUserMessages.any { remote ->
                    remote.text.trim() == item.text.trim() &&
                        kotlin.math.abs(remote.timestampMs - item.timestampMs) <= 120_000L
                }
        }
        .forEach { item ->
            val key = item.callId?.takeIf { it.isNotBlank() }?.let { "call:$it" }
                ?: item.id.ifBlank { "${item.timestampMs}:${item.role}:${item.text}" }
            val previous = merged[key]
            merged[key] = if (previous != null && item.callId?.isNotBlank() == true) {
                item.copy(
                    text = item.text.ifBlank { previous.text },
                    toolStatus = item.toolStatus ?: previous.toolStatus,
                    toolDetail = item.toolDetail ?: previous.toolDetail,
                    turnId = item.turnId ?: previous.turnId,
                    approvalOptions = if (item.approvalOptions.isNotEmpty()) item.approvalOptions else previous.approvalOptions,
                    seq = maxOf(item.seq, previous.seq),
                    seqStart = minOfNonZero(item.seqStart, previous.seqStart),
                    seqEnd = maxOf(item.seqEnd, previous.seqEnd),
                )
            } else {
                item
            }
        }
    return merged.values
        .sortedWith(
            compareBy<AtlasMessage> { it.seq == 0L }
                .thenBy { if (it.seq > 0) it.seq else it.timestampMs }
                .thenBy { it.timestampMs },
        )
}

private fun minOfNonZero(first: Long, second: Long): Long = when {
    first == 0L -> second
    second == 0L -> first
    else -> minOf(first, second)
}

private sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Connected(val title: String) : ConnectionState
    data class Reconnecting(val message: String = "") : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

private fun connectionFailureMessage(error: Throwable, chinese: Boolean): String {
    val detail = error.message.orEmpty().lowercase(Locale.ROOT)
    return when {
        "401" in detail || "unauthorized" in detail -> if (chinese) "配对凭证已失效" else "Pairing credentials expired"
        "403" in detail || "forbidden" in detail -> if (chinese) "连接被服务器拒绝" else "Connection rejected by server"
        "404" in detail || "not found" in detail -> if (chinese) "设备服务未就绪" else "Device service is not ready"
        "502" in detail || "503" in detail -> if (chinese) "服务器通道暂不可用" else "Server route is unavailable"
        "timeout" in detail || "timed out" in detail -> if (chinese) "连接超时，请重试" else "Connection timed out"
        "failed to connect" in detail || "connection refused" in detail || "unable to resolve" in detail ->
            if (chinese) "无法连接设备" else "Could not reach the device"
        else -> if (chinese) "连接失败，请重试" else "Connection failed; try again"
    }
}

private fun reconcileLocalDeliveryStates(
    messagesBySession: Map<String, List<AtlasMessage>>,
    queuedMessages: List<QueuedAtlasMessage>,
): Map<String, List<AtlasMessage>> {
    val queueById = queuedMessages.associateBy(QueuedAtlasMessage::id)
    return messagesBySession.mapValues { (_, messages) ->
        messages.map messageLoop@ { item ->
            val queueId = item.id.takeIf { it.startsWith("queued-") }?.removePrefix("queued-")
                ?: return@messageLoop item
            val queueItem = queueById[queueId]
            val nextKind = when {
                queueItem == null && item.kind.equals("failed", ignoreCase = true) -> "failed"
                queueItem == null -> "sent"
                queueItem.state == AtlasQueueItemState.Sending.key -> "sending"
                queueItem.state == AtlasQueueItemState.Failed.key -> "failed"
                else -> "queued"
            }
            if (item.kind == nextKind) item else item.copy(kind = nextKind)
        }
    }
}

private sealed interface MessageDeliveryResult {
    data object Sent : MessageDeliveryResult
    data object Deferred : MessageDeliveryResult
    data class Failed(val message: String) : MessageDeliveryResult
}

private val AtlasTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
)

@Composable
private fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF2F7C3B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE5F3E7),
            onPrimaryContainer = Color(0xFF17351D),
            secondary = Color(0xFF6C816F),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEDF2ED),
            onSecondaryContainer = Color(0xFF26332A),
            tertiary = Color(0xFF8A6A2A),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFF2D8),
            onTertiaryContainer = Color(0xFF4D3A16),
            background = Color(0xFFFCFDFC),
            onBackground = Color(0xFF1F2A22),
            surface = Color.White,
            onSurface = Color(0xFF1F2A22),
            surfaceVariant = Color(0xFFF3F6F3),
            onSurfaceVariant = Color(0xFF68736B),
            outline = Color(0xFFD3DAD3),
            outlineVariant = Color(0xFFE6EAE6),
            error = Color(0xFFB44A45),
            onError = Color.White,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
        ),
        typography = AtlasTypography,
        content = content,
    )
}

private val AtlasControlShape = RoundedCornerShape(8.dp)

@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AtlasControlShape,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1F2A22),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFE8ECE8),
            disabledContentColor = Color(0xFF9AA39B),
        ),
        content = content,
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AtlasControlShape,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color(0xFF26332A),
            disabledContentColor = Color(0xFF9AA39B),
        ),
        content = content,
    )
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = AtlasControlShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color(0xFF2F7C3B),
            disabledContentColor = Color(0xFF9AA39B),
        ),
        content = content,
    )
}

@Composable
private fun AtlasMobileApp(initialPairing: String, initialSessionId: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zh = remember { Locale.getDefault().language.startsWith("zh") }
    var pairing by remember(initialPairing) { mutableStateOf(initialPairing.ifBlank { MainActivity.storedPairing(context) }) }
    var deviceProfiles by remember { mutableStateOf(BridgePreferences.devices(context)) }
    var selectedDeviceId by remember { mutableStateOf(BridgePreferences.selectedDeviceId(context)) }
    var deviceManagerVisible by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
    var snapshot by remember { mutableStateOf(BridgePreferences.cachedSnapshot(context)) }
    var sessions by remember { mutableStateOf<List<AtlasSession>>(emptyList()) }
    var selectedSessionId by remember(initialSessionId) { mutableStateOf(initialSessionId) }
    var mobilePage by remember { mutableStateOf(if (initialSessionId.isNotBlank()) MobilePage.Conversation else MobilePage.Home) }
    var syncCursorMs by remember { mutableStateOf(BridgePreferences.syncCursor(context)) }
    var syncEpoch by remember { mutableStateOf(BridgePreferences.syncEpoch(context)) }
    var syncAfterSeq by remember { mutableStateOf(BridgePreferences.syncSeq(context)) }
    // Keep the timeline scoped by session. A single mutable list allowed a
    // slow request for the previous session to overwrite the newly selected
    // conversation, which made every mobile conversation appear identical.
    var messagesBySession by remember { mutableStateOf<Map<String, List<AtlasMessage>>>(emptyMap()) }
    var messageRequestToken by remember { mutableStateOf(0L) }
    var connectionRequestToken by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var queuedMessageCount by remember { mutableStateOf(AtlasMessageQueue.count(context)) }
    var queuedMessages by remember { mutableStateOf(AtlasMessageQueue.items(context)) }
    var queueControl by remember { mutableStateOf(AtlasMessageQueue.control(context)) }
    var queueVisible by remember { mutableStateOf(false) }
    var sessionQuery by remember { mutableStateOf("") }
    var messageBusy by remember { mutableStateOf(false) }
    var messageError by remember { mutableStateOf<String?>(null) }
    var createVisible by remember { mutableStateOf(false) }
    var createCwd by remember { mutableStateOf("") }
    var createPrompt by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AtlasUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val updateManager = remember(context) { AppUpdateManager(context) }
    val scrollState = rememberScrollState()
    val messageScrollState = rememberScrollState()
    var scannerVisible by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var connectionRoute by remember { mutableStateOf(ConnectionRoute.fromKey(BridgePreferences.connectionRoute(context))) }
    var audioPermissionDenied by remember { mutableStateOf(false) }
    var pendingVoiceMode by remember { mutableStateOf(false) }
    var voiceSnapshot by remember { mutableStateOf(VoiceInputSnapshot()) }
    var pendingVoiceSend by remember { mutableStateOf<List<String>>(emptyList()) }
    var readRepliesAloud by remember { mutableStateOf(BridgePreferences.readRepliesAloud(context)) }
    val voiceController = remember(context, zh) {
        VoiceInputController(context, zh) { next -> voiceSnapshot = next }
    }
    val speechOutput = remember(context, zh) { SpeechOutputController(context, zh) }
    DisposableEffect(voiceController) {
        voiceController.setContinuousTranscriptListener { text ->
            pendingVoiceSend = pendingVoiceSend + text
        }
        onDispose {
            voiceController.setContinuousTranscriptListener(null)
            voiceController.destroy()
            speechOutput.destroy()
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPermissionDenied = !granted
        if (granted) voiceController.start(message, pendingVoiceMode)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) scannerVisible = true
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    BackHandler(enabled = mobilePage != MobilePage.Home) { mobilePage = MobilePage.Home }

    fun checkForUpdate() {
        if (updateBusy) return
        updateBusy = true
        updateError = null
        scope.launch {
            updateManager.check()
                .onSuccess { availableUpdate = it }
                .onFailure { error -> updateError = error.message ?: if (zh) "检查更新失败" else "Update check failed" }
            updateBusy = false
        }
    }

    fun downloadAndInstallUpdate() {
        val update = availableUpdate ?: return
        if (!update.available || update.apkUrl.isNullOrBlank() || updateBusy) return
        updateBusy = true
        updateProgress = 0
        updateError = null
        scope.launch {
            updateManager.download(update) { progress ->
                scope.launch(Dispatchers.Main) { updateProgress = progress }
            }.onSuccess { file ->
                when (val result = updateManager.openInstaller(file)) {
                    InstallerResult.Opened -> Unit
                    InstallerResult.NeedsUnknownSources -> Toast.makeText(context, if (zh) "请允许安装未知应用，然后再次点击下载并安装" else "Allow installs from this source, then tap Download & install again", Toast.LENGTH_LONG).show()
                    is InstallerResult.SignatureConflict -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    is InstallerResult.Failure -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                updateError = error.message ?: if (zh) "下载更新失败" else "Update download failed"
            }
            updateBusy = false
        }
    }

    LaunchedEffect(Unit) { checkForUpdate() }

    fun connect(value: String) {
        val details = MainActivity.parsePairing(value)
        if (details == null) {
            state = ConnectionState.Failed(if (zh) "配对链接无效" else "Invalid pairing link")
            return
        }
        val requestToken = connectionRequestToken + 1
        connectionRequestToken = requestToken
        val requestedSessionId = selectedSessionId
        state = ConnectionState.Testing
        scope.launch {
            val profile = AtlasDeviceProfile(
                id = details.deviceId,
                name = details.deviceName.ifBlank { "Codex Atlas" },
                kind = details.deviceKind.ifBlank { "desktop" },
                lanUrl = details.lanUrl,
                tunnelUrl = details.tunnelUrl,
                token = details.token,
                preferTunnel = details.preferTunnel,
                route = connectionRoute.key,
                lastConnectedAtMs = System.currentTimeMillis(),
            )
            BridgePreferences.saveDevice(context, profile)
            deviceProfiles = BridgePreferences.devices(context)
            selectedDeviceId = profile.id
            syncCursorMs = BridgePreferences.syncCursor(context)
            syncEpoch = BridgePreferences.syncEpoch(context)
            syncAfterSeq = BridgePreferences.syncSeq(context)
            snapshot = BridgePreferences.cachedSnapshot(context)
            BridgePreferences.saveConnectionRoute(context, connectionRoute.key)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val primary = primaryBridgeUrl(details, connectionRoute)
                    val fallback = fallbackBridgeUrl(details, connectionRoute)
                    if (primary.isBlank()) error(if (zh) "没有可用的服务器通道地址" else "No server route is available")
                    val client = AtlasBridgeClient(primary, details.token)
                    val bootstrap = client.syncAny(0, fallback)
                    val fresh = bootstrap.snapshot
                        ?: error(if (zh) "桌面端没有可用会话" else "No session is available on the desktop")
                    val preferredId = bootstrap.sessions.firstOrNull { it.id == fresh.sessionId }?.id ?: fresh.sessionId
                    bootstrap to preferredId
                }
            }
            result.onSuccess { (bootstrap, preferredId) ->
                if (connectionRequestToken != requestToken) return@onSuccess
                val freshSnapshot = bootstrap.snapshot ?: return@onSuccess
                val available = bootstrap.sessions
                snapshot = freshSnapshot
                sessions = available
                syncCursorMs = bootstrap.cursorMs
                syncEpoch = bootstrap.syncEpoch
                syncAfterSeq = bootstrap.nextSeq
                if (syncEpoch.isNotBlank()) {
                    BridgePreferences.saveSyncPosition(context, syncEpoch, syncAfterSeq, syncCursorMs)
                } else {
                    BridgePreferences.saveSyncCursor(context, syncCursorMs)
                }
                BridgePreferences.saveCachedSnapshot(context, freshSnapshot)
                val requestedId = requestedSessionId.takeIf { requested -> available.any { it.id == requested } }
                    ?: initialSessionId.takeIf { requested -> available.any { it.id == requested } }
                selectedSessionId = requestedId ?: preferredId.ifBlank { available.firstOrNull()?.id.orEmpty() }
                val target = selectedSessionId
                if (target.isNotBlank()) {
                    val loadedMessages = withContext(Dispatchers.IO) {
                        val (primary, fallback) = primaryBridgeUrl(details, connectionRoute) to fallbackBridgeUrl(details, connectionRoute)
                        runCatching { AtlasBridgeClient(primary, details.token).messagesAny(target, fallback, limit = 200) }.getOrDefault(emptyList())
                    }
                    if (connectionRequestToken == requestToken && selectedSessionId == target) {
                        messagesBySession = messagesBySession + (target to loadedMessages)
                    }
                }
                state = ConnectionState.Connected(freshSnapshot.title)
                AtlasSyncService.start(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                AtlasWidgetReceiver.requestRefresh(context)
            }.onFailure { error ->
                if (connectionRequestToken == requestToken) {
                    state = ConnectionState.Failed(connectionFailureMessage(error, zh))
                }
            }
        }
    }

    fun switchDevice(profile: AtlasDeviceProfile) {
        if (profile.id == selectedDeviceId) {
            deviceManagerVisible = false
            return
        }
        BridgePreferences.selectDevice(context, profile.id)
        selectedDeviceId = profile.id
        pairing = MainActivity.pairingForProfile(profile)
        connectionRoute = ConnectionRoute.fromKey(profile.route)
        syncCursorMs = BridgePreferences.syncCursor(context)
        syncEpoch = BridgePreferences.syncEpoch(context)
        syncAfterSeq = BridgePreferences.syncSeq(context)
        snapshot = BridgePreferences.cachedSnapshot(context)
        sessions = emptyList()
        messagesBySession = emptyMap()
        messageRequestToken += 1
        connectionRequestToken += 1
        selectedSessionId = ""
        deviceManagerVisible = false
        state = ConnectionState.Idle
    }

    LaunchedEffect(pairing, connectionRoute) {
        if (MainActivity.parsePairing(pairing) != null) {
            delay(250)
            while (true) {
                if (state !is ConnectionState.Connected && state !is ConnectionState.Testing) connect(pairing)
                delay(5_000)
            }
        }
    }

    LaunchedEffect(state, pairing, selectedSessionId, voiceSnapshot.active, readRepliesAloud, connectionRoute) {
        val details = MainActivity.parsePairing(pairing) ?: return@LaunchedEffect
        val selectionAtStart = selectedSessionId
        while (state is ConnectionState.Connected) {
            val syncResult = withContext(Dispatchers.IO) {
                runCatching {
                    val primary = primaryBridgeUrl(details, connectionRoute)
                    val fallback = fallbackBridgeUrl(details, connectionRoute)
                    if (primary.isBlank()) error("No server route is available")
                    // The bridge holds this request until a new event arrives
                    // (or the server-side timeout expires), so the UI updates
                    // immediately without a fixed three-second polling tick.
                    AtlasBridgeClient(primary, details.token).syncAny(
                        syncCursorMs,
                        fallback,
                        20_000,
                        syncEpoch,
                        syncAfterSeq,
                    )
                }
            }
            val fresh = syncResult.getOrNull()
            if (fresh != null) {
                // A long-poll response can finish after the user selected a
                // different conversation. Let the keyed effect restart and
                // discard this response instead of applying it to the new UI.
                if (selectedSessionId != selectionAtStart) break
                val previousCursor = syncCursorMs
                var recoverySucceeded = true
                snapshot = fresh.snapshot ?: snapshot
                sessions = fresh.sessions
                val target = selectionAtStart
                    .takeIf { id -> id.isNotBlank() && (fresh.sessions.isEmpty() || fresh.sessions.any { it.id == id }) }
                    ?: fresh.snapshot?.sessionId?.takeIf { id -> fresh.sessions.any { it.id == id } }
                    ?: fresh.sessions.firstOrNull()?.id.orEmpty()
                if (target.isNotBlank() && selectedSessionId != target) selectedSessionId = target
                val currentMessages = messagesBySession[target].orEmpty()
                val incoming = fresh.events.filter { it.sessionId == target }.flatMap { it.messages }
                if (fresh.reset || fresh.gap) {
                    val full = withContext(Dispatchers.IO) {
                        runCatching {
                            val primary = primaryBridgeUrl(details, connectionRoute)
                            val fallback = fallbackBridgeUrl(details, connectionRoute)
                            AtlasBridgeClient(primary, details.token).messagesAny(target, fallback, limit = 200)
                        }.getOrNull()
                    }
                    if (full != null) messagesBySession = messagesBySession + (target to full)
                    else {
                        recoverySucceeded = false
                        if (incoming.isNotEmpty()) {
                            messagesBySession = messagesBySession + (target to mergeAtlasMessages(currentMessages, incoming))
                        }
                    }
                } else if (incoming.isNotEmpty()) {
                    messagesBySession = messagesBySession + (target to mergeAtlasMessages(currentMessages, incoming))
                    if (readRepliesAloud) {
                        incoming.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
                            ?.let { speechOutput.speak(it.text) }
                    }
                } else if (previousCursor == 0L && fresh.snapshot?.sessionId == target) {
                    messagesBySession = messagesBySession + (target to mergeAtlasMessages(currentMessages, fresh.snapshot.messages))
                }
                if (recoverySucceeded) {
                    syncCursorMs = maxOf(syncCursorMs, fresh.cursorMs)
                    if (fresh.syncEpoch.isNotBlank()) {
                        syncAfterSeq = if (fresh.reset || (syncEpoch.isNotBlank() && syncEpoch != fresh.syncEpoch)) {
                            fresh.nextSeq
                        } else {
                            maxOf(syncAfterSeq, fresh.nextSeq)
                        }
                        syncEpoch = fresh.syncEpoch
                        BridgePreferences.saveSyncPosition(context, syncEpoch, syncAfterSeq, syncCursorMs)
                    } else {
                        BridgePreferences.saveSyncCursor(context, syncCursorMs)
                    }
                }
                state = ConnectionState.Connected(fresh.snapshot?.title ?: snapshot?.title.orEmpty())
                AtlasWidgetReceiver.requestRefresh(context)
                if (!recoverySucceeded) delay(750)
            } else {
                state = ConnectionState.Reconnecting(syncResult.exceptionOrNull()?.message.orEmpty())
            }
        }
    }

    LaunchedEffect(state, pairing, mobilePage) {
        while (true) {
            queuedMessages = AtlasMessageQueue.items(context)
            queuedMessageCount = queuedMessages.size
            queueControl = AtlasMessageQueue.control(context)
            messagesBySession = reconcileLocalDeliveryStates(messagesBySession, queuedMessages)
            delay(if (state is ConnectionState.Connected) 1_000 else 2_500)
        }
    }

    // Continuous voice mode uses the Paseo-style ordered dictation protocol.
    // If the bridge is unavailable, the final transcript falls back to the
    // durable message outbox instead of being dropped.
    LaunchedEffect(Unit) {
        while (true) {
            val text = pendingVoiceSend.firstOrNull()?.trim().orEmpty()
            val details = MainActivity.parsePairing(pairing)
            val conversationId = selectedSessionId.ifBlank { snapshot?.sessionId.orEmpty() }
            if (text.isBlank() || details == null || conversationId.isBlank()) {
                delay(100)
                continue
            }

            pendingVoiceSend = pendingVoiceSend.drop(1)
            val sent = if (state is ConnectionState.Connected) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val primary = primaryBridgeUrl(details, connectionRoute)
                        val fallback = fallbackBridgeUrl(details, connectionRoute)
                        val nextSeq = BridgePreferences.dictationSeq(context, conversationId) + 1
                        val ack = AtlasBridgeClient(primary, details.token).sendDictationChunkAny(
                            conversationId,
                            nextSeq,
                            text,
                            finalChunk = true,
                            fallbackUrl = fallback,
                        )
                        if (ack.ackSeq >= nextSeq) BridgePreferences.saveDictationSeq(context, conversationId, ack.ackSeq)
                    }.isSuccess
                }
            } else {
                false
            }
            if (!sent) AtlasMessageQueue.enqueue(context, conversationId, text)
            queuedMessages = AtlasMessageQueue.items(context)
            queuedMessageCount = queuedMessages.size
            if (!sent) AtlasSyncService.start(context)
        }
    }

    if (mobilePage == MobilePage.Queue) {
        MobileQueuePage(
            chinese = zh,
            items = queuedMessages,
            control = queueControl,
            onBack = { mobilePage = MobilePage.Home },
            onPause = {
                AtlasMessageQueue.setControl(context, AtlasQueueControl.Paused)
                queueControl = AtlasQueueControl.Paused
                AtlasSyncService.start(context)
            },
            onResume = {
                AtlasMessageQueue.setControl(context, AtlasQueueControl.Running)
                queueControl = AtlasQueueControl.Running
                AtlasSyncService.start(context)
            },
            onStop = {
                AtlasMessageQueue.setControl(context, AtlasQueueControl.Stopping)
                queueControl = AtlasQueueControl.Stopping
                AtlasSyncService.start(context)
            },
            onRetry = { id ->
                AtlasMessageQueue.retry(context, id)
                queuedMessages = AtlasMessageQueue.items(context)
                queuedMessageCount = queuedMessages.size
                AtlasSyncService.start(context)
            },
            onRemove = { id ->
                AtlasMessageQueue.remove(context, id)
                queuedMessages = AtlasMessageQueue.items(context)
                queuedMessageCount = queuedMessages.size
            },
            onNavigate = { page ->
                mobilePage = if (page == MobilePage.Conversation && selectedSessionId.isBlank()) MobilePage.Home else page
            },
        )
        return
    }

    if (mobilePage == MobilePage.Settings) {
        MobileSettingsPage(
            chinese = zh,
            profile = deviceProfiles.firstOrNull { it.id == selectedDeviceId },
            connectionState = state,
            route = connectionRoute,
            onRouteChange = { route ->
                connectionRoute = route
                BridgePreferences.saveConnectionRoute(context, route.key)
            },
            readRepliesAloud = readRepliesAloud,
            onReadRepliesChange = { enabled ->
                readRepliesAloud = enabled
                BridgePreferences.saveReadRepliesAloud(context, enabled)
                if (!enabled) speechOutput.stop()
            },
            availableUpdate = availableUpdate,
            updateBusy = updateBusy,
            updateProgress = updateProgress,
            updateError = updateError,
            onCheckUpdate = ::checkForUpdate,
            onDownloadUpdate = ::downloadAndInstallUpdate,
            onBack = { mobilePage = MobilePage.Home },
            onNavigate = { page ->
                mobilePage = if (page == MobilePage.Conversation && selectedSessionId.isBlank()) MobilePage.Home else page
            },
        )
        return
    }

    if (scannerVisible) {
        ScannerScreen(
            chinese = zh,
            onResult = { value ->
                scannerVisible = false
                pairing = value
            },
            onClose = { scannerVisible = false },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFCFDFC)) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (mobilePage == MobilePage.Conversation) {
                        Modifier
                    } else {
                        Modifier.verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 18.dp)
                    },
                ),
            verticalArrangement = if (mobilePage == MobilePage.Conversation) Arrangement.Top else Arrangement.spacedBy(20.dp),
        ) {
            if (mobilePage != MobilePage.Conversation) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Atlas", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = Color(0xFF1F2A22), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { mobilePage = MobilePage.Settings }) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (zh) "设置" else "Settings", color = Color(0xFF2F7C3B))
                }
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
            if (updateBusy || availableUpdate?.available == true || updateError != null) {
                val update = availableUpdate
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (update?.available == true) (if (zh) "有新版本" else "Update available") else if (updateBusy) (if (zh) "检查更新…" else "Checking updates…") else (if (zh) "已是最新" else "Up to date"), fontWeight = FontWeight.SemiBold, color = Color(0xFF26332A), style = MaterialTheme.typography.bodyLarge)
                                if (update != null) Text("v${update.currentVersion} → v${update.latestVersion}", color = Color(0xFF68736B), style = MaterialTheme.typography.bodySmall)
                                if (updateError != null) Text(updateError.orEmpty(), color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                            }
                            if (updateBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else if (update?.available == true && !update.apkUrl.isNullOrBlank()) Button(onClick = { downloadAndInstallUpdate() }) { Text(if (zh) "下载并安装" else "Download & install") }
                            else TextButton(onClick = { checkForUpdate() }) { Text(if (zh) "检查" else "Check") }
                        }
                        if (updateBusy && updateProgress > 0) {
                            androidx.compose.material3.LinearProgressIndicator(progress = { updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("$updateProgress%", color = Color(0xFF667466), style = MaterialTheme.typography.labelSmall)
                        }
                        if (update?.available == true && update.apkUrl.isNullOrBlank()) TextButton(onClick = { openPublicUrl(context, update.releaseUrl) }) { Text(if (zh) "打开 Release 页面" else "Open release page") }
                    }
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val selectedProfile = deviceProfiles.firstOrNull { it.id == selectedDeviceId }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                selectedProfile?.name?.ifBlank { "Codex Atlas" } ?: if (zh) "连接设备" else "Connect a device",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1F2A22),
                            )
                            if (selectedProfile != null) {
                                Text(selectedProfile.kind.uppercase(Locale.ROOT), color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (deviceProfiles.isNotEmpty()) {
                            TextButton(onClick = { deviceManagerVisible = !deviceManagerVisible }) {
                                Text(if (deviceManagerVisible) { if (zh) "收起" else "Done" } else { if (zh) "管理" else "Manage" })
                            }
                        }
                    }
                    ConnectionStatus(state, zh)
                    if (deviceManagerVisible || deviceProfiles.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                deviceProfiles.forEach { profile ->
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val selected = profile.id == selectedDeviceId
                                        if (selected) {
                                            Button(onClick = { switchDevice(profile) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                                DeviceProfileLabel(profile)
                                            }
                                        } else {
                                            OutlinedButton(onClick = { switchDevice(profile) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                                DeviceProfileLabel(profile)
                                            }
                                        }
                                        if (deviceProfiles.size > 1) {
                                            TextButton(onClick = {
                                                val wasSelected = profile.id == selectedDeviceId
                                                BridgePreferences.removeDevice(context, profile.id)
                                                deviceProfiles = BridgePreferences.devices(context)
                                                selectedDeviceId = BridgePreferences.selectedDeviceId(context)
                                                if (wasSelected) {
                                                    pairing = MainActivity.storedPairing(context)
                                                    connectionRoute = ConnectionRoute.fromKey(BridgePreferences.connectionRoute(context))
                                                    snapshot = BridgePreferences.cachedSnapshot(context)
                                                    sessions = emptyList()
                                                    messagesBySession = emptyMap()
                                                    messageRequestToken += 1
                                                    connectionRequestToken += 1
                                                    state = ConnectionState.Idle
                                                }
                                            }) { Text(if (zh) "删除" else "Remove") }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            OutlinedTextField(
                                value = pairing,
                                onValueChange = { pairing = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (zh) "配对链接" else "Pairing link") },
                                placeholder = { Text("codex-atlas://connect…") },
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val pairingDetails = MainActivity.parsePairing(pairing)
                                ConnectionRoute.entries.forEach { route ->
                                    val serverAvailable = pairingDetails?.tunnelUrl?.isNotBlank() == true
                                    val enabled = route != ConnectionRoute.Server || serverAvailable
                                    val label = when (route) {
                                        ConnectionRoute.Auto -> if (zh) "自动" else "Auto"
                                        ConnectionRoute.Lan -> if (zh) "局域网" else "LAN"
                                        ConnectionRoute.Server -> if (zh) "服务器" else "Server"
                                    }
                                    if (route == connectionRoute) {
                                        Button(onClick = {
                                            connectionRoute = route
                                            BridgePreferences.saveConnectionRoute(context, route.key)
                                        }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
                                    } else {
                                        OutlinedButton(onClick = {
                                            connectionRoute = route
                                            BridgePreferences.saveConnectionRoute(context, route.key)
                                        }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scannerVisible = true
                                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                                }, modifier = Modifier.weight(1f)) { Text(if (zh) "扫描二维码" else "Scan QR") }
                                Button(onClick = { connect(pairing) }, enabled = MainActivity.parsePairing(pairing) != null && state !is ConnectionState.Testing, modifier = Modifier.weight(1f)) {
                                    if (state is ConnectionState.Testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    else Text(if (zh) "连接" else "Connect")
                                }
                            }
                            if (permissionDenied) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (zh) "相机权限被拒绝" else "Camera permission was denied", color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        })
                                    }) { Text(if (zh) "打开设置" else "Open settings") }
                                }
                            }
                        }
                    }
                }
            if (snapshot != null && MainActivity.parsePairing(pairing) != null) {
                val details = MainActivity.parsePairing(pairing)!!
                val selectedSession = sessions.firstOrNull { it.id == selectedSessionId }
                // Never use the active-session snapshot as a fallback for a
                // different selection. The snapshot is a status summary, not
                // the selected conversation's timeline.
                val conversationId = selectedSession?.id.orEmpty()
                val conversationTitle = selectedSession?.title?.ifBlank { null }
                    ?: if (zh) "未命名会话" else "Untitled session"
                val conversationFolder = selectedSession?.cwd.orEmpty()
                val conversationModel = selectedSession?.model.orEmpty()
                val messages = messagesBySession[conversationId].orEmpty()
                LaunchedEffect(conversationId, selectedDeviceId, connectionRoute, pairing) {
                    if (conversationId.isBlank()) return@LaunchedEffect
                    val requestToken = messageRequestToken + 1
                    messageRequestToken = requestToken
                    val loaded = withContext(Dispatchers.IO) {
                        val (primary, fallback) = primaryBridgeUrl(details, connectionRoute) to fallbackBridgeUrl(details, connectionRoute)
                        runCatching { AtlasBridgeClient(primary, details.token).messagesAny(conversationId, fallback, limit = 200) }
                            .getOrDefault(emptyList())
                    }
                    if (messageRequestToken == requestToken && selectedSessionId == conversationId) {
                        messagesBySession = messagesBySession + (conversationId to loaded)
                    }
                }
                LaunchedEffect(conversationId, messages.size, messages.lastOrNull()?.id) {
                    if (messages.isNotEmpty()) messageScrollState.animateScrollTo(messageScrollState.maxValue)
                }
                if (mobilePage != MobilePage.Conversation && sessions.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (zh) "会话" else "Sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2A22))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (queuedMessageCount > 0) {
                                        Text(
                                            if (zh) "队列 $queuedMessageCount" else "Queue $queuedMessageCount",
                                            color = Color(0xFFB27A25),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    Text("${sessions.size}", color = Color(0xFF667466), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        selectedSessionId = ""
                                        mobilePage = MobilePage.Conversation
                                        createVisible = true
                                    }) { Text(if (zh) "新建" else "New") }
                                }
                            }
                            OutlinedTextField(
                                value = sessionQuery,
                                onValueChange = { sessionQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (zh) "搜索会话" else "Search sessions") },
                            )
                            val visibleSessions = sessions.filter { item ->
                                val query = sessionQuery.trim()
                                query.isBlank() || listOf(item.title, item.preview, item.cwd, item.model, item.liveState).any { it.contains(query, ignoreCase = true) }
                            }
                            visibleSessions.take(20).forEach { item ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        selectedSessionId = item.id
                                        messageError = null
                                        mobilePage = MobilePage.Conversation
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title.ifBlank { if (zh) "未命名会话" else "Untitled session" }, maxLines = 1, color = if (item.id == conversationId) Color(0xFF2F7C3B) else Color(0xFF243025), fontWeight = if (item.id == conversationId) FontWeight.SemiBold else FontWeight.Normal)
                                                Text(listOf(item.cwd, item.model, item.liveState.ifBlank { if (item.running) "working" else "idle" }).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        when {
                                                            item.requiresAttention -> Color(0xFFD85D59)
                                                            item.running -> Color(0xFF58BE70)
                                                            else -> Color(0xFFB7C2B8)
                                                        },
                                                        RoundedCornerShape(50),
                                                    ),
                                            )
                                        }
                                    }
                                    if (item.id != visibleSessions.take(20).lastOrNull()?.id) {
                                        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
                                    }
                                }
                            }
                            if (visibleSessions.isEmpty()) Text(if (zh) "没有匹配的会话" else "No matching sessions", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (mobilePage == MobilePage.Conversation) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = { mobilePage = MobilePage.Home }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conversationTitle, maxLines = 1, color = Color(0xFF243025), fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(conversationFolder, conversationModel)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                maxLines = 1,
                                color = Color(0xFF7A867B),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (queuedMessageCount > 0) {
                                TextButton(onClick = { queueVisible = !queueVisible }) {
                                    Text(if (zh) "队列 $queuedMessageCount" else "Queue $queuedMessageCount", color = Color(0xFFB27A25))
                                }
                            }
                            ConnectionStatePill(selectedSession?.liveState ?: snapshot!!.state, zh)
                        }
                    }
                }
                if (queueVisible) {
                    QueuePanel(
                        chinese = zh,
                        items = queuedMessages,
                        control = queueControl,
                        onPause = {
                            AtlasMessageQueue.setControl(context, AtlasQueueControl.Paused)
                            queueControl = AtlasQueueControl.Paused
                            AtlasSyncService.start(context)
                        },
                        onResume = {
                            AtlasMessageQueue.setControl(context, AtlasQueueControl.Running)
                            queueControl = AtlasQueueControl.Running
                            AtlasSyncService.start(context)
                        },
                        onStop = {
                            AtlasMessageQueue.setControl(context, AtlasQueueControl.Stopping)
                            queueControl = AtlasQueueControl.Stopping
                            AtlasSyncService.start(context)
                        },
                        onRetry = { id ->
                            AtlasMessageQueue.retry(context, id)
                            queuedMessages = AtlasMessageQueue.items(context)
                            queuedMessageCount = queuedMessages.size
                            AtlasSyncService.start(context)
                        },
                        onRemove = { id ->
                            AtlasMessageQueue.remove(context, id)
                            queuedMessages = AtlasMessageQueue.items(context)
                            queuedMessageCount = queuedMessages.size
                        },
                    )
                }
                if (mobilePage == MobilePage.Conversation) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (selectedSession?.requiresAttention == true) {
                            ApprovalActions(
                                chinese = zh,
                                detail = selectedSession.lastError ?: selectedSession.lastOutput.orEmpty(),
                                structured = selectedSession.approval,
                                onSelect = { choice ->
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val primary = primaryBridgeUrl(details, connectionRoute)
                                                val fallback = fallbackBridgeUrl(details, connectionRoute)
                                                AtlasBridgeClient(primary, details.token).inputAny(conversationId, choice, fallback)
                                            }
                                        }
                                        result.onFailure { error -> Toast.makeText(context, error.message ?: if (zh) "审批发送失败" else "Approval failed", Toast.LENGTH_LONG).show() }
                                    }
                                },
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(messageScrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (messages.isEmpty()) {
                                Text(selectedSession?.lastOutput.orEmpty().ifBlank { if (zh) "暂无最新输出" else "No recent output" }, color = Color(0xFF4D5C4E), style = MaterialTheme.typography.bodySmall)
                            } else {
                                messages.takeLast(80).forEach { item ->
                                    ConversationMessage(item, zh)
                                }
                            }
                        }
                        fun sendToConversation(text: String) {
                            val normalized = text.trim()
                            if (normalized.isEmpty() || conversationId.isBlank() || messageBusy) return
                            val queued = AtlasMessageQueue.enqueue(context, conversationId, normalized)
                            val immediate = queueControl == AtlasQueueControl.Running && state is ConnectionState.Connected
                            message = ""
                            messageError = null
                            queuedMessages = AtlasMessageQueue.items(context)
                            queuedMessageCount = queuedMessages.size
                            messagesBySession = messagesBySession + (conversationId to mergeAtlasMessages(
                                messages,
                                listOf(AtlasMessage("queued-${queued.id}", "user", normalized, queued.createdAtMs, if (immediate) "sending" else "queued")),
                            ))
                            if (!immediate) {
                                AtlasSyncService.start(context)
                                return
                            }
                            messageBusy = true
                            scope.launch {
                                val delivery = withContext(Dispatchers.IO) {
                                    val claimed = AtlasMessageQueue.claim(context, queued.id)
                                        ?: return@withContext MessageDeliveryResult.Deferred
                                    runCatching {
                                        val primary = primaryBridgeUrl(details, connectionRoute)
                                        val fallback = fallbackBridgeUrl(details, connectionRoute)
                                        AtlasBridgeClient(primary, details.token).sendMessageAny(
                                            conversationId,
                                            normalized,
                                            fallback,
                                            claimed.clientMessageId,
                                        )
                                    }.fold(
                                        onSuccess = {
                                            AtlasMessageQueue.remove(context, queued.id)
                                            MessageDeliveryResult.Sent
                                        },
                                        onFailure = { error ->
                                            AtlasMessageQueue.markFailure(context, queued.id, error.message)
                                            MessageDeliveryResult.Failed(
                                                error.message ?: if (zh) "消息发送失败" else "Message delivery failed",
                                            )
                                        },
                                    )
                                }
                                val optimisticId = "queued-${queued.id}"
                                when (delivery) {
                                    MessageDeliveryResult.Sent -> {
                                        messagesBySession = messagesBySession + (
                                            conversationId to messagesBySession[conversationId].orEmpty().map { item ->
                                                if (item.id == optimisticId) item.copy(kind = "sent") else item
                                            }
                                        )
                                    }
                                    is MessageDeliveryResult.Failed -> {
                                        messageError = delivery.message
                                        messagesBySession = messagesBySession + (
                                            conversationId to messagesBySession[conversationId].orEmpty().map { item ->
                                                if (item.id == optimisticId) item.copy(kind = "failed") else item
                                            }
                                        )
                                    }
                                    MessageDeliveryResult.Deferred -> Unit
                                }
                                queuedMessages = AtlasMessageQueue.items(context)
                                queuedMessageCount = queuedMessages.size
                                messageBusy = false
                                if (queuedMessageCount > 0) AtlasSyncService.start(context)
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
                            OutlinedTextField(
                                value = message,
                                onValueChange = { message = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(if (zh) "输入消息" else "Write a message") },
                                minLines = 2,
                                maxLines = 5,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { sendToConversation(message) }, enabled = message.isNotBlank() && !messageBusy, modifier = Modifier.weight(1f)) {
                                    if (messageBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    else {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text(if (zh) "发送" else "Send")
                                    }
                                }
                                OutlinedButton(onClick = { createVisible = !createVisible }, modifier = Modifier.weight(1f)) {
                                    Text(if (zh) "新建会话" else "New session")
                                }
                            }
                            if (messageError != null) {
                                Text(
                                    messageError.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (voiceSnapshot.active) {
                            VoiceInputPanel(
                                chinese = zh,
                                snapshot = voiceSnapshot,
                                onCancel = { voiceController.cancel() },
                                onStop = { voiceController.stop() },
                                onMute = { voiceController.toggleMute() },
                                onRetry = { voiceController.retry() },
                                onDiscard = { voiceController.discard() },
                                onInsert = {
                                    voiceController.acceptTranscript { text ->
                                        message = appendVoiceText(message, text)
                                    }
                                },
                                onInsertAndSend = {
                                    voiceController.acceptTranscript { text ->
                                        val composed = appendVoiceText(message, text)
                                        message = composed
                                        sendToConversation(composed)
                                    }
                                },
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = {
                                    pendingVoiceMode = false
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        voiceController.start(message, continuous = false)
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }, enabled = !messageBusy && !voiceSnapshot.active, modifier = Modifier.weight(1f)) { Text(if (zh) "语音输入" else "Dictate") }
                                OutlinedButton(onClick = {
                                    pendingVoiceMode = true
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        voiceController.start(message, continuous = true)
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }, enabled = !messageBusy && !voiceSnapshot.active, modifier = Modifier.weight(1f)) { Text(if (zh) "连续输入" else "Voice mode") }
                            }
                        }
                        if (queuedMessageCount > 0) {
                            Text(
                                if (zh) "还有 $queuedMessageCount 条消息等待发送，连接恢复后会自动继续。"
                                else "$queuedMessageCount message(s) queued and will send when the connection recovers.",
                                color = Color(0xFFB27A25),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (audioPermissionDenied) Text(if (zh) "录音权限被拒绝，请在系统设置中允许。" else "Microphone permission was denied. Allow it in system settings.", color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                        if (createVisible) {
                            OutlinedTextField(value = createCwd, onValueChange = { createCwd = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (zh) "工作目录" else "Working directory") }, singleLine = true)
                            OutlinedTextField(value = createPrompt, onValueChange = { createPrompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (zh) "初始提示词（可选）" else "Initial prompt (optional)") }, minLines = 2, maxLines = 4)
                            Button(onClick = {
                                if (createCwd.isBlank() || createBusy) return@Button
                                createBusy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        val (primary, fallback) = primaryBridgeUrl(details, connectionRoute) to fallbackBridgeUrl(details, connectionRoute)
                                        runCatching { AtlasBridgeClient(primary, details.token).createSessionAny(createCwd.trim(), createPrompt.trim(), snapshot?.model.orEmpty(), "Workspace write", fallback) }
                                    }
                                    result.onSuccess {
                                        createVisible = false
                                        createPrompt = ""
                                        val fresh = withContext(Dispatchers.IO) {
                                            val (primary, fallback) = primaryBridgeUrl(details, connectionRoute) to fallbackBridgeUrl(details, connectionRoute)
                                            runCatching { AtlasBridgeClient(primary, details.token).listSessionsAny(fallback) }.getOrDefault(sessions)
                                        }
                                        sessions = fresh
                                        selectedSessionId = fresh.firstOrNull()?.id ?: selectedSessionId
                                        snapshot = withContext(Dispatchers.IO) {
                                            val (primary, fallback) = primaryBridgeUrl(details, connectionRoute) to fallbackBridgeUrl(details, connectionRoute)
                                            runCatching { AtlasBridgeClient(primary, details.token).snapshotAny(fallback) }.getOrNull()
                                        } ?: snapshot
                                        AtlasWidgetReceiver.requestRefresh(context)
                                    }.onFailure { error -> state = ConnectionState.Failed(error.message ?: if (zh) "新会话创建失败" else "Could not create session") }
                                    createBusy = false
                                }
                            }, enabled = createCwd.isNotBlank() && !createBusy) {
                                if (createBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (zh) "打开 Codex 会话" else "Open Codex session")
                            }
                        }
                    }
                }
            }
            if (mobilePage != MobilePage.Conversation) {
                OutlinedButton(onClick = { addCardToHome(context, zh) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (zh) "添加 Codex Atlas 卡片到桌面" else "Add Codex Atlas card to home screen")
                }
            }
        }
        if (mobilePage != MobilePage.Conversation) MobileBottomNav(
                current = mobilePage,
                chinese = zh,
                onSelect = { page ->
                    mobilePage = if (page == MobilePage.Conversation && selectedSessionId.isBlank()) MobilePage.Home else page
                },
            )
        }
    }
}

@Composable
private fun MobilePageHeader(
    title: String,
    chinese: Boolean,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = if (chinese) "返回" else "Back",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF1F2A22),
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.invoke()
    }
}

@Composable
private fun MobileQueuePage(
    chinese: Boolean,
    items: List<QueuedAtlasMessage>,
    control: AtlasQueueControl,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNavigate: (MobilePage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFFCFDFC)).safeDrawingPadding()) {
        MobilePageHeader(
            title = if (chinese) "消息队列" else "Message queue",
            chinese = chinese,
            onBack = onBack,
            trailing = {
                Text(
                    when (control) {
                        AtlasQueueControl.Running -> if (chinese) "发送中" else "Sending"
                        AtlasQueueControl.Paused -> if (chinese) "已暂停" else "Paused"
                        AtlasQueueControl.Stopping -> if (chinese) "停止中" else "Stopping"
                    },
                    color = if (control == AtlasQueueControl.Running) Color(0xFF2F7C3B) else Color(0xFF8A6A2A),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
        )
        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = control == AtlasQueueControl.Running
                OutlinedButton(onClick = if (running) onPause else onResume, modifier = Modifier.weight(1f)) {
                    Text(if (running) { if (chinese) "等待" else "Pause" } else { if (chinese) "继续" else "Resume" })
                }
                OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) {
                    Text(if (chinese) "停止发送" else "Stop")
                }
            }
            if (items.isEmpty()) {
                Text(if (chinese) "队列为空" else "Queue is empty", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodyLarge)
            } else {
                items.take(50).forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (index > 0) androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
                        Text(item.text, color = Color(0xFF26332A), style = MaterialTheme.typography.bodyLarge, maxLines = 5)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    item.state == AtlasQueueItemState.Sending.key -> if (chinese) "发送中" else "Sending"
                                    item.state == AtlasQueueItemState.Failed.key -> if (chinese) "失败 ${item.attempts} 次" else "Failed ${item.attempts} times"
                                    item.attempts > 0 -> if (chinese) "等待重试" else "Retrying"
                                    else -> if (chinese) "等待发送" else "Pending"
                                },
                                modifier = Modifier.weight(1f),
                                color = if (item.state == AtlasQueueItemState.Failed.key) Color(0xFFB44A45) else Color(0xFF7A867B),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (item.state == AtlasQueueItemState.Failed.key) {
                                TextButton(onClick = { onRetry(item.id) }) { Text(if (chinese) "重试" else "Retry") }
                            }
                            TextButton(onClick = { onRemove(item.id) }) { Text(if (chinese) "移除" else "Remove") }
                        }
                        item.lastError?.let { error ->
                            Text(error, color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
                if (items.size > 50) Text(if (chinese) "还有 ${items.size - 50} 条" else "${items.size - 50} more", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
            }
        }
        MobileBottomNav(current = MobilePage.Queue, chinese = chinese, onSelect = onNavigate)
    }
}

@Composable
private fun MobileSettingsPage(
    chinese: Boolean,
    profile: AtlasDeviceProfile?,
    connectionState: ConnectionState,
    route: ConnectionRoute,
    onRouteChange: (ConnectionRoute) -> Unit,
    readRepliesAloud: Boolean,
    onReadRepliesChange: (Boolean) -> Unit,
    availableUpdate: AtlasUpdate?,
    updateBusy: Boolean,
    updateProgress: Int,
    updateError: String?,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (MobilePage) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFFCFDFC)).safeDrawingPadding()) {
        MobilePageHeader(title = if (chinese) "设置" else "Settings", chinese = chinese, onBack = onBack)
        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(if (chinese) "连接" else "Connection", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1F2A22), fontWeight = FontWeight.SemiBold)
            ConnectionStatus(state = connectionState, chinese = chinese)
            profile?.let { current ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(current.name.ifBlank { "Codex Atlas" }, color = Color(0xFF26332A), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(current.kind.uppercase(Locale.ROOT), color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(if (chinese) "通道" else "Route", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1F2A22), fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionRoute.entries.forEach { option ->
                    val enabled = option != ConnectionRoute.Server || profile?.tunnelUrl?.isNotBlank() == true
                    val label = when (option) {
                        ConnectionRoute.Auto -> if (chinese) "自动" else "Auto"
                        ConnectionRoute.Lan -> if (chinese) "局域网" else "LAN"
                        ConnectionRoute.Server -> if (chinese) "服务器" else "Server"
                    }
                    if (route == option) Button(onClick = { onRouteChange(option) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
                    else OutlinedButton(onClick = { onRouteChange(option) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (chinese) "朗读 Codex 回复" else "Read Codex replies", color = Color(0xFF26332A), style = MaterialTheme.typography.bodyLarge)
                    Text(if (chinese) "收到新的回复时使用系统语音播报" else "Speak new replies with the system voice", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = readRepliesAloud,
                    onCheckedChange = onReadRepliesChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2F7C3B),
                        uncheckedThumbColor = Color(0xFF8C978E),
                        uncheckedTrackColor = Color(0xFFEDF2ED),
                        uncheckedBorderColor = Color(0xFFCDD5CD),
                    ),
                )
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
            Text(if (chinese) "应用更新" else "App updates", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1F2A22), fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        when {
                            updateBusy -> if (chinese) "正在检查…" else "Checking…"
                            availableUpdate?.available == true -> if (chinese) "发现 v${availableUpdate.latestVersion}" else "v${availableUpdate.latestVersion} available"
                            updateError != null -> if (chinese) "检查失败" else "Check failed"
                            else -> if (chinese) "已是最新版本" else "Up to date"
                        },
                        color = if (updateError != null) Color(0xFFB44A45) else Color(0xFF26332A),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (updateError != null) Text(updateError, color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    if (updateBusy && updateProgress > 0) Text("$updateProgress%", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                }
                when {
                    updateBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    availableUpdate?.available == true && !availableUpdate.apkUrl.isNullOrBlank() -> Button(onClick = onDownloadUpdate) { Text(if (chinese) "安装" else "Install") }
                    else -> TextButton(onClick = onCheckUpdate) { Text(if (chinese) "检查" else "Check") }
                }
            }
            if (updateBusy && updateProgress > 0) {
                androidx.compose.material3.LinearProgressIndicator(progress = { updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Codex Atlas v${BuildConfig.VERSION_NAME}", modifier = Modifier.weight(1f), color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openPublicUrl(context, ProjectLinks.projectUrl) }) {
                    Text(if (chinese) "项目主页" else "Project")
                }
            }
        }
        MobileBottomNav(current = MobilePage.Settings, chinese = chinese, onSelect = onNavigate)
    }
}

@Composable
private fun MobileBottomNav(current: MobilePage, chinese: Boolean, onSelect: (MobilePage) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.White,
            tonalElevation = 0.dp,
        ) {
            listOf(
                MobilePage.Home to (if (chinese) "首页" else "Home"),
                MobilePage.Conversation to (if (chinese) "会话" else "Chat"),
                MobilePage.Queue to (if (chinese) "队列" else "Queue"),
                MobilePage.Settings to (if (chinese) "设置" else "Settings"),
            ).forEach { (page, label) ->
                val image = when (page) {
                    MobilePage.Home -> Icons.Filled.Home
                    MobilePage.Conversation -> Icons.Filled.Create
                    MobilePage.Queue -> Icons.AutoMirrored.Filled.List
                    MobilePage.Settings -> Icons.Filled.Settings
                }
                NavigationBarItem(
                    selected = current == page,
                    onClick = { onSelect(page) },
                    icon = { Icon(image, contentDescription = label, modifier = Modifier.size(22.dp)) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionState, chinese: Boolean) {
    val (dot, text) = when (state) {
        ConnectionState.Idle -> Color(0xFFD3A941) to if (chinese) "等待配对链接" else "Waiting for a pairing link"
        ConnectionState.Testing -> Color(0xFFD3A941) to if (chinese) "正在测试连接…" else "Testing connection…"
        is ConnectionState.Connected -> Color(0xFF58BE70) to if (chinese) "已连接 · ${state.title}" else "Connected · ${state.title}"
        is ConnectionState.Reconnecting -> Color(0xFFD3A941) to if (chinese) "连接中断，正在重连" else "Connection lost · reconnecting"
        is ConnectionState.Failed -> Color(0xFFD85D59) to state.message
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.size(10.dp).background(dot, RoundedCornerShape(50)))
        Spacer(Modifier.size(8.dp))
        Text(text, color = Color(0xFF4D5C4E), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnectionStatePill(value: String, chinese: Boolean) {
    val normalized = value.lowercase(Locale.ROOT)
    val color = when {
        normalized.contains("error") || normalized.contains("fail") || normalized.contains("blocked") -> Color(0xFFD85D59)
        normalized.contains("work") || normalized.contains("run") || normalized.contains("active") -> Color(0xFF58BE70)
        else -> Color(0xFFD3A941)
    }
    Row(
        modifier = Modifier.background(Color(0xFFF1F5EF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(
            when {
                normalized.contains("error") || normalized.contains("fail") || normalized.contains("blocked") -> if (chinese) "需要处理" else "Needs attention"
                normalized.contains("work") || normalized.contains("run") || normalized.contains("active") -> if (chinese) "运行中" else "Working"
                else -> if (chinese) "等待中" else "Waiting"
            },
            color = Color(0xFF4D5C4E),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun QueuePanel(
    chinese: Boolean,
    items: List<QueuedAtlasMessage>,
    control: AtlasQueueControl,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (chinese) "发送队列" else "Outgoing queue", fontWeight = FontWeight.SemiBold, color = Color(0xFF4D4230))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (control) {
                            AtlasQueueControl.Running -> if (chinese) "发送中" else "Sending"
                            AtlasQueueControl.Paused -> if (chinese) "等待" else "Waiting"
                            AtlasQueueControl.Stopping -> if (chinese) "停止中" else "Stopping"
                        },
                        color = if (control == AtlasQueueControl.Running) Color(0xFF4D8A54) else Color(0xFFB27A25),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = if (control == AtlasQueueControl.Running) onPause else onResume) {
                        Text(if (control == AtlasQueueControl.Running) { if (chinese) "等待" else "Wait" } else { if (chinese) "继续" else "Resume" })
                    }
                    if (control == AtlasQueueControl.Running) {
                        TextButton(onClick = onStop) { Text(if (chinese) "停止" else "Stop") }
                    }
                }
            }
            if (items.isEmpty()) {
                Text(if (chinese) "队列已清空" else "Queue is empty", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
            } else {
                items.take(20).forEachIndexed { index, item ->
                    if (index > 0) androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.text, maxLines = 3, color = Color(0xFF334238), style = MaterialTheme.typography.bodyMedium)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    item.state == AtlasQueueItemState.Sending.key -> if (chinese) "发送中" else "Sending"
                                    item.state == AtlasQueueItemState.Failed.key -> if (chinese) "失败 ${item.attempts} 次" else "Failed ${item.attempts} times"
                                    item.attempts > 0 -> if (chinese) "等待重试 ${item.attempts}" else "Retrying ${item.attempts}"
                                    else -> if (chinese) "等待发送" else "Pending"
                                },
                                color = when {
                                    item.state == AtlasQueueItemState.Failed.key -> Color(0xFFB44A45)
                                    item.state == AtlasQueueItemState.Sending.key -> Color(0xFF4D8A54)
                                    else -> Color(0xFF8B7B60)
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (item.state == AtlasQueueItemState.Failed.key) TextButton(onClick = { onRetry(item.id) }) { Text(if (chinese) "重试" else "Retry") }
                                TextButton(onClick = { onRemove(item.id) }) { Text(if (chinese) "删除" else "Remove") }
                            }
                        }
                        item.lastError?.let { error ->
                            Text(error, maxLines = 2, color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (items.size > 20) Text(if (chinese) "仅显示前 20 条" else "Showing the first 20 items", color = Color(0xFF8B7B60), style = MaterialTheme.typography.bodySmall)
            }
    }
}

private fun addCardToHome(context: android.content.Context, chinese: Boolean) {
    val manager = AppWidgetManager.getInstance(context)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, AtlasWidgetReceiver::class.java), null, null)
    } else {
        Toast.makeText(context, if (chinese) "请在桌面编辑模式中选择卡片 > Codex Atlas" else "Open home screen edit mode and choose Cards > Codex Atlas", Toast.LENGTH_LONG).show()
    }
}

private fun openPublicUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, it.message ?: "Unable to open link", Toast.LENGTH_SHORT).show()
    }
}

private sealed interface ConversationBlock {
    data class Paragraph(val text: String) : ConversationBlock
    data class Code(val language: String, val text: String) : ConversationBlock
}

@Composable
private fun DeviceProfileLabel(profile: AtlasDeviceProfile) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(profile.name.ifBlank { "Codex Atlas" }, maxLines = 1)
        Text(profile.kind.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private fun parseConversationBlocks(raw: String): List<ConversationBlock> {
    val blocks = mutableListOf<ConversationBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var codeLanguage = ""
    var inCode = false

    fun flushParagraph() {
        val value = paragraph.toString().trimEnd()
        if (value.isNotBlank()) blocks += ConversationBlock.Paragraph(value)
        paragraph.clear()
    }

    raw.replace("\r\n", "\n").split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += ConversationBlock.Code(codeLanguage, code.toString().trimEnd())
                code.clear()
                codeLanguage = ""
                inCode = false
            } else {
                flushParagraph()
                codeLanguage = line.trim().removePrefix("```").trim()
                inCode = true
            }
        } else if (inCode) {
            code.appendLine(line)
        } else if (line.isBlank()) {
            flushParagraph()
        } else {
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
        }
    }
    if (inCode) blocks += ConversationBlock.Code(codeLanguage, code.toString().trimEnd())
    flushParagraph()
    return blocks
}

@Composable
private fun ConversationMessage(item: AtlasMessage, chinese: Boolean) {
    val role = item.role.lowercase()
    val isUser = role == "user"
    val isTool = role == "tool" || item.kind.equals("tool", ignoreCase = true)
    val isQueued = item.kind.equals("queued", ignoreCase = true)
    val isSending = item.kind.equals("sending", ignoreCase = true)
    val isFailed = item.kind.equals("failed", ignoreCase = true) || item.toolStatus.equals("failed", ignoreCase = true)
    val roleLabel = when {
        isQueued -> if (chinese) "等待发送" else "Pending"
        isSending -> if (chinese) "发送中" else "Sending"
        isFailed -> if (chinese) "发送失败" else "Failed"
        isUser -> if (chinese) "你" else "You"
        isTool -> if (chinese) "工具" else "Tool"
        else -> "Codex"
    }
    val roleColor = when {
        isFailed -> Color(0xFFB44A45)
        isQueued -> Color(0xFF9A6B2F)
        isSending -> Color(0xFF2F7C3B)
        isUser -> Color(0xFF2F7C3B)
        isTool -> Color(0xFF9A6B2F)
        else -> Color(0xFF69766B)
    }
    val surfaceColor = when {
        isFailed -> Color(0xFFFFF4F2)
        isQueued -> Color(0xFFFFF8EA)
        isSending -> Color(0xFFF0F7F0)
        isUser -> Color(0xFFEAF5EB)
        isTool -> Color(0xFFFFF6E6)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) .86f else 1f)
                .then(
                    if (isUser || isTool) Modifier
                        .background(surfaceColor, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            when {
                                isFailed -> Color(0xFFF0D1CE)
                                isQueued -> Color(0xFFEEDDB9)
                                isUser -> Color(0xFFD5E7D6)
                                else -> Color(0xFFE8E0D1)
                            },
                            RoundedCornerShape(12.dp),
                        )
                    else Modifier
                )
                .padding(horizontal = if (isUser || isTool) 13.dp else 2.dp, vertical = if (isUser || isTool) 11.dp else 7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (isUser || isTool) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(roleLabel, color = roleColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (item.timestampMs > 0) Text(formatConversationTime(item.timestampMs), color = Color(0xFF8A968B), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (isTool && !item.toolStatus.isNullOrBlank()) {
                Text(
                    when (item.toolStatus?.lowercase()) {
                        "completed", "done", "success" -> if (chinese) "已完成" else "Completed"
                        "failed", "error" -> if (chinese) "失败" else "Failed"
                        else -> if (chinese) "执行中" else "Running"
                    },
                    color = roleColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val content = item.text.ifBlank { item.toolDetail.orEmpty() }
            if (content.isBlank()) {
                Text(if (isTool) if (chinese) "工具调用" else "Tool call" else if (chinese) "正在生成…" else "Generating…", color = Color(0xFF69766B), style = MaterialTheme.typography.bodySmall)
            } else {
                parseConversationBlocks(content).forEach { block ->
                    when (block) {
                        is ConversationBlock.Code -> Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF202A24), tonalElevation = 0.dp) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (block.language.isNotBlank()) Text(block.language, color = Color(0xFF9FC5A4), style = MaterialTheme.typography.labelSmall)
                                Text(block.text, color = Color(0xFFE2F0E2), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                        is ConversationBlock.Paragraph -> {
                            block.text.split('\n').forEach { line ->
                                val trimmed = line.trimStart()
                                val isHeading = trimmed.startsWith("#")
                                val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")
                                val rendered = when {
                                    isBullet -> "• " + trimmed.drop(2).trimStart()
                                    isHeading -> trimmed.trimStart('#').trimStart()
                                    else -> line
                                }
                                Text(
                                    rendered,
                                    color = Color(0xFF334238),
                                    style = if (isHeading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatConversationTime(timestampMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(timestampMs))

@Composable
private fun ApprovalActions(chinese: Boolean, detail: String, structured: AtlasApproval?, onSelect: (String) -> Unit) {
    val options = structured?.options
        ?.takeIf { it.isNotEmpty() }
        ?.map { option -> ApprovalOption(option.value, localizedApprovalLabel(option, chinese), option.value == "__other__") }
        ?: parseApprovalOptions(detail, chinese)
    val prompt = structured?.prompt?.takeIf { it.isNotBlank() } ?: detail
    var custom by remember(prompt) { mutableStateOf("") }
    var otherArmed by remember(prompt) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            androidx.compose.material3.HorizontalDivider(color = Color(0xFFE6EAE6))
            Text(if (chinese) "需要审批" else "Approval required", color = Color(0xFF9A6B2F), fontWeight = FontWeight.SemiBold)
            if (prompt.isNotBlank()) {
                Text(prompt, color = Color(0xFF5F4A2F), style = MaterialTheme.typography.bodySmall, maxLines = 12)
            }
            options.chunked(2).forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { index, option ->
                        if ((rowIndex * 2 + index) == 0) {
                            Button(onClick = { if (option.isOther) { if (option.value != "__other__") onSelect(option.value); otherArmed = true } else onSelect(option.value) }, modifier = Modifier.weight(1f)) {
                                Text(option.label, maxLines = 2)
                            }
                        } else {
                            OutlinedButton(onClick = { if (option.isOther) { if (option.value != "__other__") onSelect(option.value); otherArmed = true } else onSelect(option.value) }, modifier = Modifier.weight(1f)) {
                                Text(option.label, maxLines = 2)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(if (otherArmed) { if (chinese) "输入补充内容" else "Enter the additional instruction" } else { if (chinese) "其他：输入回复" else "Other: enter a response" }) },
                )
                Button(onClick = { val value = custom.trim(); if (value.isNotEmpty()) { onSelect(value); custom = ""; otherArmed = false } }, enabled = custom.trim().isNotEmpty()) {
                    Text(if (chinese) "发送" else "Send")
                }
            }
    }
}

private fun localizedApprovalLabel(option: AtlasApprovalOption, chinese: Boolean): String {
    if (!chinese) return option.label
    return when {
        option.value == "__other__" -> "其他"
        option.label.equals("Allow", ignoreCase = true) -> "允许"
        option.label.equals("Deny", ignoreCase = true) -> "拒绝"
        option.label.equals("Continue", ignoreCase = true) -> "继续"
        option.label.equals("Cancel", ignoreCase = true) -> "取消"
        else -> option.label
    }
}

private data class ApprovalOption(val value: String, val label: String, val isOther: Boolean = false)

private fun parseApprovalOptions(detail: String, chinese: Boolean): List<ApprovalOption> {
    val numbered = Regex("^\\s*(\\d+)[.)]\\s*(.+?)\\s*$")
        .findAll(detail.replace("\r\n", "\n"))
        .map {
            val rawLabel = it.groupValues[2].trim()
            val other = isOtherApprovalLabel(rawLabel)
            ApprovalOption(it.groupValues[1], if (other) if (chinese) "其他" else "Other" else rawLabel, other)
        }
        .filter { it.label.length in 1..180 }
        .distinctBy { it.value }
        .toList()
    if (numbered.isNotEmpty()) {
        val visible = numbered.take(8).toMutableList()
        numbered.firstOrNull { it.isOther }?.let { parsedOther ->
            if (visible.none { it.value == parsedOther.value }) visible += parsedOther
        }
        if (visible.none { it.isOther }) visible += ApprovalOption("__other__", if (chinese) "其他" else "Other", true)
        return visible
    }
    val lower = detail.lowercase()
    val yesNo = lower.contains("[y/n") || lower.contains("[yes/no") || lower.contains("yes/no") || lower.contains("y/n")
    if (yesNo) return listOf(
        ApprovalOption("y", if (chinese) "是 / 继续" else "Yes / continue"),
        ApprovalOption("n", if (chinese) "否 / 停止" else "No / stop"),
        ApprovalOption("__other__", if (chinese) "其他" else "Other", true),
    )
    val hasApprovalLanguage = lower.contains("allow") || lower.contains("approve") || lower.contains("permission") || detail.contains("允许") || detail.contains("审批") || detail.contains("授权")
    if (hasApprovalLanguage) return listOf(
        ApprovalOption("1", if (chinese) "允许" else "Allow"),
        ApprovalOption("2", if (chinese) "拒绝" else "Deny"),
        ApprovalOption("__other__", if (chinese) "其他" else "Other", true),
    )
    return listOf(
        ApprovalOption("1", if (chinese) "继续" else "Continue"),
        ApprovalOption("2", if (chinese) "取消" else "Cancel"),
        ApprovalOption("__other__", if (chinese) "其他" else "Other", true),
    )
}

private fun isOtherApprovalLabel(label: String): Boolean {
    val lower = label.lowercase()
    return Regex("\\bother\\b|tell\\s+codex|different\\s+(instructions|approach|way)|provide\\s+(feedback|instructions)|custom").containsMatchIn(lower)
        || label.contains("补充") || label.contains("其他") || label.contains("其它") || label.contains("自定义")
}

private fun appendVoiceText(existing: String, transcript: String): String {
    val left = existing.trim()
    val right = transcript.trim()
    return when {
        left.isBlank() -> right
        right.isBlank() -> left
        else -> "$left $right"
    }
}

@Composable
private fun VoiceInputPanel(
    chinese: Boolean,
    snapshot: VoiceInputSnapshot,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onInsert: () -> Unit,
    onInsertAndSend: () -> Unit,
) {
    val listening = snapshot.phase == VoiceInputPhase.Listening
    val review = snapshot.phase == VoiceInputPhase.Review
    val failed = snapshot.phase == VoiceInputPhase.Failed
    val transcript = snapshot.transcript.ifBlank { snapshot.partialTranscript }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (failed) Color(0xFFFFF4F2) else Color(0xFFF0F7F0),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoiceVolumeMeter(volume = snapshot.volume, muted = snapshot.muted)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            listening && snapshot.continuous -> if (chinese) "连续语音" else "Voice mode"
                            listening -> if (chinese) "正在聆听" else "Listening"
                            snapshot.phase == VoiceInputPhase.Processing -> if (chinese) "正在整理语音" else "Processing speech"
                            review -> if (chinese) "确认转写" else "Review transcription"
                            else -> if (chinese) "语音输入失败" else "Voice input failed"
                        },
                        color = if (failed) Color(0xFFB44A45) else Color(0xFF2F7C3B),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatVoiceDuration(snapshot.durationSeconds) + if (snapshot.continuous) " · " + (if (snapshot.muted) if (chinese) "已静音" else "Muted" else if (chinese) "自动发送" else "Auto-send") else "",
                        color = Color(0xFF667466),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (snapshot.continuous && listening) {
                    TextButton(onClick = onMute) { Text(if (snapshot.muted) if (chinese) "取消静音" else "Unmute" else if (chinese) "静音" else "Mute") }
                    TextButton(onClick = onStop) { Text(if (chinese) "结束" else "Stop") }
                } else if (listening) {
                    TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") }
                    TextButton(onClick = onStop) { Text(if (chinese) "完成" else "Done") }
                }
            }
            if (transcript.isNotBlank()) {
                Text(
                    transcript,
                    color = Color(0xFF334238),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            if (failed) {
                Text(snapshot.error ?: if (chinese) "语音识别失败" else "Speech recognition failed", color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text(if (chinese) "重试" else "Retry") }
                    OutlinedButton(onClick = onDiscard) { Text(if (chinese) "丢弃" else "Discard") }
                }
            } else if (review) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onInsert) { Text(if (chinese) "插入" else "Insert") }
                    Button(onClick = onInsertAndSend) { Text(if (chinese) "插入并发送" else "Insert & send") }
                }
            }
        }
    }
}

@Composable
private fun VoiceVolumeMeter(volume: Float, muted: Boolean) {
    Row(modifier = Modifier.size(width = 38.dp, height = 28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val level = if (muted) 0f else volume.coerceIn(0f, 1f)
        listOf(0.45f, 0.75f, 1f).forEach { multiplier ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((6f + 18f * level * multiplier).dp)
                    .background(if (muted) Color(0xFFB7C2B8) else Color(0xFF58BE70), RoundedCornerShape(3.dp)),
            )
        }
    }
}

private fun formatVoiceDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(Locale.US, mins, secs)
}

private enum class ScannerState { Starting, Ready, Error }
private enum class ScanFeedback { FrameUnavailable, ScannerUnavailable, InvalidPairing, RecognitionFailed }

@Composable
private fun ScannerScreen(chinese: Boolean, onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scannerOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val scanner = remember { BarcodeScanning.getClient(scannerOptions) }
    val found = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val lastStatusAt = remember { AtomicLong(0L) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var scannerState by remember { mutableStateOf(ScannerState.Starting) }
    var status by remember { mutableStateOf(if (chinese) "正在启动相机…" else "Starting camera…") }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            providerRef.value?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PreviewView(it).also { view ->
                view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    if (disposed.get()) return@addListener
                    runCatching {
                        val provider = providerFuture.get()
                        providerRef.value = provider
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            scanFrame(
                                proxy = proxy,
                                scanner = scanner,
                                found = found,
                                onResult = { value ->
                                    mainExecutor.execute {
                                        if (!disposed.get()) {
                                            found.set(true)
                                            scannerState = ScannerState.Ready
                                            status = if (chinese) "已识别，正在连接…" else "Recognized, connecting…"
                                            onResult(value)
                                        }
                                    }
                                },
                                onStatus = { message ->
                                    val now = SystemClock.elapsedRealtime()
                                    val previous = lastStatusAt.get()
                                    if (now - previous >= 1_500L && lastStatusAt.compareAndSet(previous, now)) {
                                        mainExecutor.execute {
                                            if (!disposed.get()) {
                                                status = when (message) {
                                                    ScanFeedback.FrameUnavailable -> if (chinese) "无法读取相机画面" else "Unable to read camera frames"
                                                    ScanFeedback.ScannerUnavailable -> if (chinese) "扫码服务不可用" else "Barcode scanner unavailable"
                                                    ScanFeedback.InvalidPairing -> if (chinese) "这不是 Codex Atlas 配对二维码" else "This is not a Codex Atlas pairing QR"
                                                    ScanFeedback.RecognitionFailed -> if (chinese) "扫码识别失败" else "QR recognition failed"
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        mainExecutor.execute {
                            if (!disposed.get()) {
                                scannerState = ScannerState.Ready
                                status = if (chinese) "将二维码放入取景框" else "Place the QR code inside the frame"
                            }
                        }
                    }.onFailure {
                        mainExecutor.execute {
                            if (!disposed.get()) {
                                scannerState = ScannerState.Error
                                status = it.message ?: if (chinese) "无法打开相机" else "Unable to open camera"
                            }
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
            } }, modifier = Modifier.fillMaxSize()
        )
        Column(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (chinese) "扫描 Codex Atlas 二维码" else "Scan the Codex Atlas QR", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(status, color = if (scannerState == ScannerState.Error) Color(0xFFFFB4AB) else Color(0xFFDCE8DC))
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(238.dp)
                .border(2.dp, if (scannerState == ScannerState.Error) Color(0xFFFF8275) else Color.White, RoundedCornerShape(18.dp)),
        )
        if (scannerState == ScannerState.Error) {
            Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(status, color = Color(0xFFFFB4AB))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    })
                }) { Text(if (chinese) "打开相机权限" else "Open camera permissions", color = Color.White) }
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) { Text(if (chinese) "取消" else "Cancel", color = Color.White) }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun scanFrame(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    found: AtomicBoolean,
    onResult: (String) -> Unit,
    onStatus: (ScanFeedback) -> Unit,
) {
    val image = proxy.image
    if (image == null || found.get()) {
        proxy.close()
        return
    }
    val input = runCatching { InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees) }
        .getOrElse {
            proxy.close()
            onStatus(ScanFeedback.FrameUnavailable)
            return
        }
    val task = runCatching { scanner.process(input) }
        .getOrElse {
            proxy.close()
            onStatus(ScanFeedback.ScannerUnavailable)
            return
        }
    task.addOnSuccessListener { codes ->
        if (found.get()) return@addOnSuccessListener
        val value = codes.asSequence().mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
        if (value != null) {
            if (MainActivity.parsePairing(value) != null && found.compareAndSet(false, true)) {
                onResult(value)
            } else if (MainActivity.parsePairing(value) == null) {
                onStatus(ScanFeedback.InvalidPairing)
            }
        }
    }.addOnFailureListener {
        onStatus(ScanFeedback.RecognitionFailed)
    }.addOnCompleteListener {
        proxy.close()
    }
}
