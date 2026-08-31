package com.codexatlas.mobile

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class QueuedAtlasMessage(
    val id: String,
    val sessionId: String,
    val text: String,
    val createdAtMs: Long,
    /** Stable id forwarded to the desktop Bridge for idempotent delivery. */
    val clientMessageId: String = "",
    val state: String = AtlasQueueItemState.Pending.key,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastAttemptAtMs: Long = 0,
    val nextAttemptAtMs: Long = 0,
)

enum class AtlasQueueItemState(val key: String) {
    Pending("pending"),
    Sending("sending"),
    Failed("failed");

    companion object {
        fun fromKey(value: String): AtlasQueueItemState =
            entries.firstOrNull { it.key == value } ?: Pending
    }
}

enum class AtlasQueueControl(val key: String) {
    Running("running"),
    Paused("paused"),
    Stopping("stopping");

    companion object {
        fun fromKey(value: String): AtlasQueueControl = entries.firstOrNull { it.key == value } ?: Running
    }
}

/** Small durable outbox shared by the activity, widget and background sync service. */
object AtlasMessageQueue {
    private const val PREFS = "atlas_message_queue"
    private const val KEY = "outbox"
    private const val CONTROL = "control"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun enqueue(context: Context, sessionId: String, text: String): QueuedAtlasMessage {
        val item = QueuedAtlasMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            text = text,
            createdAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) {
            val next = (read(context) + item).takeLast(100)
            write(context, next)
        }
        return item
    }

    fun peek(context: Context): QueuedAtlasMessage? = synchronized(lock) {
        val now = System.currentTimeMillis()
        read(context).firstOrNull { item ->
            val state = AtlasQueueItemState.fromKey(item.state)
            (state == AtlasQueueItemState.Pending || state == AtlasQueueItemState.Failed) &&
                item.nextAttemptAtMs <= now
        }
    }

    fun items(context: Context): List<QueuedAtlasMessage> = synchronized(lock) { read(context) }

    fun count(context: Context): Int = synchronized(lock) { read(context).size }

    fun control(context: Context): AtlasQueueControl = AtlasQueueControl.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONTROL, AtlasQueueControl.Running.key).orEmpty(),
    )

    fun setControl(context: Context, control: AtlasQueueControl) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONTROL, control.key)
            .commit()
    }

    /** Atomically claims the next eligible item so two service ticks cannot send it twice. */
    fun claim(context: Context): QueuedAtlasMessage? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val items = read(context)
        val index = items.indexOfFirst { item ->
            val state = AtlasQueueItemState.fromKey(item.state)
            (state == AtlasQueueItemState.Pending || state == AtlasQueueItemState.Failed) &&
                item.nextAttemptAtMs <= now
        }
        if (index < 0) return@synchronized null
        val claimed = items[index].copy(
            clientMessageId = items[index].clientMessageId.ifBlank { items[index].id },
            state = AtlasQueueItemState.Sending.key,
            lastAttemptAtMs = now,
            lastError = null,
        )
        write(context, items.toMutableList().also { it[index] = claimed })
        claimed
    }

    /** A process death must never strand an item in the sending state forever. */
    fun resetSending(context: Context) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val next = read(context).map { item ->
                if (AtlasQueueItemState.fromKey(item.state) == AtlasQueueItemState.Sending) {
                    item.copy(state = AtlasQueueItemState.Pending.key, nextAttemptAtMs = now)
                } else item
            }
            write(context, next)
        }
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) { write(context, read(context).filterNot { it.id == id }) }
    }

    fun markAttempt(context: Context, id: String, error: String? = null) {
        markFailure(context, id, error)
    }

    fun markFailure(context: Context, id: String, error: String? = null) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            write(context, read(context).map { item ->
                if (item.id == id) {
                    val attempts = item.attempts + 1
                    val backoff = (1_500L * attempts.coerceAtMost(4)).coerceAtMost(30_000L)
                    item.copy(
                        state = AtlasQueueItemState.Failed.key,
                        attempts = attempts,
                        lastError = error?.take(180),
                        lastAttemptAtMs = now,
                        nextAttemptAtMs = now + backoff,
                    )
                } else item
            })
        }
    }

    fun retry(context: Context, id: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            write(context, read(context).map { item ->
                if (item.id == id) {
                    item.copy(
                        state = AtlasQueueItemState.Pending.key,
                        attempts = 0,
                        lastError = null,
                        nextAttemptAtMs = now,
                    )
                } else item
            })
        }
    }

    private fun read(context: Context): List<QueuedAtlasMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty()
        val items = if (raw.isBlank()) emptyList() else runCatching {
            json.decodeFromString<List<QueuedAtlasMessage>>(raw)
        }.getOrDefault(emptyList())
        // Older queue records predate clientMessageId/state. Normalize them on read so
        // an upgrade preserves delivery identity and never emits a blank id.
        return items.map { item ->
            item.copy(
                clientMessageId = item.clientMessageId.ifBlank { item.id },
                state = AtlasQueueItemState.fromKey(item.state).key,
            )
        }
    }

    private fun write(context: Context, items: List<QueuedAtlasMessage>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(items))
            .commit()
    }
}
