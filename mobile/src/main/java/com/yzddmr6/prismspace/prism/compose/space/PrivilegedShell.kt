package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.analytics.DiagnosticLog
import eu.chainfire.libsuperuser.Shell
import rikka.shizuku.Shizuku
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The only real difference between the root and Shizuku provisioning paths:
 * how the same command transaction reaches a privileged shell. Everything else
 * (command building, parsing, rollback, success checks) is shared.
 * Semantics mirror `Shell.SU.run(String)`: blocking, returns stdout lines,
 * null when the transport itself fails.
 */
interface PrivilegedShell {
    /** "su" or "shizuku" — logs/analytics only. */
    val name: String

    /** Runs the command in a privileged shell; stdout lines, or null on transport failure. */
    fun run(command: String): List<String>?
}

class SuPrivilegedShell : PrivilegedShell {
    override val name: String get() = "su"
    override fun run(command: String): List<String>? = Shell.SU.run(command)
}

class ShizukuPrivilegedShell : PrivilegedShell {
    override val name: String get() = "shizuku"

    override fun run(command: String): List<String>? {
        val process = try {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: RuntimeException) {
            DiagnosticLog.w(TAG, "shizuku spawn failed: ${e.message}")
            return null
        }
        // Drain stderr on a worker so a full pipe buffer can never deadlock the read.
        val drain = thread(start = true, isDaemon = true, name = "shizuku-stderr") {
            try {
                process.errorStream.bufferedReader().forEachLine { }
            } catch (_: IOException) {}
        }
        return try {
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            lines
        } catch (e: IOException) {
            DiagnosticLog.w(TAG, "shizuku read failed: ${e.message}")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            drain.join(STREAM_DRAIN_JOIN_MS)
        }
    }

    private companion object {
        private const val TAG = "Prism.ShizukuShell"
        private const val STREAM_DRAIN_JOIN_MS = 2_000L
    }
}

enum class PrivilegedTransport { SU, SHIZUKU }

/**
 * Shizuku-authorized wins: the user already granted this app explicitly and using it
 * never raises an su prompt. The su probe is lazy so it only runs as the fallback —
 * probing su (which may prompt) while Shizuku is authorized would violate no-surprise.
 */
fun chooseTransport(shizukuAuthorized: Boolean, suAvailable: () -> Boolean): PrivilegedTransport? = when {
    shizukuAuthorized -> PrivilegedTransport.SHIZUKU
    suAvailable() -> PrivilegedTransport.SU
    else -> null
}
