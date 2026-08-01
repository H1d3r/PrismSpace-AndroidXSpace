package com.yzddmr6.prismspace.shortcut

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutProvisioningGuardTest {

    @Test fun packageEventsDoNotCrossUsersBeforeProvisioningCompletes() {
        val source = File("src/main/java/com/yzddmr6/prismspace/shortcut/PrismAppShortcut.kt").readText()
        val receiver = source.substringAfter("private val mPackageObserver")
            .substringBefore("override fun onCreate()")

        assertTrue(
            receiver.indexOf("isProfileProvisioningComplete") <
                receiver.indexOf("RefreshShortcutInParent"),
        )
    }
}
