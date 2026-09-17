package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.SystemClock
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shuttle.ShuttleHealth
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal class SpaceBridgeHealthStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class Entry(val health: ShuttleHealth, val updatedAtMs: Long)

    private val entries = ConcurrentHashMap<Int, Entry>()
    private val failureStreaks = ConcurrentHashMap<Int, Int>()
    private val availabilityListeners = CopyOnWriteArraySet<(Int, Boolean) -> Unit>()

    /** @return false when the failure was transient and the last-known-good entry was kept. */
    fun update(health: ShuttleHealth, nowMs: Long): Boolean {
        val previous = entries[health.profileId]
        if (health.isTransientBridgeFailure() && previous?.health?.available == true) {
            // A single transient failure (freeze blip, MIUI acquisition denial, cold-start race)
            // must not poison the cache: keep the last-known-good entry and freshen its timestamp,
            // so the next TTL window re-verifies instead of flapping the UI.
            val streak = (failureStreaks[health.profileId] ?: 0) + 1
            failureStreaks[health.profileId] = streak
            if (streak < DOWN_AFTER_CONSECUTIVE_FAILURES) {
                entries[health.profileId] = Entry(previous.health, nowMs)
                return false
            }
        }
        failureStreaks[health.profileId] = 0
        val old = entries.put(health.profileId, Entry(health, nowMs))
        if (old == null || nowMs - old.updatedAtMs > ttlMs || old.health.available != health.available) {
            availabilityListeners.forEach { it(health.profileId, health.available) }
        }
        return true
    }

    fun cached(profileId: Int, nowMs: Long): ShuttleHealth? {
        val entry = entries[profileId] ?: return null
        return if (nowMs - entry.updatedAtMs <= ttlMs) entry.health else null
    }

    fun invalidate(profileId: Int) {
        entries.remove(profileId)
        failureStreaks.remove(profileId)
    }

    fun clear() {
        entries.clear()
        failureStreaks.clear()
    }

    fun addAvailabilityListener(listener: (Int, Boolean) -> Unit) {
        availabilityListeners += listener
    }

    companion object {
        const val DEFAULT_TTL_MS = 10_000L
        const val DOWN_AFTER_CONSECUTIVE_FAILURES = 2
        private const val TAG = "Prism.BridgeHealth"
    }
}

/** Only a ping failure against a running, awake, unlocked profile is a bridge-transient
 *  candidate for debounce. Grant/authority losses (NotReady) are structural and present
 *  immediately; environment facts (not running / quiet / locked / skipped) likewise. */
internal fun ShuttleHealth.isTransientBridgeFailure(): Boolean =
    running && !quietMode && unlocked &&
        (ping is ShuttleOutcome.Failed || ping is ShuttleOutcome.TimedOut)

internal object SpaceBridgeHealthStores {
    val app = SpaceBridgeHealthStore()
}

internal class BridgeHealthRepository(
    private val appContext: Context,
    private val store: SpaceBridgeHealthStore = SpaceBridgeHealthStores.app,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    fun cachedHealth(profile: UserHandle): ShuttleHealth? =
        store.cached(profile.toId(), nowMs())

    /** Returns the effective health after debounce: a held transient failure yields the
     *  last-known-good entry, so state classification never sees a single blip. The raw
     *  measurement is always logged for diagnostics. */
    fun refreshHealth(profile: UserHandle): ShuttleHealth {
        val health = ShuttleProvider.health(appContext, profile)
        val written = store.update(health, nowMs())
        DiagnosticLog.i(TAG, "bridge health refreshed ${health.diagnosticLine()}${if (written) "" else " held_last_good=true"}")
        return if (written) health else store.cached(profile.toId(), nowMs()) ?: health
    }

    fun invalidate(profile: UserHandle) {
        store.invalidate(profile.toId())
    }

    private companion object {
        const val TAG = "Prism.BridgeHealth"
    }
}
