package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.DiagnosticSection
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.controller.ClonePreparationStore
import com.yzddmr6.prismspace.controller.PrismAppControl
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.space.BridgeHealthRepository
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.SpaceDeletionCoordinator
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.SpaceRecoveryPlan
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.recoveryPlan
import com.yzddmr6.prismspace.prism.compose.space.presentSpace
import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.model.CapabilityAvailability
import com.yzddmr6.prismspace.prism.model.CapabilityState
import com.yzddmr6.prismspace.prism.model.PrismRootStatus
import com.yzddmr6.prismspace.prism.model.PrismSettingsModeState
import com.yzddmr6.prismspace.prism.model.PrismShizukuAdbStatus
import com.yzddmr6.prismspace.prism.model.SettingsActionPlanner
import com.yzddmr6.prismspace.prism.service.CapabilityService
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileRecoveryService
import com.yzddmr6.prismspace.setup.PrismSetup
import com.yzddmr6.prismspace.setup.SetupFlow
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.space.SpaceState
import eu.chainfire.libsuperuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

// ---------------------------------------------------------------------------
// Pure UI model — Android-free, fully unit-testable
// ---------------------------------------------------------------------------

/**
 * Per-mode row model with an active flag for Compose rendering.
 */
data class SettingsModeRow(
    val title: String,
    val summary: String,
    val statusLabel: String,
    val isActive: Boolean,
)

data class SettingsUiModel(
    val modeTitle: String,
    val modeBody: String,
    val level: PrismLevel,
    val profileOwnerReady: Boolean,
    val normalMode: SettingsModeRow,
    val shizukuAdbMode: SettingsModeRow,
    val rootMode: SettingsModeRow,
    // Feedback message shown below the screen (e.g. snapshot result)
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
    val spaceFreezeState: SpaceFreezeState = SpaceFreezeState.Unknown,
    // Real aggregated dual-space usability (locked / bridge-down drive the 暂停所有分身 switch's
    // disabled reason) and the clone count used by the danger-zone delete confirmation.
    val spaceUsability: SpaceUsability = SpaceUsability.Unknown,
    val cloneCount: Int = 0,
    // Single source of truth for which mode the user has selected
    val selectedMode: PrismMode = PrismMode.Normal,
    // Non-null when an update check found a newer release.
    val updateInfo: UpdateInfo? = null,
    val spaceActionTitle: String = "",
    val spaceActionSummary: String = "",
    val spaceActionNeedsConfirmation: Boolean = true,
    val spaceActionEnabled: Boolean = true,
) {
    val spaceSuspended: Boolean get() = spaceFreezeState == SpaceFreezeState.Frozen
}

/** A newer GitHub release than the installed build. */
data class UpdateInfo(val version: String, val notes: String, val url: String)

/**
 * Compare dotted numeric versions ("0.0.2" vs "0.0.1"). Returns >0 if [a] is newer than [b],
 * 0 if equal, <0 if older. Non-numeric / missing segments are treated as 0. Pure & unit-testable.
 */
