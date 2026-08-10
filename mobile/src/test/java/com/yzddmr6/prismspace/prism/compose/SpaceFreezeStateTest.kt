package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.AppFreezeFact
import com.yzddmr6.prismspace.prism.compose.vm.SpaceFreezeState
import com.yzddmr6.prismspace.prism.compose.vm.aggregateSpaceFreeze
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceFreezeStateTest {
    @Test fun aggregateDistinguishesAllFourTruthfulStates() {
        assertEquals(SpaceFreezeState.Unknown, aggregateSpaceFreeze(null))
        assertEquals(SpaceFreezeState.Active, aggregateSpaceFreeze(emptyList()))
        assertEquals(SpaceFreezeState.Active, aggregateSpaceFreeze(listOf(AppFreezeFact(false, false))))
        assertEquals(SpaceFreezeState.Frozen, aggregateSpaceFreeze(listOf(AppFreezeFact(true, false), AppFreezeFact(false, true))))
        assertEquals(SpaceFreezeState.Mixed, aggregateSpaceFreeze(listOf(AppFreezeFact(true, false), AppFreezeFact(false, false))))
    }
}
