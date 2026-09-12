package com.yzddmr6.prismspace.prism.service

import android.content.Context
import android.os.UserHandle
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.TriggerIncrementalProvisioning
import com.yzddmr6.prismspace.bridge.WipeProfile
import com.yzddmr6.prismspace.util.Users.Companion.toId

internal object ProfileRecoveryService {
    fun repair(context: Context, profile: UserHandle): ProfileBridgeResult<Boolean> =
        runProfileBridgeOperation(
            context,
            TAG,
            "incremental profile repair",
            target = BridgeTargets.profile(profile.toId()),
            command = TriggerIncrementalProvisioning,
        )

    fun remove(context: Context, profile: UserHandle): ProfileBridgeResult<Boolean> =
        runProfileBridgeOperation(
            context,
            TAG,
            "profile-owner removal",
            target = BridgeTargets.profile(profile.toId()),
            command = WipeProfile,
        )

    private const val TAG = "Prism.ProfileRecovery"
}
