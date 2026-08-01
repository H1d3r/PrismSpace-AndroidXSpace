package com.yzddmr6.prismspace.setup.compose

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.yzddmr6.prismspace.help.PrismHelp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.setup.PrismSetup
import com.yzddmr6.prismspace.setup.SetupViewModel
import com.yzddmr6.prismspace.util.Activities
import com.yzddmr6.prismspace.prism.compose.settings.ExperimentalFlags
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningTracker
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.space.SpaceState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped bridge between Compose UI and the [SetupViewModel] state machine.
 *
 * The controller does not own mutable state. All UI state lives in
 * [SetupStateViewModel], and
 * the [provisionLauncher] is owned by the Activity (registration must run
 * before STARTED, which forbids retention across recreation). On rotation the
 * Activity rebuilds the controller; the VM keeps the [SetupUiState.Error]
 * dialog alive and the [SetupStateViewModel.incompleteSetupAcked] flag sticky.
 */
class SetupController(
    private val activity: ComponentActivity,
    private val stateVm: SetupStateViewModel,
    private val provisionLauncher: ActivityResultLauncher<Intent>,
) {

    val uiState: StateFlow<SetupUiState> get() = stateVm.uiState

    /** Primary CTA tapped (welcome state or retry-from-error). */
    fun onPrimaryCta() {
        if (stateVm.uiState.value is SetupUiState.Checking) return
        stateVm.setUiState(SetupUiState.Checking)
        activity.lifecycleScope.launch {
            if (!ExperimentalFlags.isMultiProfileEnabled(activity)) {
                when (SpaceStateRepository(activity.applicationContext).preflightCreate()) {
                    SpaceState.NoProfile -> Unit
                    is SpaceState.OrphanProfile -> {
                        stateVm.setUiState(SetupUiState.Error(
                            messageRes = R.string.setup_error_orphan_profile,
                            messageParams = null,
                            extraActionRes = R.string.button_setup_help,
                        ))
                        return@launch
                    }
                    else -> {
                        stateVm.setUiState(SetupUiState.Error(
                            messageRes = R.string.setup_error_existing_space_state,
                            messageParams = null,
                            extraActionRes = R.string.button_setup_help,
                        ))
                        return@launch
                    }
                }
            }
            val errorVm = SetupViewModel.checkManagedProvisioningPrerequisites(activity, stateVm.incompleteSetupAcked)
            if (errorVm != null) {
                stateVm.setUiState(errorVm.toErrorState())
                return@launch
            }
            stateVm.setUiState(SetupUiState.Welcome)
            launchManagedProvisioning()
        }
    }

    /** Extra action button (from error state) tapped. */
    fun onExtraAction(@StringRes extraActionRes: Int) {
        when (extraActionRes) {
            R.string.button_setup_help -> PrismHelp.showSetupHelp(activity)
            R.string.button_have_checked -> {
                stateVm.incompleteSetupAcked = true
                stateVm.setUiState(SetupUiState.Welcome)
            }
            R.string.button_setup_space_with_root -> {
                PrismSetup.requestProfileOwnerSetupWithRoot(Activities.findActivityFrom(activity))
            }
            else -> Log.w(TAG, "Unhandled extra action: $extraActionRes")
        }
    }

    /** "Having trouble?" link from welcome state. */
    fun onShowHelp() {
        PrismHelp.showSetupHelp(activity)
    }

    /** Dismiss error and return to welcome. */
    fun onDismissError() {
        stateVm.setUiState(SetupUiState.Welcome)
    }

    private fun launchManagedProvisioning() {
        val intent = SetupViewModel.buildManagedProfileProvisioningIntentPublic(activity)
        try {
            SpaceProvisioningTracker.markStarted()
            provisionLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            SpaceProvisioningTracker.clear()
            Log.w(TAG, "Managed provisioning activity not found", e)
            stateVm.setUiState(SetupUiState.Error(
                messageRes = R.string.setup_error_missing_managed_provisioning,
                messageParams = null,
                extraActionRes = R.string.button_setup_help,
            ))
        }
    }

    companion object {
        private const val TAG = "Prism.SetupCtrl"

        /**
         * Activity-scoped launcher callback. Hoisted to a static so the Activity's
         * ActivityResultLauncher (rebuilt on every recreate) does not need to hold a
         * reference to a stale Controller — it dispatches directly into the retained VM.
         */
        @JvmStatic fun handleProvisionResult(activity: Activity, vm: SetupStateViewModel, resultCode: Int) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    SpaceProvisioningTracker.markReturnedSuccess()
                    Log.i(TAG, "Managed provisioning finished — closing setup activity.")
                    activity.finish()
                }
                Activity.RESULT_CANCELED -> {
                    SpaceProvisioningTracker.clear()
                    Log.i(TAG, "Managed provisioning was cancelled — show cancel recovery options.")
                    vm.setUiState(SetupUiState.Error(
                        messageRes = R.string.setup_solution_for_cancelled_provision,
                        messageParams = null,
                        extraActionRes = R.string.button_setup_space_with_root,
                    ))
                }
                else -> {
                    SpaceProvisioningTracker.clear()
                    Log.w(TAG, "Unexpected provision resultCode=$resultCode")
                }
            }
        }
    }
}

/** Compose-friendly UI state derived from [SetupViewModel]. */
sealed interface SetupUiState {
    /** Initial welcome screen — guided install pitch. */
    data object Welcome : SetupUiState

    /** The create button is waiting for the one allowed fresh state preflight. */
    data object Checking : SetupUiState

    /** Error pane shown when prerequisites fail or provisioning is cancelled. */
    data class Error(
        @StringRes val messageRes: Int,
        /** Message format args. List (not Array) so data-class equality is content-based. */
        val messageParams: List<String>?,
        @StringRes val extraActionRes: Int?,
    ) : SetupUiState
}

/** Adapter for setup prerequisite state. Private to keep it out of the public API. */
private fun SetupViewModel.toErrorState(): SetupUiState.Error {
    @Suppress("UNCHECKED_CAST")
    val params = (message_params as? Array<Any?>)?.map { it?.toString() ?: "" }
    return SetupUiState.Error(
        messageRes = message,
        messageParams = params,
        extraActionRes = action_extra.takeIf { it != 0 },
    )
}
