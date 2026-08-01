package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Prevents new closure-based bridge calls while the command migration is in progress. */
class LegacyBridgeCallGuardTest {

    @Test fun legacyDirectShuttleCallsMatchTheShrinkingWhitelist() {
        val actual = sourceFiles().mapNotNull { path ->
            val count = Files.readAllLines(path, StandardCharsets.UTF_8).count { line ->
                line.contains("Shuttle(") && (line.contains(".launch") || line.contains(".invoke"))
            }
            relative(path).takeIf { count > 0 }?.let { it to count }
        }.toMap()

        assertEquals(DIRECT_SHUTTLE_WHITELIST, actual)
    }

    @Test fun legacyProfileBridgeClosuresMatchTheShrinkingWhitelist() {
        val actual = sourceFiles().filter { it.toString().endsWith(".kt") }.mapNotNull { path ->
            val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            if (path.fileName.toString() == "ProfileBridgeOperation.kt") return@mapNotNull null
            val calls = source.countOccurrences("runProfileBridgeOperation(")
            val commands = source.countOccurrences("command =")
            relative(path).takeIf { calls > commands }?.let { it to calls - commands }
        }.toMap()

        assertEquals(PROFILE_BRIDGE_CLOSURE_WHITELIST, actual)
    }

    private fun sourceFiles(): Sequence<Path> = MODULES.asSequence().flatMap { module ->
        val root = repoRoot().resolve("$module/src/main")
        if (!Files.exists(root)) emptySequence()
        else Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java")) }
                .toList().asSequence()
        }
    }

    private fun relative(path: Path) = repoRoot().relativize(path).toString().replace('\\', '/')

    private fun String.countOccurrences(needle: String): Int = split(needle).size - 1

    private fun repoRoot(): Path {
        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle"))) return current
            current = current.parent
        }
        error("Cannot locate repository root")
    }

    private companion object {
        val MODULES = listOf("shared", "mobile", "installer", "open", "watcher")
        val DIRECT_SHUTTLE_WHITELIST = mapOf(
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/service/ProfileBridgeOperation.kt" to 3,
        )
        val PROFILE_BRIDGE_CLOSURE_WHITELIST = emptyMap<String, Int>()
    }
}
