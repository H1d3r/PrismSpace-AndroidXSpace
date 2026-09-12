package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import com.yzddmr6.prismspace.PrismNameManager
import com.yzddmr6.prismspace.common.app.AppListProvider
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.space.SpaceState

class DefaultSpaceRepository(private val appContext: Context) : SpaceRepository {
    private val provider get() = AppListProvider.getInstance<PrismAppListProvider>(appContext)
    private val stateRepository = SpaceStateRepository(appContext)
    // Space display names must follow the per-app language (Main + the profile default name).
    private val localized get() = PrismLocale.wrap(appContext)

    override fun spaces(): List<PrismSpace> {
        return buildList {
        add(PrismSpace("main", Users.parentProfile.toId(), PrismSpaceKind.Main, localized.getString(R.string.lz_space_main_name)))
        addAll(dualSpaces())
        }
    }

    override fun dualSpaces(): List<PrismSpace> {
        val named = PrismNameManager.getAllNames(localized)
            .map { (handle, name) -> PrismSpace("space_${handle.toId()}", handle.toId(), PrismSpaceKind.Dual, name) }
            .toMutableList()
        val currentState = stateRepository.currentState()
        if ((currentState is SpaceState.HalfProvisioned || currentState is SpaceState.Provisioning) &&
            named.none { it.userId == currentState.userId }) {
            val id = requireNotNull(currentState.userId)
            named += PrismSpace(
                "space_$id",
                id,
                PrismSpaceKind.Dual,
                localized.getString(com.yzddmr6.prismspace.shared.R.string.default_space_name),
            )
        }
        return named.sortedBy { it.userId }
    }

    override fun mainSpace() = spaces().first { it.kind == PrismSpaceKind.Main }
    override fun dualSpace() = dualSpaces().firstOrNull()
    override fun space(id: String) = spaces().firstOrNull { it.id == id }

    override fun usabilityOf(space: PrismSpace): SpaceUsability {
        if (space.kind == PrismSpaceKind.Main) return SpaceUsability.Usable
        return spaceUsabilityFromState(stateRepository.currentState(), space.userId)
    }

    override fun installedApps(space: PrismSpace): Collection<PrismAppInfo> {
        // Resolve the real UserHandle from space.userId:
        // Main -> parentProfile; Dual -> the managed profile whose toId() == userId.
        val handle = if (space.kind == PrismSpaceKind.Main) Users.parentProfile
                     else Users.getProfilesManagedByPrism().firstOrNull { it.toId() == space.userId }
                          ?: return emptyList()
        return provider.installedApps(handle)
    }

    override fun cloneTargetSpaceCount(): Int {
        return 1 + Users.getProfilesManagedByPrism().size
    }
}
