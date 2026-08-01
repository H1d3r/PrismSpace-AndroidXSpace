package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceStateStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun firstSubscriberTriggersInitialCollection() = runBlocking {
        val store = SpaceStateStore(
            collector = { SpaceState.NoProfile },
            scope = scope,
            debounceMs = 20L,
        )

        assertEquals(SpaceSnapshot.Loading, store.state.value)
        val loaded = withTimeout(2_000L) {
            store.state.first { it is SpaceSnapshot.Loaded }
        }

        assertEquals(SpaceSnapshot.Loaded(SpaceState.NoProfile), loaded)
    }

    @Test fun invalidationPublishesNewStateAndDebouncesBurst() = runBlocking {
        val calls = AtomicInteger()
        val store = SpaceStateStore(
            collector = {
                if (calls.incrementAndGet() == 1) SpaceState.NoProfile else SpaceState.Healthy(22)
            },
            scope = scope,
            debounceMs = 20L,
        )
        val observer = scope.launch { store.state.collect() }
        withTimeout(2_000L) { store.state.first { it == SpaceSnapshot.Loaded(SpaceState.NoProfile) } }

        store.invalidate("profile_available")
        store.invalidate("user_unlocked")
        store.invalidate("profile_owner_changed")

        withTimeout(2_000L) { store.state.first { it == SpaceSnapshot.Loaded(SpaceState.Healthy(22)) } }
        assertEquals(2, calls.get())
        observer.cancel()
    }

    @Test fun concurrentRefreshesShareOneCollection() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = SpaceStateStore(
            collector = {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SpaceState.NoProfile
            },
            scope = scope,
        )

        val first = async { store.refresh("first") }
        entered.await()
        val second = async { store.refresh("second") }
        delay(20L)
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, calls.get())
    }

    @Test fun invalidationWhileUnobservedRefreshesWhenSubscriberReturns() = runBlocking {
        val current = AtomicReference<SpaceState>(SpaceState.NoProfile)
        val calls = AtomicInteger()
        val store = SpaceStateStore(
            collector = { calls.incrementAndGet(); current.get() },
            scope = scope,
            debounceMs = 20L,
        )
        val firstObserver = scope.launch { store.state.collect() }
        withTimeout(2_000L) { store.state.first { it == SpaceSnapshot.Loaded(SpaceState.NoProfile) } }
        firstObserver.cancel()
        delay(50L)

        current.set(SpaceState.Healthy(22))
        store.invalidate("profile_added_while_unobserved")

        withTimeout(2_000L) { store.state.first { it == SpaceSnapshot.Loaded(SpaceState.Healthy(22)) } }
        assertEquals(2, calls.get())
    }

    @Test fun initialCollectionFailureIsRetriedWithoutEscapingScope() = runBlocking {
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val store = SpaceStateStore(
            collector = {
                if (calls.incrementAndGet() == 1) error("transient")
                SpaceState.NoProfile
            },
            scope = scope,
            debounceMs = 20L,
            retryMs = 20L,
            onCollectionFailure = { _, _ -> failures.incrementAndGet() },
        )

        val loaded = withTimeout(2_000L) { store.state.first { it is SpaceSnapshot.Loaded } }

        assertEquals(SpaceSnapshot.Loaded(SpaceState.NoProfile), loaded)
        assertEquals(2, calls.get())
        assertEquals(1, failures.get())
    }

    @Test fun provisioningEndsOnEveryParentObservableSignal() {
        ProvisioningEndSignal.entries.forEach { signal ->
            val machine = ProvisioningStateMachine(nowMs = { 0L }, timeoutMs = 100L, onTimeout = {})
            machine.start()
            assertTrue(machine.isActive())
            assertTrue(machine.finish(signal))
            assertFalse(machine.isActive())
        }
    }

    @Test fun provisioningTimeoutLogsOnlyOnSafetyNetPath() {
        var now = 0L
        var timedOut = false
        val machine = ProvisioningStateMachine(
            nowMs = { now },
            timeoutMs = 100L,
            onTimeout = { timedOut = true },
        )
        machine.start()
        now = 100L

        assertFalse(machine.isActive())
        assertTrue(timedOut)
    }
}
