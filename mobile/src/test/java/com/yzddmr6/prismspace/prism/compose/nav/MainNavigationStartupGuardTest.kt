package com.yzddmr6.prismspace.prism.compose.nav

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationStartupGuardTest {
    @Test fun `cold start relies only on nav host start destination`() {
        val activity = File("src/main/java/com/yzddmr6/prismspace/MainActivity.java").readText()
        val signals = File(
            "src/main/java/com/yzddmr6/prismspace/prism/compose/nav/AppLaunchSignals.kt",
        ).readText()
        val navHost = File(
            "src/main/java/com/yzddmr6/prismspace/prism/compose/nav/PrismNavHost.kt",
        ).readText()

        assertFalse(activity.contains("signalResetToHome"))
        assertFalse(signals.contains("resetToHome"))
        assertFalse(navHost.contains("resetToHome"))
        assertTrue(navHost.contains("startDestination = PrismRoutes.HOME"))
    }
}
