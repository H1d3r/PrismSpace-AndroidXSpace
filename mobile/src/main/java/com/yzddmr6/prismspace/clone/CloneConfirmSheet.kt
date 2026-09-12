package com.yzddmr6.prismspace.clone

import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.yzddmr6.prismspace.controller.CloneRoute
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator

/**
 * Add-clone confirmation: the configured install method is a global preference, so adding a clone
 * shows the current method (with live capability summary) instead of asking every time.「更改」
 * jumps to the run-mode settings. Multi-target rows appear only with multiple dual spaces.
 */
class CloneConfirmSheet(
    private val appLabel: String,
    private val methodTitle: String,
    private val methodSummary: String,
    private val route: CloneRoute,
    private val targets: Map<UserHandle, String>,
    private val icons: Map<UserHandle, Drawable>?,
    private val isCloned: (UserHandle) -> Boolean,
    private val onChangeMethod: () -> Unit,
    private val onConfirm: (UserHandle) -> Unit,
) {
    @Composable
    fun compose() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.lz_clone_confirm_title, appLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when (route) {
                    CloneRoute.FILE_SYNC -> stringResource(R.string.lz_clone_confirm_body_filesync)
                    CloneRoute.SYSTEM_ENABLE -> stringResource(R.string.lz_clone_confirm_body_system)
                    else -> stringResource(R.string.lz_clone_confirm_body_auto, methodTitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(PrismSpacing.Xs))

            // Current method (global preference) + 更改 → run-mode settings.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = methodTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = methodSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrismTextButton(onClick = onChangeMethod) {
                    Text(stringResource(R.string.lz_clone_change_method))
                }
            }

            if (targets.size == 1) {
                // Single dual space (the common case): one clear primary CTA.
                val (user, _) = targets.entries.first()
                val cloned = isCloned(user)
                Button(
                    onClick = { if (!cloned) onConfirm(user) },
                    enabled = !cloned,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrismMinTouchTarget),
                ) {
                    Text(
                        stringResource(
                            when (route) {
                                CloneRoute.FILE_SYNC -> R.string.lz_clone_prepare_packages
                                CloneRoute.SYSTEM_ENABLE -> R.string.lz_clone_enable_system
                                else -> R.string.lz_clone_auto_install
                            }
                        )
                    )
                }
            } else {
                // Multiple dual spaces (experimental): pick which one to clone into.
                Text(
                    text = stringResource(R.string.lz_app_copy_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((user, label) in targets) {
                    val cloned = isCloned(user)
                    CloneTargetRow(label, icons?.get(user), cloned) { if (!cloned) onConfirm(user) }
                }
            }
        }
    }
}

@Composable
internal fun ClonePreparationSheet(
    appLabel: String,
    prepared: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(PrismSpacing.Md)) {
        Text(stringResource(R.string.lz_clone_confirm_title, appLabel), style = MaterialTheme.typography.titleMedium)
        Text(error ?: stringResource(if (prepared) R.string.lz_home_pending_body else R.string.toast_clone_file_sync_transferring),
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.lz_shell_prepare_read), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm)) {
            if (!prepared && error == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(if (prepared) R.string.lz_shell_prepare_copied else R.string.lz_shell_prepare_copy),
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.lz_shell_prepare_confirm), style = MaterialTheme.typography.bodyMedium)
        if (prepared) Button(onClick = onInstall,
            modifier = Modifier.fillMaxWidth().heightIn(min = PrismMinTouchTarget)) {
            Text(stringResource(R.string.lz_home_pending_action))
        }
        if (prepared || error != null) PrismTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (error != null) android.R.string.cancel else R.string.lz_app_filesync_install_later))
        }
    }
}

@Composable
private fun CloneTargetRow(label: String, icon: Drawable?, cloned: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface.let { if (cloned) it.copy(alpha = 0.38f) else it }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !cloned, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val bmp = icon?.toBitmap()?.asImageBitmap()
        if (bmp != null) Image(bmp, null, modifier = Modifier.size(28.dp))
        Text(
            text = if (cloned) stringResource(R.string.lz_app_target_already_in_space, label) else label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
    }
}
