package com.yzddmr6.prismspace.setup.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Retained state for [com.yzddmr6.prismspace.setup.SetupActivity].
 *
 * Survives configuration changes (rotation, dark-mode toggle) via the standard
 * ViewModelStore. The sealed UI state does not survive process death, but the
 * primitive "waiting for managed provisioning" fact does via [SavedStateHandle]
 * so a system-owned provisioning hand-off cannot strand the recreated setup UI.
 *
 * This is intentionally distinct from
 * [com.yzddmr6.prismspace.setup.SetupViewModel], which holds provisioning
 * business logic rather than lifecycle UI state.
 */
class SetupStateViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Welcome)
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /** Sticky across rotation. */
    var incompleteSetupAcked: Boolean = false

    var provisioningLaunched: Boolean
        get() = savedState[KEY_PROVISIONING_LAUNCHED] ?: false
        set(value) { savedState[KEY_PROVISIONING_LAUNCHED] = value }

    fun consumeProvisioningLaunched(): Boolean {
        if (!provisioningLaunched) return false
        provisioningLaunched = false
        return true
    }

    fun setUiState(next: SetupUiState) {
        _uiState.value = next
    }

    internal companion object {
        const val KEY_PROVISIONING_LAUNCHED = "provisioning_launched"
    }
}
