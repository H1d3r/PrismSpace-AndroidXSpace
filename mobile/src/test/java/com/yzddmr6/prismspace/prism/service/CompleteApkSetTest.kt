package com.yzddmr6.prismspace.prism.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompleteApkSetTest {

    @Test fun baseAndEverySplitMustBeReadable() {
        val files = listOf(File("/base.apk"), File("/split.apk"))
        val service = FileBridgeService()

        assertNull(service.completeReadableApkPaths(files) { it.name == "base.apk" })
        assertEquals(files.map(File::getAbsolutePath), service.completeReadableApkPaths(files) { true })
        assertNull(service.completeReadableApkPaths(emptyList()) { true })
    }
}
