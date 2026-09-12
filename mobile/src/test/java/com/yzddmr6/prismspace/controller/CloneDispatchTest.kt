package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloneDispatchTest {

    private fun plan(parent: Boolean, system: Boolean, mode: Int,
                     installerUsable: () -> Boolean = { true },
                     shizukuReady: Boolean = true,
                     rootReady: Boolean = false) =
        planCloneRoute(parent, system, mode, installerUsable, CloneRuntimeReadiness(shizukuReady, rootReady))

    private fun route(parent: Boolean, system: Boolean, mode: Int,
                      installerUsable: () -> Boolean = { true },
                      shizukuReady: Boolean = true,
                      rootReady: Boolean = false) =
        plan(parent, system, mode, installerUsable, shizukuReady, rootReady).route

    @Test fun parentInstallerWinsWhenParentTargetAndInstallerUsable() {
        assertEquals(CloneRoute.PARENT_INSTALLER,
            route(parent = true, system = true, mode = PrismAppClones.MODE_SHIZUKU, installerUsable = { true }))
    }

    @Test fun notParentInstallerWhenInstallerNotUsable_fallsToSystem() {
        assertEquals(CloneRoute.SYSTEM_ENABLE,
            route(parent = true, system = true, mode = PrismAppClones.MODE_INSTALLER, installerUsable = { false }))
    }

    @Test fun systemEnableWinsOverShizukuAndInProfile() {
        assertEquals(CloneRoute.SYSTEM_ENABLE,
            route(parent = false, system = true, mode = PrismAppClones.MODE_SHIZUKU, shizukuReady = true))
    }

    @Test fun rootWhenModeRootAndGranted() {
        assertEquals(CloneRoute.ROOT,
            route(parent = false, system = false, mode = PrismAppClones.MODE_ROOT, rootReady = true))
    }

    @Test fun shizukuWhenModeShizukuAndGrantedAndNotParentNotSystem() {
        assertEquals(CloneRoute.SHIZUKU,
            route(parent = false, system = false, mode = PrismAppClones.MODE_SHIZUKU, shizukuReady = true))
    }

    @Test fun fileSyncWhenShizukuModeButNotGranted() {
        // A chosen-but-unavailable privileged mode degrades to the manual file-sync path.
        assertEquals(CloneRoute.FILE_SYNC,
            route(parent = false, system = false, mode = PrismAppClones.MODE_SHIZUKU, shizukuReady = false))
        assertEquals(CloneRoute.SHIZUKU,
            plan(parent = false, system = false, mode = PrismAppClones.MODE_SHIZUKU, shizukuReady = false).requestedEnhancedRoute)
    }

    @Test fun installerModeMapsToFileSync() {
        assertEquals(CloneRoute.FILE_SYNC,
            route(parent = false, system = false, mode = PrismAppClones.MODE_INSTALLER))
    }


    @Test fun installerUsableNotEvaluatedWhenNotParentTarget() {
        var called = false
        route(parent = false, system = false, mode = PrismAppClones.MODE_INSTALLER, installerUsable = { called = true; true })
        assertFalse(called)
    }

    @Test fun staleRootPreferenceFallsBackToNormalPreparation() {
        val plan = plan(parent = false, system = false, mode = PrismAppClones.MODE_ROOT, rootReady = false)
        assertEquals(CloneRoute.FILE_SYNC, plan.route)
        assertEquals(CloneRoute.ROOT, plan.requestedEnhancedRoute)
        assertEquals(true, plan.usedNormalFallback)
    }
}
