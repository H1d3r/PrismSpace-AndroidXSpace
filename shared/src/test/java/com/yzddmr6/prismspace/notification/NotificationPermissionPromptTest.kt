package com.yzddmr6.prismspace.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPromptTest {

    @Test fun requestsOnlyOnceOnApi33PlusWhenMissing() {
        assertTrue(NotificationPermissionPrompt.shouldRequest(33, granted = false, asked = false))
        assertFalse(NotificationPermissionPrompt.shouldRequest(33, granted = false, asked = true))
        assertFalse(NotificationPermissionPrompt.shouldRequest(33, granted = true, asked = false))
        assertFalse(NotificationPermissionPrompt.shouldRequest(32, granted = false, asked = false))
    }
}
