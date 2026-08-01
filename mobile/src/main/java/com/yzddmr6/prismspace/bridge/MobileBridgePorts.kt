package com.yzddmr6.prismspace.bridge

import android.content.Context
import com.yzddmr6.prismspace.controller.PrismAppControl
import com.yzddmr6.prismspace.engine.ClonedHiddenSystemApps
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.data.MobileAppListPort
import com.yzddmr6.prismspace.prism.service.MobileFileBridgePort
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PseudoContentProvider

internal object MobileAppControlPort : AppControlPort {
    override fun setAppFrozen(context: Context, packageName: String, frozen: Boolean) =
        PrismAppControl.setAppFrozenLocally(context, packageName, frozen)

    override fun ensureAppHiddenState(context: Context, packageName: String, hidden: Boolean) =
        PrismManager.ensureAppHiddenState(context, packageName, hidden)

    override fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean) =
        PrismAppControl.setPackagesSuspended(context, arrayOf(packageName), suspended).isEmpty()

    override fun setPackagesSuspended(
        context: Context,
        packageNames: List<String>,
        suspended: Boolean,
    ) = PrismAppControl.setPackagesSuspended(context, packageNames.toTypedArray(), suspended)

    override fun setPackagesFrozen(
        context: Context,
        packageNames: List<String>,
        frozen: Boolean,
    ) = PrismAppControl.setPackagesFrozenLocally(context, packageNames, frozen)

    override fun ensureAppFreeToLaunch(context: Context, packageName: String) =
        PrismManager.ensureAppFreeToLaunch(context, packageName)

    override fun markClonedSystemApp(context: Context, packageName: String) =
        ClonedHiddenSystemApps.setCloned(context, packageName)

    override fun enableSystemApp(context: Context, packageName: String) =
        DevicePolicies(context).enableSystemApp(packageName)
}

class MobileBridgePortsProvider : PseudoContentProvider() {
    override fun onCreate(): Boolean {
        BridgePortsContributors.register { builder ->
            builder.appControl(MobileAppControlPort)
            builder.fileBridge(MobileFileBridgePort)
            builder.appList(MobileAppListPort)
        }
        return true
    }
}
