package com.yzddmr6.prismspace.prism.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

class FileTransferResourceContractTest {

    @Test
    fun `supported locales do not advertise an artificial file count limit`() {
        val root = findProjectRoot()
        listOf(
            "mobile/src/main/res/values/strings_pf.xml",
            "mobile/src/main/res/values-zh/strings_pf.xml",
            "mobile/src/main/res/values-zh-rTW/strings_pf_transfer.xml",
        ).forEach { relativePath ->
            val summary = readString(root.resolve(relativePath), "lz_pf_files_send_other_summary")
            assertTrue("summary should describe multi-file transfer in $relativePath", summary.isNotBlank())
            assertFalse("summary must not retain the removed 20-file limit in $relativePath", summary.contains("20"))
        }
    }

    private fun readString(path: Path, name: String): String {
        assertTrue("resource file exists: $path", Files.exists(path))
        val nodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(path.toFile())
            .getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttribute("name") == name) return element.textContent
        }
        error("Missing $name in $path")
    }

    private fun findProjectRoot(): Path {
        var current = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle")) && Files.exists(current.resolve("mobile/build.gradle"))) {
                return current
            }
            current = current.parent ?: error("Cannot locate PrismSpace project root")
        }
    }
}
