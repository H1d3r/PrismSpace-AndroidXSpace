package com.yzddmr6.prismspace.prism.compose.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.vm.UpdateInfo

/** Shared update-available dialog (Settings manual check + home automatic check). Release notes
 *  scroll when long and render the Markdown subset our releases use. */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    context: Context,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lz_set_update_title, info.version)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                MarkdownText(
                    markdown = info.notes,
                    onLinkClick = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
            }
        },
        confirmButton = {
            PrismTextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                onDismiss()
            }) { Text(stringResource(R.string.lz_set_update_download)) }
        },
        dismissButton = {
            PrismTextButton(onClick = onDismiss) { Text(stringResource(R.string.lz_set_update_later)) }
        },
    )
}
