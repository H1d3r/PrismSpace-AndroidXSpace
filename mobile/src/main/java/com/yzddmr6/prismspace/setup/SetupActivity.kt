package com.yzddmr6.prismspace.setup

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.yzddmr6.prismspace.util.PrismLocale
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.setup.compose.PrismSetupScreen
import com.yzddmr6.prismspace.setup.compose.SetupController
import com.yzddmr6.prismspace.setup.compose.SetupStateViewModel
import com.yzddmr6.prismspace.space.SpaceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * PrismSpace setup activity.
 *
 * Compose-only ComponentActivity. UI state lives in [SetupStateViewModel] so the
 * error dialog and the `incompleteSetupAcked` flag survive rotation /
 * dark-mode toggle. The ActivityResultLauncher is owned by the Activity
 * (must register before STARTED) and dispatches into the retained VM.
 *
 * Business logic — DPM prerequisite checks, managed-provisioning intent build,
 * result handling — stays in [SetupViewModel], which is separate from
 * lifecycle UI state in [SetupStateViewModel].
 */
class SetupActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    private val stateVm: SetupStateViewModel by viewModels()
    private var healthObservation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val launcher = registerForActivityResult(StartActivityForResult()) { result ->
            SetupController.handleProvisionResult(this, stateVm, result.resultCode)
        }
        val controller = SetupController(this, stateVm, launcher)
        setContent { PrismSetupScreen(controller) }
    }

    override fun onResume() {
        super.onResume()
        if (!stateVm.provisioningLaunched || healthObservation?.isActive == true) return
        healthObservation = lifecycleScope.launch {
            val repository = SpaceStateRepository(applicationContext)
            val refreshed = repository.refresh("setup_resumed_after_provisioning")
            DiagnosticLog.i(TAG, "setup resumed awaiting provisioning refreshed=$refreshed state=${repository.currentState()}")
            repository.state.first { snapshot ->
                (snapshot as? SpaceSnapshot.Loaded)?.state is SpaceState.Healthy
            }
            SetupController.finishSuccessfulProvisioning(this@SetupActivity, stateVm, "healthy_state")
        }
    }

    override fun onPause() {
        healthObservation?.cancel()
        healthObservation = null
        super.onPause()
    }

    private companion object {
        const val TAG = "Prism.SetupActivity"
    }
}
