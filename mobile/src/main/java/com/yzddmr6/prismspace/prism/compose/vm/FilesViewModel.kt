package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.service.TransferHistoryStore
import com.yzddmr6.prismspace.prism.service.TransferRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// Pure helpers

/** Returns true if the given MIME type is an image. */
internal fun isImageMime(mimeType: String?): Boolean =
    mimeType?.lowercase()?.startsWith("image/") == true

// ViewModel

	/**
	 * Files tab is now purely informational: file transfer between spaces happens through the system
	 * SHARE chooser ("导入到此空间 PrismSpace"), not an in-app importer. This VM just
	 * exposes the persisted transfer history so the user can see what they've moved.
	 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val _history = MutableStateFlow<List<TransferRecord>>(emptyList())
    val history: StateFlow<List<TransferRecord>> = _history

    init { refresh() }

    fun refresh() {
        _history.value = TransferHistoryStore.load(getApplication())
    }

    fun clearHistory() {
        TransferHistoryStore.clear(getApplication())
        refresh()
    }

    /** Fresh dual-space usability for the send-card gate — the same source as clone launch. */
    suspend fun dualUsability(): SpaceUsability = withContext(Dispatchers.IO) {
        val repo = SpaceRepositoryProvider.get(getApplication())
        repo.dualSpace()?.let { repo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
    }
}
