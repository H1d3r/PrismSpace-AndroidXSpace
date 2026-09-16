package com.yzddmr6.prismspace.prism.compose.screen

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Divider
import com.yzddmr6.prismspace.prism.compose.component.NavRow
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.notification.NotificationPermissionPrompt
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import com.yzddmr6.prismspace.prism.compose.component.StatusHeroCard
import com.yzddmr6.prismspace.prism.compose.component.StatusRow
import com.yzddmr6.prismspace.prism.compose.component.UpdateAvailableDialog
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
import com.yzddmr6.prismspace.prism.compose.nav.PrismRoutes
import com.yzddmr6.prismspace.prism.compose.nav.navigateToTab
import com.yzddmr6.prismspace.prism.compose.theme.LocalPrismExtraColors
import com.yzddmr6.prismspace.prism.compose.theme.PrismIconSizes
import com.yzddmr6.prismspace.prism.compose.theme.PrismRadius
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.HomePrimaryAction
import com.yzddmr6.prismspace.prism.compose.vm.HomeViewModel
import com.yzddmr6.prismspace.prism.compose.vm.overviewLabelsLine
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.setup.SetupFlow
import com.yzddmr6.prismspace.util.Activities

/** PrismSpace repository URL. */
private const val PRISM_GITHUB_URL = "https://github.com/yzddmr6/PrismSpace"

