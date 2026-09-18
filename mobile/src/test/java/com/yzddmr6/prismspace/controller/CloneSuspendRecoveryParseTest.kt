package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloneSuspendRecoveryParseTest {

    // Real `dumpsys package mark.via` excerpt shape (Xiaomi 13 / HyperOS 3, Android 16).
    private val sample = """
        Packages:
          Package [mark.via] (abc123):
            userId=22087
        """.trimIndent().lineSequence().toList() + listOf(
        "    User 0: ceDataInode=123 deDataInode=456 installed=false hidden=false suspended=false stopped=true",
        "      installReason=0",
        "    User 22: ceDataInode=129603 deDataInode=124721 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false quarantined=false",
        "      installReason=0",
        "      dataDir=/data/user/22/mark.via",
        "    Suspend params:",
        "      suspendingPackage=<22>root dialogInfo=null quarantined=false",
        "",
        "      gids=[3003]",
        "    User 22:",
        "    User 999:",
        "  queryable via uses-library:",
    )

    @Test fun `parses suspender from the target user section`() {
        assertEquals("root", CloneSuspendRecovery.parseSuspendingPackage(sample, 22))
    }

    @Test fun `ignores suspenders of other users`() {
        // User 0 section carries no Suspend params here; if it did, it must not leak into user 22.
        assertEquals("root", CloneSuspendRecovery.parseSuspendingPackage(sample, 22))
        assertNull(CloneSuspendRecovery.parseSuspendingPackage(sample, 0))
    }

    @Test fun `no suspend params section means null`() {
        val notSuspended = listOf(
            "    User 22: ceDataInode=1 deDataInode=2 installed=true hidden=false suspended=false stopped=false",
            "      installReason=0",
            "    User 999:",
        )
        assertNull(CloneSuspendRecovery.parseSuspendingPackage(notSuspended, 22))
    }

    @Test fun `missing user section means null`() {
        assertNull(CloneSuspendRecovery.parseSuspendingPackage(sample, 999))
        assertNull(CloneSuspendRecovery.parseSuspendingPackage(emptyList(), 22))
    }

    @Test fun `system suspender identity is preserved`() {
        val systemSuspended = listOf(
            "    User 22: ceDataInode=1 deDataInode=2 installed=true hidden=false suspended=true stopped=false",
            "    Suspend params:",
            "      suspendingPackage=<22>android dialogInfo=null quarantined=false",
        )
        assertEquals("android", CloneSuspendRecovery.parseSuspendingPackage(systemSuspended, 22))
    }

    @Test fun `later bare user lines do not truncate the section`() {
        // The trailing "User 22:" / "User 999:" lines (uses-library block) share the same indent;
        // the suspender line before them must still be found.
        assertEquals("root", CloneSuspendRecovery.parseSuspendingPackage(sample, 22))
    }
}
