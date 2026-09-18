package com.yzddmr6.prismspace.prism.compose.vm

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yzddmr6.prismspace.analytics.CrashMarker
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.DiagnosticSection
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared diagnostics export used by Settings and by the setup wizard — a user stuck in setup
 * may never reach the Settings tab, so the entry must exist there too. Produces the export
 * file and the ACTION_SEND intent; callers own feedback and launching.
 */
object DiagnosticsExporter {
    private const val TAG = "Prism.DiagExport"

    /** Builds the export file and share intent. IO-bound; call from a coroutine. */
    suspend fun buildShare(
        context: Context,
        subject: String,
        attachedText: (String) -> String,
    ): Pair<String, Intent> = withContext(Dispatchers.IO) {
        DiagnosticLog.i(TAG, "diagnostic export start")
        val sections = buildList {
            // A pending crash marker rides along on every export until consumed, so a user who
            // exports manually from Settings also captures the crash scene.
            CrashMarker.pending(context.applicationContext)?.let { crash ->
                add(DiagnosticSection(title = "Last uncaught crash (pending report)", body = crash))
            }
            addAll(collectProfileDiagnostics(context.applicationContext))
        }
        val file = DiagnosticLog.createExportFile(
            context,
            extraSections = sections,
            includeLogcat = true,
        )
        val uri: Uri = DiagnosticLog.shareUri(context, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, attachedText(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, file.name, uri) }
        file.name to intent
    }

    fun launchShare(context: Context, subject: String, intent: Intent) {
        context.startActivity(
            Intent.createChooser(intent, subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun collectProfileDiagnostics(context: Context): List<DiagnosticSection> {
        val profile = Users.profile ?: run {
            // No PrismSpace-managed profile: the classified state and its facts are the single most
            // valuable clue for foreign-profile (XSpace/OEM clone) and orphan misclassification bugs.
            val stateRepo = SpaceStateRepository(context)
            val state = (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state
            val facts = stateRepo.lastFactsDiagnosticLine()
            return listOf(DiagnosticSection(
                title = "Dual-space diagnostic snapshot",
                body = buildString {
                    append("No managed profile is currently known to the main space.")
                    append("\nClassified space state: ").append(state ?: "unknown")
                    if (facts != null) append("\nLast classification: ").append(facts)
                },
            ))
        }
        val healthSection = try {
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = ShuttleProvider.health(context, profile).diagnosticLine(),
            )
        } catch (error: Exception) {
            DiagnosticLog.w(TAG, "dual-space shuttle health collection failed user=${profile.toId()}", error)
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = "Health check failed: ${error.javaClass.name}: ${error.message.orEmpty()}",
            )
        }
        return try {
            DiagnosticLog.i(TAG, "dual-space diagnostic chunk collection start user=${profile.toId()}")
            val target = BridgeTargets.profile(profile.toId())
            val result = if (target == null) {
                DiagnosticsCollectionResult.Failure(
                    "Dual-space diagnostic snapshot unavailable: managed profile target is invalid.",
                    openAttempts = 0,
                )
            } else {
                collectDiagnosticsSnapshot(BridgeDiagnosticsSnapshotTransport(context, target))
            }
            val body = when (result) {
                is DiagnosticsCollectionResult.Success -> {
                    DiagnosticLog.i(
                        TAG,
                        "dual-space diagnostic chunk collection success user=${profile.toId()} " +
                            "bytes=${result.bytes.size} attempts=${result.openAttempts}",
                    )
                    result.bytes.toString(Charsets.UTF_8).ifBlank {
                        "Dual-space diagnostic snapshot was empty (profileBytes=${result.expectedLength})."
                    }
                }
                is DiagnosticsCollectionResult.Failure -> {
                    DiagnosticLog.w(
                        TAG,
                        "dual-space diagnostic chunk collection failed user=${profile.toId()} " +
                            "attempts=${result.openAttempts} reason=${result.reason}",
                    )
                    result.reason
                }
            }
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = body,
            ))
        } catch (e: Exception) {
            DiagnosticLog.e(TAG, "dual-space diagnostic collection failed", e)
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = "Failed to read dual-space diagnostic snapshot: ${e.javaClass.name}: ${e.message.orEmpty()}",
            ))
        }
    }
}
