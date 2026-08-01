package com.yzddmr6.prismspace.bridge

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shared.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

interface AppControlPort {
    fun setAppFrozen(context: Context, packageName: String, frozen: Boolean): Boolean
    fun ensureAppHiddenState(context: Context, packageName: String, hidden: Boolean): Boolean
    fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean): Boolean
    fun setPackagesSuspended(context: Context, packageNames: List<String>, suspended: Boolean): Array<String>
    fun setPackagesFrozen(context: Context, packageNames: List<String>, frozen: Boolean): Array<String>
    fun ensureAppFreeToLaunch(context: Context, packageName: String): String
    fun markClonedSystemApp(context: Context, packageName: String): Boolean
    fun enableSystemApp(context: Context, packageName: String): Boolean
}

interface FileBridgePort {
    fun openWriteSession(
        context: Context,
        store: BridgeFileStore,
        safeName: String,
        mimeType: String,
        relativePath: String,
    ): WriteSessionDto
    fun finishWriteSession(
        context: Context,
        store: BridgeFileStore,
        targetUri: String,
        history: TransferHistoryDto?,
    ): String
    fun abortWriteSession(context: Context, store: BridgeFileStore, targetUri: String)
    fun importApkSet(
        context: Context,
        paths: List<String>,
        label: String,
        packageName: String,
        cloneLocation: String,
    ): String?
    fun queryLatestVisibleImage(context: Context): ProfileMediaEntryDto?
    fun openImagePicker(context: Context): Boolean
    fun openLatestForRead(context: Context, store: BridgeFileStore): ReadSessionDto?
    fun writePerAppShareMarker(context: Context, packageName: String): String
    fun deletePerAppShareMarker(context: Context, packageName: String): Boolean
    fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto?
    fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind): Boolean
}

interface AppListPort {
    fun queryProfileApps(context: Context, pageIndex: Int, pageSize: Int): ProfileAppPage
}

data class BridgeHandlers(
    val appControl: AppControlPort?,
    val fileBridge: FileBridgePort?,
    val appList: AppListPort?,
) {
    fun missing(): List<String> = buildList {
        if (appControl == null) add("app_control")
        if (fileBridge == null) add("file_bridge")
        if (appList == null) add("app_list")
    }
}

class BridgeHandlersBuilder internal constructor() {
    private var appControl: AppControlPort? = null
    private var fileBridge: FileBridgePort? = null
    private var appList: AppListPort? = null

    fun appControl(port: AppControlPort) {
        check(appControl == null) { "AppControlPort already contributed" }
        appControl = port
    }

    fun fileBridge(port: FileBridgePort) {
        check(fileBridge == null) { "FileBridgePort already contributed" }
        fileBridge = port
    }

    fun appList(port: AppListPort) {
        check(appList == null) { "AppListPort already contributed" }
        appList = port
    }

    internal fun build() = BridgeHandlers(appControl, fileBridge, appList)
}

fun interface BridgePortsContributor {
    fun contribute(builder: BridgeHandlersBuilder)
}

object BridgePortsContributors {
    private val contributors = CopyOnWriteArrayList<BridgePortsContributor>()

    fun register(contributor: BridgePortsContributor) {
        contributors += contributor
    }

    fun hasContributors(): Boolean = contributors.isNotEmpty()

    internal fun assemble(): BridgeHandlers = BridgeHandlersBuilder().also { builder ->
        contributors.forEach { it.contribute(builder) }
    }.build()
}

object BridgePorts {
    @Volatile private var installed: BridgeHandlers? = null

    fun install(handlers: BridgeHandlers) {
        if (installed != null) {
            val message = "bridge_ports_duplicate_install"
            if (BuildConfig.DEBUG) error(message)
            DiagnosticLog.w(TAG, message)
            return
        }
        installed = handlers
    }

    fun verifyInstalled() {
        val missing = installed?.missing().orEmpty().ifEmpty {
            if (installed == null) listOf("installation") else emptyList()
        }
        if (missing.isEmpty()) return
        val message = "bridge_ports_missing handlers=${missing.joinToString()}"
        if (BuildConfig.DEBUG) error(message)
        DiagnosticLog.e(TAG, message)
    }

    internal fun handlers(): BridgeHandlers? = installed
}

private const val TAG = "Prism.Bridge"
