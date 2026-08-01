package com.yzddmr6.prismspace.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PrismManagerTest {

    @Test fun `launch readiness requires installation`() {
        assertEquals("not_installed", launchReadinessReason(installed = false, hidden = false, suspended = false))
    }

    @Test fun `launch readiness rejects either freeze mechanism`() {
        assertEquals("still_hidden", launchReadinessReason(installed = true, hidden = true, suspended = false))
        assertEquals("still_suspended", launchReadinessReason(installed = true, hidden = false, suspended = true))
    }

    @Test fun `launch readiness is empty only when app can launch`() {
        assertEquals("", launchReadinessReason(installed = true, hidden = false, suspended = false))
    }
}
