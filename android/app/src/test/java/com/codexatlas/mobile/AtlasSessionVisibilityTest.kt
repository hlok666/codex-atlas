package com.codexatlas.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AtlasSessionVisibilityTest {
    private val running = AtlasSession(id = "running", title = "Live task", running = true)
    private val stopped = AtlasSession(id = "stopped", title = "Archived task", running = false)

    @Test
    fun collapsedHomeShowsOnlyRunningSessions() {
        assertEquals(
            listOf("running"),
            visibleSessionsForHome(listOf(running, stopped), showAll = false, query = "")
                .map(AtlasSession::id),
        )
    }

    @Test
    fun expandedHomeShowsAndSearchesAllSessions() {
        assertEquals(
            listOf("stopped"),
            visibleSessionsForHome(listOf(running, stopped), showAll = true, query = "archived")
                .map(AtlasSession::id),
        )
    }
}
