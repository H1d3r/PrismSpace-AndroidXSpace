package com.yzddmr6.prismspace.settings.profile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.QueryPendingClonePreparations
import com.yzddmr6.prismspace.prism.service.TransferHistoryStore
import com.yzddmr6.prismspace.prism.service.TransferRecord
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome

/** Null tasks mean the canonical source was unavailable, not that there are no tasks. */
internal data class ProfileTransfers(val history: List<TransferRecord>, val pending: List<TransferRecord>?)

/** Run off main: the parent owns workflow state; history only supplies display metadata. */
internal fun loadProfileTransfers(context: Context): ProfileTransfers {
    val history = TransferHistoryStore.load(context)
    val pending = try {
        val target = BridgeTargets.parentFresh(context) ?: return ProfileTransfers(history, null)
        val result = Bridge.inParent(context, target).execute(QueryPendingClonePreparations)
        val packages = when (result) {
            is ShuttleOutcome.Value -> result.value ?: return ProfileTransfers(history, null)
            else -> return ProfileTransfers(history, null)
        }
        pendingProfileInstalls(history, packages.toSet(), isInstalled = { pkg ->
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                info.flags and ApplicationInfo.FLAG_INSTALLED != 0
            } catch (_: PackageManager.NameNotFoundException) { false }
        }, hasApks = { item ->
            ProfileApkInstaller.hasCopiedApkSet(context, item.packageName!!, item.name)
        })
    } catch (error: RuntimeException) {
        DiagnosticLog.w("Prism.ProfileTasks", "pending task query failed exception=${error.javaClass.simpleName}")
        null
    }
    return ProfileTransfers(history, pending)
}

internal fun pendingProfileInstalls(
    transfers: List<TransferRecord>,
    pendingPackages: Set<String>,
    isInstalled: (String) -> Boolean,
    hasApks: (TransferRecord) -> Boolean,
): List<TransferRecord> {
    val latest = transfers.sortedByDescending { it.timeMillis }.distinctBy { it.packageName }.associateBy { it.packageName }
    return pendingPackages.filter { it.isNotBlank() && !isInstalled(it) }.map { pkg ->
        // History is capped; an older task must remain reachable after its history row expires.
        latest[pkg] ?: TransferRecord(pkg, pkg, "", false, 0)
    }.filter(hasApks).sortedByDescending { it.timeMillis }
}
