package com.yzddmr6.prismspace.prism.service

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.UserHandle
import com.yzddmr6.prismspace.util.DevicePolicies

internal object ProfileRecoveryService {
    fun repair(context: Context, profile: UserHandle): ProfileBridgeResult<Boolean> =
        runProfileBridgeOperation(context, TAG, "incremental profile repair", target = profile) {
            if (!DevicePolicies(this).isProfileOwner) return@runProfileBridgeOperation false
            val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                .setComponent(ComponentName(packageName, PROVISIONING_SERVICE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            true
        }

    fun remove(context: Context, profile: UserHandle): ProfileBridgeResult<Boolean> =
        runProfileBridgeOperation(context, TAG, "profile-owner removal", target = profile) {
            val policies = DevicePolicies(this)
            if (!policies.isProfileOwner) return@runProfileBridgeOperation false
            policies.manager.wipeData(0)
            true
        }

    private const val TAG = "Prism.ProfileRecovery"
    private const val PROVISIONING_SERVICE = "com.yzddmr6.prismspace.provisioning.PrismProvisioning"
}
