package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.shuttle.ShuttleHealth
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthDebounceTest {

    private fun health(ping: ShuttleOutcome<Boolean>, running: Boolean = true) = ShuttleHealth(
        profileId = 21,
        running = running,
        quietMode = false,
        unlocked = true,
        forwardGrant = true,
        backwardGrant = true,
        ping = ping,
    )

    private val ok get() = health(ShuttleOutcome.Value(true))
    private val nullFailed get() = health(ShuttleOutcome.Failed(IllegalArgumentException("Missing bridge response")))
    private val timedOut get() = health(ShuttleOutcome.TimedOut)
    private val structural get() = health(ShuttleOutcome.NotReady(ShuttleNotReadyCause.PermissionDenied))

    @Test
    fun `single transient failure after healthy keeps the last-known-good state`() {
        val store = SpaceBridgeHealthStore()
        store.update(ok, nowMs = 0)
        store.update(nullFailed, nowMs = 5_000)
        assertTrue(store.cached(21, nowMs = 5_000)!!.available)
    }

    @Test
    fun `second consecutive transient failure flips to unavailable`() {
        val store = SpaceBridgeHealthStore()
        store.update(ok, nowMs = 0)
        store.update(nullFailed, nowMs = 5_000)                       // held
        store.update(timedOut, nowMs = 20_000)                        // streak 2 -> written
        assertFalse(store.cached(21, nowMs = 20_000)!!.available)
    }

    @Test
    fun `success after a held failure recovers immediately and resets the streak`() {
        val store = SpaceBridgeHealthStore()
        store.update(ok, nowMs = 0)
        store.update(nullFailed, nowMs = 5_000)                       // held
        store.update(ok, nowMs = 20_000)                              // recovered, streak reset
        store.update(nullFailed, nowMs = 25_000)                      // held again (streak 1)
        assertTrue(store.cached(21, nowMs = 25_000)!!.available)
    }

    @Test
    fun `structural grant loss presents immediately without debounce`() {
        val store = SpaceBridgeHealthStore()
        store.update(ok, nowMs = 0)
        store.update(structural, nowMs = 5_000)
        assertFalse(store.cached(21, nowMs = 5_000)!!.available)
    }

    @Test
    fun `environment-driven unavailability presents immediately`() {
        val store = SpaceBridgeHealthStore()
        store.update(ok, nowMs = 0)
        store.update(health(ShuttleOutcome.TimedOut, running = false), nowMs = 5_000)
        assertFalse(store.cached(21, nowMs = 5_000)!!.available)
    }

    @Test
    fun `failure with no prior good state is written immediately`() {
        val store = SpaceBridgeHealthStore()
        store.update(nullFailed, nowMs = 0)
        assertFalse(store.cached(21, nowMs = 0)!!.available)
    }
}
