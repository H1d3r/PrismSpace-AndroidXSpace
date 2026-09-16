package com.yzddmr6.prismspace.prism.compose.vm

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A newer GitHub release than the installed build. */
data class UpdateInfo(val version: String, val notes: String, val url: String)

/**
 * Compare dotted numeric versions ("0.0.2" vs "0.0.1"). Returns >0 if [a] is newer than [b],
 * 0 if equal, <0 if older. Non-numeric / missing segments are treated as 0. Pure & unit-testable.
 */
fun compareSemver(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pa = parts(a); val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

/** What an update check decided. */
sealed interface UpdateDecision {
    /** A newer release exists and the user has not dismissed this exact version. */
    data class Prompt(val info: UpdateInfo) : UpdateDecision
    /** Installed build is current (or newer), or the user already dismissed this version. */
    data object NoPrompt : UpdateDecision
    /** The check itself failed (network/API); automatic checks must stay silent. */
    data object Unavailable : UpdateDecision
}

/** Pure policy: an automatic background check runs at most once per [intervalMs]. */
fun shouldAutoCheck(lastCheckMs: Long, nowMs: Long, intervalMs: Long): Boolean =
    lastCheckMs <= 0L || nowMs - lastCheckMs >= intervalMs

/** Pure policy: prompt only for a strictly newer, not-yet-dismissed version. */
fun updatePromptDecision(latest: UpdateInfo?, currentVersion: String, dismissedVersion: String?): UpdateDecision = when {
    latest == null -> UpdateDecision.Unavailable
    compareSemver(latest.version, currentVersion) <= 0 -> UpdateDecision.NoPrompt
    latest.version == dismissedVersion -> UpdateDecision.NoPrompt
    else -> UpdateDecision.Prompt(latest)
}

/**
 * Shared update check for the manual (Settings) and automatic (home) paths. Persistence remembers
 * the last check instant (GitHub API throttling) and the version the user dismissed (no re-nagging).
 */
object UpdateChecker {
    private const val TAG = "Prism.Update"
    private const val GITHUB_REPO = "yzddmr6/PrismSpace"
    private const val PREFS = "prism_update_checker"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val KEY_PENDING_VERSION = "pending_prompt_version"
    private const val KEY_PENDING_NOTES = "pending_prompt_notes"
    private const val KEY_PENDING_URL = "pending_prompt_url"

    /** Automatic checks are throttled to one per 6 hours; the manual check always runs. */
    const val AUTO_INTERVAL_MS = 6 * 60 * 60_000L

    /** A failed fetch must not burn the full throttle window; retry after 30 minutes. */
    const val RETRY_INTERVAL_MS = 30 * 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckMs(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)

    fun dismissedVersion(context: Context): String? =
        prefs(context).getString(KEY_DISMISSED_VERSION, null)

    /** Record a dismissal so the same version never prompts again automatically. */
    fun markDismissed(context: Context, version: String) {
        prefs(context).edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    /** The last prompted release, kept until dismissed or the installed build catches up. */
    fun pendingPrompt(context: Context): UpdateInfo? {
        val version = prefs(context).getString(KEY_PENDING_VERSION, null) ?: return null
        if (version == dismissedVersion(context)) return null
        if (compareSemver(version, currentVersion(context)) <= 0) return null
        return UpdateInfo(
            version = version,
            notes = prefs(context).getString(KEY_PENDING_NOTES, null).orEmpty(),
            url = prefs(context).getString(KEY_PENDING_URL, null)
                ?: "https://github.com/yzddmr6/PrismSpace/releases",
        )
    }

    private fun savePendingPrompt(context: Context, info: UpdateInfo) {
        prefs(context).edit()
            .putString(KEY_PENDING_VERSION, info.version)
            .putString(KEY_PENDING_NOTES, info.notes)
            .putString(KEY_PENDING_URL, info.url)
            .apply()
    }

    private fun clearPendingPrompt(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_VERSION).remove(KEY_PENDING_NOTES).remove(KEY_PENDING_URL).apply()
    }

    private fun markChecked(context: Context, nowMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_MS, nowMs).apply()
    }

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    /**
     * Runs a check if [force] or the throttle window elapsed. The check instant is recorded
     * whenever a fetch happened. The dismissed-version memory applies only to the automatic
     * path: a manual check must answer truthfully even for a dismissed version.
     */
    suspend fun check(context: Context, force: Boolean, nowMs: Long = System.currentTimeMillis()): UpdateDecision {
        if (!force && !shouldAutoCheck(lastCheckMs(context), nowMs, AUTO_INTERVAL_MS)) {
            return UpdateDecision.NoPrompt
        }
        val latest = withContext(Dispatchers.IO) { fetchLatestRelease() }
        if (latest == null) {
            // Failed fetch: allow a retry after RETRY_INTERVAL_MS instead of the full window.
            markChecked(context, nowMs - AUTO_INTERVAL_MS + RETRY_INTERVAL_MS)
        } else {
            markChecked(context, nowMs)
            if (compareSemver(latest.version, currentVersion(context)) <= 0) {
                // Installed build caught up: any stale pending prompt is obsolete.
                clearPendingPrompt(context)
            }
        }
        val dismissed = if (force) null else dismissedVersion(context)
        val decision = updatePromptDecision(latest, currentVersion(context), dismissed)
        if (decision is UpdateDecision.Prompt) savePendingPrompt(context, decision.info)
        DiagnosticLog.i(TAG, "update check force=$force latest=${latest?.version ?: "-"} decision=${decision.javaClass.simpleName}")
        return decision
    }

    private fun fetchLatestRelease(): UpdateInfo? = runCatching {
        val conn = (java.net.URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest").openConnection()
            as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrismSpace")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            UpdateInfo(
                version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V"),
                notes = json.optString("body").ifBlank { json.optString("name") },
                url = json.optString("html_url").ifBlank { "https://github.com/$GITHUB_REPO/releases" })
        } finally { conn.disconnect() }
    }.getOrNull()
}
