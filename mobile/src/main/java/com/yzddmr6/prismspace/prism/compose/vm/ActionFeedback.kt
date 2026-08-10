package com.yzddmr6.prismspace.prism.compose.vm

import android.content.Context
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale

/** Unified feedback-channel currency. */
data class ActionFeedback(val message: String, val isError: Boolean)

/**
 * Resolver from a string-resource id (+ optional format args) to a localized string.
 *
 * Production callers pass a lambda backed by `PrismLocale.wrap(context)::getString`, so the
 * copy follows the user's chosen language. Tests provide their own resource-backed resolver.
 */
typealias StringResolver = (Int, Array<out Any>) -> String

/**
 * Build a locale-aware [StringResolver] backed by the user's chosen language. Resolves each id
 * through `PrismLocale.wrap(context)` so the copy follows the in-app language override.
 */
fun prismResolver(context: Context): StringResolver =
    { id, args -> PrismLocale.wrap(context).getString(id, *args) }

/** Pure: Files import success/failure counts → user feedback. */
fun filesImportFeedback(success: Int, failed: Int, res: StringResolver): ActionFeedback = when {
    success == 0 && failed == 0 -> ActionFeedback("", false)
    failed == 0 -> ActionFeedback(res(R.string.lz_vm_files_imported, arrayOf(success)), false)
    success == 0 -> ActionFeedback(res(R.string.lz_vm_files_import_all_failed, arrayOf(failed)), true)
    else -> ActionFeedback(res(R.string.lz_vm_files_import_partial, arrayOf(success, failed)), true)
}

/**
 * Pure: detailed Files import outcome. For the common no-oversize path it
 * delegates to [filesImportFeedback]; the oversize case adds specific copy.
 */
fun filesImportFeedbackDetailed(ok: Int, oversize: Int, otherFail: Int, res: StringResolver): ActionFeedback =
    if (oversize == 0) filesImportFeedback(ok, otherFail, res)
    else ActionFeedback(res(R.string.lz_vm_files_import_detailed, arrayOf(ok, oversize, otherFail)), isError = true)

/**
 * Pure: batch-action feedback shared by SpaceViewModel and tests.
 * failed == failures.size; isError == failures.isNotEmpty().
 */
fun batchActionFeedback(action: BatchAction, succeeded: Int, failed: Int, res: StringResolver): ActionFeedback {
    val msg = when (action) {
        BatchAction.Freeze ->
            if (failed == 0) res(R.string.lz_vm_batch_freeze_ok, arrayOf(succeeded))
            else res(R.string.lz_vm_batch_freeze_partial, arrayOf(succeeded, failed))
        BatchAction.Uninstall ->
            if (failed == 0) res(R.string.lz_vm_batch_uninstall_ok, arrayOf(succeeded))
            else res(R.string.lz_vm_batch_uninstall_partial, arrayOf(succeeded, failed))
        BatchAction.CopyToDual ->
            if (failed == 0) res(R.string.lz_vm_batch_clone_ok, arrayOf(succeeded))
            else res(R.string.lz_vm_batch_clone_partial, arrayOf(succeeded, failed))
    }
    return ActionFeedback(msg, isError = failed > 0)
}

/** Pure, truthful summary for the system-uninstaller queue. */
internal fun uninstallQueueFeedback(
    summary: UninstallSummary,
    skipped: Int = 0,
    res: StringResolver,
): ActionFeedback = ActionFeedback(
    res(
        R.string.lz_vm_uninstall_queue_summary,
        arrayOf(summary.succeeded, summary.cancelled, summary.timedOut + skipped),
    ),
    isError = summary.cancelled > 0 || summary.timedOut > 0 || skipped > 0,
)
