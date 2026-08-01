package com.yzddmr6.prismspace.shizuku

import com.yzddmr6.prismspace.analytics.DiagnosticLog
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui

class NonRootShizukuProvider: ShizukuProvider() {

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        val packageName = requireNotNull(context).packageName
        val initialized = runCatching { Sui.init(packageName) }
            .onFailure { DiagnosticLog.w(TAG, "Sui initialization failed package=$packageName", it) }
            .getOrDefault(false)
        val created = super.onCreate()
        val transport = when {
            runCatching { Sui.isSui() }.getOrDefault(false) -> "sui"
            runCatching { Shizuku.getVersion() >= 11 }.getOrDefault(false) -> "shizuku"
            else -> "none"
        }
        DiagnosticLog.i(TAG, "privileged transport=$transport suiInitialized=$initialized package=$packageName")
        return created
    }

    private companion object { const val TAG = "Prism.ShizukuProvider" }
}
