package com.yzddmr6.prismspace.action

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalFeatureActionExposureTest {

    @Test fun manifestDoesNotExposeFeatureActionButKeepsShortcutTrampoline() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("FeatureActionActivity"))
        assertFalse(manifest.contains("android:scheme=\"prismspace\""))
        assertFalse(manifest.contains("android:host=\"feature\""))
        assertTrue(manifest.contains("PrismAppShortcut\$ShortcutLauncher"))
        assertTrue(manifest.contains("com.yzddmr6.prismspace.action.LAUNCH_APP"))
    }

    @Test fun removedFeatureActionSourceDoesNotReturn() {
        assertFalse(File("src/main/java/com/yzddmr6/prismspace/action/FeatureAction.kt").exists())
    }
}
