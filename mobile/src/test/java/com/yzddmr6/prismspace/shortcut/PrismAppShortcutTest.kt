package com.yzddmr6.prismspace.shortcut

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismAppShortcutTest {
    @Test fun profileLaunchFieldsAreBoundedAtTheHandlingSide() {
        assertTrue(PrismAppShortcut.profileLaunchFieldsValid(
            "com.example.app",
            "android.intent.action.VIEW",
            "https://example.test/path",
            listOf("android.intent.category.BROWSABLE"),
        ))
        assertFalse(PrismAppShortcut.profileLaunchFieldsValid("", null, null, emptyList()))
        assertFalse(PrismAppShortcut.profileLaunchFieldsValid("pkg", "a".repeat(256), null, emptyList()))
        assertFalse(PrismAppShortcut.profileLaunchFieldsValid("pkg", null, "d".repeat(8_193), emptyList()))
        assertFalse(PrismAppShortcut.profileLaunchFieldsValid("pkg", null, null, List(17) { "category.$it" }))
        assertFalse(PrismAppShortcut.profileLaunchFieldsValid("pkg", null, null, listOf("c".repeat(256))))
    }
}
