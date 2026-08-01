package com.yzddmr6.prismspace.analytics

import com.yzddmr6.prismspace.bridge.DIAGNOSTICS_CHUNK_BYTES
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticLogWindowTest {

    @Test fun snapshotLeaseTouchRenewsActiveSession() {
        val leases = DiagnosticSnapshotLeaseRegistry(maxActive = 2, ttlMs = 100)
        assertTrue(leases.acquire("active", nowMs = 0))

        assertTrue(leases.touch("active", nowMs = 99))
        assertTrue(leases.expire(nowMs = 150).isEmpty())
        assertEquals(setOf("active"), leases.activeTokens())
    }

    @Test fun snapshotLeaseExpiresAfterInactivity() {
        val leases = DiagnosticSnapshotLeaseRegistry(maxActive = 2, ttlMs = 100)
        assertTrue(leases.acquire("abandoned", nowMs = 0))

        assertEquals(setOf("abandoned"), leases.expire(nowMs = 100))
        assertFalse(leases.touch("abandoned", nowMs = 101))
        assertTrue(leases.activeTokens().isEmpty())
    }

    @Test fun snapshotLeaseRejectsThirdConcurrentSessionUntilOneCloses() {
        val leases = DiagnosticSnapshotLeaseRegistry(maxActive = 2, ttlMs = 100)
        assertTrue(leases.acquire("first", nowMs = 0))
        assertTrue(leases.acquire("second", nowMs = 0))

        assertFalse(leases.acquire("third", nowMs = 1))
        leases.release("first")
        assertTrue(leases.acquire("third", nowMs = 2))
        assertEquals(setOf("second", "third"), leases.activeTokens())
    }

    @Test fun snapshotChunksUseBoundedReadsAndExactBoundaryEof() {
        val input = ByteArray(DIAGNOSTICS_CHUNK_BYTES + 17) { (it % 251).toByte() }
        val file = File.createTempFile("diagnostic-snapshot", ".tmp")
        try {
            file.writeBytes(input)
            val first = DiagnosticLog.readSnapshotChunk(file, 0, DIAGNOSTICS_CHUNK_BYTES)
            assertEquals(DIAGNOSTICS_CHUNK_BYTES, first.bytes.size)
            assertFalse(first.eof)
            val second = DiagnosticLog.readSnapshotChunk(file, first.bytes.size.toLong(), DIAGNOSTICS_CHUNK_BYTES)
            assertArrayEquals(input.copyOfRange(DIAGNOSTICS_CHUNK_BYTES, input.size), second.bytes)
            assertTrue(second.eof)
            val boundary = DiagnosticLog.readSnapshotChunk(file, input.size.toLong(), DIAGNOSTICS_CHUNK_BYTES)
            assertEquals(0, boundary.bytes.size)
            assertTrue(boundary.eof)
        } finally {
            file.delete()
        }
    }

    @Test fun trimKeepsInputWhenAlreadyUnderLimit() {
        val input = ByteArray(128) { it.toByte() }

        val output = DiagnosticLog.trimToWindowBytes(input, DiagnosticLog.MAX_ROLLING_BYTES)

        assertSame(input, output)
    }

    @Test fun trimCapsLogToTwoMiBAndKeepsNewestBytes() {
        val input = ByteArray(DiagnosticLog.MAX_ROLLING_BYTES + 4096) { (it % 251).toByte() }

        val output = DiagnosticLog.trimToWindowBytes(input, DiagnosticLog.MAX_ROLLING_BYTES)

        assertEquals(DiagnosticLog.MAX_ROLLING_BYTES, output.size)
        assertArrayEquals(
            DiagnosticLog.TRIM_MARKER,
            output.copyOfRange(0, DiagnosticLog.TRIM_MARKER.size),
        )
        assertArrayEquals(
            input.copyOfRange(input.size - (DiagnosticLog.MAX_ROLLING_BYTES - DiagnosticLog.TRIM_MARKER.size), input.size),
            output.copyOfRange(DiagnosticLog.TRIM_MARKER.size, output.size),
        )
    }

    @Test fun trimCanKeepHeadroomBelowTwoMiBBudget() {
        val targetBytes = DiagnosticLog.MAX_ROLLING_BYTES - 128 * 1024
        val input = ByteArray(DiagnosticLog.MAX_ROLLING_BYTES + 4096) { (it % 251).toByte() }

        val output = DiagnosticLog.trimToWindowBytes(input, targetBytes)

        assertEquals(targetBytes, output.size)
        assertArrayEquals(
            input.copyOfRange(input.size - (targetBytes - DiagnosticLog.TRIM_MARKER.size), input.size),
            output.copyOfRange(DiagnosticLog.TRIM_MARKER.size, output.size),
        )
    }
}
