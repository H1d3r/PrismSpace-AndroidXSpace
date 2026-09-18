package com.yzddmr6.prismspace.analytics

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Synchronous last-crash marker.
 *
 * The uncaught-exception path cannot rely on [DiagnosticLog]'s async executor — the process dies
 * before the write drains — so the crash snapshot (full stack included) is written to disk
 * directly, before chaining to the original handler. The next launch surfaces the marker as a
 * send-diagnostics prompt, and any diagnostics export attaches it while pending.
 */
object CrashMarker {
    private const val DIR = "diagnostics"
    private const val MARKER = "pending-crash.txt"
    internal const val MAX_SNAPSHOT_BYTES = 32 * 1024

    /** Crashes observed before any Context exists are stashed here and flushed by [flushStashed]. */
    @Volatile private var stashed: String? = null

    /** Never throws: a crashing process must reach the original handler no matter what. */
    @JvmStatic fun record(context: Context?, threadName: String, error: Throwable) {
        runCatching {
            val stackTrace = runCatching { Log.getStackTraceString(error) }
                .getOrElse { "${error.javaClass.name}: ${error.message}" }
            val snapshot = renderSnapshot(
                threadName = threadName,
                stackTrace = stackTrace,
                version = context?.let(::versionName) ?: "unknown",
                userId = currentUserId(),
            )
            if (context != null) writeTo(diagnosticsDir(context), snapshot) else stash(snapshot)
        }
    }

    @JvmStatic fun flushStashed(context: Context) = flushStashedTo(diagnosticsDir(context))

    @JvmStatic fun pending(context: Context): String? = pendingIn(diagnosticsDir(context))

    @JvmStatic fun clear(context: Context) {
        stashed = null
        clearIn(diagnosticsDir(context))
    }

    internal fun diagnosticsDir(context: Context): File = File(context.filesDir, DIR)

    internal fun stash(snapshot: String) {
        stashed = snapshot
    }

    internal fun flushStashedTo(dir: File) {
        val snapshot = stashed ?: return
        stashed = null
        runCatching { writeTo(dir, snapshot) }
    }

    internal fun pendingIn(dir: File): String? = runCatching {
        val file = File(dir, MARKER)
        if (file.isFile) file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
    }.getOrNull()

    internal fun clearIn(dir: File) {
        runCatching { File(dir, MARKER).delete() }
    }

    internal fun writeTo(dir: File, content: String) {
        val file = File(dir, MARKER)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    internal fun renderSnapshot(threadName: String, stackTrace: String, version: String, userId: String): String {
        val body = buildString {
            appendLine("Timestamp: ${timestamp()}")
            appendLine("Thread: $threadName")
            appendLine("User: $userId")
            appendLine("Version: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine(stackTrace.trimEnd())
        }
        return truncateToBytes(body, MAX_SNAPSHOT_BYTES)
    }

    /** Cuts at a UTF-8 sequence boundary so the persisted file stays decodable. */
    internal fun truncateToBytes(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName ?: "unknown"} ($code)"
    }.getOrDefault("unknown")

    private fun currentUserId(): String =
        runCatching { Process.myUserHandle().hashCode().toString() }.getOrDefault("unknown")

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date())
}
