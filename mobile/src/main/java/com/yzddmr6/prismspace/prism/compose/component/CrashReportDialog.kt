package com.yzddmr6.prismspace.prism.compose.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yzddmr6.prismspace.mobile.R

/** Prompts once per crash scene: send the diagnostics report (system share sheet, user picks the
 *  destination) or dismiss. The marker is consumed on either path, so this never nags twice. */
@Composable
fun CrashReportDialog(
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lz_home_crash_title)) },
        text = { Text(stringResource(R.string.lz_home_crash_message)) },
        confirmButton = {
            PrismTextButton(onClick = onSend) { Text(stringResource(R.string.lz_home_crash_send)) }
        },
        dismissButton = {
            PrismTextButton(onClick = onDismiss) { Text(stringResource(R.string.lz_home_crash_dismiss)) }
        },
    )
}
