package com.yzddmr6.prismspace.prism.service

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFileOpenerTest {

    @Test fun fileManagerLaunchKeepsDownloadsPrimaryAndAddsXiaomiFileManager() {
        val spec = SystemFileManagerLaunchPlanner.launchSpec()

        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, spec.primary.action)
        assertTrue(
            spec.initialIntents.any {
                it.action == SystemFileManagerLaunchPlanner.ACTION_XIAOMI_FILE_MANAGER_HOME &&
                    it.packageName == InstallSourcePermissionHelper.SYSTEM_FILE_MANAGER_PACKAGE
            },
        )
    }

    @Test fun apkTransferRecordsKeepFileManagerOpenAndAddInstallAction() {
        val actions = TransferRecordActions.forRecord(
            TransferRecord(
                name = "Via",
                packageName = "mark.via",
                location = "Download/PrismSpace",
                isImage = false,
                timeMillis = 1L,
            ),
            hasInstallableApkSet = true,
        )

        assertTrue(actions.canOpenWithFileManager)
        assertTrue(actions.canInstall)
    }

    @Test fun plainTransferRecordsOnlyOpenWithFileManager() {
        val actions = TransferRecordActions.forRecord(
            TransferRecord(
                name = "report.pdf",
                packageName = null,
                location = "Download/PrismSpace",
                isImage = false,
                timeMillis = 1L,
            ),
            hasInstallableApkSet = false,
        )

        assertTrue(actions.canOpenWithFileManager)
        assertFalse(actions.canInstall)
    }

    @Test fun crossProfileForwarderIsNotAFileSurface() {
        assertFalse(isSystemFileSurfacePackage("android"))
        assertTrue(isSystemFileSurfacePackage("com.android.fileexplorer"))
        assertTrue(isSystemFileSurfacePackage("com.google.android.documentsui"))
    }

    @Test fun everyResolvedPickerRouteMustHaveAReadySurface() {
        val routes = listOf(
            setOf("com.google.android.documentsui"),
            setOf("com.android.fileexplorer"),
        )

        assertFalse(routesHaveUsableSurface(routes, setOf("com.google.android.documentsui")))
        assertTrue(
            routesHaveUsableSurface(
                routes,
                setOf("com.google.android.documentsui", "com.android.fileexplorer"),
            ),
        )
    }
}
