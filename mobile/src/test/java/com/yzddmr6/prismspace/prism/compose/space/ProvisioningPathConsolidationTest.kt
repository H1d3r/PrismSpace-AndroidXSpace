package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.prism.compose.vm.specificRootSetupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvisioningPathConsolidationTest {

    @Test fun `existing package is enabled without replacement install or verifier changes`() {
        val command = command(maxUsersOriginal = "4")
        assertTrue(command.contains("trap handle_prism_unexpected_exit EXIT"))
        assertTrue(command.contains("trap 'fail_prism_provisioning 70 interrupted' HUP INT TERM"))
        assertTrue(command.contains("pm install-existing --user \"${'$'}PROFILE_ID\" 'com.example'"))
        assertTrue(command.contains("fail_prism_provisioning 30 install_existing"))
        assertTrue(command.contains("am start-user \"${'$'}PROFILE_ID\" || fail_prism_provisioning 50 start"))
        assertTrue(command.contains("echo \"PRISM_PROVISION_FAILED stage=${'$'}PRISM_FAILURE_STAGE\""))
        assertTrue(command.contains("PRISM_PROVISION_SUCCESS user=${'$'}PROFILE_ID"))
        assertTrue(command.contains("fail_prism_provisioning()"))
        assertTrue(command.contains("finish_prism_transaction\n  echo \"PRISM_PROVISION_FAILED stage=${'$'}PRISM_FAILURE_STAGE\"\n  exit \"${'$'}PRISM_FAILURE_STATUS\""))
        assertFalse(command.contains("pm install -r"))
        assertFalse(command.contains("verifier_verify_adb_installs"))
    }

    @Test fun `failed or interrupted transaction rolls back only its newly created profile`() {
        val command = command()
        assertTrue(command.contains("PROFILE_ID=\"\""))
        assertTrue(command.contains("PRISM_PROVISION_SUCCEEDED=0"))
        assertTrue(command.contains("[ -n \"${'$'}PROFILE_ID\" ]"))
        assertTrue(command.contains("pm remove-user \"${'$'}PROFILE_ID\""))
        assertTrue(command.contains("PRISM_PROVISION_ROLLBACK user=${'$'}PROFILE_ID result=success"))
        assertTrue(command.contains("PRISM_PROVISION_SUCCEEDED=1\nfinish_prism_transaction\necho \"PRISM_PROVISION_SUCCESS"))
        assertTrue(command.indexOf("restore_prism_side_effects\n  if [") < command.indexOf("pm remove-user"))
    }

    @Test fun `max users property restores its exact original state`() {
        assertTrue(command(maxUsersOriginal = "4").contains("setprop $MAX_USERS_PROPERTY '4'"))
        assertTrue(command(maxUsersOriginal = null).contains("setprop $MAX_USERS_PROPERTY ''"))
    }

    @Test fun `shell quoting preserves apostrophes in package names`() {
        assertTrue(command(packageName = "com.example.it's").contains("'com.example.it'\"'\"'s'"))
    }

    @Test fun `root transaction remains detached so rollback survives caller interruption`() {
        val launcher = detachedRootProvisioningLauncher("pm install-existing --user 22 com.example", "/data/user/0/app/cache/out")
        assertTrue(launcher.startsWith("setsid sh -c "))
        assertTrue(launcher.contains("</dev/null & echo ${'$'}!"))
        assertTrue(provisioningTransactionFinished(listOf("PRISM_PROVISION_SUCCESS user=22")))
        assertTrue(provisioningTransactionFinished(listOf("PRISM_PROVISION_FAILED stage=install")))
        assertFalse(provisioningTransactionFinished(listOf("Success: created user id 22")))
    }

    @Test fun `completion requires the exact final transaction sentinel`() {
        assertFalse(provisioningCompleted(listOf("Success: created user id 22"), 22))
        assertFalse(provisioningCompleted(listOf("PRISM_PROVISION_SUCCESS user=23"), 22))
        assertTrue(provisioningCompleted(listOf("PRISM_PROVISION_SUCCESS user=22"), 22))
        assertEquals(
            "PRISM_PROVISION_FAILED stage=owner",
            provisioningFailure(listOf("noise", "PRISM_PROVISION_FAILED stage=owner")),
        )
    }

    @Test fun `legacy root setup maps every engine result without changing analytics phases`() {
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.Success, null),
            RootSetupResultMapping.presentation(CreateSpaceResult.Success(22)),
        )
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.ExistingProfile, null),
            RootSetupResultMapping.presentation(CreateSpaceResult.BlockedByState(SpaceState.Healthy(22))),
        )
        listOf(
            CreateSpaceResult.RootUnavailable,
            CreateSpaceResult.CapReached(4),
            CreateSpaceResult.ManagedProfileLimitReached,
        ).forEach {
            assertEquals(
                RootSetupPresentation(RootSetupUiOutcome.Error, 1),
                RootSetupResultMapping.presentation(it),
            )
        }
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.Error, 2),
            RootSetupResultMapping.presentation(CreateSpaceResult.Failed("install", analyticsPhase = 2)),
        )
        assertEquals(
            "已达本设备空间上限（最多 4 个用户），无法再创建",
            specificRootSetupFailure(CreateSpaceResult.CapReached(4)),
        )
        assertEquals(
            "本设备系统仅允许一个双开空间（已达系统工作资料上限），无法再创建",
            specificRootSetupFailure(CreateSpaceResult.ManagedProfileLimitReached),
        )
        assertEquals(null, specificRootSetupFailure(CreateSpaceResult.Failed("install", analyticsPhase = 2)))
    }

    @Test fun `all deletion entrances use the same pure route decision`() {
        assertEquals(SpaceDeletionRoute.Self, deletionRoute(currentUserId = 22, targetUserId = 22, useRoot = false))
        assertEquals(SpaceDeletionRoute.Self, deletionRoute(currentUserId = 22, targetUserId = 22, useRoot = true))
        assertEquals(SpaceDeletionRoute.Root, deletionRoute(currentUserId = 0, targetUserId = 22, useRoot = true))
        assertEquals(SpaceDeletionRoute.Bridge, deletionRoute(currentUserId = 0, targetUserId = 22, useRoot = false))

        listOf(
            File("src/main/java/com/yzddmr6/prismspace/prism/compose/vm/SpaceViewModel.kt"),
            File("src/main/java/com/yzddmr6/prismspace/prism/compose/vm/SettingsViewModel.kt"),
        ).forEach { source ->
            val text = source.readText()
            assertTrue("${source.name} must use the coordinator", text.contains("SpaceDeletionCoordinator.delete("))
            assertFalse("${source.name} must not branch to legacy destruction", text.contains("destroyProfileDirect"))
        }
        val spaceScreen = File("src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SpaceScreen.kt").readText()
        assertTrue("Space screen must wire its delete entry to the shared coordinator path", spaceScreen.contains("vm.deleteSpace("))
        assertTrue("Space delete must only be offered for the dual segment", spaceScreen.contains("segment == SpaceSegment.Dual && canDeleteSpace"))
    }

    @Test fun `profile wipe has exactly one implementation`() {
        val implementationCount = listOf(File("src/main"), File("../shared/src/main"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
            .sumOf { source -> Regex("\\.wipeData\\(0\\)").findAll(source.readText()).count() }
        assertEquals(1, implementationCount)
    }

    private fun command(
        maxUsersOriginal: String? = "4",
        packageName: String = "com.example",
    ) = buildRootProvisioningCommand(
        RootProvisioningCommandInput(
            parentUserId = 0,
            temporaryMaxUsers = 4,
            packageName = packageName,
            adminComponent = "com.example/.Admin",
            maxUsersOriginal = maxUsersOriginal,
        ),
    )
}
