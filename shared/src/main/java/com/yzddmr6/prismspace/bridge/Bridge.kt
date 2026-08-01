package com.yzddmr6.prismspace.bridge

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shuttle.DEFAULT_SYNC_TIMEOUT_MS
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.shuttle.runBoundedOutcome
import com.yzddmr6.prismspace.util.Users

object Bridge {
    fun inProfile(context: Context, target: ProfileTarget) = ProfileBridgeEndpoint(context, target)
    fun inParent(context: Context, target: ParentTarget) = ParentBridgeEndpoint(context, target)
    fun at(context: Context, target: ProfileTarget) = DestinationBridgeEndpoint(context, target.handle)
    fun at(context: Context, target: ParentTarget) = DestinationBridgeEndpoint(context, target.handle)
    fun at(context: Context, target: BridgeTarget) = when (target) {
        is ProfileTarget -> at(context, target)
        is ParentTarget -> at(context, target)
    }
}

class ProfileBridgeEndpoint internal constructor(
    private val context: Context,
    private val target: ProfileTarget,
) {
    fun <R> execute(command: ProfileCommand<R>, timeoutMs: Long? = DEFAULT_SYNC_TIMEOUT_MS): ShuttleOutcome<R> =
        executeCommand(context, target.handle, command, timeoutMs)
}

class ParentBridgeEndpoint internal constructor(
    private val context: Context,
    private val target: ParentTarget,
) {
    fun <R> execute(command: ParentCommand<R>, timeoutMs: Long? = DEFAULT_SYNC_TIMEOUT_MS): ShuttleOutcome<R> =
        executeCommand(context, target.handle, command, timeoutMs)
}

class DestinationBridgeEndpoint internal constructor(
    private val context: Context,
    private val target: android.os.UserHandle,
) {
    fun <R> execute(command: DestinationCommand<R>, timeoutMs: Long? = DEFAULT_SYNC_TIMEOUT_MS): ShuttleOutcome<R> =
        executeCommand(context, target, command, timeoutMs)
}

private fun <R> executeCommand(
    context: Context,
    target: android.os.UserHandle,
    command: BridgeCommand<R>,
    timeoutMs: Long?,
): ShuttleOutcome<R> {
    if (target == Users.current()) return runCatching {
        ShuttleOutcome.Value(BridgeWire.decode(command, BridgeDispatcher.dispatch(context, command)))
    }.getOrElse { ShuttleOutcome.Failed(it) }

    val outcome = if (timeoutMs == null) ShuttleProvider.call(context, target, command)
    else runBoundedOutcome(timeoutMs) { ShuttleProvider.call(context, target, command) }
    return outcome.also {
        when (it) {
            ShuttleOutcome.TimedOut -> DiagnosticLog.w(
                TAG,
                "bridge_timed_out operation=${command.id} targetUser=${target.hashCode()} timeoutMs=$timeoutMs",
            )
            is ShuttleOutcome.Failed -> DiagnosticLog.w(
                TAG,
                "bridge_failed operation=${command.id} targetUser=${target.hashCode()} exception=${it.error.javaClass.name}",
                it.error,
            )
            else -> Unit
        }
    }
}

private const val TAG = "Prism.Bridge"
