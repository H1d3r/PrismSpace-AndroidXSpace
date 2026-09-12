package com.yzddmr6.prismspace.settings.profile

import com.yzddmr6.prismspace.prism.service.TransferRecord

/** Transfer history is an event log; pending installation is one task per absent package. */
internal fun pendingProfileInstalls(
    transfers: List<TransferRecord>,
    isInstalled: (String) -> Boolean,
    hasApks: (TransferRecord) -> Boolean,
): List<TransferRecord> = transfers
    .filter { !it.packageName.isNullOrBlank() }
    .sortedByDescending { it.timeMillis }
    .distinctBy { it.packageName }
    .filter { !isInstalled(it.packageName!!) && hasApks(it) }
