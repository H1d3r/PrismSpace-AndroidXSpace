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

    @Test fun apkTransferRecordsArePlainHistoryRows() {
        // APK 安装条目不再出现在传输记录（文件页/入口页记录行只承诺「打开文件夹」）——
        // TransferRecordActions 已删除；记录仅承载展示数据。
        val record = TransferRecord(
            name = "Via",
            packageName = "mark.via",
            location = "Download/PrismSpace",
            isImage = false,
            timeMillis = 1L,
        )

        assertEquals("Via-mark.via", record.displayTitle())
    }

    @Test fun plainTransferRecordsDisplayByName() {
        val record = TransferRecord(
            name = "report.pdf",
            packageName = null,
            location = "Download/PrismSpace",
            isImage = false,
            timeMillis = 1L,
        )

        assertEquals("report.pdf", record.displayTitle())
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
