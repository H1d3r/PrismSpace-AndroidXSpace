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
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.prism.compose.settings.ExperimentalFlags
import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.ExperimentalBlockInfo
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.resolveSpaceSelection
import com.yzddmr6.prismspace.prism.compose.space.SpaceCapProbe
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningEngine
import com.yzddmr6.prismspace.prism.compose.space.SpaceDeletionCoordinator
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.experimentalBlockInfo
import com.yzddmr6.prismspace.setup.PrismSetup
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
    val segment: SpaceSegment,
    val critical: Boolean = false,
)

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
    val segment: SpaceSegment,
    val chipText: String,      // status label for the chip
    val chipOk: Boolean,       // true → ok-green, false → muted/warn
    val critical: Boolean = false,
)

// ---------------------------------------------------------------------------
// Pure mapper — the only business logic owned by this layer
// Dual segment chip rules:
//   frozen     → "已冻结"  (warn)
//   else       → "运行中"  (ok)
// Main segment chip rules:
//   cloned     → "已双开" (info/ok)
//   else       → "未双开" (muted)
// ---------------------------------------------------------------------------

internal fun mapRows(inputs: List<SpaceAppInput>, res: StringResolver = zhFallback): List<SpaceRow> = inputs.map { app ->
    val (chipText, chipOk) = when (app.segment) {
        SpaceSegment.Dual ->
            // Truthful badge: 已冻结 covers both freeze mechanisms and any lingering
            // suspended state, so a paused clone never reads as running.
            if (app.frozen || app.suspended) res(R.string.lz_vm_chip_frozen, emptyArray()) to false
            else res(R.string.lz_vm_chip_running, emptyArray()) to true
        SpaceSegment.Main ->
            if (app.cloned) res(R.string.lz_vm_chip_cloned, emptyArray()) to true
            else res(R.string.lz_vm_chip_not_cloned, emptyArray()) to false
    }
    SpaceRow(
        pkg       = app.pkg,
        label     = app.label,
        frozen    = app.frozen,
        suspended = app.suspended,
        launchable = app.launchable,
        system    = app.system,
        cloned    = app.cloned,
        segment   = app.segment,
        chipText  = chipText,
        chipOk    = chipOk,
        critical  = app.critical,
    )
}

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

enum class SortOrder { Name, Time, Cloned }

enum class CloneFilter { All, Yes, No }

