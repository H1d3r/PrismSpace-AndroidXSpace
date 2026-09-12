package com.yzddmr6.prismspace.settings.profile

import com.yzddmr6.prismspace.prism.service.TransferRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePendingInstallsTest {
    private fun record(pkg: String?, time: Long = 0) = TransferRecord("App", pkg, "Download/PrismSpace", false, time)

    @Test fun repeatedPreparationIsOneTaskWithLatestLabel() {
        val latest = record("app", 2).copy(name = "New name")
        assertEquals(listOf(latest), pendingProfileInstalls(listOf(record("app", 1), latest), setOf("app"), { false }, { true }))
    }

    @Test fun installedAppsAndPlainFilesAreNotPendingEvenWhenApksRemain() {
        val pending = record("pending")
        assertEquals(listOf(pending), pendingProfileInstalls(
            listOf(record("installed"), record(null), pending), setOf("installed", "pending"), { it == "installed" }, { true }))
    }

    @Test fun missingApksAreNotPresentedAsReadyToInstall() {
        assertTrue(pendingProfileInstalls(listOf(record("app")), setOf("app"), { false }, { false }).isEmpty())
    }
    @Test fun uninstalledCompletedCloneDoesNotResurrectFromTransferHistory() {
        assertTrue(pendingProfileInstalls(listOf(record("completed")), emptySet(), { false }, { true }).isEmpty())
    }

    @Test fun pendingTaskSurvivesTrimmedTransferHistory() {
        val tasks = pendingProfileInstalls(emptyList(), setOf("older.app"), { false }, { true })
        assertEquals("older.app", tasks.single().packageName)
    }
}
