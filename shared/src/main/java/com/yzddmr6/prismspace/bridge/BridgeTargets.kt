package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.os.UserHandle
import androidx.annotation.WorkerThread
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
    fun profile(): ProfileTarget? = Users.profile?.let { profile(it.toId()) }

    fun profile(userId: Int): ProfileTarget? {
        val handle = UserHandles.of(userId) ?: return null
        val valid = if (handle == Users.current()) {
            !Users.isParentProfile() && Users.isCurrentProfileManagedByPrism()
        } else {
            Users.isParentProfile() && runCatching { Users.isProfileManagedByPrism(handle) }.getOrDefault(false)
        }
        return handle.takeIf { valid }?.let(ProfileTarget::validated)
    }

    @WorkerThread
    fun profileFresh(context: Context, userId: Int): ProfileTarget? {
        runCatching { Users.refreshUsers(context) }.getOrElse { return null }
        return profile(userId)
    }

    fun parent(): ParentTarget? {
        val parent = runCatching { Users.parentProfile }.getOrNull() ?: return null
        val valid = if (parent == Users.current()) Users.isParentProfile()
        else !Users.isParentProfile() && Users.isCurrentProfileManagedByPrism()
        return parent.takeIf { valid }?.let(ParentTarget::validated)
    }

    @WorkerThread
    fun parentFresh(context: Context): ParentTarget? {
        runCatching { Users.refreshUsers(context) }.getOrElse { return null }
        return parent()
    }
}
