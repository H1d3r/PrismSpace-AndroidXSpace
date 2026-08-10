package com.yzddmr6.prismspace.prism.compose.vm

enum class SpaceFreezeState { Active, Frozen, Mixed, Unknown }

internal data class AppFreezeFact(val hidden: Boolean, val suspended: Boolean) {
    val frozen: Boolean get() = hidden || suspended
}

internal fun aggregateSpaceFreeze(facts: List<AppFreezeFact>?): SpaceFreezeState {
    if (facts == null) return SpaceFreezeState.Unknown
    if (facts.isEmpty()) return SpaceFreezeState.Active
    val frozenCount = facts.count(AppFreezeFact::frozen)
    return when (frozenCount) {
        0 -> SpaceFreezeState.Active
        facts.size -> SpaceFreezeState.Frozen
        else -> SpaceFreezeState.Mixed
    }
}
