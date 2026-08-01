package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.ProfileProvisioningFactsDto
import com.yzddmr6.prismspace.bridge.QueryProfileProvisioningFacts
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceFacts
import com.yzddmr6.prismspace.space.SpaceProfileFacts
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.space.SpaceStateClassifier
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

/** Collects Android facts; all policy remains in the shared pure classifier. */
class SpaceStateRepository(private val appContext: Context) {
    private val bridgeHealth = BridgeHealthRepository(appContext)

    fun state(): SpaceState {
        val facts = facts()
        return SpaceStateClassifier.classify(facts).also { state ->
            DiagnosticLog.i(TAG, "${facts.diagnosticLine()} -> $state")
        }
    }

    fun preflightCreate(): SpaceState = state()

    fun facts(): SpaceFacts {
        runCatching { Users.refreshUsers(appContext) }
            .onFailure { DiagnosticLog.w(TAG, "refresh users before classification failed", it) }
        val userManager = appContext.getSystemService(UserManager::class.java)
            ?: return SpaceFacts(emptyList())
        val launcherApps = appContext.getSystemService(LauncherApps::class.java)
            ?: return SpaceFacts(emptyList())
        val profiles = runCatching { userManager.userProfiles.orEmpty() }
            .onFailure { DiagnosticLog.w(TAG, "profile enumeration failed", it) }
            .getOrDefault(emptyList())
            .filterNot { it == Users.current() }

        return SpaceFacts(profiles.map { profile ->
            collectProfileFacts(userManager, launcherApps, profile)
        })
    }

    fun userHandle(userId: Int): UserHandle? =
        appContext.getSystemService(UserManager::class.java)?.userProfiles
            ?.firstOrNull { it.toId() == userId }

    fun profileOwnerPackage(userId: Int): String? = userHandle(userId)?.let { profile ->
        DevicePolicies.getProfileOwnerAsUser(appContext, profile)?.orElse(null)?.packageName
    }

    private fun collectProfileFacts(
        userManager: UserManager,
        launcherApps: LauncherApps,
        profile: UserHandle,
    ): SpaceProfileFacts {
        val userId = profile.toId()
        val packagePresent = runCatching {
            LauncherAppsCompat.getApplicationInfoNoThrows(
                launcherApps, Modules.MODULE_ENGINE, MATCH_UNINSTALLED_PACKAGES, profile,
            )
                ?.installed == true
        }.onFailure { DiagnosticLog.w(TAG, "package fact unavailable user=$userId", it) }
            .getOrDefault(false)
        val markerPresent = runCatching {
            launcherApps.getActivityList(Modules.MODULE_ENGINE, profile).orEmpty()
                .any { it.componentName.className == PrismSettingsActivity::class.java.name }
        }.onFailure { DiagnosticLog.w(TAG, "marker fact unavailable user=$userId", it) }
            .getOrDefault(false)
        val running = runCatching { Users.isProfileRunning(appContext, profile) }.getOrDefault(false)
        val quiet = runCatching { Users.isProfileQuietModeEnabled(appContext, profile) }.getOrDefault(false)
        val unlocked = runCatching { userManager.isUserUnlocked(profile) }.getOrDefault(false)
        val cachedHealth = bridgeHealth.cachedHealth(profile)
        val bridgeReady = cachedHealth?.available
        val exact = if (packagePresent && !markerPresent && bridgeReady == true) exactProfileFacts(profile) else null

        return SpaceProfileFacts(
            userId = userId,
            prismPackagePresent = packagePresent,
            ownershipMarkerPresent = markerPresent,
            provisioningActive = SpaceProvisioningTracker.isActive(),
            running = running,
            unlocked = unlocked,
            quietMode = quiet,
            bridgeReady = bridgeReady,
            bridgeCause = cachedHealth?.ping?.toBridgeCause() ?: SpaceBridgeCause.NotChecked,
            profileOwner = exact?.profileOwner,
            provisionComplete = exact?.provisionComplete,
        )
    }

    private fun exactProfileFacts(profile: UserHandle): ProfileProvisioningFactsDto? {
        val target = BridgeTargets.profile(appContext, profile.toId()) ?: return null
        return when (val result = Bridge.inProfile(appContext, target)
            .execute(QueryProfileProvisioningFacts, timeoutMs = 1_500L)) {
            is ShuttleOutcome.Value -> result.value
            else -> null
        }
    }

    private fun SpaceFacts.diagnosticLine(): String = profiles.joinToString(
        prefix = "Prism.SpaceState facts(profiles=[",
        postfix = "])",
    ) { p ->
        "${p.userId}:pkg=${p.prismPackagePresent},marker=${p.ownershipMarkerPresent}," +
            "running=${p.running},unlocked=${p.unlocked},quiet=${p.quietMode},bridge=${p.bridgeReady}"
    }

    private fun ShuttleOutcome<Boolean>.toBridgeCause(): SpaceBridgeCause = when (this) {
        is ShuttleOutcome.Value -> SpaceBridgeCause.NotChecked
        is ShuttleOutcome.NotReady -> when (cause) {
            ShuttleNotReadyCause.PermissionDenied -> SpaceBridgeCause.PermissionDenied
            ShuttleNotReadyCause.UnknownAuthority -> SpaceBridgeCause.ProviderUnavailable
            ShuttleNotReadyCause.PermissionPresentCallFailed -> SpaceBridgeCause.Failed
        }
        ShuttleOutcome.TimedOut -> SpaceBridgeCause.TimedOut
        is ShuttleOutcome.Failed -> SpaceBridgeCause.Failed
        is ShuttleOutcome.Skipped -> SpaceBridgeCause.ProfileUnavailable
    }

    private companion object {
        const val TAG = "Prism.SpaceState"
    }
}

/** Small process-local signal covering the system provisioning hand-off window. */
object SpaceProvisioningTracker {
    @Volatile private var activeUntilMs: Long = 0L

    @JvmStatic fun markStarted() { activeUntilMs = Long.MAX_VALUE }
    @JvmStatic fun markReturnedSuccess() { activeUntilMs = SystemClock.elapsedRealtime() + RETURN_WINDOW_MS }
    @JvmStatic fun clear() { activeUntilMs = 0L }
    fun isActive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = nowMs <= activeUntilMs

    private const val RETURN_WINDOW_MS = 30_000L
}
