package com.yzddmr6.prismspace.controller

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneRequestOutcomeTest {

    @Test fun missingSpaceIsHandledWithoutProgrammerAssertion() {
        val source = File("src/main/java/com/yzddmr6/prismspace/controller/PrismAppClones.kt").readText()
        // Anchored to the next real function after request(); a deleted anchor would silently
        // widen the slice to the file tail (false-positive risk), so keep it real.
        val request = source.substringAfter("fun request(): CloneRequestOutcome")
            .substringBefore("fun requestForBatch")

        assertTrue(request.contains("names.isEmpty()"))
        assertTrue(request.contains("CloneRequestOutcome.Unavailable"))
        assertTrue(!request.contains("check(names.isNotEmpty())"))
    }
}
