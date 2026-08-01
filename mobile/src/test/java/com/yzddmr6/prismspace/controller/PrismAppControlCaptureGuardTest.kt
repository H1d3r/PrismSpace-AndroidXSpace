package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class PrismAppControlCaptureGuardTest {

    @Test fun appControlBridgeClosuresCapturePackageNameInsteadOfPrismAppInfo() {
        val source = String(Files.readAllBytes(sourcePath()), StandardCharsets.UTF_8)

        listOf("freeze", "setSuspended", "unfreezeInitiallyFrozenSystemApp", "stopTreatingHiddenSysAppAsDisabled")
            .forEach { method ->
                val body = source.substringAfter("fun $method(").substringBefore("\n\t}")
                assertTrue("$method must extract the package before bridging", body.contains("val pkg = app.packageName"))
                val closure = body.substringAfter("runAppControl(").substringAfter("{")
                assertFalse("$method must not capture PrismAppInfo", closure.contains("app.packageName"))
            }
    }

    private fun sourcePath(): Path {
        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            val source = current.resolve("mobile/src/main/java/com/yzddmr6/prismspace/controller/PrismAppControl.kt")
            if (Files.exists(source)) return source
            current = current.parent
        }
        error("Cannot locate PrismAppControl.kt")
    }
}
