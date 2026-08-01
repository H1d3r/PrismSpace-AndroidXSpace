package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val DIAGNOSTICS_CHUNK_BYTES = 256 * 1024

@Parcelize
data class DiagnosticsSnapshotSessionDto(
    val token: String,
    val totalLength: Long,
) : Parcelable

sealed interface DiagnosticsChunkResultDto : Parcelable

@Parcelize
data class DiagnosticsChunkDto(
    val bytes: ByteArray,
    val eof: Boolean,
) : DiagnosticsChunkResultDto

@Parcelize
data object DiagnosticsSnapshotInvalid : DiagnosticsChunkResultDto

@Parcelize
data object OpenDiagnosticsSnapshot : ProfileCommand<DiagnosticsSnapshotSessionDto> {
    override val id get() = "diagnostics.open_snapshot"
    override fun encodeResult(result: DiagnosticsSnapshotSessionDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): DiagnosticsSnapshotSessionDto = src.requireDiagnosticsParcelable()
}

@Parcelize
data class ReadDiagnosticsChunk(
    val token: String,
    val offset: Long,
) : ProfileCommand<DiagnosticsChunkResultDto> {
    override val id get() = "diagnostics.read_chunk"
    override fun encodeResult(result: DiagnosticsChunkResultDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): DiagnosticsChunkResultDto = src.requireDiagnosticsParcelable()
}

@Parcelize
data class CloseDiagnosticsSnapshot(val token: String) : ProfileCommand<Unit> {
    override val id get() = "diagnostics.close_snapshot"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

internal val DIAGNOSTICS_COMMAND_SAMPLES: List<BridgeCommand<*>> = listOf(
    OpenDiagnosticsSnapshot,
    ReadDiagnosticsChunk("00000000-0000-0000-0000-000000000000", 0L),
    CloseDiagnosticsSnapshot("00000000-0000-0000-0000-000000000000"),
)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.requireDiagnosticsParcelable(): T {
    classLoader = BridgeCommand::class.java.classLoader
    return requireNotNull(getParcelable(RESULT)) { "Missing ${T::class.java.simpleName} diagnostics result" }
}
