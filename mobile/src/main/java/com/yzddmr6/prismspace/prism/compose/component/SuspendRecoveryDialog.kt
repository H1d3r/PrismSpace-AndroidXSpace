package com.yzddmr6.prismspace.prism.compose.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.vm.SuspendRecoveryPrompt

/**
 * Dead-end escape for cross-suspender freezes: the clone is suspended by a component only that
 * component may un-suspend (platform rule), so the in-app restore levers provably cannot work.
 * The only guaranteed recovery is reinstalling the clone (its data is lost) — explicitly
 * user-confirmed here, never automatic.
 */
@Composable
fun SuspendRecoveryDialog(
    prompt: SuspendRecoveryPrompt,
    onReinstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val suspender = prompt.suspender ?: stringResource(R.string.lz_space_suspend_unknown_suspender)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lz_space_suspend_recovery_title)) },
        text = { Text(stringResource(R.string.lz_space_suspend_recovery_body, suspender)) },
        confirmButton = {
            PrismTextButton(onClick = onReinstall) {
                Text(stringResource(R.string.lz_space_suspend_recovery_reinstall))
            }
        },
        dismissButton = {
            PrismTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lz_space_suspend_recovery_cancel))
            }
        },
    )
}