fun compareSemver(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pa = parts(a); val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

/** The GitHub repo whose Releases drive the in-app update check. */
private const val GITHUB_REPO = "yzddmr6/PrismSpace"

// ---------------------------------------------------------------------------
// Pure mapper — maps raw flags + PrismSettingsModeState to SettingsUiModel.
// ---------------------------------------------------------------------------

/**
 * Pure mapper: given boolean flags, builds the SettingsUiModel.
 * selectedMode is the user's explicit choice.
 * Selection reflects persisted user intent; status text and capability availability reflect runtime facts.
 */
internal fun mapSettingsUiModel(
    profileOwner: Boolean,
    shizukuAuthorized: Boolean,
    modeState: PrismSettingsModeState,
    capabilityState: CapabilityState,
    selectedMode: PrismMode = PrismMode.Normal,
    res: StringResolver,
): SettingsUiModel {
    val shizukuCapable = shizukuAuthorized && capabilityState.shizuku is CapabilityAvailability.Available
    val rootCapable = capabilityState.root is CapabilityAvailability.Available
    val preferredReady = when (selectedMode) {
        PrismMode.Normal -> true
        PrismMode.Shizuku -> shizukuCapable
        PrismMode.Root -> rootCapable
    }
    val selectedTitle = when (selectedMode) {
        PrismMode.Normal -> modeState.normal.title
        PrismMode.Shizuku -> modeState.shizukuAdb.title
        PrismMode.Root -> modeState.root.title
    }
    val modeBody = when {
        !profileOwner -> res(R.string.lz_setvm_mode_body_not_created, emptyArray())
        !preferredReady -> res(R.string.lz_setvm_mode_body_preference_unavailable, arrayOf(selectedTitle))
        selectedMode == PrismMode.Shizuku -> res(R.string.lz_setvm_mode_body_shizuku, emptyArray())
        selectedMode == PrismMode.Root -> res(R.string.lz_setvm_mode_body_root, emptyArray())
        else -> res(R.string.lz_setvm_mode_body_normal, emptyArray())
    }
    val level = when {
        !profileOwner -> PrismLevel.Error
        !preferredReady -> PrismLevel.Warn
        else -> PrismLevel.Ok
    }

    return SettingsUiModel(
        modeTitle = res(R.string.lz_setvm_mode_title, emptyArray()),
        modeBody = modeBody,
        level = level,
        profileOwnerReady = profileOwner,
        normalMode = SettingsModeRow(
            title = modeState.normal.title,
            summary = modeState.normal.summary,
            statusLabel = modeState.normal.status,
            isActive = selectedMode == PrismMode.Normal,
        ),
        shizukuAdbMode = SettingsModeRow(
            title = modeState.shizukuAdb.title,
            summary = modeState.shizukuAdb.summary,
            statusLabel = modeState.shizukuAdb.status,
            isActive = selectedMode == PrismMode.Shizuku,
        ),
        rootMode = SettingsModeRow(
            title = modeState.root.title,
            summary = modeState.root.summary,
            statusLabel = modeState.root.status,
            isActive = selectedMode == PrismMode.Root,
        ),
        selectedMode = selectedMode,
        spaceActionTitle = if (profileOwner) {
            res(R.string.lz_set_repair_title, emptyArray())
        } else {
            res(R.string.lz_set_create_title, emptyArray())
        },
        spaceActionSummary = if (profileOwner) {
            res(R.string.lz_set_repair_summary, emptyArray())
        } else {
            res(R.string.lz_set_create_summary, emptyArray())
        },
        spaceActionNeedsConfirmation = profileOwner,
    )
}

// ---------------------------------------------------------------------------
// ViewModel backing the Settings screen.
// ---------------------------------------------------------------------------

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_PERMISSION_REQUEST = 1601
private const val PRISM_PROBE_PACKAGE = "com.yzddmr6.prismprobe"
private const val BYTES_PER_MB = 1024L * 1024L

/** Centralized PrismSpace repository URL. */
const val PRISM_RELEASES_URL = "https://github.com/yzddmr6/PrismSpace"

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val bridgeHealthRepo: BridgeHealthRepository by lazy { BridgeHealthRepository(getApplication()) }
    private val stateRepo: SpaceStateRepository by lazy { SpaceStateRepository(getApplication()) }
    private val capRepo: CapabilityRepository by lazy { CapabilityRepositoryProvider.get(getApplication()) }

    private val _uiState = MutableStateFlow<SettingsUiModel?>(null)
    val uiState: StateFlow<SettingsUiModel?> = _uiState

    // Selected mode is owned by CapabilityRepository and shared with Home.
    val selectedMode: StateFlow<PrismMode> get() = capRepo.selectedMode

    init {
        viewModelScope.launch {
            stateRepo.state.collectLatest { snapshot ->
                when (snapshot) {
                    SpaceSnapshot.Loading -> _uiState.value = null
                    is SpaceSnapshot.Failed -> {
                        _uiState.value = withContext(Dispatchers.IO) { buildUnavailableUiModel(snapshot) }
                    }
                    is SpaceSnapshot.Loaded -> {
                        _uiState.value = withContext(Dispatchers.IO) { buildUiModel(snapshot.state) }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Capability refresh
    // ---------------------------------------------------------------------------

    fun refreshCapabilities() {
        viewModelScope.launch {
            stateRepo.refresh("settings_explicit")
            val state = (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state ?: return@launch
            _uiState.value = withContext(Dispatchers.IO) { buildUiModel(state) }
        }
    }

    // ---------------------------------------------------------------------------
    // Shizuku action handling
    // ---------------------------------------------------------------------------

    /**
     * Calls SettingsActionPlanner.shizukuAction and dispatches:
     *   OpenManager → launches Shizuku manager app
     *   RequestPermission → Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
     *   Refresh → refreshCapabilities()
     */
    fun handleShizukuAction() {
        val available = isShizukuAvailable()
        val authorized = isShizukuAuthorized()
        val action = SettingsActionPlanner.shizukuAction(available, authorized)
        when (action) {
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.OpenManager -> openShizukuManager()
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.RequestPermission -> requestShizukuPermission()
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.Refresh -> refreshCapabilities()
        }
    }

    // ---------------------------------------------------------------------------
    // Normal mode is always selectable.
    // ---------------------------------------------------------------------------
    fun setNormalMode() {
        capRepo.setSelectedMode(PrismMode.Normal)
        setFeedback(str(R.string.lz_setvm_switched_normal), isError = false)
        refreshCapabilities()
    }

    // ---------------------------------------------------------------------------
    // If Shizuku is authorized, set selected mode to Shizuku via CapabilityRepository
    // and emits success feedback; otherwise keeps current mode + emits error feedback.
    // Returns true if authorized (used by ModeGuideSheet button).
    // ---------------------------------------------------------------------------
    /**
     * 检查更新: query the GitHub Releases API for the latest release, compare its tag_name to the
     * installed versionName, and surface the update dialog (notes + release page) when newer.
     */
    fun checkForUpdate() {
        setFeedback(str(R.string.lz_setvm_update_checking), isError = false)
        viewModelScope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            val ctx: Context = getApplication()
            val current = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrNull() ?: "0.0.0"
            when {
                release == null -> setFeedback(str(R.string.lz_setvm_update_failed), isError = true)
                compareSemver(release.version, current) > 0 ->
                    _uiState.value = _uiState.value?.copy(updateInfo = UpdateInfo(
                        release.version.trim().removePrefix("v").removePrefix("V"), release.notes, release.url))
                else -> setFeedback(str(R.string.lz_setvm_update_latest, current), isError = false)
            }
        }
    }

    /** Dismiss the update dialog (用户点「稍后」). */
    fun dismissUpdate() { _uiState.value = _uiState.value?.copy(updateInfo = null) }

    private fun fetchLatestRelease(): ReleaseInfo? = runCatching {
        val conn = (java.net.URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest").openConnection()
            as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrismSpace")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            ReleaseInfo(
                version = json.optString("tag_name"),
                notes = json.optString("body").ifBlank { json.optString("name") },
                url = json.optString("html_url").ifBlank { "https://github.com/$GITHUB_REPO/releases" })
        } finally { conn.disconnect() }
    }.getOrNull()

    private data class ReleaseInfo(val version: String, val notes: String, val url: String)

    fun checkShizuku(): Boolean {
        val authorized = isShizukuAuthorized()
        if (authorized) {
            capRepo.markShizukuReady()
            capRepo.setSelectedMode(PrismMode.Shizuku)
            setFeedback(str(R.string.lz_setvm_shizuku_connected), isError = false)
            refreshCapabilities()
        } else {
            // The mode sheet is the user-facing Shizuku setup entry. Merely re-checking
            // permission here leaves the documented authorization flow unreachable.
            handleShizukuAction()
        }
        return authorized
    }

    // ---------------------------------------------------------------------------
    // Root detection/request flow.
    // For detection: runs Shell.SU.run("id") — same libsuperuser Shell pattern.
    // If root grant succeeds, reports availability; if not, reports unavailable.
    // (The codebase has no stored root-enable toggle; this detects/requests root.)
    // ---------------------------------------------------------------------------
    // If root is granted, set selected mode to Root via CapabilityRepository + success feedback;
    // otherwise keeps current mode + error feedback. Never marks Root active unless granted.
    fun requestRoot() {
        viewModelScope.launch {
            setFeedback(str(R.string.lz_setvm_root_requesting), isError = false)
            val result = withContext(Dispatchers.IO) {
                try {
                    // libsuperuser returns stdout only when su execution was granted.
                    val out = Shell.SU.run("id")
                    out != null && out.isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            if (result) {
                capRepo.markRootReady()
                capRepo.setSelectedMode(PrismMode.Root)
                setFeedback(str(R.string.lz_setvm_root_granted), isError = false)
            } else {
                capRepo.markRootUnavailable()
                // Keep current mode unchanged — do NOT switch to Root on failure
                setFeedback(str(R.string.lz_setvm_root_denied), isError = true)
            }
            refreshCapabilities()
        }
    }

    // ---------------------------------------------------------------------------
    // Freeze or unfreeze every user-facing app in the dual space.
    // The package list is sourced via SpaceRepository.
    // ---------------------------------------------------------------------------
    fun suspendSpace(suspend: Boolean) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val result: SuspendResult = withContext(Dispatchers.IO) {
                try {
                    val dual = spaceRepo.dualSpace() ?: return@withContext SuspendResult.NoSpace
                    // Only the user's cloned apps participate in a space freeze.
                    // Exclude (a) system apps auto-provisioned into the work profile
                    // (settings/dialer/Play/files/…) — infrastructure DPM refuses to
                    // suspend, and (b) PrismSpace itself — the profile owner can't suspend
                    // its own admin package.
                    val self = context.packageName
                    // Operate on the user's 分身 only — same definition as the Space tab / count
                    // third-party clones, plus system apps the user explicitly cloned.
                    val apps = spaceRepo.installedApps(dual)
                        .filter {
                            it.isInstalled && it.packageName != self &&
                                (!it.isSystem || UserCloneRegistry.contains(context, it.packageName))
                        }
                        .toList()
                    if (apps.isEmpty()) return@withContext SuspendResult.NoApps
                    // 冻结整个空间 uses the same profile-owner freeze path as per-app 冻结,
                    // keeping badges and recovery behavior consistent.
                    val failed = PrismAppControl.setSpaceFrozen(apps, suspend)
                    when {
                        failed == null -> SuspendResult.NoSpace          // profile not ready
                        failed.isEmpty() -> SuspendResult.Ok
                        failed.size >= apps.size -> SuspendResult.AllFail  // nothing succeeded
                        else -> SuspendResult.PartialFail(failed.size)
                    }
                } catch (e: Exception) { SuspendResult.Error(e.message ?: e.javaClass.simpleName) }
            }
            when (result) {
                SuspendResult.Ok -> {
                    setFeedback(
                        if (suspend) str(R.string.lz_setvm_space_suspended)
                        else str(R.string.lz_setvm_space_resumed),
                        isError = false,
                    )
                }
                SuspendResult.NoSpace -> setFeedback(
                    str(R.string.lz_setvm_space_not_ready), isError = true)
                SuspendResult.NoApps -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_no_apps_to_suspend)
                    else str(R.string.lz_setvm_no_apps_to_resume), isError = true)
                // All failed → explain the cause + prerequisite (not a vague "partially failed").
                SuspendResult.AllFail -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_all_fail_freeze)
                    else str(R.string.lz_setvm_all_fail_unfreeze), isError = true)
                is SuspendResult.PartialFail -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_partial_fail_suspend, result.count)
                    else str(R.string.lz_setvm_partial_fail_resume, result.count), isError = true)
                is SuspendResult.Error -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_suspend_failed, result.detail)
                    else str(R.string.lz_setvm_resume_failed, result.detail), isError = true)
            }
            refreshCapabilities()
        }
    }

    private sealed class SuspendResult {
        object Ok : SuspendResult()
        object NoSpace : SuspendResult()
        object NoApps : SuspendResult()
        object AllFail : SuspendResult()
        data class PartialFail(val count: Int) : SuspendResult()
        data class Error(val detail: String) : SuspendResult()
    }

    // ---------------------------------------------------------------------------
    // Create/repair entry point. When the profile is absent, this opens the normal setup
    // wizard. When it exists but is paused/locked, it asks Android to bring that profile back.
    // ---------------------------------------------------------------------------
    fun repairSpace(context: Context) {
        viewModelScope.launch {
            val appContext = context.applicationContext
            if (!stateRepo.refresh("settings_repair")) {
                setFeedback(str(R.string.lz_setvm_state_refresh_failed), isError = true)
                return@launch
            }
            val plan = withContext(Dispatchers.IO) {
                val initial = (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state
                    ?: return@withContext null
                if (initial is SpaceState.HalfProvisioned || initial is SpaceState.BridgeDown) {
                    initial.userId?.let(UserHandles::of)?.let { profile ->
                        runCatching { bridgeHealthRepo.refreshHealth(profile) }
                            .onFailure { DiagnosticLog.w(TAG, "refresh bridge health before repair failed", it) }
                    }
                }
                if (!stateRepo.refresh("settings_repair_health")) return@withContext null
                (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state?.let(::recoveryPlan)
            }
            if (plan == null) {
                setFeedback(str(R.string.lz_setvm_state_refresh_failed), isError = true)
                return@launch
            }
            when (plan) {
                SpaceRecoveryPlan.StartSetup -> {
                    setFeedback(str(R.string.lz_setvm_opening_setup), isError = false)
                    SetupFlow.open(context)
                }
				is SpaceRecoveryPlan.Activate -> {
					setFeedback(str(R.string.lz_setvm_repairing), isError = false)
					val profile = UserHandles.of(plan.userId)
					val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
						runCatching { Users.requestQuietModeDisabled(context, profile) }
							.onFailure { DiagnosticLog.e(TAG, "profile activation failed", it) }
							.getOrDefault(false)
					} else {
						false
					}
                    if (ok) {
                        setFeedback(str(R.string.lz_setvm_space_resumed), isError = false)
                    } else {
                        setFeedback(str(R.string.lz_setvm_repair_failed, str(R.string.lz_setvm_profile_activation_failed)), isError = true)
                    }
                    refreshCapabilities()
                }
                is SpaceRecoveryPlan.ActivateThenOpenEntry -> {
                    setFeedback(str(R.string.lz_setvm_repairing), isError = false)
                    val profile = UserHandles.of(plan.userId)
                    val active = Users.isProfileAvailable(context, profile) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            runCatching { Users.requestQuietModeDisabled(context, profile) }.getOrDefault(false))
                    val opened = active && ProfileEntryLauncher.start(context, profile)
                    setFeedback(
                        if (opened) str(R.string.lz_setvm_bridge_repair_opened_profile)
                        else str(R.string.lz_setvm_incomplete_cannot_auto_repair),
                        isError = !opened,
                    )
                    refreshCapabilities()
                }
                is SpaceRecoveryPlan.OpenProfileUnlock -> {
                    val profile = UserHandles.of(plan.userId)
                    val opened = ProfileEntryLauncher.start(context, profile)
                    setFeedback(
                        if (opened) str(R.string.lz_setvm_unlock_opened_profile)
                        else str(R.string.lz_setvm_repair_failed, str(R.string.lz_setvm_profile_activation_failed)),
                        isError = !opened,
                    )
                    refreshCapabilities()
                }
                is SpaceRecoveryPlan.RepairIncrementally -> {
                    setFeedback(str(R.string.lz_setvm_repairing), isError = false)
                    val result = withContext(Dispatchers.IO) {
                        ProfileRecoveryService.repair(appContext, UserHandles.of(plan.userId))
                    }
                    val repaired = result is ProfileBridgeResult.Value && result.value == true
                    setFeedback(
                        if (repaired) str(R.string.lz_setvm_incomplete_repaired)
                        else str(R.string.lz_setvm_incomplete_cannot_auto_repair),
                        isError = !repaired,
                    )
                    refreshCapabilities()
                }
                is SpaceRecoveryPlan.ReconnectBridge -> {
                    setFeedback(str(R.string.lz_setvm_bridge_repairing), isError = false)
                    val opened = ProfileEntryLauncher.start(context, UserHandles.of(plan.userId))
                    if (opened) {
                        setFeedback(str(R.string.lz_setvm_bridge_repair_opened_profile), isError = false)
                    } else {
                        setFeedback(str(R.string.lz_setvm_repair_failed, str(R.string.lz_setvm_profile_activation_failed)), isError = true)
                    }
                    refreshCapabilities()
                }
                is SpaceRecoveryPlan.OpenSystemProfileSettings -> {
                    val manager = stateRepo.profileOwnerPackage(plan.userId)
                        ?: str(R.string.lz_setvm_orphan_manager_unknown)
                    setFeedback(str(R.string.lz_setvm_orphan_profile, manager), isError = true)
                    (context as? Activity)?.let(PrismSetup::promptManualRemoval)
                }
                is SpaceRecoveryPlan.WaitForProvisioning ->
                    setFeedback(str(R.string.lz_setvm_provisioning_active), isError = false)
                is SpaceRecoveryPlan.AlreadyReady -> {
                    setFeedback(str(R.string.lz_setvm_space_already_ready), isError = false)
                    refreshCapabilities()
                }
            }
        }
    }

    // Delete is exposed from Settings, but still delegates to the existing space deletion path.
    fun deleteDualSpace(activity: Activity?) {
        if (activity == null) {
            setFeedback(str(R.string.lz_space_delete_target_missing), isError = true)
            return
        }
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            setFeedback(res(R.string.lz_vm_deleting_space, emptyArray()), isError = false)
            if (!stateRepo.refresh("settings_delete_preflight")) {
                setFeedback(str(R.string.lz_setvm_state_refresh_failed), isError = true)
                return@launch
            }
            val space = withContext(Dispatchers.IO) {
                val state = (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state
                    ?: return@withContext null
                state.userId?.let { userId ->
                    spaceRepo.dualSpace() ?: PrismSpace(
                        id = "space_$userId",
                        userId = userId,
                        kind = PrismSpaceKind.Dual,
                        displayName = res(R.string.lz_vm_default_space_name, emptyArray()),
                    )
                }
            }
            if (space == null) {
                setFeedback(res(R.string.lz_space_delete_target_missing, emptyArray()), isError = true)
                refreshCapabilities()
                return@launch
            }
            val result = SpaceDeletionCoordinator.delete(
                getApplication(),
                space,
                capabilities = capRepo,
            )
            val fb = provisioningFeedback(result, res)
            if (result == DeleteSpaceResult.Success) {
                UserCloneRegistry.clear(getApplication())
                // Pending-install markers target a space that no longer exists — clear them so the
                // home todo card and the main-space rows stop offering 待安装 for a deleted space.
                ClonePreparationStore.clear(getApplication())
            }
            setFeedback(fb.message, isError = fb.isError)
            if (fb.routeToSystemRemoval) PrismSetup.promptManualRemoval(activity)
            refreshCapabilities()
        }
    }

    fun exportLogs(context: Context) {
        viewModelScope.launch {
            setFeedback(str(R.string.lz_setvm_sharing_report), isError = false)
            try {
                val (fileName, uri) = withContext(Dispatchers.IO) {
                    DiagnosticLog.i(TAG, "diagnostic export start")
                    val profileSections = collectProfileDiagnostics(context.applicationContext)
                    val file = DiagnosticLog.createExportFile(
                        context,
                        extraSections = profileSections,
                        includeLogcat = true,
                    )
                    file.name to DiagnosticLog.shareUri(context, file)
                }
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, str(R.string.lz_setvm_report_subject))
                    .putExtra(Intent.EXTRA_TEXT, str(R.string.lz_setvm_report_attached, fileName))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newUri(context.contentResolver, fileName, uri) }
                context.startActivity(
                    Intent.createChooser(intent, str(R.string.lz_setvm_report_subject))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                DiagnosticLog.i(TAG, "diagnostic export share sheet opened file=$fileName")
            } catch (e: Exception) {
                DiagnosticLog.e(TAG, "diagnostic export failed", e)
                setFeedback(str(R.string.lz_setvm_export_failed, e.message ?: e.javaClass.simpleName), isError = true)
            }
        }
    }

    // Alias retained for existing callers.
    fun exportDiagnostics(context: Context) = exportLogs(context)

    // ---------------------------------------------------------------------------
    // PrismProbe launcher.
    // ---------------------------------------------------------------------------

    fun runProbe(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PRISM_PROBE_PACKAGE)
        if (intent == null) {
            setFeedback(str(R.string.lz_setvm_probe_not_installed), isError = true)
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            setFeedback(str(R.string.lz_setvm_probe_open_failed, e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    // ---------------------------------------------------------------------------
    // One-shot performance snapshot. No background polling.
    // ---------------------------------------------------------------------------

    fun performanceSnapshot() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val maxMb = runtime.maxMemory() / BYTES_PER_MB
        val threads = Thread.activeCount()
        val cores = runtime.availableProcessors()
        val msg = str(R.string.lz_setvm_perf_snapshot, usedMb, maxMb, threads, cores)
        setFeedback(msg, isError = false)
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun buildUiModel(spaceState: SpaceState): SettingsUiModel {
        val context: Context = getApplication()
        val presentation = presentSpace(spaceState)
        val shizukuAvailable = isShizukuAvailable()
        val runtime = capRepo.runtimeSnapshot()
        val shizukuAuthorized = runtime.shizukuReady
        val profileOwner = presentation.hasProfile && presentation.kind != SpacePresentationKind.Orphan
        DiagnosticLog.d(
            TAG,
            "settings state profile=${Users.profile?.toId() ?: Users.NULL_ID} " +
                "profileOwner=$profileOwner state=$spaceState " +
                "shizukuAvailable=$shizukuAvailable shizukuAuthorized=$shizukuAuthorized",
        )

        val modeState = PrismSettingsModeState.from(
            shizuku = when {
                shizukuAuthorized -> PrismShizukuAdbStatus.Ready
                shizukuAvailable -> PrismShizukuAdbStatus.WaitingAuthorization
                else -> PrismShizukuAdbStatus.NotRunning
            },
            root = when (runtime.rootReadiness) {
                is RootReadiness.ReadyUntil -> if (runtime.preferredMode == PrismMode.Root) {
                    PrismRootStatus.Enabled
                } else PrismRootStatus.AvailableButDisabled
                RootReadiness.Unknown -> PrismRootStatus.NotDetected
                RootReadiness.Unavailable -> PrismRootStatus.Unavailable
            },
            res = prismResolver(context),
        )

        // Settings-local capability detection for mode-status display.
        // Home shows the configured mode via CapabilityRepository.
        val capabilityState = CapabilityService().buildState(
            profileOwner = profileOwner,
            shizukuReady = shizukuAuthorized,
            adbReady = false,
            rootDetected = runtime.rootReady,
            rootEnabled = runtime.rootReady && runtime.preferredMode == PrismMode.Root,
        )

        val current = _uiState.value
        // Pass the configured mode so isActive always reflects the user's choice.
        val result = mapSettingsUiModel(
            profileOwner = profileOwner,
            shizukuAuthorized = shizukuAuthorized,
            modeState = modeState,
            capabilityState = capabilityState,
            selectedMode = runtime.preferredMode,
            res = prismResolver(getApplication()),
        )
        val freezeState = observeSpaceFreezeState(context, presentation.kind)
        val spaceAction = settingsSpaceAction(presentation.kind, presentation.bridgeCause, prismResolver(context))
        val dual = spaceRepo.dualSpace()
        val usability = dual?.let { spaceRepo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
        val cloneCount = runCatching {
            val self = context.packageName
            spaceRepo.dualSpaces().sumOf { d ->
                runCatching {
                    // Same counting rule as Home: user clones plus launchable system apps.
                    spaceRepo.installedApps(d).count { app ->
                        app.isInstalled && app.shouldShowAsEnabled() && app.packageName != self &&
                            (!app.isSystem || UserCloneRegistry.contains(context, app.packageName) || app.isLaunchable)
                    }
                }.getOrElse { 0 }
            }
        }.getOrElse { 0 }
        return result.copy(
            feedbackMessage = current?.feedbackMessage,
            feedbackIsError = current?.feedbackIsError ?: false,
            spaceFreezeState = freezeState,
            spaceUsability = usability,
            cloneCount = cloneCount,
            spaceActionTitle = spaceAction.title,
            spaceActionSummary = spaceAction.summary,
            spaceActionNeedsConfirmation = spaceAction.needsConfirmation,
            spaceActionEnabled = spaceAction.enabled,
        )
    }

    private fun observeSpaceFreezeState(context: Context, kind: SpacePresentationKind): SpaceFreezeState {
        if (kind != SpacePresentationKind.Ready) return SpaceFreezeState.Unknown
        return runCatching {
            val dual = spaceRepo.dualSpace() ?: return@runCatching SpaceFreezeState.Unknown
            val self = context.packageName
            val facts = spaceRepo.installedApps(dual)
                .filter {
                    it.isInstalled && it.packageName != self &&
                        (!it.isSystem || UserCloneRegistry.contains(context, it.packageName))
                }
                .map { AppFreezeFact(it.isHidden, it.isSuspended) }
            aggregateSpaceFreeze(facts)
        }.getOrElse {
            DiagnosticLog.w(TAG, "whole-space freeze observation failed", it)
            SpaceFreezeState.Unknown
        }
    }

    private fun buildUnavailableUiModel(snapshot: SpaceSnapshot.Failed): SettingsUiModel {
        val base = buildUiModel(snapshot.lastKnown ?: SpaceState.NoProfile)
        return base.copy(
            modeBody = str(R.string.lz_setvm_state_refresh_failed),
            // 读取失败且无可用快照：无法判定好坏，呈现为 Neutral（不阻断、不渲染为正常）。
            level = PrismLevel.Neutral,
            profileOwnerReady = false,
            spaceFreezeState = SpaceFreezeState.Unknown,
            spaceActionTitle = str(R.string.lz_set_state_unavailable_title),
            spaceActionSummary = str(R.string.lz_set_state_unavailable_summary),
            spaceActionNeedsConfirmation = false,
            spaceActionEnabled = false,
        )
    }

    private fun collectProfileDiagnostics(context: Context): List<DiagnosticSection> {
        val profile = Users.profile ?: return listOf(DiagnosticSection(
            title = "Dual-space diagnostic snapshot",
            body = "No managed profile is currently known to the main space.",
        ))
        val healthSection = try {
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = ShuttleProvider.health(context, profile).diagnosticLine(),
            )
        } catch (error: Exception) {
            DiagnosticLog.w(TAG, "dual-space shuttle health collection failed user=${profile.toId()}", error)
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = "Health check failed: ${error.javaClass.name}: ${error.message.orEmpty()}",
            )
        }
        return try {
            DiagnosticLog.i(TAG, "dual-space diagnostic chunk collection start user=${profile.toId()}")
            val target = BridgeTargets.profile(profile.toId())
            val result = if (target == null) {
                DiagnosticsCollectionResult.Failure(
                    "Dual-space diagnostic snapshot unavailable: managed profile target is invalid.",
                    openAttempts = 0,
                )
            } else {
                collectDiagnosticsSnapshot(BridgeDiagnosticsSnapshotTransport(context, target))
            }
            val body = when (result) {
                is DiagnosticsCollectionResult.Success -> {
                    DiagnosticLog.i(
                        TAG,
                        "dual-space diagnostic chunk collection success user=${profile.toId()} " +
                            "bytes=${result.bytes.size} attempts=${result.openAttempts}",
                    )
                    result.bytes.toString(Charsets.UTF_8).ifBlank {
                        "Dual-space diagnostic snapshot was empty (profileBytes=${result.expectedLength})."
                    }
                }
                is DiagnosticsCollectionResult.Failure -> {
                    DiagnosticLog.w(
                        TAG,
                        "dual-space diagnostic chunk collection failed user=${profile.toId()} " +
                            "attempts=${result.openAttempts} reason=${result.reason}",
                    )
                    result.reason
                }
            }
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = body,
            ))
        } catch (e: Exception) {
            DiagnosticLog.e(TAG, "dual-space diagnostic collection failed", e)
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = "Failed to read dual-space diagnostic snapshot: ${e.javaClass.name}: ${e.message.orEmpty()}",
            ))
        }
    }

    // Launch the Shizuku manager if installed.
    private fun openShizukuManager() {
        val context: Context = getApplication()
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            setFeedback(str(R.string.lz_setvm_shizuku_not_installed), isError = true)
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            setFeedback(str(R.string.lz_setvm_shizuku_open_failed), isError = true)
        }
    }

    // Request Shizuku permission in-process.
    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            setFeedback(str(R.string.lz_setvm_shizuku_permission_requested), isError = false)
        } catch (e: RuntimeException) {
            setFeedback(str(R.string.lz_setvm_shizuku_permission_failed, e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    /** Clears the feedback message after it has been shown (called by the UI layer). */
    fun clearFeedback() {
        val current = _uiState.value ?: return
        _uiState.value = current.copy(feedbackMessage = null, feedbackIsError = false)
    }

    /** Locale-aware string resolution following the user's in-app language override. */
    private fun str(resId: Int, vararg args: Any): String =
        PrismLocale.wrap(getApplication()).getString(resId, *args)

    private fun setFeedback(message: String, isError: Boolean) {
        val current = _uiState.value
        if (current != null) {
            _uiState.value = current.copy(feedbackMessage = message, feedbackIsError = isError)
        } else {
            // state not yet loaded; create a minimal placeholder — level Neutral, never green
            // for an unknown state.
            _uiState.value = SettingsUiModel(
                modeTitle = "", modeBody = "", level = PrismLevel.Neutral,
                profileOwnerReady = false,
                normalMode = SettingsModeRow("", "", "", false),
                shizukuAdbMode = SettingsModeRow("", "", "", false),
                rootMode = SettingsModeRow("", "", "", false),
                feedbackMessage = message,
                feedbackIsError = isError,
                selectedMode = capRepo.selectedMode.value,
            )
        }
        AppFeedbackBus.emit(ActionFeedback(message, isError))
    }

    private fun isShizukuAvailable(): Boolean = ShizukuUtil.isAvailable()

    private fun isShizukuAuthorized(): Boolean = ShizukuUtil.isAuthorized()
}

private const val TAG = "Prism.SettingsVM"
