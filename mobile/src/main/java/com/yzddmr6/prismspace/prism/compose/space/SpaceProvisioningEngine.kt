package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.content.pm.LauncherApps
import android.os.SystemClock
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.BuildConfig
import com.yzddmr6.prismspace.util.DeviceAdmins
import com.yzddmr6.prismspace.util.Hacks
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.prism.compose.settings.ExperimentalFlags
import com.yzddmr6.prismspace.space.SpaceState
import eu.chainfire.libsuperuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Root-gated create/delete of PrismSpace-managed profile spaces. PUBLIC APIs only;
 *  pure parsing/decisions delegated to SpaceProvisioningParsers. Activity-free. */
object SpaceProvisioningEngine {

    private const val DEFAULT_MAX_USERS_SETPROP = 10  // historical fw.max_users AOSP default; used only when the real cap is Unknown

    private fun rootOk(): Boolean = isRootOutput(Shell.SU.run("id"))

    private fun maxUsersProperty(): String? =
        (Hacks.SystemProperties_get.invoke(MAX_USERS_PROPERTY).statically() as? String)?.takeIf { it.isNotBlank() }

    private fun maxUsers(): Int? {
        val property = maxUsersProperty()?.toIntOrNull()
        val resource = runCatching {
                val r = android.content.res.Resources.getSystem()
                val id = r.getIdentifier("config_multiuserMaximumUsers", "integer", "android")
                if (id == 0) null else r.getInteger(id)
            }.getOrNull()
        return effectiveMaxUsers(property, resource)
    }

    fun probeMaxSpaces(): SpaceCapProbe =
        computeCap(maxUsers(), Users.getProfilesManagedByPrism().size)

    @JvmStatic fun createSpaceBlocking(context: Context): CreateSpaceResult =
        runBlocking { createSpace(context) }

    suspend fun createSpace(context: Context): CreateSpaceResult = withContext(Dispatchers.IO) {
        val stateRepository = SpaceStateRepository(context)
        stateRepository.refresh("root_create_preflight")
        if (!ExperimentalFlags.isMultiProfileEnabled(context)) {
            val preflight = stateRepository.preflightCreate()
            if (preflight != SpaceState.NoProfile) {
                DiagnosticLog.w(TAG, "root create blocked by state=$preflight")
                return@withContext CreateSpaceResult.BlockedByState(preflight)
            }
        }
        if (!rootOk()) return@withContext CreateSpaceResult.RootUnavailable
        val probe = probeMaxSpaces()
        (probe as? SpaceCapProbe.Known)
            ?.takeIf { it.current >= it.max }
            ?.let { return@withContext CreateSpaceResult.CapReached(it.max) }
        val cap = (probe as? SpaceCapProbe.Known)?.max ?: DEFAULT_MAX_USERS_SETPROP
        val verifierOriginal = ProvisioningSideEffects.verifierOriginal(context)
        val maxUsersOriginal = maxUsersProperty()
        val sourcePath = context.packageManager.getApplicationInfo(Modules.MODULE_ENGINE, 0).sourceDir
        val admin = DeviceAdmins.getComponentName(context).flattenToString()
        val command = buildRootProvisioningCommand(
            RootProvisioningCommandInput(
                parentUserId = Users.currentId(),
                temporaryMaxUsers = cap,
                apkPath = sourcePath,
                adminComponent = admin,
                debugBuild = BuildConfig.DEBUG,
                verifierOriginal = verifierOriginal,
                maxUsersOriginal = maxUsersOriginal,
            ),
        )
        val sideEffectToken = if (verifierOriginal == "0") null else
            ProvisioningSideEffects.recordBeforeWrite(context, verifierOriginal) ?: run {
            return@withContext CreateSpaceResult.Failed(
                "could not persist verifier restoration state",
                analyticsPhase = 2,
            )
        }
        ProvisioningSideEffects.logMaxUsersWrite(maxUsersOriginal, cap)
        SpaceProvisioningTracker.markStarted()
        val output = try {
            runDetachedProvisioningTransaction(context, command)
        } catch (e: RuntimeException) {
            DiagnosticLog.e(TAG, "root provisioning shell failed", e)
            SpaceProvisioningTracker.clear()
            return@withContext CreateSpaceResult.Failed(e.message, analyticsPhase = 2)
        } finally {
            ProvisioningSideEffects.onShellCompleted(context, sideEffectToken, verifierOriginal, maxUsersOriginal)
        }
        val create = parsePmCreateOutput(output)
        when (create) {
            PmCreateOutcome.LimitReached -> {
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.CapReached(cap)
            }
            PmCreateOutcome.ManagedProfileLimit -> {
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.ManagedProfileLimitReached
            }
            is PmCreateOutcome.Failed -> {
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.Failed(create.reason, analyticsPhase = 1)
            }
            is PmCreateOutcome.Created -> Unit
        }
        val pid = (create as PmCreateOutcome.Created).userId
        if (!provisioningCompleted(output, pid)) {
            SpaceProvisioningTracker.clear()
            val reason = provisioningFailure(output) ?: "provisioning transaction incomplete"
            DiagnosticLog.w(TAG, "root create incomplete user=$pid reason=$reason")
            return@withContext CreateSpaceResult.Failed(reason, analyticsPhase = 2)
        }
        val pending = UserHandles.of(pid)
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        return@withContext if (la.getActivityList(context.packageName, pending).isNotEmpty()) {
            SpaceProvisioningTracker.markReturnedSuccess()
            DiagnosticLog.i(TAG, "root create success user=$pid")
            CreateSpaceResult.Success(pid)
        } else {
            SpaceProvisioningTracker.clear()
            val reason = output?.joinToString("\n")?.ifBlank { null } ?: "provisioning incomplete"
            DiagnosticLog.w(TAG, "root create incomplete user=$pid reason=$reason")
            CreateSpaceResult.Failed(reason, analyticsPhase = 2)
        }
    }

