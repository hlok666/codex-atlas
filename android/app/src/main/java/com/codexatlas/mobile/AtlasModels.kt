package com.codexatlas.mobile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtlasDeviceProfile(
    val id: String = "",
    val name: String = "Codex Atlas",
    val kind: String = "desktop",
    val lanUrl: String = "",
    val tunnelUrl: String = "",
    val token: String = "",
    val preferTunnel: Boolean = false,
    val route: String = "auto",
    val lastConnectedAtMs: Long = 0,
)

@Serializable
data class AtlasSnapshot(
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceKind: String = "",
    val updatedAtMs: Long = 0,
    val sessionId: String = "",
    val title: String = "No active session",
    val folder: String = "",
    val model: String = "",
    val state: String = "idle",
    val lastOutput: String = "",
    val canActivate: Boolean = false,
    val canInputContinue: Boolean = false,
    val balanceRemaining: Double? = null,
    val balanceUnit: String = "USD",
    val balanceProvider: String = "",
    val balanceCheckedAtMs: Long = 0,
    val balanceStatus: String = "loading",
    val balanceStale: Boolean = false,
    val balanceError: String? = null,
    val requiresAttention: Boolean = false,
    val lastError: String? = null,
    val foreground: Boolean = false,
    val statusSource: String = "",
    val lastEventAtMs: Long = 0,
    val messages: List<AtlasMessage> = emptyList(),
    val approval: AtlasApproval? = null,
)

@Serializable
data class AtlasBalance(
    val success: Boolean = false,
    val remaining: Double? = null,
    val unit: String = "USD",
    val provider: String = "",
    val status: String = "loading",
    val checkedAtMs: Long = 0,
    val stale: Boolean = false,
    val error: String? = null,
)

@Serializable
data class AtlasApprovalOption(
    val value: String = "",
    val label: String = "",
)

@Serializable
data class AtlasApproval(
    val requestId: String = "",
    val prompt: String = "",
    val options: List<AtlasApprovalOption> = emptyList(),
)

@Serializable
data class AtlasMessage(
    val id: String = "",
    val role: String = "assistant",
    val text: String = "",
    val timestampMs: Long = 0,
    val kind: String = "",
    val seq: Long = 0,
    val seqStart: Long = 0,
    val seqEnd: Long = 0,
    val sourceSeqRanges: List<AtlasSeqRange> = emptyList(),
    val turnId: String? = null,
    val callId: String? = null,
    val toolStatus: String? = null,
    val toolDetail: String? = null,
    val approvalId: String? = null,
    val approvalOptions: List<AtlasApprovalOption> = emptyList(),
)

@Serializable
data class AtlasSeqRange(
    val source: String = "",
    val start: Long = 0,
    val end: Long = 0,
)

@Serializable
data class AtlasSession(
    val id: String = "",
    val title: String = "",
    val preview: String = "",
    val cwd: String = "",
    val model: String = "",
    val permission: String = "",
    val running: Boolean = false,
    val liveState: String = "",
    val lastOutput: String? = null,
    val requiresAttention: Boolean = false,
    val lastError: String? = null,
    val foreground: Boolean = false,
    val statusSource: String = "",
    val lastEventAtMs: Long = 0,
    val approval: AtlasApproval? = null,
)

@Serializable
data class AtlasSyncEventBatch(
    val sessionId: String = "",
    val messages: List<AtlasMessage> = emptyList(),
)

@Serializable
data class AtlasSyncResponse(
    val cursorMs: Long = 0,
    val syncEpoch: String = "",
    val nextSeq: Long = 0,
    val reset: Boolean = false,
    val gap: Boolean = false,
    val snapshot: AtlasSnapshot? = null,
    val sessions: List<AtlasSession> = emptyList(),
    val events: List<AtlasSyncEventBatch> = emptyList(),
)

@Serializable
data class AtlasModelOption(
    val slug: String = "",
    val displayName: String = "",
    val official: Boolean = false,
    val source: String = "",
)

@Serializable
data class AtlasRuntimeDefaults(
    val model: String = "",
    val permission: String = "Workspace write",
    val reasoningEffort: String = "medium",
    val provider: String = "",
    val providerModel: String? = null,
    val models: List<AtlasModelOption> = emptyList(),
    val source: String = "",
    val fetchedAtMs: Long = 0,
    val error: String? = null,
)

@Serializable
data class AtlasDictationAck(
    val ok: Boolean = false,
    val ackSeq: Long = 0,
    val finalSeq: Long? = null,
    val deduplicated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class AtlasDictationChunk(
    val seq: Long,
    val text: String = "",
    @SerialName("final") val finalChunk: Boolean = false,
    val clientMessageId: String = "",
)

@Serializable
data class AtlasWorkspaceEntry(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val size: Long = 0,
    val modifiedAtMs: Long = 0,
    val mime: String = "application/octet-stream",
    val previewable: Boolean = false,
)

@Serializable
data class AtlasWorkspaceListing(
    val path: String = "",
    val rootName: String = "workspace",
    val entries: List<AtlasWorkspaceEntry> = emptyList(),
)

data class AtlasWorkspaceFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray = ByteArray(0),
)

data class AtlasWorkspaceDownload(
    val name: String,
    val mime: String,
    val size: Long,
    val file: java.io.File,
)
