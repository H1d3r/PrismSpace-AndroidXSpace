package com.yzddmr6.prismspace.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SuiInitializationGuardTest {
    @Test fun manualSuiInitializationPrecedesShizukuProviderCreation() {
        val source = File("src/main/java/com/yzddmr6/prismspace/shizuku/NonRootShizukuProvider.kt").readText()
        assertTrue(source.indexOf("disableAutomaticSuiInitialization()") < source.indexOf("Sui.init(packageName)"))
        assertTrue(source.indexOf("Sui.init(packageName)") < source.indexOf("super.onCreate()"))
        assertTrue(source.contains("requireNotNull(context).packageName"))
        assertFalse(source.contains("BuildConfig.APPLICATION_ID"))
    }

    @Test fun cloneReadinessUsesCentralizedDetection() {
        val source = File("src/main/java/com/yzddmr6/prismspace/controller/PrismAppClones.kt").readText()
        assertTrue(source.contains("ShizukuUtil.isAvailable()"))
        assertTrue(source.contains("ShizukuUtil.isAuthorized()"))
        assertFalse(source.contains("Shizuku.getVersion() >= 11"))
    }
}
