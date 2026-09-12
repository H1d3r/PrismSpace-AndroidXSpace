package com.yzddmr6.prismspace.settings.profile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileApkInstallerCompletionTest {

    @Test fun verifiedSuccessNotifiesParentWithExactPackage() {
        val source = File("src/main/java/com/yzddmr6/prismspace/settings/profile/ProfileApkInstaller.kt").readText()
        val success = source.substringAfter("PackageInstaller.STATUS_SUCCESS ->")
            .substringBefore("else ->")

        assertTrue(source.contains("putExtra(EXTRA_PACKAGE, pkg)"))
        assertTrue(success.contains("CompleteClonePreparation(packageName)"))
        assertTrue(success.contains("Bridge.inParent"))
    }
}
