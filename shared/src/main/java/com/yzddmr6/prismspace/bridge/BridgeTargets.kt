package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.os.UserHandle
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

@JvmInline
value class ProfileTarget internal constructor(internal val handle: UserHandle) {
    val userId: Int get() = handle.toId()
}

@JvmInline
value class ParentTarget internal constructor(internal val handle: UserHandle) {
    val userId: Int get() = handle.toId()
}

object BridgeTargets {
    fun profile(context: Context): ProfileTarget? = Users.profile?.let { profile(context, it.toId()) }

    fun profile(context: Context, userId: Int): ProfileTarget? {
        val handle = UserHandles.of(userId) ?: return null
        val valid = if (handle == Users.current()) {
            !Users.isParentProfile() && DevicePolicies(context).isProfileOwner
        } else {
            Users.isParentProfile() && runCatching { Users.isProfileManagedByPrism(context, handle) }.getOrDefault(false)
        }
        return handle.takeIf { valid }?.let(::ProfileTarget)
    }

    fun parent(context: Context): ParentTarget? {
        val parent = runCatching { Users.parentProfile }.getOrNull() ?: return null
        val valid = if (parent == Users.current()) Users.isParentProfile()
        else !Users.isParentProfile() && DevicePolicies(context).isProfileOwner
        return parent.takeIf { valid }?.let(::ParentTarget)
    }
}