/**
 * Pure client-side list transform: search → filter → sort.
 *
 * - search:      matches label OR packageName, case-insensitive; applied to both segments.
 * - showSystem:  hides system apps (row.system == true) when false.
 * - cloneFilter: All / Yes / No clone filter; MAIN segment only.
 * - sort Name:   localeCompare with zh collation (both segments).
 * - sort Time:   stable provider load order (index in list = install order proxy;
 *                most-recently-loaded = highest index → reversed → first); both segments.
 * - sort Cloned: cloned-first (already-cloned at top), then name; MAIN segment only.
 *                Falls back to Name sort for Dual segment.
 *
 * No backend or provider changes — operates only on the already-loaded in-memory list.
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
        sort == SortOrder.Time -> result.reversed() // stable load order proxy: last-loaded first
        sort == SortOrder.Cloned && segment == SpaceSegment.Main ->
            result.sortedWith(compareByDescending<SpaceRow> { if (it.cloned) 1 else 0 }
                .thenBy { it.label.lowercase() })
        else -> result.sortedWith(compareBy { it.label.lowercase() }) // Cloned sort falls back to Name for Dual
    }

    return result
}

// ---------------------------------------------------------------------------
// Compat alias kept so existing SpaceUiStateTest still compiles
// (SpaceUiStateTest uses SpaceAppInput with 4 fields + mapSpaceRows)
// ---------------------------------------------------------------------------

internal data class SpaceAppInput4(
    val pkg: String,
    val label: String,
    val frozen: Boolean,
    val launchable: Boolean,
)

internal fun mapSpaceRows(apps: List<SpaceAppInput4>): List<SpaceRow> =
    mapRows(apps.map { a ->
        SpaceAppInput(
            pkg = a.pkg, label = a.label, frozen = a.frozen, suspended = false,
            launchable = a.launchable, system = false, cloned = false,
            segment = SpaceSegment.Dual,
        )
    })

// ---------------------------------------------------------------------------
// UI state — one per segment
// ---------------------------------------------------------------------------

sealed interface SpaceSegmentState {
    object Loading : SpaceSegmentState
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
    // Batch progress message while a batch op is running, null otherwise
    val batchProgress: String? = null,
    // Filter, sort, and search state.
    val sortOrder: SortOrder = SortOrder.Name,
    val cloneFilter: CloneFilter = CloneFilter.All,
    val showSystem: Boolean = true,
    // Dual space uses its own toggle state so each segment can be narrowed independently.
    val showSystemDual: Boolean = true,
    val selectedDualSpaceId: String? = null,
    val spaces: List<PrismSpace> = emptyList(),
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
    val experimentalMultiProfile: Boolean = false,
    val dualUsability: SpaceUsability = SpaceUsability.Unknown,
    val experimentalCreateBlocked: ExperimentalBlockInfo? = null,
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
    private val capabilityRepo: CapabilityRepository by lazy { CapabilityRepositoryProvider.get(getApplication()) }
    private val _pendingUninstallRequest = MutableStateFlow<UninstallRequest?>(null)
    internal val pendingUninstallRequest: StateFlow<UninstallRequest?> = _pendingUninstallRequest
    private var uninstallQueue = UninstallQueueState()
    private var uninstallValidationJob: Job? = null
    private var uninstallSkipped = 0

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
                    SpaceSnapshot.Loading -> Unit
                    is SpaceSnapshot.Loaded -> loadContent()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Segment switching
    // -----------------------------------------------------------------------

    fun selectSegment(segment: SpaceSegment) {
        _uiState.value = _uiState.value.copy(segment = segment)
    }

    fun selectSpace(dualSpaceId: String) {
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

    fun createSpace() {
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            setFeedback(res(R.string.lz_vm_creating_space, emptyArray()), isError = false)
            val r = SpaceProvisioningEngine.createSpace(getApplication())
            if (r is CreateSpaceResult.CapReached || r is CreateSpaceResult.ManagedProfileLimitReached) {
                val duals = _uiState.value.spaces.filter { it.kind == PrismSpaceKind.Dual }
                val primary = duals.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    feedbackMessage = null, feedbackIsError = false,
                    experimentalCreateBlocked = experimentalBlockInfo(
                        primary?.displayName ?: res(R.string.lz_vm_default_space_name, emptyArray()),
                        primary?.userId ?: -1,
                        duals.size,
                        res,
                    ),
                )
                return@launch
            }
            val fb = provisioningFeedback(r, res)
            setFeedback(fb.message, isError = fb.isError)
            if (r is CreateSpaceResult.Success) {
                _uiState.value = _uiState.value.copy(
                    segment = SpaceSegment.Dual,
                    selectedDualSpaceId = null,
                )
            }
            refresh()
        }
    }

    fun clearExperimentalCreateBlocked() {
        _uiState.value = _uiState.value.copy(experimentalCreateBlocked = null)
    }

    fun clearMainCopyLostWarning() {
        _uiState.value = _uiState.value.copy(mainCopyLostPackage = null)
    }

    /** Re-read the experimental flag only (cheap; for screen re-entry — the
     *  flag may have been toggled on the Settings tab while we were away). */
    fun syncExperimentalFlag() {
        _uiState.value = _uiState.value.copy(
            experimentalMultiProfile = ExperimentalFlags.isMultiProfileEnabled(getApplication()),
        )
    }

    fun deleteSpace(activity: Activity, space: PrismSpace) {
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            setFeedback(res(R.string.lz_vm_deleting_space, emptyArray()), isError = false)
            val result = SpaceDeletionCoordinator.delete(
                getApplication(),
                space,
                useRoot = capabilityRepo.selectedMode.value == PrismMode.Root,
            )
            val fb = provisioningFeedback(result, res)
            if (result == DeleteSpaceResult.Success) {
                UserCloneRegistry.clear(getApplication())
            }
            setFeedback(fb.message, isError = fb.isError)
            if (fb.routeToSystemRemoval) PrismSetup.promptManualRemoval(activity)
            refresh()
        }
    }

    fun probeSpaceCap(onResult: (SpaceCapProbe) -> Unit) {
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) { SpaceProvisioningEngine.probeMaxSpaces() }
            onResult(probe)
        }
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
            experimentalMultiProfile = ExperimentalFlags.isMultiProfileEnabled(getApplication()),
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

    /** Long-press an app card to enter multi-select mode with that app pre-selected. */
    fun enterMultiSelect(pkg: String) {
        // System packages are deliberately single-action only so the critical-package warning
        // cannot be bypassed through a batch operation.
        if (appFor(pkg, _uiState.value.segment)?.isSystem == true) return
        _uiState.value = _uiState.value.copy(selectedPkgs = setOf(pkg))
    }

    /** Toggle selection of a pkg while in multi-select mode. */
    fun toggleSelect(pkg: String) {
        val current = _uiState.value.selectedPkgs ?: return
        if (appFor(pkg, _uiState.value.segment)?.isSystem == true) return
        val updated = if (current.contains(pkg)) current - pkg else current + pkg
        _uiState.value = _uiState.value.copy(
            selectedPkgs = if (updated.isEmpty()) null else updated
        )
    }

    /** Select every app currently shown in the active segment (the "全选" action). */
    fun selectAll() {
        val rows = (_uiState.value.current as? SpaceSegmentState.Content)?.rows ?: return
        val selectable = rows.filterNot { it.system }
        if (selectable.isEmpty()) return
        _uiState.value = _uiState.value.copy(selectedPkgs = selectable.map { it.pkg }.toSet())
    }

    /** Exit multi-select mode and clear selection. */
    fun exitMultiSelect() {
        _uiState.value = _uiState.value.copy(selectedPkgs = null, batchProgress = null)
    }

    // -----------------------------------------------------------------------
    // Batch execution — loops single-item APIs sequentially off main thread
    // -----------------------------------------------------------------------

    fun executeBatch(
        action: BatchAction,
        activity: FragmentActivity,
        prismAppsVm: PrismAppsViewModel,
    ) {
        val pkgs = _uiState.value.selectedPkgs?.toList() ?: return
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
                    BatchAction.Uninstall -> error("Uninstall is handled by the ActivityResult queue")
                    BatchAction.CopyToDual -> {
                        pkgs.forEachIndexed { i, pkg ->
                            runCatching {
                                val app = appFor(pkg, SpaceSegment.Main)
                                if (app != null) {
                                    withContext(Dispatchers.Main) {
                                        _uiState.value = _uiState.value.copy(
                                            batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(i + 1, total))
                                        )
                                        // Headless clone: no per-app selector; mode follows the configured
                                        // run mode. Batch is confirmed once before this loop.
                                    PrismAppClones(activity, prismAppsVm, app).requestSilently()
                                    }
                                    kotlinx.coroutines.delay(200)
                                    succeeded++
                                }
                            }.onFailure { failures.add(pkg) }
                        }
                        AppFeedbackBus.emit(batchActionFeedback(action, succeeded, failures.size, res))
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedPkgs = null, batchProgress = null)
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
                val usable = withContext(Dispatchers.IO) {
                    val sel = _uiState.value.selectedDualSpaceId?.let { spaceRepo.space(it) }
                        ?: spaceRepo.dualSpace()
                    sel != null && spaceRepo.usabilityOf(sel) == SpaceUsability.Usable
                }
                if (!usable) {
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
        if (app.isSystem) PrismAppControl.requestRemoval(activity, app)
        else startUninstallQueue(listOf(pkg), segment)
    }

    fun onUninstallLaunched() {
        updateUninstallQueue(UninstallQueueReducer.launched(uninstallQueue))
    }

    fun onUninstallActivityResult(resultCode: Int) {
        val hint = if (resultCode == Activity.RESULT_CANCELED) UninstallReturnHint.Cancelled
        else UninstallReturnHint.Completion
        beginUninstallVerification(hint)
    }

    fun onHostResumed() {
        beginUninstallVerification(UninstallReturnHint.Foreground)
    }

    fun onUninstallLaunchFailed() {
        viewModelScope.launch {
            val mainExists = withContext(Dispatchers.IO) {
                uninstallQueue.current?.request?.packageName?.let(::mainCopyExists) ?: false
            }
            completeUninstallTransition(UninstallQueueReducer.launchFailed(uninstallQueue, mainExists))
        }
    }

    private fun startUninstallQueue(pkgs: List<String>, segment: SpaceSegment) {
        if (!uninstallQueue.complete || uninstallQueue.total > 0) return
        viewModelScope.launch {
            val requests = withContext(Dispatchers.IO) {
                pkgs.mapNotNull { pkg ->
                    val app = appFor(pkg, segment)?.takeUnless { it.isSystem } ?: return@mapNotNull null
                    UninstallRequest(app.packageName, app.user.toId(), mainCopyExists(app.packageName))
                }
            }
            uninstallSkipped = pkgs.size - requests.size
            if (requests.isEmpty()) {
                AppFeedbackBus.emit(batchActionFeedback(BatchAction.Uninstall, 0, pkgs.size, prismResolver(getApplication())))
                _uiState.value = _uiState.value.copy(selectedPkgs = null, batchProgress = null)
                return@launch
            }
            updateUninstallQueue(UninstallQueueReducer.start(requests))
        }
    }

    private fun beginUninstallVerification(hint: UninstallReturnHint) {
        val next = UninstallQueueReducer.returned(uninstallQueue, hint, SystemClock.elapsedRealtime())
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
            _uiState.value = _uiState.value.copy(selectedPkgs = null, batchProgress = null)
            uninstallQueue = UninstallQueueState()
            uninstallSkipped = 0
            refresh()
        }
    }

    private fun updateUninstallQueue(next: UninstallQueueState) {
        uninstallQueue = next
        _pendingUninstallRequest.value = next.current
            ?.takeIf { it.stage == UninstallStage.ReadyToLaunch }
            ?.request
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
        return LauncherAppsCompat(context)
            .getApplicationInfoNoThrows(request.packageName, MATCH_UNINSTALLED_PACKAGES, user)
            ?.installed == true
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
                cloned    = app.packageName in dualPkgs,
                segment   = SpaceSegment.Main,
                critical  = app.isCritical,
            )
        }, res)

        appCache = AppCache(allDualApps, mainAppsList)
        return LoadedRows(dualRows, mainRows, systemRows)
    }

}

private const val TAG = "Prism.SpaceVM"
