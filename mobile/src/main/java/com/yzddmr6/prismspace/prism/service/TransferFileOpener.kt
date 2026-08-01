package com.yzddmr6.prismspace.prism.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PrismLocale

/**
	 * Open the system file manager so the user can locate a transferred file.
	 *
	 * In the work profile the Downloads/Files viewer (DocumentsUI) may be installed but
	 * suspended by policy. As the profile owner we can lift hide/suspend (and enable a
	 * still-disabled system viewer) before launching, so the tap actually opens the file list.
 *
 * We deliberately do NOT ACTION_VIEW the recorded file directly: for APKs, install is a separate,
 * explicit action so split packages can use a foreground PackageInstaller session; for other types a
 * direct viewer is a surprising side-trip. Opening the file manager stays consistent for every
 * transfer record, while the chooser also exposes Xiaomi's file manager when it is installed.
 */
fun openSystemFileManager(context: Context) {
    val loc = PrismLocale.wrap(context)
    DiagnosticLog.i(TAG, "open system file manager requested")
    if (!prepareDownloadsViewerUsable(context)) {
        DiagnosticLog.w(TAG, "open system file manager unavailable after recovery")
        Toast.makeText(context, loc.getString(R.string.lz_pf_open_fail), Toast.LENGTH_LONG).show()
        return
    }
    val intent = SystemFileManagerLaunchPlanner.buildChooserIntent(context, loc.getString(R.string.lz_pf_open_action))
    if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
        DiagnosticLog.i(TAG, "open system file manager chooser launched")
        return
    }
    DiagnosticLog.w(TAG, "open system file manager failed")
    Toast.makeText(context, loc.getString(R.string.lz_pf_open_fail), Toast.LENGTH_LONG).show()
}

internal object SystemFileManagerLaunchPlanner {
    const val ACTION_XIAOMI_FILE_MANAGER_HOME = "com.android.fileexplorer.export.VIEW_HOME"
    const val ACTION_XIAOMI_OPEN_DOCUMENT = "hyper.intent.action.OPEN_DOCUMENT"

    fun launchSpec(): SystemFileManagerLaunchSpec =
        SystemFileManagerLaunchSpec(
            primary = FileManagerIntentSpec(action = DownloadManager.ACTION_VIEW_DOWNLOADS),
            initialIntents = listOf(
                FileManagerIntentSpec(
                    action = ACTION_XIAOMI_FILE_MANAGER_HOME,
                    packageName = InstallSourcePermissionHelper.SYSTEM_FILE_MANAGER_PACKAGE,
                ),
            ),
        )

    fun buildChooserIntent(context: Context, title: CharSequence): Intent {
        val spec = launchSpec()
        val primary = spec.primary.toIntent()
        val initialIntents = spec.initialIntents
            .map { it.toIntent() }
            .filter { it.canResolve(context) }
            .toTypedArray()
        return Intent.createChooser(primary, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                if (initialIntents.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents)
                }
            }
    }

    private fun FileManagerIntentSpec.toIntent(): Intent =
        Intent(action)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { intent -> packageName?.let(intent::setPackage) }

    private fun Intent.canResolve(context: Context): Boolean =
        context.packageManager.queryIntentActivities(this, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}

internal data class SystemFileManagerLaunchSpec(
    val primary: FileManagerIntentSpec,
    val initialIntents: List<FileManagerIntentSpec>,
)

internal data class FileManagerIntentSpec(
    val action: String,
    val packageName: String? = null,
)

/**
 * Only meaningful inside the managed profile (in the main space PrismSpace is not a profile owner,
 * so this is a no-op there). Enables the viewer if it is a still-disabled system app, then clears
 * hide + suspend on every package that can handle VIEW_DOWNLOADS (plus the well-known DocumentsUI
 * fallbacks), so the system installer-policy block no longer fires.
 */
fun prepareDownloadsViewerUsable(
    context: Context,
    intent: Intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
): Boolean {
    return runCatching {
        val dp = DevicePolicies(context)
        if (!dp.isProfileOwner) return true
        val pm = context.packageManager
        val routeIntents = listOf(
            intent,
            // HyperOS rewrites ACTION_OPEN_DOCUMENT to this OEM route. Query and restore it too;
            // otherwise DocumentsUI can be healthy while the actual Xiaomi picker is suspended.
            Intent(SystemFileManagerLaunchPlanner.ACTION_XIAOMI_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(intent.type ?: "*/*"),
        )
        val routePackages = routeIntents.mapNotNull { route ->
            runCatching { dp.enableSystemAppByIntent(route) }
            runCatching {
                pm.queryIntentActivities(
                    route,
                    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES,
                )
                    // `android` is the cross-profile forwarder, not a file surface. Treating it as
                    // a ready handler would launch into the profile-owner policy block while the
                    // real picker remains suspended.
                    .map { it.activityInfo.packageName }
                    .filterTo(linkedSetOf(), ::isSystemFileSurfacePackage)
                    .takeIf { it.isNotEmpty() }
            }.getOrNull()
        }.ifEmpty {
            listOf(setOf(
                "com.google.android.documentsui",
                "com.android.documentsui",
                "com.android.fileexplorer",
            ))
        }
        // Restore every discovered route before deciding. HyperOS can silently rewrite the
        // standard route to its OEM picker, so short-circuiting after DocumentsUI is insufficient.
        val readyPackages = routePackages.flatten().toSet().filterTo(mutableSetOf()) { pkg ->
            val reason = runCatching { PrismManager.ensureAppFreeToLaunch(context, pkg) }
                .getOrElse { it.javaClass.simpleName }
            if (reason.isNotEmpty()) DiagnosticLog.w(TAG, "system file surface unavailable pkg=$pkg reason=$reason")
            reason.isEmpty()
        }
        routesHaveUsableSurface(routePackages, readyPackages)
    }.getOrElse {
        DiagnosticLog.w(TAG, "system file surface recovery failed", it)
        false
    }
}

/** Restore and verify the exact system surface used by OpenMultipleDocuments. */
fun prepareSystemFilePickerUsable(context: Context): Boolean =
    prepareDownloadsViewerUsable(
        context,
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"),
    )

internal fun isSystemFileSurfacePackage(packageName: String): Boolean = packageName != "android"

internal fun routesHaveUsableSurface(routes: List<Set<String>>, readyPackages: Set<String>): Boolean =
    routes.isNotEmpty() && routes.all { route -> route.any(readyPackages::contains) }

private const val TAG = "Prism.FileOpen"
