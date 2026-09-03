package com.codexatlas.mobile

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AtlasMessageModeTest {
    @Test
    fun sendModeAcceptsOnlyKnownValues() {
        assertEquals(AtlasMessageMode.Queue, AtlasMessageMode.fromKey("queue"))
        assertEquals(AtlasMessageMode.Interrupt, AtlasMessageMode.fromKey(" INTERRUPT "))
        assertEquals(AtlasMessageMode.Queue, AtlasMessageMode.fromKey("unknown"))
    }

    @Test
    fun olderOutboxItemsDefaultToQueueMode() {
        val item = Json.decodeFromString<QueuedAtlasMessage>(
            """{"id":"1","sessionId":"session-1","text":"hello","createdAtMs":1}""",
        )

        assertEquals(AtlasMessageMode.Queue.key, item.mode)
    }
}
