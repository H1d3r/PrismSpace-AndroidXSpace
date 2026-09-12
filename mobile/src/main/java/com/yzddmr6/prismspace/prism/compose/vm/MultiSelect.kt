package com.yzddmr6.prismspace.prism.compose.vm

/**
 * Batch-selection domain: a SNAPSHOT of the filtered result set (search + filter + sort applied)
 * taken when multi-select is entered. Selection, 全选, and the count are all scoped to this domain;
 * later background list refreshes must not change it. Exiting multi-select (or an emptied
 * selection) invalidates the snapshot. System packages never enter the domain: batch operations
 * must not bypass the per-app critical-package warning.
 */
internal data class MultiSelectState(
    val domain: List<SpaceRow>,
    val selected: Set<String>,
)

internal object MultiSelect {

    /** Enter multi-select on [pkg] with the entry-time filtered rows as the domain. */
    fun enter(pkg: String, rows: List<SpaceRow>): MultiSelectState? {
        if (rows.firstOrNull { it.pkg == pkg }?.system != false) return null
        return MultiSelectState(domain = rows.filterNot { it.system }, selected = setOf(pkg))
    }

    /** Enter multi-select with an empty selection (the explicit 批量管理 top-bar entry). */
    fun enterEmpty(rows: List<SpaceRow>): MultiSelectState? {
        val domain = rows.filterNot { it.system }
        if (domain.isEmpty()) return null
        return MultiSelectState(domain = domain, selected = emptySet())
    }

    /** Toggle [pkg]; toggling outside the domain (or a system row) is a no-op. Emptying the
     *  selection exits multi-select (null). */
    fun toggle(state: MultiSelectState, pkg: String): MultiSelectState? {
        if (state.domain.firstOrNull { it.pkg == pkg } == null) return state
        val updated = if (pkg in state.selected) state.selected - pkg else state.selected + pkg
        return if (updated.isEmpty()) null else state.copy(selected = updated)
    }

    /** 全选 covers exactly the domain — never the whole segment. */
    fun selectAll(state: MultiSelectState): MultiSelectState {
        val all = state.domain.map { it.pkg }.toSet()
        return if (all.isEmpty()) state else state.copy(selected = all)
    }
}

/** 全选/取消全选 toggle: domain-driven — true only when the whole SNAPSHOT domain is selected. */
internal fun batchAllSelected(domainSize: Int, selectedCount: Int): Boolean =
    domainSize > 0 && selectedCount >= domainSize
