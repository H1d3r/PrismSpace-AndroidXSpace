package com.yzddmr6.prismspace.bridge

import android.content.Intent
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallIntentSpecTest {

    @Test fun blankPackageIsRefused() {
        assertEquals(UninstallIntentSpec.Refused("blank_package"), uninstallIntentSpec("", "com.self"))
        assertEquals(UninstallIntentSpec.Refused("blank_package"), uninstallIntentSpec("   ", "com.self"))
    }

    @Test fun selfPackageIsRefused() {
        assertEquals(
            UninstallIntentSpec.Refused("self_uninstall"),
            uninstallIntentSpec("com.self", "com.self"),
        )
    }

    @Test fun normalPackageBuildsSystemUninstallIntent() {
        assertEquals(
            UninstallIntentSpec.Launch(Intent.ACTION_UNINSTALL_PACKAGE, "package:com.example.app"),
            uninstallIntentSpec("com.example.app", "com.self"),
        )
    }

    @Test fun launchSpecStructurallyCannotTargetAnotherUser() {
        // The intent is built solely from this spec: no field exists that could carry a user
        // handle, an EXTRA_USER value, or any other caller-chosen targeting input.
        val fields = UninstallIntentSpec.Launch::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(setOf("action", "packageUri"), fields.toSet())
        assertTrue(fields.none { it.contains("user", ignoreCase = true) })
    }

    @Test fun handlerDelegatesToTheSpec() {
        val source = java.io.File("src/main/java/com/yzddmr6/prismspace/bridge/CoreBridgeOperations.kt")
            .readText().codeLinesOnly()
        val handler = source.substringAfter("fun requestAppUninstall").substringBefore("\nfun ")

        assertTrue(handler.contains("uninstallIntentSpec("))
        assertTrue(handler.contains("Intent(spec.action, Uri.parse(spec.packageUri))"))
        assertTrue(!handler.contains("EXTRA_USER"))
    }

    /** Strips comment lines so assertions target code, not prose mentioning guarded tokens. */
    private fun String.codeLinesOnly(): String = lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .joinToString("\n")
}