/**
 * 首页 = 状态 + 任务 + 待办 + 概览:
 *   状态区（健康=一行安静文字；异常=状态卡带单一主动作）→ 待安装任务卡（仅存在时）→
 *   主任务「添加分身/发送文件」→ 空间概览 → 页尾「设备与版本」只读参考区。
 * GitHub 仅为顶栏图标；首屏不呈现诊断信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val context = LocalContext.current
    val activity = Activities.findActivityFrom(context)

    LaunchedEffect(Unit) { vm.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PrismSpace") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    // GitHub icon — normal web Intent (external browser, no embedded WebView)
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PRISM_GITHUB_URL))
                            )
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
        ) {
            val state = uiState

            // ── 状态区: 健康一行安静文字，异常一张状态卡（单一主动作） ─────────────
            if (state == null || state.calm) {
                Text(
                    text = state?.statusTitle ?: stringResource(R.string.lz_home_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PrismSpacing.Xs),
                )
            } else {
                StatusHeroCard(
                    level       = state.level,
                    title       = state.statusTitle,
                    body        = state.statusBody,
                    tag         = state.tag,
                    // Status icon is always the space "shield"; severity is conveyed by the card's
                    // level color/tag. (An action icon would be semantically wrong as a status.)
                    leadingIcon = PrismIcons.Shield,
                    primary     = {
                        // The card's ONLY primary action; its label mirrors the real state
                        // (创建/恢复/修复/查看), never a generic "fix".
                        Button(
                            onClick   = {
                                when (state.primaryAction) {
                                    HomePrimaryAction.StartSetup -> SetupFlow.open(context)
                                    HomePrimaryAction.OpenSettings, HomePrimaryAction.ActivateSpace -> {
                                        activity?.let(NotificationPermissionPrompt::requestOnce)
                                        vm.repair { route -> nav.navigateToTab(route) }
                                    }
                                    HomePrimaryAction.OpenSpace -> nav.navigateToTab(PrismRoutes.SPACE)
                                }
                            },
                            modifier  = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                // 图标按真实动作区分：创建空间=加号，查看应用=网格，
                                // 修复/解锁等设置类动作=扳手（不再是所有动作都用扳手）。
                                imageVector = when (state.primaryAction) {
                                    HomePrimaryAction.StartSetup -> PrismIcons.Add
                                    HomePrimaryAction.OpenSpace -> PrismIcons.Grid
                                    HomePrimaryAction.OpenSettings -> PrismIcons.Wrench
                                    HomePrimaryAction.ActivateSpace -> PrismIcons.Play
                                },
                                contentDescription = null,
                                modifier = Modifier.padding(end = PrismSpacing.Sm),
                            )
                            Text(state.primaryLabel.ifBlank { stringResource(R.string.lz_home_repair_fallback) })
                        }
                    },
                )
            }

            // ── 待安装任务卡（仅存在时）: clone-preparation 存储的投影 ────────────
            if (state != null && state.pendingInstallLabels.isNotEmpty()) {
                PendingInstallCard(
                    labels = state.pendingInstallLabels,
                    gateEnabled = state.installEntryEnabled,
                    guidance = state.installEntryGuidance,
                    onGoInstall = {
                        if (activity != null) {
                            val result = FileBridgeService().openProfileInstallEntry(activity)
                            if (!result.success) {
                                AppFeedbackBus.emit(ActionFeedback(result.message, isError = true))
                            }
                        }
                    },
                    onBlocked = { guidance ->
                        guidance?.let { AppFeedbackBus.emit(ActionFeedback(it, isError = true)) }
                    },
                )
            }

            // ── 主任务：添加分身直达空间页主空间分段；发送文件直达文件页 ────────────
            if (state?.primaryAction != HomePrimaryAction.StartSetup) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
                ) {
                    Button(
                        onClick = {
                            AppLaunchSignals.signalOpenSpaceMainSegment()
                            nav.navigateToTab(PrismRoutes.SPACE)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = PrismMinTouchTarget),
                    ) {
                        Icon(
                            imageVector = PrismIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = PrismSpacing.Sm),
                        )
                        Text(stringResource(R.string.lz_home_task_add_clone))
                    }
                    FilledTonalButton(
                        onClick = { nav.navigateToTab(PrismRoutes.FILES) },
                        modifier = Modifier.weight(1f).heightIn(min = PrismMinTouchTarget),
                    ) {
                        Icon(
                            imageVector = PrismIcons.File,
                            contentDescription = null,
                            modifier = Modifier.padding(end = PrismSpacing.Sm),
                        )
                        Text(stringResource(R.string.lz_home_task_send_files))
                    }
                }

                // ── 空间概览：分身头像组 + 最近传输一行（点卡进入空间板块） ──────────────
                if (state != null) {
                    GroupCard(title = stringResource(R.string.lz_home_overview_title)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { nav.navigateToTab(PrismRoutes.SPACE) }
                                .padding(PrismSpacing.Lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(-PrismSpacing.Xs)) {
                                state.overviewClonePkgs.forEach { pkg -> HomeCloneIcon(pkg) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.lz_home_overview_clones, state.cloneCount),
                                    style = MaterialTheme.typography.titleSmall)
                                if (state.overviewCloneLabels.isNotEmpty()) Text(
                                    overviewLabelsLine(state.overviewCloneLabels, state.cloneCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(PrismIcons.Chev, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Divider(color = LocalPrismExtraColors.current.cardBorder)
                        NavRow(
                            title = stringResource(R.string.lz_home_recent_transfer),
                            summary = state.recentTransferText ?: stringResource(R.string.lz_home_no_transfer),
                            leadingIcon = PrismIcons.File,
                            onClick = { nav.navigateToTab(PrismRoutes.FILES) },
                        )
                    }
                }

                // ── 页尾只读参考区：设备与版本（诊断时有上下文，日常使用零打扰） ──────────
                GroupCard(title = stringResource(R.string.lz_home_device_version)) {
                    StatusRow(
                        title = stringResource(R.string.lz_home_run_mode),
                        value = state?.capabilityText ?: "…",
                        leadingIcon = PrismIcons.Key,
                    )
                    StatusRow(
                        title = stringResource(R.string.lz_home_android_version),
                        value = state?.androidText ?: "…",
                        leadingIcon = PrismIcons.Droid,
                    )
                    StatusRow(
                        title = stringResource(R.string.lz_home_device),
                        value = state?.deviceText ?: "…",
                        leadingIcon = PrismIcons.Phone,
                    )
                }

            }

            Spacer(Modifier.height(PrismSpacing.Sm))
        }
    }

    // ── 自动检查更新：发现新版本时弹窗（「稍后」后同版本不再提示） ──────────────
    updateInfo?.let { info ->
        UpdateAvailableDialog(info = info, context = context, onDismiss = { vm.dismissUpdate() })
    }
}

/** 待安装任务卡：等待系统安装器确认的克隆；点击「前往安装」进入双开空间安装入口（带空间门禁）。 */
@Composable
private fun PendingInstallCard(
    labels: List<String>,
    gateEnabled: Boolean,
    guidance: String?,
    onGoInstall: () -> Unit,
    onBlocked: (String?) -> Unit,
) {
    val extra = LocalPrismExtraColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PrismRadius.Lg),
        colors = CardDefaults.cardColors(
            containerColor = extra.warnContainer,
            contentColor = extra.warn,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = PrismSpacing.None),
    ) {
        Row(modifier = Modifier.padding(PrismSpacing.Lg), horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Md)) {
            Icon(PrismIcons.File, null, Modifier.size(PrismIconSizes.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (labels.size == 1) stringResource(R.string.lz_home_pending_title_one, labels.first())
                    else stringResource(R.string.lz_home_pending_title_more, labels.first(), labels.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = guidance?.takeIf { !gateEnabled } ?: stringResource(R.string.lz_home_pending_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = PrismSpacing.Xs),
                )
                Button(
                    onClick = { if (gateEnabled) onGoInstall() else onBlocked(guidance) },
                    modifier = Modifier
                        .heightIn(min = PrismMinTouchTarget)
                        .padding(top = PrismSpacing.Sm),
                ) {
                    Text(stringResource(R.string.lz_home_pending_action))
                }
            }
        }
    }
}

/** Small clone icon for the overview avatar group (resolved like the Space list icons). */
@Composable
private fun HomeCloneIcon(pkg: String) {
    val context = LocalContext.current
    val icon: Drawable? = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }
    if (icon != null) {
        val bmp = remember(icon) { icon.toBitmap(48, 48).asImageBitmap() }
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .size(PrismIconSizes.Md + PrismSpacing.Xs)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(PrismIconSizes.Md + PrismSpacing.Xs)
                .clip(CircleShape),
        )
    }
}
