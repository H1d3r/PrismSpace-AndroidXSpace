package com.yzddmr6.prismspace.prism.compose.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerPolicyTest {

    private val info = UpdateInfo("0.2.0", "notes", "https://example.test/release")

    @Test fun `auto check runs when never checked before`() {
        assertTrue(shouldAutoCheck(0L, 1_000L, UpdateChecker.AUTO_INTERVAL_MS))
        assertTrue(shouldAutoCheck(-1L, 1_000L, UpdateChecker.AUTO_INTERVAL_MS))
    }

    @Test fun `auto check is throttled inside the interval`() {
        val now = 10_000_000L
        assertFalse(shouldAutoCheck(now - 1_000L, now, UpdateChecker.AUTO_INTERVAL_MS))
        assertTrue(shouldAutoCheck(now - UpdateChecker.AUTO_INTERVAL_MS, now, UpdateChecker.AUTO_INTERVAL_MS))
        assertTrue(shouldAutoCheck(now - UpdateChecker.AUTO_INTERVAL_MS - 1, now, UpdateChecker.AUTO_INTERVAL_MS))
    }

    @Test fun `prompt only for a strictly newer version`() {
        assertEquals(UpdateDecision.Prompt(info), updatePromptDecision(info, "0.1.0", dismissedVersion = null))
        assertEquals(UpdateDecision.NoPrompt, updatePromptDecision(info, "0.2.0", dismissedVersion = null))
        assertEquals(UpdateDecision.NoPrompt, updatePromptDecision(info, "0.3.0", dismissedVersion = null))
    }

    @Test fun `dismissed version never prompts again`() {
        assertEquals(UpdateDecision.NoPrompt, updatePromptDecision(info, "0.1.0", dismissedVersion = "0.2.0"))
        // A newer release than the dismissed one prompts again.
        val newer = UpdateInfo("0.3.0", "notes", "https://example.test/release")
        assertEquals(UpdateDecision.Prompt(newer), updatePromptDecision(newer, "0.1.0", dismissedVersion = "0.2.0"))
    }

    @Test fun `fetch failure stays silent`() {
        assertEquals(UpdateDecision.Unavailable, updatePromptDecision(null, "0.1.0", dismissedVersion = null))
    }
}
