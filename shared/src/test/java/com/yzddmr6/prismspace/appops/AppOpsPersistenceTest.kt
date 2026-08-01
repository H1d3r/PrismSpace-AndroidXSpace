package com.yzddmr6.prismspace.appops

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOpsPersistenceTest {
    @Test fun modesIncludingTwoDigitsRoundTripWithoutTruncation() {
        val entries = linkedMapOf(11 to 0, 12 to 1, 13 to 4, 14 to 10)
        assertEquals(entries, AppOpsPersistence.decode(AppOpsPersistence.encode(entries.map { it.key to it.value })))
    }

    @Test fun invalidEntriesAreDroppedWithoutLosingValidNeighbors() {
        assertEquals(linkedMapOf(1 to 10, 3 to 4), AppOpsPersistence.decode("1:10,bad,2:x,3:4"))
    }
}
