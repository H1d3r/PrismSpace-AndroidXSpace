package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.os.UserHandle
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

sealed interface BridgeTarget {
    val userId: Int
}

class ProfileTarget private constructor(internal val handle: UserHandle) : BridgeTarget {
    override val userId: Int get() = handle.toId()

    internal companion object {
        fun validated(handle: UserHandle) = ProfileTarget(handle)
    }
}

class ParentTarget private constructor(internal val handle: UserHandle) : BridgeTarget {
    override val userId: Int get() = handle.toId()

    internal companion object {
        fun validated(handle: UserHandle) = ParentTarget(handle)
    }
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
        return handle.takeIf { valid }?.let(ProfileTarget::validated)
    }

    fun parent(context: Context): ParentTarget? {
        val parent = runCatching { Users.parentProfile }.getOrNull() ?: return null
        val valid = if (parent == Users.current()) Users.isParentProfile()
        else !Users.isParentProfile() && DevicePolicies(context).isProfileOwner
        return parent.takeIf { valid }?.let(ParentTarget::validated)
    }
}