    suspend fun deleteSpace(context: Context, space: PrismSpace): DeleteSpaceResult = withContext(Dispatchers.IO) {
        if (!rootOk()) return@withContext DeleteSpaceResult.RootUnavailable
        val output = Shell.SU.run(buildVerifiedRootRemovalCommand(space.userId, Modules.MODULE_ENGINE))
        if (rootRemovalOwnerMismatch(output)) {
            DiagnosticLog.w(TAG, "root delete refused: owner mismatch user=${space.userId}")
            return@withContext DeleteSpaceResult.ManualRemovalRequired("PrismSpace is not profile owner")
        }
        when (val r = parsePmRemoveOutput(output)) {
            PmRemoveOutcome.Removed -> {
                SpaceStateRepository(context).refresh("root_delete_success")
                DiagnosticLog.i(TAG, "root delete success user=${space.userId}")
                DeleteSpaceResult.Success
            }
            is PmRemoveOutcome.Failed -> {
                DiagnosticLog.w(TAG, "root delete failed user=${space.userId} reason=${r.reason}")
                DeleteSpaceResult.Failed(r.reason)
            }
        }
    }

    private suspend fun runDetachedProvisioningTransaction(context: Context, command: String): List<String>? {
        val outputFile = File(context.cacheDir, ROOT_TRANSACTION_OUTPUT_FILE)
        if (runCatching {
                outputFile.parentFile?.mkdirs()
                outputFile.writeText("")
            }.isFailure) {
            return null
        }
        val pid = Shell.SU.run(detachedRootProvisioningLauncher(command, outputFile.absolutePath))
            ?.asSequence()
            ?.map(String::trim)
            ?.mapNotNull(String::toLongOrNull)
            ?.lastOrNull()
            ?: return null
        val deadline = SystemClock.elapsedRealtime() + ROOT_TRANSACTION_TIMEOUT_MS
        var lines = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lines = runCatching { outputFile.readLines() }.getOrDefault(emptyList())
            if (provisioningTransactionFinished(lines)) {
                outputFile.delete()
                return lines
            }
            delay(ROOT_TRANSACTION_POLL_MS)
        }
        Shell.SU.run("kill -TERM $pid")
        delay(ROOT_TRANSACTION_POLL_MS)
        lines = runCatching { outputFile.readLines() }.getOrDefault(lines)
        outputFile.delete()
        return lines + "PRISM_PROVISION_FAILED stage=timeout"
    }

    private const val TAG = "Prism.SpaceProvision"
    private const val ROOT_TRANSACTION_OUTPUT_FILE = "root-provisioning-transaction.log"
    private const val ROOT_TRANSACTION_TIMEOUT_MS = 120_000L
    private const val ROOT_TRANSACTION_POLL_MS = 200L
}
