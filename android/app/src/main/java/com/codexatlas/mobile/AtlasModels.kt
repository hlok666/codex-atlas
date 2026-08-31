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
    val requiresAttention: Boolean = false,
    val lastError: String? = null,
    val foreground: Boolean = false,
    val statusSource: String = "",
    val lastEventAtMs: Long = 0,
    val messages: List<AtlasMessage> = emptyList(),
    val approval: AtlasApproval? = null,
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
