package com.codexatlas.mobile

import kotlinx.serialization.Serializable

@Serializable
data class AtlasSnapshot(
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
)

@Serializable
data class AtlasMessage(
    val id: String = "",
    val role: String = "assistant",
    val text: String = "",
    val timestampMs: Long = 0,
    val kind: String = "",
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
)
