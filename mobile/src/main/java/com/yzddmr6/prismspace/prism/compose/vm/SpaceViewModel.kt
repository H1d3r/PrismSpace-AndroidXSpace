package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.controller.PrismAppClones
import com.yzddmr6.prismspace.controller.PrismAppControl
import com.yzddmr6.prismspace.controller.ClonePreparationStore
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.resolveSpaceSelection
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.service.ProfileUninstallLauncher
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.prism.ui.PrismAppsViewModel
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.UserHandles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Segment — which tab is active
// ---------------------------------------------------------------------------

enum class SpaceSegment { Main, Dual }

// ---------------------------------------------------------------------------
// Pure data transfer object — Android-free, unit-testable
// ---------------------------------------------------------------------------

internal data class SpaceAppInput(
    val pkg: String,
    val label: String,
    val frozen: Boolean,
    val suspended: Boolean,
    val launchable: Boolean,
    val system: Boolean,
    val cloned: Boolean,       // true = this main-space app is also in dual profile
    val prepared: Boolean = false,
    val segment: SpaceSegment,
    val critical: Boolean = false,
)

/** The row's single next-step action rendered as its inline button; null = no action row button. */
enum class SpaceRowAction { Open, Resume, AddClone, ContinueInstall }

// ---------------------------------------------------------------------------
// Pure row model surfaced to the Compose UI
// ---------------------------------------------------------------------------

data class SpaceRow(
    val pkg: String,
    val label: String,
    val frozen: Boolean,
    val suspended: Boolean,
    val launchable: Boolean,
    val system: Boolean,
    val cloned: Boolean,
    val prepared: Boolean,
    val segment: SpaceSegment,
    val chipText: String?,     // status tag text; null = healthy rows carry no tag
    val chipOk: Boolean,       // true → ok-green, false → muted/warn
    val primaryAction: SpaceRowAction? = null,   // the row's only inline next-step action
    val critical: Boolean = false,
)

// ---------------------------------------------------------------------------
// Pure mapper — the only business logic owned by this layer
// Tag honesty: healthy rows carry NO tag; only exceptional states are labelled.
//   Dual: system → "系统应用"; frozen/suspended → "已暂停"; healthy → no tag.
//   Main: prepared → "待安装"; cloned / not-cloned → no tag (the row action carries state).
// Inline action: dual → 打开/恢复; main → 添加分身/去安装; added or system rows → none.
// ---------------------------------------------------------------------------

internal fun mapRows(inputs: List<SpaceAppInput>, res: StringResolver): List<SpaceRow> = inputs.map { app ->
    val (chipText, chipOk) = when (app.segment) {
        SpaceSegment.Dual -> when {
            app.system -> res(R.string.lz_vm_chip_system, emptyArray()) to false
            // Truthful badge: 已暂停 covers both freeze mechanisms and any lingering
            // suspended state, so a paused clone never reads as running.
            app.frozen || app.suspended -> res(R.string.lz_vm_chip_paused, emptyArray()) to false
            else -> null to true
        }
        SpaceSegment.Main -> when {
            app.prepared -> res(R.string.lz_vm_chip_pending_install, emptyArray()) to false
            else -> null to true
        }
    }
    val primaryAction = when (app.segment) {
        SpaceSegment.Dual -> when {
            app.system -> null
            app.frozen || app.suspended -> SpaceRowAction.Resume
            app.launchable -> SpaceRowAction.Open
            else -> null
        }
        SpaceSegment.Main -> when {
            app.prepared -> SpaceRowAction.ContinueInstall
            app.cloned -> null
            else -> SpaceRowAction.AddClone
        }
    }
    SpaceRow(
        pkg       = app.pkg,
        label     = app.label,
        frozen    = app.frozen,
        suspended = app.suspended,
        launchable = app.launchable,
        system    = app.system,
        cloned    = app.cloned,
        prepared  = app.prepared,
        segment   = app.segment,
        chipText  = chipText,
        chipOk    = chipOk,
        primaryAction = primaryAction,
        critical  = app.critical,
    )
}

/** System packages exist in managed profiles for platform reasons; only an explicit marker makes one a user clone. */
internal fun mainAppIsCloned(isSystem: Boolean, installedInDual: Boolean, systemCloneMarked: Boolean): Boolean =
    if (isSystem) systemCloneMarked else installedInDual

/**
 * The system-app view is intentionally search-first: a managed profile can contain hundreds of
 * packages, so a blank query reveals nothing and cannot invite accidental bulk operations.
 */
