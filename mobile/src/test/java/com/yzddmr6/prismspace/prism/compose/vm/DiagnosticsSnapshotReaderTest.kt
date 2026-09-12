package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.bridge.DiagnosticsChunkDto
import com.yzddmr6.prismspace.bridge.DiagnosticsChunkResultDto
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotInvalid
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotSessionDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSnapshotReaderTest {

    @Test fun readsUntilEofAtDeclaredBoundary() {
        val transport = FakeTransport(
            sessions = ArrayDeque(listOf(DiagnosticsSnapshotSessionDto("one", 3))),
            reads = ArrayDeque(listOf(
                DiagnosticsChunkDto(byteArrayOf(1, 2), eof = false),
                DiagnosticsChunkDto(byteArrayOf(3), eof = true),
            )),
        )

        val result = collectDiagnosticsSnapshot(transport) as DiagnosticsCollectionResult.Success

        assertArrayEquals(byteArrayOf(1, 2, 3), result.bytes)
        assertEquals(listOf(0L, 2L), transport.offsets)
        assertEquals(listOf("one"), transport.closed)
    }

    @Test fun reopensOnceWhenSessionWasReclaimed() {
        val transport = FakeTransport(
            sessions = ArrayDeque(listOf(
                DiagnosticsSnapshotSessionDto("first", 1),
                DiagnosticsSnapshotSessionDto("second", 1),
            )),
            reads = ArrayDeque(listOf(
                DiagnosticsSnapshotInvalid,
                DiagnosticsChunkDto(byteArrayOf(7), eof = true),
            )),
        )

        val result = collectDiagnosticsSnapshot(transport) as DiagnosticsCollectionResult.Success

        assertArrayEquals(byteArrayOf(7), result.bytes)
        assertEquals(2, result.openAttempts)
        assertEquals(listOf("first", "second"), transport.closed)
    }

    @Test fun reportsFailureWhenReopenedSessionIsAlsoInvalid() {
        val transport = FakeTransport(
            sessions = ArrayDeque(listOf(
                DiagnosticsSnapshotSessionDto("first", 1),
                DiagnosticsSnapshotSessionDto("second", 1),
            )),
            reads = ArrayDeque(listOf(DiagnosticsSnapshotInvalid, DiagnosticsSnapshotInvalid)),
        )

        val result = collectDiagnosticsSnapshot(transport) as DiagnosticsCollectionResult.Failure

        assertEquals(2, result.openAttempts)
        assertTrue(result.reason.contains("expired again"))
        assertEquals(listOf("first", "second"), transport.closed)
    }

    @Test fun reportsEarlyEofInsteadOfReturningPartialSnapshot() {
        val transport = FakeTransport(
            sessions = ArrayDeque(listOf(DiagnosticsSnapshotSessionDto("one", 2))),
            reads = ArrayDeque(listOf(DiagnosticsChunkDto(byteArrayOf(1), eof = true))),
        )

        val result = collectDiagnosticsSnapshot(transport) as DiagnosticsCollectionResult.Failure

        assertTrue(result.reason.contains("ended at 1 of 2"))
        assertEquals(listOf("one"), transport.closed)
    }

    private class FakeTransport(
        private val sessions: ArrayDeque<DiagnosticsSnapshotSessionDto>,
        private val reads: ArrayDeque<DiagnosticsChunkResultDto>,
    ) : DiagnosticsSnapshotTransport {
        val offsets = mutableListOf<Long>()
        val closed = mutableListOf<String>()

        override fun open(): DiagnosticsTransportResult<DiagnosticsSnapshotSessionDto> =
            DiagnosticsTransportResult.Value(sessions.removeFirst())

        override fun read(token: String, offset: Long): DiagnosticsTransportResult<DiagnosticsChunkResultDto> {
            offsets += offset
            return DiagnosticsTransportResult.Value(reads.removeFirst())
        }

        override fun close(token: String) {
            closed += token
        }
    }
}
