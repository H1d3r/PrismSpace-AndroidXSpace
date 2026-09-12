package com.yzddmr6.prismspace.installer

import android.content.Context
import com.yzddmr6.prismspace.bridge.BridgePortsContributors
import com.yzddmr6.prismspace.bridge.InstallerPort
import com.yzddmr6.prismspace.util.PseudoContentProvider

internal object AppSettingsInstallerPort : InstallerPort {
    override fun notifyPackageRestarted(
        context: Context,
        packageName: String,
        uid: Int,
        uptimeMillis: Long,
    ): Boolean {
        AppSettingsHelperService.onPackageRestarted(packageName, uid, uptimeMillis)
        return true
    }
}

class InstallerBridgePortsProvider : PseudoContentProvider() {
    override fun onCreate(): Boolean {
        BridgePortsContributors.register { it.installer(AppSettingsInstallerPort) }
        return true
    }
}
