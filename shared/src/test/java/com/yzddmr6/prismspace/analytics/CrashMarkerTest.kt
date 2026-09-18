package com.yzddmr6.prismspace.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashMarkerTest {

    private fun tempDir(): File = Files.createTempDirectory("crash-marker-test").toFile()

    @Test fun renderSnapshotContainsScene() {
        val snapshot = CrashMarker.renderSnapshot(
            threadName = "main",
            stackTrace = "java.lang.IllegalStateException: boom\n\tat a.b.c(Foo.kt:1)",
            version = "0.1.7 (11)",
            userId = "0",
        )
        assertTrue(snapshot.contains("Thread: main"))
        assertTrue(snapshot.contains("Version: 0.1.7 (11)"))
        assertTrue(snapshot.contains("User: 0"))
        assertTrue(snapshot.contains("IllegalStateException: boom"))
    }

    @Test fun renderSnapshotTruncatesToByteBudget() {
        val huge = "x".repeat(CrashMarker.MAX_SNAPSHOT_BYTES * 4)
        val snapshot = CrashMarker.renderSnapshot("main", huge, "1.0 (1)", "0")
        assertTrue(snapshot.toByteArray(Charsets.UTF_8).size <= CrashMarker.MAX_SNAPSHOT_BYTES)
    }

    @Test fun truncateKeepsUtf8Boundary() {
        // '中' is 3 bytes in UTF-8; cutting mid-sequence must not produce replacement chars.
        val text = "a".repeat(10) + "中".repeat(100)
        val cut = CrashMarker.truncateToBytes(text, 11) // lands inside the first '中'
        assertFalse(cut.contains('\uFFFD'))
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 11)
        assertEquals("a".repeat(10), cut)
    }

    @Test fun writePendingClearRoundtrip() {
        val dir = tempDir()
        assertNull(CrashMarker.pendingIn(dir))

        CrashMarker.writeTo(dir, "crash scene")
        assertEquals("crash scene", CrashMarker.pendingIn(dir))

        CrashMarker.writeTo(dir, "newer crash")
        assertEquals("newer crash", CrashMarker.pendingIn(dir))

        CrashMarker.clearIn(dir)
        assertNull(CrashMarker.pendingIn(dir))
    }

    @Test fun stashedCrashFlushesToDisk() {
        val dir = tempDir()
        CrashMarker.clearIn(dir)
        // record(null, ...) simulates the pre-Context window: snapshot must survive in memory.
        CrashMarker.record(null, "early-thread", RuntimeException("early boom"))
        assertNull(CrashMarker.pendingIn(dir))

        CrashMarker.flushStashedTo(dir)
        val pending = CrashMarker.pendingIn(dir)
        assertNotNull(pending)
        assertTrue(pending!!.contains("early-thread"))
        assertTrue(pending.contains("RuntimeException"))

        // Flushed once: a second flush must not resurrect anything after clear.
        CrashMarker.clearIn(dir)
        CrashMarker.flushStashedTo(dir)
        assertNull(CrashMarker.pendingIn(dir))
    }

    @Test fun blankMarkerReadsAsAbsent() {
        val dir = tempDir()
        CrashMarker.writeTo(dir, "   ")
        assertNull(CrashMarker.pendingIn(dir))
    }
}
