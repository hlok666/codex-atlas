package com.codexatlas.mobile

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private var pairingFromIntent by mutableStateOf("")
    private var sessionIdFromIntent by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumePairing(intent)
        consumeSessionIntent(intent)
        setContent { AtlasMobileApp(pairingFromIntent, sessionIdFromIntent) }
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
            BridgePreferences.savePairing(this, pairing.lanUrl, pairing.tunnelUrl, pairing.token, pairing.preferTunnel)
        }
    }

    private fun consumeSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        if (sessionId.isNotBlank()) sessionIdFromIntent = sessionId
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.codexatlas.mobile.extra.SESSION_ID"

        fun storedPairing(context: Context): String {
            val lan = BridgePreferences.url(context).trim()
            val tunnel = BridgePreferences.tunnelUrl(context).trim()
            val token = BridgePreferences.token(context).trim()
            if (lan.isBlank() || token.isBlank()) return ""
            return Uri.Builder()
                .scheme("codex-atlas")
                .authority("connect")
                .appendQueryParameter("lan", lan)
                .apply { if (tunnel.isNotBlank()) appendQueryParameter("tunnel", tunnel) }
                .appendQueryParameter("token", token)
                .appendQueryParameter("preferTunnel", if (BridgePreferences.preferTunnel(context)) "1" else "0")
                .build()
                .toString()
        }

        fun parsePairing(raw: String): PairingDetails? {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "codex-atlas" || uri.host != "connect") return null
            val lan = uri.getQueryParameter("lan")?.trim().orEmpty()
            val tunnel = uri.getQueryParameter("tunnel")?.trim().orEmpty()
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (lan.isBlank() || token.isBlank()) return null
            return PairingDetails(lan, tunnel, token, uri.getQueryParameter("preferTunnel") == "1")
        }
    }
}

data class PairingDetails(val lanUrl: String, val tunnelUrl: String, val token: String, val preferTunnel: Boolean)

private fun mergeAtlasMessages(current: List<AtlasMessage>, incoming: List<AtlasMessage>): List<AtlasMessage> =
    (current + incoming)
        .filter { it.id.isNotBlank() || it.text.isNotBlank() }
        .distinctBy { it.id.ifBlank { "${it.timestampMs}:${it.role}:${it.text}" } }
        .sortedBy { it.timestampMs }

private sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Connected(val title: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

@Composable
private fun AtlasMobileApp(initialPairing: String, initialSessionId: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zh = remember { Locale.getDefault().language.startsWith("zh") }
    var pairing by remember(initialPairing) { mutableStateOf(initialPairing.ifBlank { MainActivity.storedPairing(context) }) }
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
    var snapshot by remember { mutableStateOf<AtlasSnapshot?>(null) }
    var sessions by remember { mutableStateOf<List<AtlasSession>>(emptyList()) }
    var selectedSessionId by remember(initialSessionId) { mutableStateOf(initialSessionId) }
    var syncCursorMs by remember { mutableStateOf(0L) }
    var messages by remember { mutableStateOf<List<AtlasMessage>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var sessionQuery by remember { mutableStateOf("") }
    var messageBusy by remember { mutableStateOf(false) }
    var createVisible by remember { mutableStateOf(false) }
    var createCwd by remember { mutableStateOf("") }
    var createPrompt by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    var paseoBusy by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AtlasUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val updateManager = remember(context) { AppUpdateManager(context) }
    val scrollState = rememberScrollState()
    val messageScrollState = rememberScrollState()
    var scannerVisible by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
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
                val opened = updateManager.openInstaller(file)
                if (!opened) Toast.makeText(context, if (zh) "请允许安装未知应用后继续" else "Allow installs from this source, then continue", Toast.LENGTH_LONG).show()
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
        state = ConnectionState.Testing
        syncCursorMs = 0L
        scope.launch {
            BridgePreferences.savePairing(context, details.lanUrl, details.tunnelUrl, details.token, details.preferTunnel)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                    val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                    val client = AtlasBridgeClient(primary, details.token)
                    val fresh = client.snapshotAny(fallback)
                    val available = client.listSessionsAny(fallback)
                    Triple(fresh, available, available.firstOrNull { it.id == fresh.sessionId }?.id ?: fresh.sessionId)
                }
            }
            result.onSuccess { (freshSnapshot, available, preferredId) ->
                snapshot = freshSnapshot
                sessions = available
                val requestedId = initialSessionId.takeIf { requested -> available.any { it.id == requested } }
                selectedSessionId = requestedId ?: preferredId.ifBlank { available.firstOrNull()?.id.orEmpty() }
                val target = selectedSessionId
                messages = if (target.isBlank()) freshSnapshot.messages else withContext(Dispatchers.IO) { runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).messagesAny(target, if (details.preferTunnel) details.lanUrl else details.tunnelUrl) }.getOrDefault(freshSnapshot.messages) }
                state = ConnectionState.Connected(freshSnapshot.title)
                AtlasWidgetReceiver.requestRefresh(context)
            }.onFailure { error ->
                state = ConnectionState.Failed(error.message ?: if (zh) "连接失败" else "Connection failed")
            }
        }
    }

    LaunchedEffect(pairing) {
        if (MainActivity.parsePairing(pairing) != null) {
            delay(250)
            connect(pairing)
        }
    }

    LaunchedEffect(state, pairing, selectedSessionId, voiceSnapshot.active, readRepliesAloud) {
        val details = MainActivity.parsePairing(pairing) ?: return@LaunchedEffect
        while (state is ConnectionState.Connected) {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                    val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                    // The bridge holds this request until a new event arrives
                    // (or the server-side timeout expires), so the UI updates
                    // immediately without a fixed three-second polling tick.
                    AtlasBridgeClient(primary, details.token).syncAny(syncCursorMs, fallback, 20_000)
                }.getOrNull()
            }
            if (fresh != null) {
                val previousCursor = syncCursorMs
                snapshot = fresh.snapshot ?: snapshot
                sessions = fresh.sessions
                val target = selectedSessionId
                    .takeIf { id -> id.isNotBlank() && fresh.sessions.any { it.id == id } }
                    ?: fresh.snapshot?.sessionId?.takeIf { id -> fresh.sessions.any { it.id == id } }
                    ?: fresh.sessions.firstOrNull()?.id.orEmpty()
                if (target.isNotBlank() && selectedSessionId != target) selectedSessionId = target
                val incoming = fresh.events.firstOrNull { it.sessionId == target }?.messages.orEmpty()
                if (incoming.isNotEmpty()) {
                    messages = mergeAtlasMessages(messages, incoming)
                    if (readRepliesAloud) {
                        incoming.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
                            ?.let { speechOutput.speak(it.text) }
                    }
                } else if (previousCursor == 0L && fresh.snapshot?.sessionId == target) {
                    messages = mergeAtlasMessages(messages, fresh.snapshot.messages)
                }
                syncCursorMs = maxOf(syncCursorMs, fresh.cursorMs)
                state = ConnectionState.Connected(fresh.snapshot?.title ?: snapshot?.title.orEmpty())
                AtlasWidgetReceiver.requestRefresh(context)
            } else {
                // Back off briefly on a disconnected LAN/tunnel before trying
                // the next candidate. A healthy bridge uses long-polling above.
                delay(1_500)
            }
        }
    }

    // Continuous voice mode queues every utterance and sends it to the same
    // Codex session as the normal composer. This keeps short pauses from
    // dropping speech while a previous request is still in flight.
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
            messageBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                    val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                    AtlasBridgeClient(primary, details.token).sendMessageAny(conversationId, text, fallback)
                }
            }
            result.onSuccess {
                val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                messages = withContext(Dispatchers.IO) {
                    runCatching { AtlasBridgeClient(primary, details.token).messagesAny(conversationId, fallback) }
                        .getOrDefault(messages)
                }
            }.onFailure { error ->
                state = ConnectionState.Failed(error.message ?: if (zh) "语音消息发送失败" else "Voice message failed")
                Toast.makeText(context, error.message ?: if (zh) "语音消息发送失败" else "Voice message failed", Toast.LENGTH_LONG).show()
            }
            messageBusy = false
        }
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9F6)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Codex Atlas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF243025))
                    Text(if (zh) "连接一次，手机卡片实时同步" else "Connect once, keep the phone card live", color = Color(0xFF667466))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openPublicUrl(context, ProjectLinks.projectUrl) }) {
                        Text("GitHub")
                    }
                    TextButton(onClick = { Toast.makeText(context, if (zh) "请从桌面添加 Codex Atlas 卡片" else "Add the Codex Atlas card from your home screen", Toast.LENGTH_SHORT).show() }) {
                        Text(if (zh) "卡片" else "Card")
                    }
                }
            }
            if (updateBusy || availableUpdate != null || updateError != null) {
                val update = availableUpdate
                Surface(shape = RoundedCornerShape(12.dp), color = if (updateError != null) Color(0xFFFFF4F2) else Color.White, tonalElevation = 0.dp) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (update?.available == true) (if (zh) "发现新版本" else "Update available") else if (updateBusy) (if (zh) "检查 GitHub Release…" else "Checking GitHub releases…") else (if (zh) "已是最新版本" else "You're up to date"), fontWeight = FontWeight.SemiBold, color = Color(0xFF243025))
                                if (update != null) Text("v${update.currentVersion} → v${update.latestVersion}", color = Color(0xFF667466), style = MaterialTheme.typography.bodySmall)
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
            }
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 0.dp) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(if (zh) "配对手机" else "Pair this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(if (zh) "从桌面端扫描二维码，或粘贴配对链接。链接有效时会自动连接。" else "Scan the QR from Atlas Desktop, or paste the pairing link. A valid link connects automatically.", color = Color(0xFF667466), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = pairing,
                        onValueChange = { pairing = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (zh) "配对链接" else "Pairing link") },
                        placeholder = { Text("codex-atlas://connect…") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scannerVisible = true
                            else permissionLauncher.launch(Manifest.permission.CAMERA)
                        }) { Text(if (zh) "扫描二维码" else "Scan QR") }
                        Button(onClick = { connect(pairing) }, enabled = MainActivity.parsePairing(pairing) != null && state !is ConnectionState.Testing) {
                            if (state is ConnectionState.Testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(if (zh) "立即连接" else "Connect")
                        }
                    }
                    if (permissionDenied) Text(if (zh) "相机权限被拒绝，请在系统设置中允许。" else "Camera permission was denied. Allow it in system settings.", color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            ConnectionStatus(state, zh)
            if (snapshot != null && MainActivity.parsePairing(pairing) != null) {
                val details = MainActivity.parsePairing(pairing)!!
                val selectedSession = sessions.firstOrNull { it.id == selectedSessionId }
                val conversationId = selectedSession?.id ?: snapshot!!.sessionId
                val conversationTitle = selectedSession?.title?.ifBlank { null } ?: snapshot!!.title
                val conversationFolder = selectedSession?.cwd?.ifBlank { null } ?: snapshot!!.folder
                val conversationModel = selectedSession?.model?.ifBlank { null } ?: snapshot!!.model
                LaunchedEffect(conversationId) {
                    if (conversationId.isBlank()) return@LaunchedEffect
                    messages = withContext(Dispatchers.IO) {
                        runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).messagesAny(conversationId, if (details.preferTunnel) details.lanUrl else details.tunnelUrl) }
                            .getOrDefault(emptyList())
                    }
                }
                LaunchedEffect(conversationId, messages.size, messages.lastOrNull()?.id) {
                    if (messages.isNotEmpty()) messageScrollState.animateScrollTo(messageScrollState.maxValue)
                }
                if (sessions.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 0.dp) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (zh) "会话" else "Sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Text("${sessions.size}", color = Color(0xFF667466), style = MaterialTheme.typography.bodySmall)
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
                                TextButton(onClick = { selectedSessionId = item.id }, modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title.ifBlank { if (zh) "未命名会话" else "Untitled session" }, maxLines = 1, color = if (item.id == conversationId) Color(0xFF2F7C3B) else Color(0xFF243025), fontWeight = if (item.id == conversationId) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(listOf(item.cwd, item.model, item.liveState.ifBlank { if (item.running) "working" else "idle" }).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            when {
                                                item.requiresAttention -> "!"
                                                item.running -> "●"
                                                else -> "○"
                                            },
                                            color = when {
                                                item.requiresAttention -> Color(0xFFD85D59)
                                                item.running -> Color(0xFF58BE70)
                                                else -> Color(0xFFB7C2B8)
                                            },
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            if (visibleSessions.isEmpty()) Text(if (zh) "没有匹配的会话" else "No matching sessions", color = Color(0xFF7A867B), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 0.dp) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (zh) "Codex 对话" else "Codex conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(conversationTitle, color = Color(0xFF243025), fontWeight = FontWeight.SemiBold)
                        Text(listOf(conversationFolder, conversationModel, selectedSession?.liveState ?: snapshot!!.state).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFF667466), style = MaterialTheme.typography.bodySmall)
                        if (selectedSession?.requiresAttention == true) {
                            ApprovalActions(
                                chinese = zh,
                                detail = selectedSession.lastError ?: selectedSession.lastOutput ?: snapshot!!.lastOutput,
                                structured = selectedSession.approval ?: snapshot!!.approval,
                                onSelect = { choice ->
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                                                val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                                                AtlasBridgeClient(primary, details.token).inputAny(conversationId, choice, fallback)
                                            }
                                        }
                                        result.onFailure { error -> Toast.makeText(context, error.message ?: if (zh) "审批发送失败" else "Approval failed", Toast.LENGTH_LONG).show() }
                                    }
                                },
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 320.dp).verticalScroll(messageScrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (messages.isEmpty()) {
                                Text(snapshot!!.lastOutput.ifBlank { if (zh) "暂无最新输出" else "No recent output" }, color = Color(0xFF4D5C4E), style = MaterialTheme.typography.bodySmall)
                            } else {
                                messages.takeLast(80).forEach { item ->
                                    ConversationMessage(item, zh)
                                }
                            }
                        }
                        fun sendToConversation(text: String) {
                            val normalized = text.trim()
                            if (normalized.isEmpty() || messageBusy || conversationId.isBlank()) return
                            messageBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                                        val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                                        AtlasBridgeClient(primary, details.token).sendMessageAny(conversationId, normalized, fallback)
                                    }
                                }
                                result.onSuccess {
                                    message = ""
                                    val primary = if (details.preferTunnel) details.tunnelUrl else details.lanUrl
                                    val fallback = if (details.preferTunnel) details.lanUrl else details.tunnelUrl
                                    messages = withContext(Dispatchers.IO) {
                                        runCatching { AtlasBridgeClient(primary, details.token).messagesAny(conversationId, fallback) }
                                            .getOrDefault(messages)
                                    }
                                }.onFailure { error ->
                                    state = ConnectionState.Failed(error.message ?: if (zh) "消息发送失败" else "Message failed")
                                }
                                messageBusy = false
                            }
                        }
                        OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (zh) "发送消息" else "Send a message") }, minLines = 2, maxLines = 5)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { sendToConversation(message) }, enabled = message.isNotBlank() && !messageBusy) {
                                if (messageBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (zh) "发送" else "Send")
                            }
                            TextButton(onClick = {
                                pendingVoiceMode = false
                                if (!voiceController.isAvailable()) {
                                    Toast.makeText(context, if (zh) "系统不支持语音识别" else "Speech recognition is unavailable", Toast.LENGTH_SHORT).show()
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    voiceController.start(message, continuous = false)
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }, enabled = voiceController.isAvailable() && !messageBusy && !voiceSnapshot.active) { Text(if (zh) "语音输入" else "Dictate") }
                            OutlinedButton(onClick = {
                                pendingVoiceMode = true
                                if (!voiceController.isAvailable()) {
                                    Toast.makeText(context, if (zh) "系统不支持语音识别" else "Speech recognition is unavailable", Toast.LENGTH_SHORT).show()
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    voiceController.start(message, continuous = true)
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }, enabled = voiceController.isAvailable() && !messageBusy && !voiceSnapshot.active) { Text(if (zh) "连续语音" else "Voice mode") }
                            TextButton(onClick = {
                                readRepliesAloud = !readRepliesAloud
                                BridgePreferences.saveReadRepliesAloud(context, readRepliesAloud)
                                if (!readRepliesAloud) speechOutput.stop()
                            }) {
                                Text(if (readRepliesAloud) { if (zh) "朗读中" else "Read on" } else { if (zh) "朗读" else "Read" })
                            }
                            OutlinedButton(onClick = { createVisible = !createVisible }) { Text(if (zh) "新建会话" else "New session") }
                        }
                        OutlinedButton(onClick = {
                            if (paseoBusy) return@OutlinedButton
                            paseoBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).importAllPaseoAny(if (details.preferTunnel) details.lanUrl else details.tunnelUrl) } }
                                result.onSuccess { Toast.makeText(context, if (zh) "已同步到 Paseo" else "Imported into Paseo", Toast.LENGTH_SHORT).show() }.onFailure { error -> Toast.makeText(context, error.message ?: if (zh) "Paseo 同步失败" else "Paseo import failed", Toast.LENGTH_LONG).show() }
                                paseoBusy = false
                            }
                        }, enabled = !paseoBusy && conversationId.isNotBlank()) {
                            if (paseoBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (zh) "同步到 Paseo" else "Sync to Paseo")
                        }
                        if (audioPermissionDenied) Text(if (zh) "录音权限被拒绝，请在系统设置中允许。" else "Microphone permission was denied. Allow it in system settings.", color = Color(0xFFB44A45), style = MaterialTheme.typography.bodySmall)
                        if (createVisible) {
                            OutlinedTextField(value = createCwd, onValueChange = { createCwd = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (zh) "工作目录" else "Working directory") }, singleLine = true)
                            OutlinedTextField(value = createPrompt, onValueChange = { createPrompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (zh) "初始提示词（可选）" else "Initial prompt (optional)") }, minLines = 2, maxLines = 4)
                            Button(onClick = {
                                if (createCwd.isBlank() || createBusy) return@Button
                                createBusy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).createSessionAny(createCwd.trim(), createPrompt.trim(), snapshot!!.model, "Workspace write", if (details.preferTunnel) details.lanUrl else details.tunnelUrl) } }
                                    result.onSuccess {
                                        createVisible = false
                                        createPrompt = ""
                                        val fresh = withContext(Dispatchers.IO) { runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).listSessionsAny(if (details.preferTunnel) details.lanUrl else details.tunnelUrl) }.getOrDefault(sessions) }
                                        sessions = fresh
                                        selectedSessionId = fresh.firstOrNull()?.id ?: selectedSessionId
                                        snapshot = withContext(Dispatchers.IO) { runCatching { AtlasBridgeClient(if (details.preferTunnel) details.tunnelUrl else details.lanUrl, details.token).snapshotAny(if (details.preferTunnel) details.lanUrl else details.tunnelUrl) }.getOrNull() } ?: snapshot
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
            OutlinedButton(onClick = { addCardToHome(context, zh) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (zh) "添加 Codex Atlas 卡片到桌面" else "Add Codex Atlas card to home screen")
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
        is ConnectionState.Failed -> Color(0xFFD85D59) to state.message
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text("●", color = dot, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(text, color = Color(0xFF4D5C4E), style = MaterialTheme.typography.bodyMedium)
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
    val roleLabel = when {
        isUser -> if (chinese) "你" else "You"
        isTool -> if (chinese) "工具" else "Tool"
        else -> "Codex"
    }
    val roleColor = when {
        isUser -> Color(0xFF2F7C3B)
        isTool -> Color(0xFF9A6B2F)
        else -> Color(0xFF69766B)
    }
    val surfaceColor = when {
        isUser -> Color(0xFFEAF5EB)
        isTool -> Color(0xFFFFF6E6)
        else -> Color(0xFFF7F9F6)
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(surfaceColor, RoundedCornerShape(10.dp)).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(roleLabel, color = roleColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (item.timestampMs > 0) Text(formatConversationTime(item.timestampMs), color = Color(0xFF8A968B), style = MaterialTheme.typography.labelSmall)
        }
        if (item.text.isBlank()) {
            Text(if (isTool) if (chinese) "工具调用" else "Tool call" else if (chinese) "正在生成…" else "Generating…", color = Color(0xFF69766B), style = MaterialTheme.typography.bodySmall)
        } else {
            parseConversationBlocks(item.text).forEach { block ->
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
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFFF6E6), tonalElevation = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                    modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
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

@Composable
private fun ScannerScreen(chinese: Boolean, onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val found = remember { AtomicBoolean(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PreviewView(it).also { view ->
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy -> scanFrame(proxy, scanner, found, onResult) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { error = it.message ?: if (chinese) "无法打开相机" else "Unable to open camera" }
                }, ContextCompat.getMainExecutor(context))
            } }, modifier = Modifier.fillMaxSize()
        )
        Column(modifier = Modifier.align(Alignment.TopCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (chinese) "扫描 Codex Atlas 二维码" else "Scan the Codex Atlas QR", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(if (chinese) "识别后会自动连接" else "It connects as soon as it is recognized", color = Color(0xFFDCE8DC))
        }
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFFB4AB), modifier = Modifier.align(Alignment.Center).padding(24.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text(if (chinese) "取消" else "Cancel", color = Color.White) }
    }
}

private fun scanFrame(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, found: AtomicBoolean, onResult: (String) -> Unit) {
    val image = proxy.image
    if (image == null || found.get()) {
        proxy.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { codes ->
            val value = codes.firstNotNullOfOrNull { it.rawValue }
            if (value != null && MainActivity.parsePairing(value) != null && found.compareAndSet(false, true)) onResult(value)
        }
        .addOnCompleteListener { proxy.close() }
}
