package com.yzddmr6.prismspace.prism.model

data class AppPolicy(
    val fileAccess: FileAccessMode = FileAccessMode.Isolated,
    val background: BackgroundPolicy = BackgroundPolicy.SystemDefault,
    val network: NetworkPolicy = NetworkPolicy.SystemDefault,
    val notifications: NotificationPolicy = NotificationPolicy.SystemDefault,
    val crossSpaceOpen: CrossSpaceOpenPolicy = CrossSpaceOpenPolicy.AskEveryTime,
    val restrictedPermissions: Set<PermissionRestriction> = emptySet(),
) { val permissionsRestricted: Int get() = restrictedPermissions.size }

enum class FileAccessMode {
    Isolated,
    ImportExportOnly,
    SharedMedia,
    SharedFolder,
}

enum class BackgroundPolicy {
    SystemDefault,
    FreezeWhenIdle,
    Suspended,
    BlockBackground,
}

enum class NetworkPolicy {
    SystemDefault,
    BlockBackground,
    BlockAll,
}

enum class NotificationPolicy {
    SystemDefault,
    HideInCloneSpace,
    Allow,
}

enum class CrossSpaceOpenPolicy {
    AskEveryTime,
    PreferMain,
    PreferClone,
}

enum class PermissionRestriction {
    Contacts,
    Location,
    Camera,
    Microphone,
    Photos,
    Files,
}

enum class PolicyAction {
    Freeze,
    Suspend,
    ImportExportFiles,
    RestrictBackground,
    RestrictNetwork,
    HideNotifications,
    ConfigureSharedMedia,
}

object AppPolicyPlanner {

    fun availability(action: PolicyAction, state: CapabilityState): CapabilityAvailability = when (action) {
        PolicyAction.Freeze,
        PolicyAction.Suspend -> if (state.profileOwner is CapabilityAvailability.Available)
            CapabilityAvailability.Available
        else CapabilityAvailability.NeedsSetup

        PolicyAction.ImportExportFiles -> if (state.normal is CapabilityAvailability.Available)
            CapabilityAvailability.Available
        else CapabilityAvailability.NeedsSetup

        PolicyAction.RestrictBackground,
        PolicyAction.RestrictNetwork,
        PolicyAction.HideNotifications,
        PolicyAction.ConfigureSharedMedia -> enhancedAvailability(state)
    }

    private fun enhancedAvailability(state: CapabilityState): CapabilityAvailability = when {
        state.shizuku is CapabilityAvailability.Available -> CapabilityAvailability.Available
        state.adb is CapabilityAvailability.Available -> CapabilityAvailability.Available
        state.root is CapabilityAvailability.Available -> CapabilityAvailability.Available
        state.root is CapabilityAvailability.AvailableButDisabled -> CapabilityAvailability.NeedsSetup
        else -> CapabilityAvailability.NeedsSetup
    }
}