internal fun filterSystemAppRows(rows: List<SpaceRow>, query: String): List<SpaceRow> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return rows.asSequence()
        .filter { it.system }
        .filter { it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

// ---------------------------------------------------------------------------
// Filter, sort, and search model.
// ---------------------------------------------------------------------------

enum class SortOrder { Name, Cloned }

enum class CloneFilter { All, Yes, No }

/**
 * Pure client-side list transform: search → filter → sort.
 *
 * - search:      matches label OR packageName, case-insensitive; applied to both segments.
 * - showSystem:  hides system apps (row.system == true) when false.
 * - cloneFilter: All / Yes / No clone filter; MAIN segment only.
 * - sort Name:   localeCompare with zh collation (both segments).
 * - sort Cloned: 已添加优先 — cloned-first (already-cloned at top), then name; MAIN segment only.
 *                Falls back to Name sort for Dual segment.
 *
 * No backend or provider changes — operates only on the already-loaded in-memory list.
 * (The pseudo "install time" ordering was removed: load order never equaled install time.)
 */
internal fun applyListTransform(
    rows: List<SpaceRow>,
    segment: SpaceSegment,
    query: String,
    sort: SortOrder,
    cloneFilter: CloneFilter,
    showSystem: Boolean,
): List<SpaceRow> {
    var result = rows

    // 1. Search (both segments)
    if (query.isNotBlank()) {
        val q = query.lowercase()
        result = result.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    // 2a. System-app visibility. Caller passes the segment's own flag; both default ON so a space
    // initially shows its complete launchable app list.
    if (!showSystem) result = result.filter { !it.system }
    // 2b. Clone filter (main segment only)
    if (segment == SpaceSegment.Main) {
        result = when (cloneFilter) {
            CloneFilter.Yes -> result.filter { it.cloned }
            CloneFilter.No  -> result.filter { !it.cloned }
            CloneFilter.All -> result
        }
    }

    // 3. Sort
    result = when {
        sort == SortOrder.Name -> result.sortedWith(compareBy { it.label.lowercase() })
        sort == SortOrder.Cloned && segment == SpaceSegment.Main ->
            result.sortedWith(compareByDescending<SpaceRow> { if (it.cloned) 1 else 0 }
                .thenBy { it.label.lowercase() })
        else -> result.sortedWith(compareBy { it.label.lowercase() }) // Cloned sort falls back to Name for Dual
    }

    return result
}

// ---------------------------------------------------------------------------
// UI state — one per segment
// ---------------------------------------------------------------------------

sealed interface SpaceSegmentState {
    object Loading : SpaceSegmentState
    object Unavailable : SpaceSegmentState
    object Empty : SpaceSegmentState
    data class Content(val rows: List<SpaceRow>) : SpaceSegmentState
}

data class SpaceUiState(
    val segment: SpaceSegment = SpaceSegment.Dual,
    val dual: SpaceSegmentState = SpaceSegmentState.Loading,
    val main: SpaceSegmentState = SpaceSegmentState.Loading,
    val systemApps: SpaceSegmentState = SpaceSegmentState.Loading,
    // Multi-select: null = not in multi-select mode; non-null = set of selected pkgs
    val selectedPkgs: Set<String>? = null,
    // Snapshot of the entry-time filtered result set (search/filter/sort applied, system rows
    // removed) — the domain for selection, 全选, and counts while multi-select is active.
    val multiSelectDomain: List<SpaceRow>? = null,
    // Batch progress message while a batch op is running, null otherwise
    val batchProgress: String? = null,
    // Filter, sort, and search state.
    val sortOrder: SortOrder = SortOrder.Name,
    val cloneFilter: CloneFilter = CloneFilter.All,
    val showSystem: Boolean = false,
    // Dual space uses its own toggle state so each segment can be narrowed independently.
    val showSystemDual: Boolean = false,
    val selectedDualSpaceId: String? = null,
    val spaces: List<PrismSpace> = emptyList(),
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
    val dualUsability: SpaceUsability = SpaceUsability.Unknown,
    val mainCopyLostPackage: String? = null,
) {
    val current: SpaceSegmentState get() = if (segment == SpaceSegment.Dual) dual else main
    val dualCount: Int get() = (dual as? SpaceSegmentState.Content)?.rows?.size ?: 0
    val mainCount: Int get() = (main as? SpaceSegmentState.Content)?.rows?.size ?: 0
    val isMultiSelect: Boolean get() = selectedPkgs != null
    val selectedCount: Int get() = selectedPkgs?.size ?: 0
}

// ---------------------------------------------------------------------------
// Batch action types per segment — pure, unit-testable
// ---------------------------------------------------------------------------

enum class BatchAction { Freeze, Uninstall, CopyToDual }

/** Which batch actions are available for a given segment. */
internal fun batchActionsFor(segment: SpaceSegment): List<BatchAction> = when (segment) {
    SpaceSegment.Main -> listOf(BatchAction.CopyToDual)
    SpaceSegment.Dual -> listOf(BatchAction.Freeze, BatchAction.Uninstall)
}

// ---------------------------------------------------------------------------
// ViewModel — bridges the Compose UI to space/app data via SpaceRepository.
// All profile + provider acquisition is delegated to SpaceRepository (the
// single source of truth); this VM never touches Users/AppListProvider.
// ---------------------------------------------------------------------------

class SpaceViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SpaceUiState())
    val uiState: StateFlow<SpaceUiState> = _uiState
    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val stateRepo: SpaceStateRepository by lazy { SpaceStateRepository(getApplication()) }
    private var uninstallHostResumed = false
    private var uninstallQueue = UninstallQueueState()
    private var uninstallValidationJob: Job? = null
    private var uninstallSkipped = 0
    private var uninstallLaunchInFlight: UninstallRequest? = null
    private val uninstallLaunchPort = UninstallLaunchPort { request ->
        when (val result = ProfileUninstallLauncher().requestUninstall(
            getApplication(), request.packageName, request.targetUserId, canLaunch = { uninstallHostResumed },
        )) {
            ProfileUninstallLauncher.Result.Launched -> UninstallLaunchReply.Launched
            is ProfileUninstallLauncher.Result.Failed -> UninstallLaunchReply.Failed(result.message, result.reason)
        }
    }

    // Internal cache so callers can look up PrismAppInfo by package.
    // One immutable snapshot is published atomically via a single @Volatile ref,
    // so readers always get a coherent (dual, main) pair.
    private data class AppCache(val dual: List<PrismAppInfo>, val main: List<PrismAppInfo>)

    private data class LoadedRows(
        val dual: List<SpaceRow>,
        val main: List<SpaceRow>,
        val systemApps: List<SpaceRow>,
    )

    @Volatile private var appCache: AppCache = AppCache(emptyList(), emptyList())

    init {
        viewModelScope.launch {
            stateRepo.state.collectLatest { snapshot ->
                when (snapshot) {
                    SpaceSnapshot.Loading -> _uiState.value = _uiState.value.copy(
                        dual = SpaceSegmentState.Loading,
                        main = SpaceSegmentState.Loading,
                        systemApps = SpaceSegmentState.Loading,
                        dualUsability = SpaceUsability.Unknown,
                    )
                    is SpaceSnapshot.Failed -> _uiState.value = _uiState.value.copy(
                        dual = SpaceSegmentState.Unavailable,
                        main = SpaceSegmentState.Unavailable,
                        systemApps = SpaceSegmentState.Unavailable,
                        dualUsability = SpaceUsability.Unknown,
                    )
                    is SpaceSnapshot.Loaded -> loadContent()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Segment switching
    // -----------------------------------------------------------------------

    fun selectSegment(segment: SpaceSegment) {
        if (_uiState.value.batchProgress != null) return
        _uiState.value = _uiState.value.copy(segment = segment)
    }

    fun selectSpace(dualSpaceId: String) {
        if (_uiState.value.batchProgress != null) return
        // selectSegment only flips the already-loaded view; selectSpace changes WHICH dual is loaded, so it must reload.
        _uiState.value = _uiState.value.copy(segment = SpaceSegment.Dual, selectedDualSpaceId = dualSpaceId)
        refresh()
    }

    private fun setFeedback(message: String, isError: Boolean) {
        _uiState.value = _uiState.value.copy(feedbackMessage = message, feedbackIsError = isError)
        AppFeedbackBus.emit(ActionFeedback(message, isError))
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null, feedbackIsError = false)
    }

    fun reportTransientError(message: String) { setFeedback(message, isError = true) }

    fun clearMainCopyLostWarning() {
        _uiState.value = _uiState.value.copy(mainCopyLostPackage = null)
    }

    // -----------------------------------------------------------------------
    // Data refresh
    // -----------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            stateRepo.refresh("space_explicit")
            loadContent()
        }
    }

    private suspend fun loadContent() {
        _uiState.value = _uiState.value.copy(
            dual = SpaceSegmentState.Loading,
            main = SpaceSegmentState.Loading,
            systemApps = SpaceSegmentState.Loading,
        )
        val selectedDualId = _uiState.value.selectedDualSpaceId
        val (pair, allSpaces, dualUsability) = withContext(Dispatchers.IO) {
            val segments = loadBothSegments()
            val spaces = spaceRepo.spaces()
            val usability = (selectedDualId?.let { spaceRepo.space(it) } ?: spaceRepo.dualSpace())
                ?.let { spaceRepo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
            Triple(segments, spaces, usability)
        }
        val selection = resolveSpaceSelection(
            requestedSegment = _uiState.value.segment,
            selectedDualSpaceId = selectedDualId,
            spaces = allSpaces,
        )
        _uiState.value = _uiState.value.copy(
            segment = selection.segment,
            selectedDualSpaceId = selection.selectedDualSpaceId,
            dual = pair.dual.toSegmentState(),
            main = pair.main.toSegmentState(),
            systemApps = pair.systemApps.toSegmentState(),
            spaces = allSpaces,
            dualUsability = dualUsability,
        )
    }

    private fun List<SpaceRow>.toSegmentState(): SpaceSegmentState =
        if (isEmpty()) SpaceSegmentState.Empty else SpaceSegmentState.Content(this)

    // -----------------------------------------------------------------------
    // Look-up
    // -----------------------------------------------------------------------

    fun appFor(pkg: String, segment: SpaceSegment): PrismAppInfo? {
        val snapshot = appCache  // single @Volatile read → a coherent (dual,main) pair
        val cache = if (segment == SpaceSegment.Dual) snapshot.dual else snapshot.main
        return cache.firstOrNull { it.packageName == pkg }
    }

    // -----------------------------------------------------------------------
    // Multi-select
    // -----------------------------------------------------------------------

    /** Long-press an app card to enter multi-select mode with that app pre-selected. The current
     *  search/filter/sort result set is snapshotted as the selection domain. */
    fun enterMultiSelect(pkg: String, domain: List<SpaceRow>) {
        // System packages are deliberately single-action only so the critical-package warning
        // cannot be bypassed through a batch operation.
        val state = MultiSelect.enter(pkg, domain) ?: return
        _uiState.value = _uiState.value.copy(selectedPkgs = state.selected, multiSelectDomain = state.domain)
    }

    /** The explicit 批量管理 top-bar entry: enter multi-select with an empty selection over the
     *  current domain snapshot. */
    fun enterMultiSelect(domain: List<SpaceRow>) {
        if (_uiState.value.batchProgress != null) return
        val state = MultiSelect.enterEmpty(domain) ?: return
        _uiState.value = _uiState.value.copy(selectedPkgs = state.selected, multiSelectDomain = state.domain)
    }

    /** Toggle selection of a pkg while in multi-select mode. */
    fun toggleSelect(pkg: String) {
        if (_uiState.value.batchProgress != null) return
        val current = _uiState.value
        val domain = current.multiSelectDomain ?: return
        val selected = current.selectedPkgs ?: return
        val next = MultiSelect.toggle(MultiSelectState(domain, selected), pkg)
        _uiState.value = _uiState.value.copy(
            selectedPkgs = next?.selected,
            multiSelectDomain = next?.domain,
        )
    }

    /** Select every app in the selection domain (the "全选" action) — the entry-time filtered
     *  result set, never the whole segment. */
    fun selectAll() {
        if (_uiState.value.batchProgress != null) return
        val current = _uiState.value
        val domain = current.multiSelectDomain ?: return
        val selected = current.selectedPkgs ?: return
        val next = MultiSelect.selectAll(MultiSelectState(domain, selected))
        _uiState.value = _uiState.value.copy(selectedPkgs = next.selected)
    }

    /** Exit multi-select mode and clear selection. */
    fun exitMultiSelect() {
        if (_uiState.value.batchProgress != null) return
        _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
    }

    // -----------------------------------------------------------------------
    // Batch execution — loops single-item APIs sequentially off main thread
    // -----------------------------------------------------------------------

    fun executeBatch(
        action: BatchAction,
        activity: FragmentActivity,
        prismAppsVm: PrismAppsViewModel,
    ) {
        if (_uiState.value.batchProgress != null) return
        val pkgs = _uiState.value.selectedPkgs?.takeIf { it.isNotEmpty() }?.toList() ?: return
        val segment = _uiState.value.segment
        val total = pkgs.size
        if (action == BatchAction.Uninstall) {
            startUninstallQueue(pkgs, segment)
            return
        }
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(0, total)))
            }
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                when (action) {
                    BatchAction.Freeze -> {
                        withContext(Dispatchers.IO) {
                            pkgs.forEachIndexed { i, pkg ->
                                runCatching {
                                    val app = appFor(pkg, segment)
                                    if (app != null && !app.isSystem) {
                                        if (PrismAppControl.freeze(app)) succeeded++
                                        else failures.add(pkg)
                                    } else {
                                        failures.add(pkg)
                                    }
                                }.onFailure { failures.add(pkg) }
                                withContext(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(
                                        batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(i + 1, total))
                                    )
                                }
                            }
                        }
                        AppFeedbackBus.emit(batchActionFeedback(action, succeeded, failures.size, res))
                    }
                    BatchAction.Uninstall -> error("Uninstall is handled by the profile-routed uninstall queue")
                    BatchAction.CopyToDual -> {
                        // Real per-package results: staging (normal mode) counts as prepared, never
                        // cloned; only a verified enhanced-route install counts as installed. The
                        // batch is confirmed once up front; no fixed-delay success guessing.
                        // Quiet-mode activation is asked at most once per run (driver-owned budget):
                        // a refused/timed-out prompt fails the remaining packages without each of
                        // them showing the system dialog.
                        val counts = runBatchClone(
                            pkgs,
                            BatchClonePort { pkg ->
                                val app = appFor(pkg, SpaceSegment.Main) ?: return@BatchClonePort BatchCloneResult.Failed()
                                PrismAppClones(activity, prismAppsVm, app).requestForBatch()
                            },
                            activate = {
                                val context: Context = getApplication()
                                val profile = Users.profile
                                if (profile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    runCatching { Users.requestQuietModeDisabled(context, profile) }
                                        .onFailure {
                                            DiagnosticLog.e(TAG, "batch clone activation failed user=${profile.toId()}", it)
                                        }
                                        .getOrDefault(false)
                                } else false
                            },
                        ) { done, totalCount ->
                            _uiState.value = _uiState.value.copy(
                                batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(done, totalCount)),
                            )
                        }
                        AppFeedbackBus.emit(batchCloneFeedback(counts, res))
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
                }
            }
            refresh()
        }
    }

    // -----------------------------------------------------------------------
    // Passthroughs — exact PrismAppControl signatures, no Intent
    // -----------------------------------------------------------------------

    fun launch(context: Context, pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        if (segment == SpaceSegment.Dual) {
            viewModelScope.launch checkSpace@{
                if (selectedDualUsability() != SpaceUsability.Usable) {
                    val fb = launchFeedback(LaunchResult.SpaceNotReady, app.label.toString(), prismResolver(context))
                    setFeedback(fb.message, isError = fb.isError)
                    return@checkSpace
                }
                PrismAppControl.launch(context, app)
            }
            return
        }
        PrismAppControl.launch(context, app)
    }

    /** Fresh usability of the selected dual space — the single source both launch and uninstall
     *  gating read from. A missing space reads as [SpaceUsability.NotProvisioned]. */
    private suspend fun selectedDualUsability(): SpaceUsability = withContext(Dispatchers.IO) {
        val sel = _uiState.value.selectedDualSpaceId?.let { spaceRepo.space(it) } ?: spaceRepo.dualSpace()
        if (sel == null) SpaceUsability.NotProvisioned else spaceRepo.usabilityOf(sel)
    }

    /** Emits the state-specific uninstall guidance for an unusable space; fires no request. */
    private fun blockUninstallWithGuidance(usability: SpaceUsability) {
        uninstallGate(usability, prismResolver(getApplication())).guidance
            ?.let { setFeedback(it, isError = true) }
    }

    fun setFrozen(pkg: String, frozen: Boolean) {
        val app = appFor(pkg, SpaceSegment.Dual) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (frozen) {
                    PrismAppControl.freeze(app)
                } else {
                    // 解冻 must fully recover: clear both freeze mechanisms so a clone
                    // paused by either path becomes launchable again.
                    PrismAppControl.unfreeze(app)
                    runCatching { PrismAppControl.setSuspended(app, false) }
                }
                // Re-query the package as a "package change" (add=false) so the cached isHidden reflects
                // the freeze NOW — add=true would force isHidden=false (that path is for fresh installs).
                // Without this, refresh() reads a stale snapshot and the badge only flips on screen re-entry.
                PrismAppListProvider.getInstance(getApplication()).refreshPackage(app.packageName, app.user, false)
            }
            refresh()
        }
    }

    fun remove(activity: Activity, pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        if (segment == SpaceSegment.Dual) {
            // Same space-usability source as launch(): an unusable space must never receive an
            // uninstall request (fail closed; the gate guidance is shown instead).
            viewModelScope.launch {
                val usability = selectedDualUsability()
                if (usability != SpaceUsability.Usable) return@launch blockUninstallWithGuidance(usability)
                if (app.isSystem) PrismAppControl.requestRemoval(activity, app)
                else startUninstallQueue(listOf(pkg), segment)
            }
            return
        }
        if (app.isSystem) PrismAppControl.requestRemoval(activity, app)
        else startUninstallQueue(listOf(pkg), segment)
    }

    fun onHostPaused() { uninstallHostResumed = false }

    fun onHostResumed() {
        uninstallHostResumed = true
        beginUninstallVerification()
    }

    private fun startUninstallQueue(pkgs: List<String>, segment: SpaceSegment) {
        if (!uninstallQueue.complete || uninstallQueue.total > 0 || _uiState.value.batchProgress != null) return
        _uiState.value = _uiState.value.copy(batchProgress = prismResolver(getApplication())(
            R.string.lz_vm_batch_progress, arrayOf(0, pkgs.size)))
        viewModelScope.launch {
            if (segment == SpaceSegment.Dual) {
                val usability = selectedDualUsability()
                if (usability != SpaceUsability.Usable) {
                    _uiState.value = _uiState.value.copy(batchProgress = null)
                    return@launch blockUninstallWithGuidance(usability)
                }
            }
            val requests = withContext(Dispatchers.IO) {
                pkgs.mapNotNull { pkg ->
                    val app = appFor(pkg, segment)?.takeUnless { it.isSystem } ?: return@mapNotNull null
                    UninstallRequest(app.packageName, app.user.toId(), mainCopyExists(app.packageName))
                }
            }
            uninstallSkipped = pkgs.size - requests.size
            if (requests.isEmpty()) {
                AppFeedbackBus.emit(batchActionFeedback(BatchAction.Uninstall, 0, pkgs.size, prismResolver(getApplication())))
                _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
                return@launch
            }
            updateUninstallQueue(UninstallQueueReducer.start(requests))
            driveUninstallLaunch()
        }
    }

    /** Fires the profile-routed system-uninstall request for the ready queue head. The launch and
     *  its result both happen inside the managed profile; verification keeps using the existing
     *  resume/observation flow. There is deliberately no user-0 fallback (issue #6). */
    private fun driveUninstallLaunch() {
        val current = uninstallQueue.current?.takeIf { it.stage == UninstallStage.ReadyToLaunch } ?: return
        if (uninstallLaunchInFlight == current.request) return
        uninstallLaunchInFlight = current.request
        viewModelScope.launch {
            // Per-head gate: re-check FRESH usability before EVERY request — a space that turned
            // unusable mid-queue stops the run; the head's request is never fired.
            val usability = selectedDualUsability()
            if (usability != SpaceUsability.Usable) {
                uninstallLaunchInFlight = null
                abortUninstallQueue(usability)
                return@launch
            }
            val reply = uninstallLaunchPort.requestUninstall(current.request)
            uninstallLaunchInFlight = null
            val request = current.request
            val system = appFor(request.packageName, SpaceSegment.Dual)?.isSystem == true
            when (reply) {
                UninstallLaunchReply.Launched -> {
                    PrismAppControl.logUninstallLaunchOutcome(request.packageName, system, launched = true, failureReason = null)
                    updateUninstallQueue(UninstallQueueReducer.launched(uninstallQueue))
                    val awaiting = uninstallQueue.current
                    // Android can silently reject an activity start. If the host never leaves
                    // foreground, terminate instead of keeping the batch locked forever.
                    viewModelScope.launch {
                        delay(3_000L)
                        if (uninstallHostResumed && uninstallQueue.current === awaiting) {
                            DiagnosticLog.w(TAG, "uninstall UI not observed pkg=${request.packageName}")
                            val mainExists = withContext(Dispatchers.IO) { mainCopyExists(request.packageName) }
                            if (uninstallHostResumed) {
                                val next = UninstallQueueReducer.launchUnobserved(uninstallQueue, awaiting, mainExists)
                                if (next !== uninstallQueue) completeUninstallTransition(next)
                            }
                        }
                    }
                }
                is UninstallLaunchReply.Failed -> {
                    PrismAppControl.logUninstallLaunchOutcome(
                        request.packageName, system, launched = false,
                        failureReason = reply.reason ?: reply.message,
                    )
                    reply.message?.let { setFeedback(it, isError = true) }
                    val mainExists = withContext(Dispatchers.IO) { mainCopyExists(request.packageName) }
                    completeUninstallTransition(UninstallQueueReducer.launchFailed(uninstallQueue, mainExists))
                }
            }
        }
    }

    /** Mid-queue gate trip: stop without firing the pending head. Already-completed heads keep
     *  their verified outcomes; unlaunched heads are honestly reported as not attempted. */
    private fun abortUninstallQueue(usability: SpaceUsability) {
        val res = prismResolver(getApplication())
        val guidance = uninstallGate(usability, res).guidance
            ?: res(R.string.prompt_space_not_ready, emptyArray())
        AppFeedbackBus.emit(uninstallAbortFeedback(uninstallQueue, uninstallSkipped, guidance, res))
        uninstallValidationJob?.cancel()
        uninstallQueue = UninstallQueueState()
        uninstallSkipped = 0
        _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
        refresh()
    }

    private fun beginUninstallVerification() {
        val next = UninstallQueueReducer.returned(uninstallQueue, SystemClock.elapsedRealtime())
        if (next == uninstallQueue) return
        updateUninstallQueue(next)
        if (next.current?.stage == UninstallStage.Verifying) startUninstallVerificationLoop()
    }

    private fun startUninstallVerificationLoop() {
        uninstallValidationJob?.cancel()
        uninstallValidationJob = viewModelScope.launch {
            while (true) {
                val request = uninstallQueue.current?.takeIf { it.stage == UninstallStage.Verifying }?.request ?: return@launch
                val (installed, mainExists) = withContext(Dispatchers.IO) {
                    targetInstalled(request) to mainCopyExists(request.packageName)
                }
                val next = UninstallQueueReducer.observed(
                    uninstallQueue,
                    installed,
                    mainExists,
                    SystemClock.elapsedRealtime(),
                )
                if (next.outcomes.size > uninstallQueue.outcomes.size) {
                    completeUninstallTransition(next)
                    return@launch
                }
                delay(250L)
            }
        }
    }

    private suspend fun completeUninstallTransition(next: UninstallQueueState) {
        val outcome = next.outcomes.lastOrNull()
        if (outcome != null) DiagnosticLog.i(TAG,
            "uninstall verified pkg=${outcome.request.packageName} targetUser=${outcome.request.targetUserId} status=${outcome.status}")
        if (outcome != null && shouldClearCloneRegistry(outcome.status)) {
            withContext(Dispatchers.IO) { UserCloneRegistry.remove(getApplication(), outcome.request.packageName) }
        }
        if (outcome?.mainCopyLost == true) {
            DiagnosticLog.w(
                TAG,
                "main_copy_lost pkg=${outcome.request.packageName} rom=${Build.MANUFACTURER}/${Build.DISPLAY}",
            )
            _uiState.value = _uiState.value.copy(mainCopyLostPackage = outcome.request.packageName)
        }
        updateUninstallQueue(next)
        if (next.complete) {
            val summary = next.summary
            AppFeedbackBus.emit(uninstallQueueFeedback(summary, uninstallSkipped, prismResolver(getApplication())))
            _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
            uninstallQueue = UninstallQueueState()
            uninstallSkipped = 0
            refresh()
        } else driveUninstallLaunch()
    }

    private fun updateUninstallQueue(next: UninstallQueueState) {
        uninstallQueue = next
        if (next.total > 0 && !next.complete) {
            _uiState.value = _uiState.value.copy(
                batchProgress = prismResolver(getApplication())(
                    R.string.lz_vm_batch_progress,
                    arrayOf(next.outcomes.size, next.total),
                ),
            )
        }
    }

    private fun targetInstalled(request: UninstallRequest): Boolean? {
        val context: Context = getApplication()
        val user = UserHandles.of(request.targetUserId)
        if (!Users.isProfileAvailable(context, user)) return null
        return try {
            LauncherAppsCompat(context)
                .getApplicationInfoNoThrows(request.packageName, MATCH_UNINSTALLED_PACKAGES, user)
                ?.installed == true
        } catch (error: RuntimeException) {
            DiagnosticLog.w(TAG, "uninstall observation unavailable targetUser=${request.targetUserId} exception=${error.javaClass.simpleName}")
            null
        }
    }

    private fun mainCopyExists(pkg: String): Boolean = runCatching {
        getApplication<Application>().packageManager.getApplicationInfo(pkg, 0).installed
    }.getOrDefault(false)

    fun openSystemSettings(pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                PrismAppControl.launchSystemAppSettings(app)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Filter, sort, and search state updates.
    // -----------------------------------------------------------------------

    fun setSortOrder(order: SortOrder) {
        _uiState.value = _uiState.value.copy(sortOrder = order)
    }

    fun setCloneFilter(filter: CloneFilter) {
        _uiState.value = _uiState.value.copy(cloneFilter = filter)
    }

    fun setShowSystem(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystem = show)
    }

    /** Dual-space «显示系统应用» toggle — independent state, same default (ON) as main space. */
    fun setShowSystemDual(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemDual = show)
    }

    // -----------------------------------------------------------------------
    // Private helpers (run on IO dispatcher)
    // -----------------------------------------------------------------------

    private fun loadBothSegments(): LoadedRows {
        val context: Context = getApplication()
        val res: StringResolver = prismResolver(context)
        val dual = _uiState.value.selectedDualSpaceId
            ?.let { id -> spaceRepo.space(id)?.takeIf { it.kind == PrismSpaceKind.Dual } }
            ?: spaceRepo.dualSpace()

        // --- Dual profile (PrismSpace/work profile) ---
        var allDualApps: List<PrismAppInfo> = emptyList()
        val dualRows: List<SpaceRow> = if (dual == null) {
            emptyList()
        } else {
            allDualApps = spaceRepo.installedApps(dual)
                .filter { it.isInstalled && it.packageName != context.packageName }
            val apps = allDualApps
                .filter { it.shouldShowAsEnabled() }
                // 分身 = what the USER cloned. Third-party apps in a profile are always user clones;
                // system apps are only 分身 if the user explicitly cloned them (UserCloneRegistry).
                // This hides the system apps a managed profile carries by provisioning (Play/设置/文件…).
                // Include launchable system apps (browser/camera/files/…) so the dual space lists
                // useful default system apps; non-launchable background packages stay excluded.
                // The «显示系统应用» toggle (default ON) hides/reveals them in the UI layer (applyListTransform).
                .filter { !it.isSystem || UserCloneRegistry.contains(context, it.packageName) || it.isLaunchable }
                .sortedBy { it.label.toString().lowercase() }
            val inputs = apps.map { app ->
                SpaceAppInput(
                    pkg       = app.packageName,
                    label     = app.label.toString(),
                    frozen    = app.isHidden,
                    suspended = app.isSuspended,
                    launchable = app.isLaunchable,
                    system    = app.isSystem,
                    cloned    = false, // dual profile always cloned
                    segment   = SpaceSegment.Dual,
                    critical  = app.isCritical,
                )
            }
            mapRows(inputs, res)
        }

        // --- Main profile ---
        val systemRows = mapRows(allDualApps
            .filter { it.isSystem }
            .map { app ->
                SpaceAppInput(
                    pkg = app.packageName,
                    label = app.label.toString(),
                    frozen = app.isHidden,
                    suspended = app.isSuspended,
                    launchable = app.isLaunchable,
                    system = true,
                    cloned = false,
                    segment = SpaceSegment.Dual,
                    critical = app.isCritical,
                )
            }, res)

        val dualPkgs: Set<String> = allDualApps.map { it.packageName }.toSet()
        val pendingClonePkgs = ClonePreparationStore.reconcileInstalled(context, dualPkgs)
        val mainAppsList = spaceRepo.installedApps(spaceRepo.mainSpace())
            .filter { it.isInstalled && it.enabled && it.packageName != context.packageName }
            .sortedBy { it.label.toString().lowercase() }
        val mainRows = mapRows(mainAppsList.map { app ->
            SpaceAppInput(
                pkg       = app.packageName,
                label     = app.label.toString(),
                frozen    = app.isHidden,
                suspended = app.isSuspended,
                launchable = app.isLaunchable,
                system    = app.isSystem,
                cloned    = mainAppIsCloned(
                    isSystem = app.isSystem,
                    installedInDual = app.packageName in dualPkgs,
                    systemCloneMarked = UserCloneRegistry.contains(context, app.packageName),
                ),
                prepared  = app.packageName in pendingClonePkgs,
                segment   = SpaceSegment.Main,
                critical  = app.isCritical,
            )
        }, res)

        appCache = AppCache(allDualApps, mainAppsList)
        return LoadedRows(dualRows, mainRows, systemRows)
    }

}

private const val TAG = "Prism.SpaceVM"
