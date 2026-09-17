package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-healing for recoverable bridge-down states. Manual repair is empirically 100% effective
 * (launching the profile-side entry both revives the profile-side provider and forces a grant
 * re-exchange), so requiring the user to tap repair every time the ROM breaks the bridge is
 * pure friction. This watchdog fires the same action automatically, throttled and fused, from
 * the single point where the space state is classified.
 *
 * Only provider-unresolvable and grant-lost causes qualify: both are cured by the entry launch.
 * Environmental states (profile stopped/quiet/locked) never reach BridgeDown — the classifier
 * maps them to Inactive/Locked first — and HalfProvisioned belongs to the convergence trampoline.
 */
internal object SpaceBridgeAutoRecovery {
    data class Gate(val lastAttemptMs: Long, val consecutiveAttempts: Int)

    enum class Action { Trigger, Throttled, Exhausted, Irrecoverable, NotBridgeDown }

    private val gates = ConcurrentHashMap<Int, Gate>()

    fun isRecoverableCause(cause: SpaceBridgeCause): Boolean =
        cause == SpaceBridgeCause.ProviderUnavailable || cause == SpaceBridgeCause.PermissionDenied

    /** Pure decision, unit-tested. [gate] is the persisted throttle state for the profile. */
    fun decide(state: SpaceState, gate: Gate?, nowMs: Long): Action {
        if (state !is SpaceState.BridgeDown) return Action.NotBridgeDown
        if (!isRecoverableCause(state.cause)) return Action.Irrecoverable
        if (gate != null && gate.consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) return Action.Exhausted
        if (gate != null && nowMs - gate.lastAttemptMs < MIN_ATTEMPT_INTERVAL_MS) return Action.Throttled
        return Action.Trigger
    }

    /** Called from the space-state collector after every classification. Never throws. */
    fun maybeRecover(
        context: Context,
        state: SpaceState,
        nowMs: Long = SystemClock.elapsedRealtime(),
        scheduleRecheck: (Int) -> Unit = {},
    ) {
        if (state !is SpaceState.BridgeDown) {
            // Recovery evidence: any non-BridgeDown classification resets that profile's fuse.
            state.userId?.let { gates.remove(it) }
            return
        }
        val userId = state.userId
        when (val action = decide(state, gates[userId], nowMs)) {
            Action.Trigger -> {
                val previous = gates[userId]
                gates[userId] = Gate(nowMs, (previous?.consecutiveAttempts ?: 0) + 1)
                DiagnosticLog.i(
                    TAG,
                    "bridge auto-recover triggered user=$userId cause=${state.cause} " +
                        "attempt=${(previous?.consecutiveAttempts ?: 0) + 1}",
                )
                val launched = runCatching {
                    val userManager = context.getSystemService(UserManager::class.java)
                    val profile = userManager?.userProfiles?.firstOrNull { it.toId() == userId }
                    profile != null && ProfileEntryLauncher.start(context, profile)
                }.getOrDefault(false)
                if (!launched) {
                    DiagnosticLog.w(TAG, "bridge auto-recover launch failed user=$userId")
                } else {
                    scheduleRecheck(userId)
                }
            }
            Action.Throttled -> Unit
            Action.Exhausted -> {
                // Log only on the boundary crossing; every later classification re-enters this branch.
                if (gates[userId]?.consecutiveAttempts == MAX_CONSECUTIVE_ATTEMPTS) {
                    DiagnosticLog.w(
                        TAG,
                        "bridge auto-recover exhausted user=$userId cause=${state.cause}; manual repair required",
                    )
                }
            }
            else -> DiagnosticLog.v(TAG, "bridge auto-recover skipped user=$userId action=$action")
        }
    }

    /** Test hook. */
    internal fun reset() = gates.clear()

    private const val MIN_ATTEMPT_INTERVAL_MS = 20_000L
    private const val MAX_CONSECUTIVE_ATTEMPTS = 3
    private const val TAG = "Prism.BridgeRecovery"
}
