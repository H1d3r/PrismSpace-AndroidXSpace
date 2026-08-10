package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment

/** A chip in the space-page switcher row. */
data class SpaceChip(
    val id: String,
    val label: String,
    val selected: Boolean,
)

/** First dual space id in [spaces] order (assumed userId-sorted by SpaceRepository.spaces()), or null when none. */
fun pickDefaultDualSpaceId(spaces: List<PrismSpace>): String? =
    spaces.firstOrNull { it.kind == PrismSpaceKind.Dual }?.id

data class ResolvedSpaceSelection(
    val segment: SpaceSegment,
    val selectedDualSpaceId: String?,
)

/** A Dual segment is valid only while at least one real dual space exists. */
fun resolveSpaceSelection(
    requestedSegment: SpaceSegment,
    selectedDualSpaceId: String?,
    spaces: List<PrismSpace>,
): ResolvedSpaceSelection {
    val duals = spaces.filter { it.kind == PrismSpaceKind.Dual }
    if (duals.isEmpty()) return ResolvedSpaceSelection(SpaceSegment.Main, null)
    val resolvedDualId = selectedDualSpaceId?.takeIf { selected -> duals.any { it.id == selected } }
        ?: duals.first().id
    return ResolvedSpaceSelection(requestedSegment, resolvedDualId)
}

/** Mutual-exclusion gate: a dual chip may be highlighted ONLY when the Dual
 *  segment is active; on Main no dual chip is selected. Preserves last-selected
 *  dual (selectedDualSpaceId untouched; restored when segment returns to Dual). */
fun selectedDualChipId(
    segment: SpaceSegment,
    selectedDualSpaceId: String?,
    spaces: List<PrismSpace>,
): String? = if (segment == SpaceSegment.Dual)
    (selectedDualSpaceId ?: pickDefaultDualSpaceId(spaces)) else null

/** Chip row = main + each real dual space in order. */
fun spaceChips(
    spaces: List<PrismSpace>,
    selectedMain: Boolean,
    selectedDualId: String?,
): List<SpaceChip> =
    spaces.map { space ->
        val selected = if (space.kind == PrismSpaceKind.Main) selectedMain else space.id == selectedDualId
        SpaceChip(space.id, space.displayName, selected)
    }
