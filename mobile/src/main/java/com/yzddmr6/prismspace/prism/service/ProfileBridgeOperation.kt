package com.yzddmr6.prismspace.prism.service

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.BridgeTarget
import com.yzddmr6.prismspace.bridge.DestinationCommand
import com.yzddmr6.prismspace.bridge.ProfileCommand
import com.yzddmr6.prismspace.bridge.ProfileTarget
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users

internal fun <R> runProfileBridgeOperation(
    context: Context,
    tag: String,
    operation: String,
    target: ProfileTarget? = BridgeTargets.profile(context),
    timeoutMs: Long? = null,
    command: ProfileCommand<R>,
): ProfileBridgeResult<R> {
    val profileTarget = target ?: return ProfileBridgeResult.SpaceMissing
    val profile = com.yzddmr6.prismspace.util.UserHandles.of(profileTarget.userId)
    if (profile == Users.current()) {
        DiagnosticLog.i(tag, "$operation local profile=${profileTarget.userId}")
        return ProfileBridgeResult.from(Bridge.inProfile(context, profileTarget).execute(command, timeoutMs))
    }
    val health = ShuttleProvider.health(context, profile)
    DiagnosticLog.i(
        tag,
        "$operation preflight profile=${profileTarget.userId} ${health.diagnosticLine()}",
    )
    if (!health.available) return ProfileBridgeResult.from(health.ping).asFailureResult()
    return ProfileBridgeResult.from(Bridge.inProfile(context, profileTarget).execute(command, timeoutMs))
}

internal fun <R> runDestinationBridgeOperation(
    context: Context,
    tag: String,
    operation: String,
    target: BridgeTarget?,
    timeoutMs: Long? = null,
    command: DestinationCommand<R>,
): ProfileBridgeResult<R> {
    val destination = target ?: return ProfileBridgeResult.SpaceMissing
    val user = com.yzddmr6.prismspace.util.UserHandles.of(destination.userId)
    if (user == Users.current()) {
        DiagnosticLog.i(tag, "$operation local target=${destination.userId}")
        return ProfileBridgeResult.from(Bridge.at(context, destination).execute(command, timeoutMs))
    }
    if (destination is ProfileTarget) {
        val health = ShuttleProvider.health(context, user)
        DiagnosticLog.i(tag, "$operation preflight target=${destination.userId} ${health.diagnosticLine()}")
        if (!health.available) return ProfileBridgeResult.from(health.ping).asFailureResult()
    }
    return ProfileBridgeResult.from(Bridge.at(context, destination).execute(command, timeoutMs))
}

internal sealed class ProfileBridgeResult<out R> {
    data class Value<out R>(val value: R?) : ProfileBridgeResult<R>()
    data object SpaceMissing : ProfileBridgeResult<Nothing>()
    data class SpaceInactive(val reason: String) : ProfileBridgeResult<Nothing>()
    data class BridgeNotReady(val cause: ShuttleNotReadyCause?) : ProfileBridgeResult<Nothing>()
    data object TimedOut : ProfileBridgeResult<Nothing>()
    data class Failed(val error: Throwable) : ProfileBridgeResult<Nothing>()

    companion object {
        fun <R> from(outcome: ShuttleOutcome<R>): ProfileBridgeResult<R> =
            when (outcome) {
                is ShuttleOutcome.Value -> Value(outcome.value)
                is ShuttleOutcome.NotReady -> BridgeNotReady(outcome.cause)
                ShuttleOutcome.TimedOut -> TimedOut
                is ShuttleOutcome.Failed -> Failed(outcome.error)
                is ShuttleOutcome.Skipped -> SpaceInactive(outcome.reason)
            }
    }
}

internal fun ProfileBridgeResult<*>.failureReason(): FileTransferFailureReason? =
    when (this) {
        ProfileBridgeResult.SpaceMissing -> FileTransferFailureReason.SpaceMissing
        is ProfileBridgeResult.SpaceInactive -> FileTransferFailureReason.SpaceInactive
        is ProfileBridgeResult.BridgeNotReady -> FileTransferFailureReason.BridgeNotReady
        ProfileBridgeResult.TimedOut -> FileTransferFailureReason.TimedOut
        is ProfileBridgeResult.Failed -> FileTransferFailureReason.IOError
        is ProfileBridgeResult.Value -> null
    }

internal fun crossSpaceFailureReason(result: ProfileBridgeResult<*>): FileTransferFailureReason = when (result) {
    ProfileBridgeResult.SpaceMissing,
    is ProfileBridgeResult.SpaceInactive -> FileTransferFailureReason.SpaceUnavailable
    is ProfileBridgeResult.BridgeNotReady,
    ProfileBridgeResult.TimedOut -> FileTransferFailureReason.BridgeNotReady
    is ProfileBridgeResult.Failed,
    is ProfileBridgeResult.Value -> FileTransferFailureReason.TargetWriteFailed
}

internal fun <R> ProfileBridgeResult<*>.asFailureResult(): ProfileBridgeResult<R> =
    when (this) {
        ProfileBridgeResult.SpaceMissing -> ProfileBridgeResult.SpaceMissing
        is ProfileBridgeResult.SpaceInactive -> ProfileBridgeResult.SpaceInactive(reason)
        is ProfileBridgeResult.BridgeNotReady -> ProfileBridgeResult.BridgeNotReady(cause)
        ProfileBridgeResult.TimedOut -> ProfileBridgeResult.TimedOut
        is ProfileBridgeResult.Failed -> ProfileBridgeResult.Failed(error)
        is ProfileBridgeResult.Value -> error("Value result cannot be converted to failure")
    }

internal fun profileBridgeFailureMessage(
    context: Context,
    result: ProfileBridgeResult<*>,
    fallbackMessage: String,
): String {
    val strings = PrismLocale.wrap(context)
    val spec = profileBridgeFailureMessageSpec(result) ?: return fallbackMessage
    return if (spec.argument == null) strings.getString(spec.resourceId)
    else strings.getString(spec.resourceId, spec.argument)
}

internal data class ProfileBridgeFailureMessageSpec(val resourceId: Int, val argument: String? = null)

internal fun profileBridgeFailureMessageSpec(result: ProfileBridgeResult<*>): ProfileBridgeFailureMessageSpec? =
    when (result) {
        ProfileBridgeResult.SpaceMissing -> ProfileBridgeFailureMessageSpec(R.string.fb_need_create_space)
        is ProfileBridgeResult.SpaceInactive -> ProfileBridgeFailureMessageSpec(R.string.fb_space_inactive)
        is ProfileBridgeResult.BridgeNotReady -> ProfileBridgeFailureMessageSpec(R.string.fb_space_bridge_repair_needed)
        ProfileBridgeResult.TimedOut -> ProfileBridgeFailureMessageSpec(R.string.fb_space_not_ready)
        is ProfileBridgeResult.Failed -> ProfileBridgeFailureMessageSpec(
            R.string.fb_space_internal_operation_failed,
            result.error.javaClass.simpleName.ifBlank { result.error.javaClass.name },
        )
        is ProfileBridgeResult.Value -> null
    }
