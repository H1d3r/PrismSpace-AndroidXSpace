package com.yzddmr6.prismspace.prism.compose.vm

internal const val UNINSTALL_VERIFICATION_TIMEOUT_MS = 15_000L

internal data class UninstallRequest(
    val packageName: String,
    val targetUserId: Int,
    val mainCopyExisted: Boolean,
)

internal enum class UninstallStage { ReadyToLaunch, AwaitingSystemUi, Verifying }
internal enum class UninstallReturnHint { Cancelled, Completion, Foreground }
internal enum class UninstallOutcomeStatus { Success, Cancelled, TimedOut }

internal data class UninstallCurrent(
    val request: UninstallRequest,
    val stage: UninstallStage,
    val verificationStartedAtMs: Long? = null,
    val returnHint: UninstallReturnHint? = null,
)

internal data class UninstallOutcome(
    val request: UninstallRequest,
    val status: UninstallOutcomeStatus,
    val mainCopyLost: Boolean,
)

internal data class UninstallQueueState(
    val pending: List<UninstallRequest> = emptyList(),
    val current: UninstallCurrent? = null,
    val outcomes: List<UninstallOutcome> = emptyList(),
    val total: Int = 0,
) {
    val complete: Boolean get() = current == null && pending.isEmpty()
    val summary: UninstallSummary get() = UninstallSummary(
        succeeded = outcomes.count { it.status == UninstallOutcomeStatus.Success },
        cancelled = outcomes.count { it.status == UninstallOutcomeStatus.Cancelled },
        timedOut = outcomes.count { it.status == UninstallOutcomeStatus.TimedOut },
    )
}

internal data class UninstallSummary(val succeeded: Int, val cancelled: Int, val timedOut: Int) {
    val failed: Int get() = cancelled + timedOut
}

internal fun shouldClearCloneRegistry(status: UninstallOutcomeStatus): Boolean =
    status == UninstallOutcomeStatus.Success

internal object UninstallQueueReducer {

    fun start(requests: List<UninstallRequest>): UninstallQueueState {
        val first = requests.firstOrNull()
        return UninstallQueueState(
            pending = requests.drop(1),
            current = first?.let { UninstallCurrent(it, UninstallStage.ReadyToLaunch) },
            total = requests.size,
        )
    }

    fun launched(state: UninstallQueueState): UninstallQueueState {
        val current = state.current?.takeIf { it.stage == UninstallStage.ReadyToLaunch } ?: return state
        return state.copy(current = current.copy(stage = UninstallStage.AwaitingSystemUi))
    }

    fun returned(
        state: UninstallQueueState,
        hint: UninstallReturnHint,
        nowMs: Long,
    ): UninstallQueueState {
        val current = state.current ?: return state
        return when (current.stage) {
            UninstallStage.AwaitingSystemUi -> state.copy(current = current.copy(
                stage = UninstallStage.Verifying,
                verificationStartedAtMs = nowMs,
                returnHint = hint,
            ))
            UninstallStage.Verifying -> if (hint == UninstallReturnHint.Foreground) state else
                state.copy(current = current.copy(returnHint = hint))
            UninstallStage.ReadyToLaunch -> state
        }
    }

    fun observed(
        state: UninstallQueueState,
        installedInTarget: Boolean?,
        mainCopyExists: Boolean,
        nowMs: Long,
    ): UninstallQueueState {
        val current = state.current?.takeIf { it.stage == UninstallStage.Verifying } ?: return state
        val status = when {
            installedInTarget == false -> UninstallOutcomeStatus.Success
            installedInTarget == true && current.returnHint == UninstallReturnHint.Cancelled ->
                UninstallOutcomeStatus.Cancelled
            nowMs - (current.verificationStartedAtMs ?: nowMs) >= UNINSTALL_VERIFICATION_TIMEOUT_MS ->
                UninstallOutcomeStatus.TimedOut
            else -> return state
        }
        return completeCurrent(state, status, mainCopyExists)
    }

    fun launchFailed(state: UninstallQueueState, mainCopyExists: Boolean): UninstallQueueState =
        completeCurrent(state, UninstallOutcomeStatus.TimedOut, mainCopyExists)

    private fun completeCurrent(
        state: UninstallQueueState,
        status: UninstallOutcomeStatus,
        mainCopyExists: Boolean,
    ): UninstallQueueState {
        val current = state.current ?: return state
        val outcome = UninstallOutcome(
            current.request,
            status,
            mainCopyLost = current.request.mainCopyExisted && !mainCopyExists,
        )
        val next = state.pending.firstOrNull()
        return state.copy(
            pending = state.pending.drop(1),
            current = next?.let { UninstallCurrent(it, UninstallStage.ReadyToLaunch) },
            outcomes = state.outcomes + outcome,
        )
    }
}
