package com.yzddmr6.prismspace.controller

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneRequestOutcomeTest {

    @Test fun missingSpaceIsHandledWithoutProgrammerAssertion() {
        val source = File("src/main/java/com/yzddmr6/prismspace/controller/PrismAppClones.kt").readText()
        val request = source.substringAfter("fun request(): CloneRequestOutcome")
            .substringBefore("fun requestSilently")

        assertTrue(request.contains("names.isEmpty()"))
        assertTrue(request.contains("CloneRequestOutcome.Unavailable"))
        assertTrue(!request.contains("check(names.isNotEmpty())"))
    }
}
