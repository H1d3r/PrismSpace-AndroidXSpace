package com.yzddmr6.prismspace.prism.compose.vm

import android.content.Context
import android.preference.PreferenceManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the user-selected mode. Home and
 * Settings both read [selectedMode] from one process singleton, so they
 * can never display contradicting modes.
 *
 * The selected mode is persisted via [ModeStore] so a user's
 * Shizuku/Root choice survives app restarts instead of silently reverting
 * to detection-only each launch.
 */
interface CapabilityRepository {
    val selectedMode: StateFlow<PrismMode>
    fun setSelectedMode(mode: PrismMode)
    fun runtimeSnapshot(): RuntimeCapabilitySnapshot
    fun markRootReady()
    fun markRootUnavailable()
    fun markShizukuReady()
    fun markShizukuUnavailable()
}

sealed interface RootReadiness {
    object Unknown : RootReadiness
    object Unavailable : RootReadiness
    data class ReadyUntil(val expiresAtElapsedMs: Long) : RootReadiness
}

data class RuntimeCapabilitySnapshot(
    val preferredMode: PrismMode,
    val shizukuReady: Boolean,
    val rootReadiness: RootReadiness,
) {
    val rootReady: Boolean get() = rootReadiness is RootReadiness.ReadyUntil
}

/**
 * Persistence seam for the selected mode. Kept as an interface so tests and
 * any non-persisted construction can stay in-memory ([NoopModeStore]).
 */
interface ModeStore {
    fun load(): PrismMode?
    fun save(mode: PrismMode)
}

/** In-memory no-op store — used by tests and any non-persisted construction. */
object NoopModeStore : ModeStore {
    override fun load(): PrismMode? = null
    override fun save(mode: PrismMode) {}
}

/** Default SharedPreferences-backed store (credential-protected — mirrors [ExperimentalFlags]). */
class SharedPrefsModeStore(context: Context) : ModeStore {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    override fun load(): PrismMode? =
        prefs.getString(KEY_SELECTED_MODE, null)
            ?.let { name -> PrismMode.values().firstOrNull { it.name == name } }
    override fun save(mode: PrismMode) {
        prefs.edit().putString(KEY_SELECTED_MODE, mode.name).apply()
    }
    private companion object { const val KEY_SELECTED_MODE = "prism_selected_mode" }
}

class DefaultCapabilityRepository(
    private val shizukuAuthorized: () -> Boolean = { ShizukuUtil.isAuthorized() },
    private val modeStore: ModeStore = NoopModeStore,
    private val elapsedRealtime: () -> Long = { System.nanoTime() / 1_000_000L },
    private val rootReadyTtlMs: Long = DEFAULT_ROOT_READY_TTL_MS,
) : CapabilityRepository {
    private val _selectedMode = MutableStateFlow(modeStore.load() ?: PrismMode.Normal)
    private var rootReadiness: RootReadiness = RootReadiness.Unknown
    private var shizukuInvalidated = false

    override val selectedMode: StateFlow<PrismMode> = _selectedMode

    override fun setSelectedMode(mode: PrismMode) {
        _selectedMode.value = mode
        modeStore.save(mode)
    }

    @Synchronized override fun runtimeSnapshot(): RuntimeCapabilitySnapshot {
        val now = elapsedRealtime()
        val root = when (val cached = rootReadiness) {
            is RootReadiness.ReadyUntil -> if (now < cached.expiresAtElapsedMs) cached else RootReadiness.Unknown
            else -> cached
        }
        rootReadiness = root
        return RuntimeCapabilitySnapshot(
            preferredMode = _selectedMode.value,
            shizukuReady = !shizukuInvalidated && runCatching(shizukuAuthorized).getOrDefault(false),
            rootReadiness = root,
        )
    }

    @Synchronized override fun markRootReady() {
        rootReadiness = RootReadiness.ReadyUntil(elapsedRealtime() + rootReadyTtlMs)
    }

    @Synchronized override fun markRootUnavailable() {
        rootReadiness = RootReadiness.Unavailable
    }

    @Synchronized override fun markShizukuReady() {
        shizukuInvalidated = false
    }

    @Synchronized override fun markShizukuUnavailable() {
        shizukuInvalidated = true
    }

    private companion object {
        const val DEFAULT_ROOT_READY_TTL_MS = 2 * 60 * 1_000L
    }
}

/** Manual DI seam matching the repository-provider pattern. */
object CapabilityRepositoryProvider {
    @Volatile private var instance: CapabilityRepository? = null
    fun get(context: Context): CapabilityRepository =
        instance ?: synchronized(this) {
            instance ?: DefaultCapabilityRepository(
                modeStore = SharedPrefsModeStore(context),
            ).also { instance = it }
        }
    @VisibleForTesting fun setForTest(repo: CapabilityRepository?) { instance = repo }
}
