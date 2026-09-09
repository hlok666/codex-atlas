package com.codexatlas.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasRetryPolicyTest {
    @Test fun allowsFailuresBeforeConfiguredLimit() {
        assertTrue(retryAllowed(0, 3))
        assertTrue(retryAllowed(2, 3))
    }

    @Test fun stopsAfterConfiguredLimit() {
        assertFalse(retryAllowed(3, 3))
        assertFalse(retryAllowed(4, 3))
    }

    @Test fun nullLimitMeansUnlimited() {
        assertTrue(retryAllowed(0, null))
        assertTrue(retryAllowed(10_000, null))
    }
}
