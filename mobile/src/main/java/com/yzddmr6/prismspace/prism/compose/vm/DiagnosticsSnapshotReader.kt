package com.yzddmr6.prismspace.prism.compose.vm

import android.content.Context
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.CloseDiagnosticsSnapshot
import com.yzddmr6.prismspace.bridge.DIAGNOSTICS_CHUNK_BYTES
import com.yzddmr6.prismspace.bridge.DiagnosticsChunkDto
import com.yzddmr6.prismspace.bridge.DiagnosticsChunkResultDto
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotInvalid
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotSessionDto
import com.yzddmr6.prismspace.bridge.OpenDiagnosticsSnapshot
import com.yzddmr6.prismspace.bridge.ProfileCommand
import com.yzddmr6.prismspace.bridge.ProfileTarget
import com.yzddmr6.prismspace.bridge.ReadDiagnosticsChunk
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import java.io.ByteArrayOutputStream

internal sealed interface DiagnosticsTransportResult<out T> {
    data class Value<T>(val value: T) : DiagnosticsTransportResult<T>
    data class Failure(val reason: String) : DiagnosticsTransportResult<Nothing>
}

internal interface DiagnosticsSnapshotTransport {
    fun open(): DiagnosticsTransportResult<DiagnosticsSnapshotSessionDto>
    fun read(token: String, offset: Long): DiagnosticsTransportResult<DiagnosticsChunkResultDto>
    fun close(token: String)
}

internal class BridgeDiagnosticsSnapshotTransport(
    private val context: Context,
    private val target: ProfileTarget,
) : DiagnosticsSnapshotTransport {
    override fun open() = execute("open", OpenDiagnosticsSnapshot)

    override fun read(token: String, offset: Long) = execute("read", ReadDiagnosticsChunk(token, offset))

    override fun close(token: String) {
        runCatching { Bridge.inProfile(context, target).execute(CloseDiagnosticsSnapshot(token), BRIDGE_TIMEOUT_MS) }
    }

    private fun <T> execute(operation: String, command: ProfileCommand<T>): DiagnosticsTransportResult<T> =
        when (val outcome = Bridge.inProfile(context, target).execute(command, BRIDGE_TIMEOUT_MS)) {
            is ShuttleOutcome.Value -> outcome.value?.let { DiagnosticsTransportResult.Value(it) }
                ?: DiagnosticsTransportResult.Failure(
                    "Dual-space diagnostic snapshot $operation returned no value.",
                )
            is ShuttleOutcome.NotReady -> DiagnosticsTransportResult.Failure(
                "Dual-space diagnostic snapshot unavailable: shuttle is not ready (${outcome.cause}).",
            )
            ShuttleOutcome.TimedOut -> DiagnosticsTransportResult.Failure(
                "Dual-space diagnostic snapshot unavailable: shuttle timed out during $operation.",
            )
            is ShuttleOutcome.Failed -> DiagnosticsTransportResult.Failure(
                "Dual-space diagnostic snapshot $operation failed: " +
                    "${outcome.error.javaClass.name}: ${outcome.error.message.orEmpty()}",
            )
            is ShuttleOutcome.Skipped -> DiagnosticsTransportResult.Failure(
                "Dual-space diagnostic snapshot skipped: ${outcome.reason}",
            )
        }
}

internal sealed interface DiagnosticsCollectionResult {
    data class Success(
        val bytes: ByteArray,
        val expectedLength: Long,
        val openAttempts: Int,
    ) : DiagnosticsCollectionResult

    data class Failure(val reason: String, val openAttempts: Int) : DiagnosticsCollectionResult
}

/** Reads until the handler-declared EOF and reopens exactly once if a session was reclaimed. */
internal fun collectDiagnosticsSnapshot(transport: DiagnosticsSnapshotTransport): DiagnosticsCollectionResult {
    attempts@ for (attempt in 1..MAX_OPEN_ATTEMPTS) {
        val session = when (val opened = transport.open()) {
            is DiagnosticsTransportResult.Value -> opened.value
            is DiagnosticsTransportResult.Failure -> return DiagnosticsCollectionResult.Failure(opened.reason, attempt)
        }
        try {
            if (session.totalLength !in 0..Int.MAX_VALUE.toLong()) {
                return DiagnosticsCollectionResult.Failure(
                    "Dual-space diagnostic snapshot declared invalid length ${session.totalLength}.",
                    attempt,
                )
            }
            val output = ByteArrayOutputStream(session.totalLength.toInt())
            var offset = 0L
            while (true) {
                when (val read = transport.read(session.token, offset)) {
                    is DiagnosticsTransportResult.Failure ->
                        return DiagnosticsCollectionResult.Failure(read.reason, attempt)
                    is DiagnosticsTransportResult.Value -> when (val chunk = read.value) {
                        DiagnosticsSnapshotInvalid -> {
                            if (attempt == MAX_OPEN_ATTEMPTS) {
                                return DiagnosticsCollectionResult.Failure(
                                    "Dual-space diagnostic snapshot session expired again after reopening.",
                                    attempt,
                                )
                            }
                            continue@attempts
                        }
                        is DiagnosticsChunkDto -> {
                            if (chunk.bytes.size > DIAGNOSTICS_CHUNK_BYTES) {
                                return DiagnosticsCollectionResult.Failure(
                                    "Dual-space diagnostic chunk exceeded the handler limit.",
                                    attempt,
                                )
                            }
                            output.write(chunk.bytes)
                            offset += chunk.bytes.size
                            if (offset > session.totalLength) {
                                return DiagnosticsCollectionResult.Failure(
                                    "Dual-space diagnostic snapshot exceeded its declared length.",
                                    attempt,
                                )
                            }
                            if (chunk.eof) {
                                if (offset != session.totalLength) {
                                    return DiagnosticsCollectionResult.Failure(
                                        "Dual-space diagnostic snapshot ended at $offset of ${session.totalLength} bytes.",
                                        attempt,
                                    )
                                }
                                return DiagnosticsCollectionResult.Success(output.toByteArray(), offset, attempt)
                            }
                            if (chunk.bytes.isEmpty()) {
                                return DiagnosticsCollectionResult.Failure(
                                    "Dual-space diagnostic snapshot returned an empty non-final chunk.",
                                    attempt,
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            transport.close(session.token)
        }
    }
    return DiagnosticsCollectionResult.Failure("Dual-space diagnostic snapshot could not be read.", MAX_OPEN_ATTEMPTS)
}

private const val MAX_OPEN_ATTEMPTS = 2
private const val BRIDGE_TIMEOUT_MS = 4_500L
