package com.yzddmr6.prismspace.prism.compose.vm

import kotlinx.coroutines.CancellationException

/**
 * Real per-package outcome of one batch-clone request. A staged APK set (normal mode) is
 * [Prepared] — never "cloned"; only a verified install through a ready enhanced route is
 * [Installed]. Counting must never be estimated from fixed delays.
 */
internal sealed interface BatchCloneResult {
    data object Prepared : BatchCloneResult
    data object Installed : BatchCloneResult

    /** @param needsActivation the staging failed only because the dual space is in quiet mode;
     *  the batch driver may activate the space ONCE per run and retry this package. */
    data class Failed(val needsActivation: Boolean = false) : BatchCloneResult
}

internal data class BatchCloneCounts(val prepared: Int, val installed: Int, val failed: Int)

/** Per-package batch-clone execution; the production port delegates to PrismAppClones. */
internal fun interface BatchClonePort {
    suspend fun clone(packageName: String): BatchCloneResult
}

/**
 * Serial batch-clone driver: consumes each request's real asynchronous result, no delay-based
 * counting. A throwing package counts as failed; cancellation propagates.
 *
 * Quiet-mode activation is a per-batch budget: the first [BatchCloneResult.Failed] with
 * needsActivation asks [activate] once and retries that package; a refused/timed-out activation
 * (or any later activation need) fails fast WITHOUT re-prompting — remaining packages never each
 * show the system dialog.
 */
internal suspend fun runBatchClone(
    packages: List<String>,
    port: BatchClonePort,
    activate: suspend () -> Boolean = { false },
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): BatchCloneCounts {
    var prepared = 0
    var installed = 0
    var failed = 0
    var activationSpent = false
    packages.forEachIndexed { index, pkg ->
        var result = try {
            port.clone(pkg)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            BatchCloneResult.Failed()
        }
        val activationNeed = (result as? BatchCloneResult.Failed)?.needsActivation == true
        if (activationNeed && !activationSpent) {
            activationSpent = true
            if (activate()) {
                result = try {
                    port.clone(pkg)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    BatchCloneResult.Failed()
                }
            }
        }
        when (result) {
            BatchCloneResult.Prepared -> prepared++
            BatchCloneResult.Installed -> installed++
            is BatchCloneResult.Failed -> failed++
        }
        onProgress(index + 1, packages.size)
    }
    return BatchCloneCounts(prepared, installed, failed)
}
