package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSuitePublisherTest {

    @Test fun namesAreCanonicalAndLegacyDuplicatesStayInsideExactPackageNamespace() {
        assertEquals("Label-pkg.apk", ApkSuiteNames.canonical("Label-pkg", 0))
        assertEquals("Label-pkg.split2.apk", ApkSuiteNames.canonical("Label-pkg", 2))
        assertEquals(".Label-pkg.prism-pending-token-1.apk", ApkSuiteNames.pending("Label-pkg", "token", 1))
        assertTrue(ApkSuiteNames.isPublishedFor("Label-pkg", "Label-pkg.apk"))
        assertTrue(ApkSuiteNames.isPublishedFor("Label-pkg", "Label-pkg.split1 (2).apk"))
        assertTrue(ApkSuiteNames.isPublishedFor("Label-pkg", "Label-pkg (1).split1.apk"))
        assertFalse(ApkSuiteNames.isPublishedFor("Label-pkg", "Label-other.apk"))
        assertFalse(ApkSuiteNames.isPublishedFor("Label-pkg", ".Label-pkg.prism-pending-token-0.apk"))
        assertTrue(ApkSuiteNames.isPendingFor("Label-pkg", ".Label-pkg.prism-pending-a1b2-0.apk"))
        assertFalse(ApkSuiteNames.isPendingFor("Label-pkg", ".Other.prism-pending-a1b2-0.apk"))
    }

    @Test fun completeSuiteReplacesPreviousRowsInOnePublishStep() {
        val store = RecordingStore(previous = listOf(PublishedApk("old", "App-pkg.apk")))
        val first = ApkSuitePublisher(store) { "fixed" }.replace(
            listOf("/base.apk", "/split.apk"),
            "App-pkg",
            "Download/PrismSpace/",
        )

        assertEquals("new-0", first)
        assertEquals(listOf("App-pkg.apk", "App-pkg.split1.apk"), store.published.map { it.canonicalName })
        assertEquals(listOf("old"), store.replacedPrevious.map { it.uri })
        assertTrue(store.aborted.isEmpty())
    }

    @Test fun stablePackageNamespaceAlsoRemovesCurrentLegacyLabelNamespace() {
        val store = RecordingStoreByBase(
            mapOf(
                "pkg" to listOf(PublishedApk("canonical-old", "pkg.apk")),
                "Label-pkg" to listOf(PublishedApk("legacy-old", "Label-pkg.apk")),
            ),
        )

        ApkSuitePublisher(store) { "fixed" }.replace(
            listOf("/base.apk"),
            "pkg",
            "Download/PrismSpace/",
            previousSafeBases = setOf("pkg", "Label-pkg"),
        )

        assertEquals(setOf("canonical-old", "legacy-old"), store.replaced.map(PublishedApk::uri).toSet())
    }

    @Test fun copyFailureAbortsOnlyNewRowsAndNeverPublishesOverPreviousSuite() {
        val store = RecordingStore(
            previous = listOf(PublishedApk("old", "App-pkg.apk")),
            failStageIndex = 1,
        )

        runCatching {
            ApkSuitePublisher(store) { "fixed" }.replace(
                listOf("/base.apk", "/split.apk"),
                "App-pkg",
                "Download/PrismSpace/",
            )
        }

        assertEquals(listOf("new-0"), store.aborted.map { it.uri })
        assertTrue(store.published.isEmpty())
        assertEquals(listOf("old"), store.previous.map { it.uri })
    }

    @Test fun atomicPublishFailureCleansNewRowsAndLeavesPreviousSuiteToProviderTransaction() {
        val store = RecordingStore(
            previous = listOf(PublishedApk("old", "App-pkg.apk")),
            failPublish = true,
        )

        runCatching {
            ApkSuitePublisher(store) { "fixed" }.replace(listOf("/base.apk"), "App-pkg", "Download/PrismSpace/")
        }

        assertEquals(listOf("new-0"), store.aborted.map { it.uri })
        assertEquals(listOf("old"), store.previous.map { it.uri })
    }

    private class RecordingStore(
        val previous: List<PublishedApk>,
        private val failStageIndex: Int? = null,
        private val failPublish: Boolean = false,
    ) : ApkSuiteStore {
        val staged = mutableListOf<StagedApk>()
        val aborted = mutableListOf<StagedApk>()
        var replacedPrevious = emptyList<PublishedApk>()
        var published = emptyList<StagedApk>()

        override fun stage(sourcePath: String, pendingName: String, canonicalName: String, relativePath: String): StagedApk {
            if (staged.size == failStageIndex) error("copy failed")
            return StagedApk("new-${staged.size}", canonicalName).also(staged::add)
        }

        override fun existingFor(safeBase: String, relativePath: String) = previous

        override fun replaceAtomically(previous: List<PublishedApk>, staged: List<StagedApk>) {
            if (failPublish) error("publish failed")
            replacedPrevious = previous
            published = staged
        }

        override fun abort(staged: StagedApk) { aborted += staged }
    }

    private class RecordingStoreByBase(
        private val rows: Map<String, List<PublishedApk>>,
    ) : ApkSuiteStore {
        var replaced = emptyList<PublishedApk>()
        override fun stage(sourcePath: String, pendingName: String, canonicalName: String, relativePath: String) =
            StagedApk("new", canonicalName)
        override fun existingFor(safeBase: String, relativePath: String) = rows[safeBase].orEmpty()
        override fun replaceAtomically(previous: List<PublishedApk>, staged: List<StagedApk>) { replaced = previous }
        override fun abort(staged: StagedApk) = Unit
    }
}
