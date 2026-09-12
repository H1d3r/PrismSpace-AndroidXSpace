@file:Suppress("LongMethod", "MagicNumber")
package com.yzddmr6.prismspace.prism.compose.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.ActionRow
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.ui.draw.rotate
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.fileTransferGate
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import kotlinx.coroutines.launch
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.FilesViewModel
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.prism.service.TransferRecord
import com.yzddmr6.prismspace.prism.service.displayTitle
import com.yzddmr6.prismspace.prism.service.openSystemFileManager
import com.yzddmr6.prismspace.prism.service.prepareSystemFilePickerUsable
import com.yzddmr6.prismspace.prism.ui.CrossSpaceTransferEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val vm: FilesViewModel = viewModel()
    val context = LocalContext.current
    val activity = context as? Activity
    val history by vm.history.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var showReturnGuide by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sendFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        CrossSpaceTransferEntry.launch(context, uris)
    }

    // 记录动作只承诺「打开文件夹」：APK 记录开双开空间下载目录，普通文件开系统文件管理器。
    fun openRecordFolder(item: TransferRecord) {
        if (item.packageName != null && activity != null) {
            FileBridgeService().openProfileDownloadsFolder(activity)
        } else {
            openSystemFileManager(context)
        }
    }

    // Refresh whenever the tab is shown (a transfer may have happened in another app meanwhile).
    LaunchedEffect(Unit) { vm.refresh() }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.lz_pf_files_clear_confirm_title)) },
            // Make explicit it only clears the LOG, not the transferred files (users fear data loss).
            text = { Text(stringResource(R.string.lz_pf_files_clear_confirm_body)) },
            confirmButton = {
                PrismTextButton(onClick = { showClearConfirm = false; vm.clearHistory() }) {
                    Text(stringResource(R.string.lz_pf_files_clear))
                }
            },
            dismissButton = {
                PrismTextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.lz_set_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lz_pf_files_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.None),
        ) {
            // ── 发送主卡（含空间门禁预检：空间不可用时不弹选择器，给状态引导） ──
            GroupCard(title = null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Lg),
                ) {
                    Text(
                        text = stringResource(R.string.lz_pf_files_send_other),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.lz_pf_files_send_other_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PrismSpacing.Xs),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                // 空间门禁预检：与启动分身同一可用性来源，不可用即引导不发请求。
                                val gate = fileTransferGate(vm.dualUsability(), prismResolver(context))
                                if (!gate.enabled) {
                                    gate.guidance?.let {
                                        AppFeedbackBus.emit(ActionFeedback(it, isError = true))
                                    }
                                    return@launch
                                }
                                // The profile owner may have frozen the ROM's file picker together with
                                // other explicit system clones. Restore that required system surface at
                                // the point of use before Android resolves OPEN_DOCUMENT.
                                if (prepareSystemFilePickerUsable(context)) {
                                    sendFiles.launch(arrayOf("*/*"))
                                } else {
                                    Toast.makeText(context, R.string.lz_pf_open_fail, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PrismSpacing.Md),
                    ) {
                        Text(stringResource(R.string.lz_pf_choose_files))
                    }
                }
            }

            // ── 传回教学（折叠） ─────────────────────────────────────────────
            GroupCard(title = null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReturnGuide = !showReturnGuide }
                            .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.lz_pf_files_return_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = PrismIcons.Chev,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(if (showReturnGuide) 90f else 0f),
                        )
                    }
                    if (showReturnGuide) {
                        GuideStep(1, stringResource(R.string.lz_pf_files_step1))
                        GuideStep(2, stringResource(R.string.lz_pf_files_step2))
                        GuideStep(3, stringResource(R.string.lz_pf_files_step3))
                        GuideStep(4, stringResource(R.string.lz_pf_files_step4))
                        Text(
                            text = stringResource(R.string.lz_pf_files_tab_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
                        )
                    }
                }
            }

            // ── Persisted transfer history (survives app restart) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = PrismSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.lz_pf_files_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (history.isNotEmpty()) {
                    PrismTextButton(onClick = { showClearConfirm = true }) { Text(stringResource(R.string.lz_pf_files_clear)) }
                }
            }

            if (history.isNotEmpty()) {
                GroupCard(title = null) {
                    history.forEach { item ->
                        // 记录动作只承诺「打开文件夹」；APK 安装任务不属于文件页（归属分身链路：
                        // 首页待办卡、主空间行、双开空间入口页）。
                        ActionRow(
                            title = item.displayTitle(),
                            summary = listOf(
                                item.direction?.let { direction -> stringResource(
                                    if (direction == TransferDirection.ToMain) R.string.lz_pf_direction_to_main
                                    else R.string.lz_pf_direction_to_profile,
                                ) },
                                item.location.takeIf { it.isNotBlank() },
                                formatTime(item.timeMillis).takeIf { it.isNotBlank() },
                            )
                                .filterNotNull().joinToString(" · "),
                            leadingIcon = if (item.isImage) PrismIcons.Img else PrismIcons.File,
                            trailing = {
                                PrismTextButton(onClick = { openRecordFolder(item) }) {
                                    Text(stringResource(R.string.lz_pf_open_folder))
                                }
                            },
                            onClick = { openRecordFolder(item) },
                        )
                    }
                }
            } else {
                GroupCard(title = null) {
                    Text(
                        text = stringResource(R.string.lz_pf_files_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PrismSpacing.Lg, vertical = 18.dp),
                    )
                }
            }

            Spacer(Modifier.height(PrismSpacing.Sm))
        }
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$n",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = PrismSpacing.Md),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
