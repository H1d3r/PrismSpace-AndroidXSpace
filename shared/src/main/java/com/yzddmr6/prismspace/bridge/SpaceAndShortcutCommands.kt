package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProfileProvisioningFactsDto(
    val profileOwner: Boolean,
    val provisionComplete: Boolean,
) : Parcelable

enum class LaunchOutcomeKind { Ok, AppMissing, Denied, Unknown }

@Parcelize
data class LaunchOutcomeDto(
    val kind: LaunchOutcomeKind,
    val reason: String? = null,
) : Parcelable

@Parcelize
data class RequestPinShortcutInProfile(
    val packageName: String,
    val dynamicLabel: Boolean,
) : ProfileCommand<Boolean> {
    override val id get() = "shortcut.request_pin"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class UpdateAllShortcutsInProfile(val dynamicLabel: Boolean) : ProfileCommand<Boolean> {
    override val id get() = "shortcut.update_all"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class RemoveShortcutsInParent(
    val packageName: String,
    val profileUserId: Int,
) : ParentCommand<Boolean> {
    override val id get() = "shortcut.remove_in_parent"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class RefreshShortcutInParent(
    val packageName: String,
    val profileUserId: Int,
) : ParentCommand<Boolean> {
    override val id get() = "shortcut.refresh_in_parent"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data object QueryDynamicShortcutLabelEnabled : ParentCommand<Boolean> {
    override val id get() = "shortcut.query_dynamic_label"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data object QueryProfileProvisioningFacts : ProfileCommand<ProfileProvisioningFactsDto> {
    override val id get() = "space.query_provisioning_facts"
    override fun encodeResult(result: ProfileProvisioningFactsDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): ProfileProvisioningFactsDto = src.requireParcelableBridgeResult()
}

@Parcelize
data object TriggerIncrementalProvisioning : ProfileCommand<Boolean> {
    override val id get() = "space.trigger_incremental_provisioning"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data object WipeProfile : ProfileCommand<Boolean> {
    override val id get() = "space.wipe_profile"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data object QueryParentIsProfileOwner : ParentCommand<Boolean> {
    override val id get() = "space.query_parent_profile_owner"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class SaveProfileName(val profileUserId: Int, val name: String) : ParentCommand<Boolean> {
    override val id get() = "space.save_profile_name"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data object EstablishBackwardGrant : ProfileCommand<Unit> {
    override val id get() = "space.establish_backward_grant"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

@Parcelize
data class SetAppOpMode(
    val packageName: String,
    val op: Int,
    val mode: Int,
    val uid: Int,
) : ProfileCommand<Unit> {
    override val id get() = "app_ops.set_mode"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

@Parcelize
data class NotifyPackageRestarted(
    val packageName: String,
    val uid: Int,
    val uptimeMillis: Long,
) : ParentCommand<Boolean> {
    override val id get() = "installer.notify_package_restarted"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class StartProfileDeactivation(val profileUserId: Int) : ParentCommand<Unit> {
    override val id get() = "watcher.start_profile_deactivation"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

@Parcelize
data class UnfreezeAndLaunchApp(val packageName: String) : ProfileCommand<LaunchOutcomeDto> {
    override val id get() = "app.unfreeze_and_launch"
    override fun encodeResult(result: LaunchOutcomeDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): LaunchOutcomeDto = src.requireParcelableBridgeResult()
}

/** Internal shortcut producers only encode package launches; no Intent/component crosses the boundary. */
@Parcelize
data class LaunchAppInProfile(
    val packageName: String,
    val unfreezeFirst: Boolean,
) : ProfileCommand<Boolean> {
    override val id get() = "app.launch_in_profile"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class OpenAppDetailsInProfile(val packageName: String) : ProfileCommand<Unit> {
    override val id get() = "app.open_details_in_profile"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

internal val SPACE_AND_SHORTCUT_COMMAND_SAMPLES: List<BridgeCommand<*>> = listOf(
    RequestPinShortcutInProfile("pkg", true),
    UpdateAllShortcutsInProfile(true),
    RemoveShortcutsInParent("pkg", 10),
    RefreshShortcutInParent("pkg", 10),
    QueryDynamicShortcutLabelEnabled,
    QueryProfileProvisioningFacts,
    TriggerIncrementalProvisioning,
    WipeProfile,
    QueryParentIsProfileOwner,
    SaveProfileName(10, "Space"),
    EstablishBackwardGrant,
    SetAppOpMode("pkg", 1, 2, 10001),
    NotifyPackageRestarted("pkg", 10001, 1L),
    StartProfileDeactivation(10),
    UnfreezeAndLaunchApp("pkg"),
    LaunchAppInProfile("pkg", true),
    OpenAppDetailsInProfile("pkg"),
)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.requireParcelableBridgeResult(): T {
    classLoader = BridgeCommand::class.java.classLoader
    return requireNotNull(getParcelable(RESULT)) { "Missing ${T::class.java.simpleName} bridge result" }
}
