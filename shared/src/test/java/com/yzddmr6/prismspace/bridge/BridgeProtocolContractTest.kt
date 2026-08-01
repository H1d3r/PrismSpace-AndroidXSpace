package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolContractTest {

    @Test fun commandIdsAreUniqueAndUsedAsWireMethodNames() {
        val commands = BridgeCommandCatalog.all

        assertEquals(commands.size, commands.map { it.id }.distinct().size)
        commands.forEach { command -> assertEquals(command.id, BridgeWire.methodName(command)) }
    }

    @Test fun assembledHandlersExposeNoMissingPorts() {
        val handlers = BridgeHandlers(
            FakeAppControlPort,
            FakeFileBridgePort,
            FakeAppListPort,
            FakeShortcutPort,
            FakeInstallerPort,
        )

        assertTrue(handlers.missing().isEmpty())
    }

    @Test fun commandPayloadsCannotCarryExecutableOrIntentTargets() {
        BridgeCommandCatalog.all.forEach { command ->
            command.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                assertTrue("${command.id}.${field.name} carries Intent", !Intent::class.java.isAssignableFrom(field.type))
                assertTrue("${command.id}.${field.name} carries function", !Function::class.java.isAssignableFrom(field.type))
                assertTrue("${command.id}.${field.name} carries reflection target", !Class::class.java.isAssignableFrom(field.type))
                assertTrue(
                    "${command.id}.${field.name} looks like an executable escape hatch",
                    FORBIDDEN_EXECUTABLE_FIELD_NAMES.none { field.name.contains(it, ignoreCase = true) },
                )
            }
        }
    }

    @Test fun profileAppPageSizeIsServerBounded() {
        assertEquals(1, clampProfileAppPageSize(-1))
        assertEquals(50, clampProfileAppPageSize(50))
        assertEquals(MAX_PROFILE_APP_PAGE_SIZE, clampProfileAppPageSize(Int.MAX_VALUE))
    }

    @Test fun diagnosticsChunkSizeCannotBeSelectedByCaller() {
        val payloadFields = ReadDiagnosticsChunk::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(setOf("token", "offset"), payloadFields.toSet())
        assertEquals(2, payloadFields.size)
        assertTrue(DIAGNOSTICS_CHUNK_BYTES < 1024 * 1024)
    }

    @Test fun profileAppPageAccumulatorStopsAtBoundaryPage() {
        val first = sampleProfileApp("first")
        val second = sampleProfileApp("second")
        val accumulator = ProfileAppPageAccumulator()

        assertTrue(accumulator.accept(ProfileAppPage(listOf(first), hasMore = true)))
        assertTrue(!accumulator.accept(ProfileAppPage(listOf(second), hasMore = false)))
        assertEquals(listOf(first, second), accumulator.entries)
    }

    private object FakeAppControlPort : AppControlPort {
        override fun setAppFrozen(context: Context, packageName: String, frozen: Boolean) = true
        override fun ensureAppHiddenState(context: Context, packageName: String, hidden: Boolean) = true
        override fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean) = true
        override fun setPackagesSuspended(
            context: Context,
            packageNames: List<String>,
            suspended: Boolean,
        ) = emptyArray<String>()
        override fun setPackagesFrozen(
            context: Context,
            packageNames: List<String>,
            frozen: Boolean,
        ) = emptyArray<String>()
        override fun ensureAppFreeToLaunch(context: Context, packageName: String) = ""
        override fun markClonedSystemApp(context: Context, packageName: String) = true
        override fun enableSystemApp(context: Context, packageName: String) = true
    }

    private object FakeFileBridgePort : FileBridgePort {
        override fun openWriteSession(
            context: Context,
            store: BridgeFileStore,
            safeName: String,
            mimeType: String,
            relativePath: String,
        ): WriteSessionDto = error("unused")
        override fun finishWriteSession(
            context: Context,
            store: BridgeFileStore,
            targetUri: String,
            history: TransferHistoryDto?,
        ) = targetUri
        override fun abortWriteSession(context: Context, store: BridgeFileStore, targetUri: String) = Unit
        override fun importApkSet(
            context: Context,
            paths: List<String>,
            label: String,
            packageName: String,
            cloneLocation: String,
        ): String? = null
        override fun queryLatestVisibleImage(context: Context): ProfileMediaEntryDto? = null
        override fun openImagePicker(context: Context) = true
        override fun openLatestForRead(context: Context, store: BridgeFileStore): ReadSessionDto? = null
        override fun writePerAppShareMarker(context: Context, packageName: String) = "marker"
        override fun deletePerAppShareMarker(context: Context, packageName: String) = true
        override fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto? = null
        override fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind) = true
    }

    private object FakeAppListPort : AppListPort {
        override fun queryProfileApps(context: Context, pageIndex: Int, pageSize: Int) =
            ProfileAppPage(emptyList(), false)
    }

    private object FakeShortcutPort : ShortcutPort {
        override fun requestPin(context: Context, packageName: String, dynamicLabel: Boolean) = true
        override fun updateAll(context: Context, dynamicLabel: Boolean) = true
        override fun removeInParent(context: Context, packageName: String, profileUserId: Int) = true
        override fun refreshInParent(context: Context, packageName: String, profileUserId: Int) = true
        override fun queryDynamicLabelEnabled(context: Context) = true
    }

    private object FakeInstallerPort : InstallerPort {
        override fun notifyPackageRestarted(
            context: Context,
            packageName: String,
            uid: Int,
            uptimeMillis: Long,
        ) = true
    }

    private fun sampleProfileApp(packageName: String) = ProfileAppEntry(
        packageName,
        uid = 1,
        flags = 0,
        hidden = false,
        enabled = true,
        targetSdkVersion = 35,
        label = packageName,
        iconResource = 0,
        sourceDir = null,
        publicSourceDir = null,
        splitSourceDirs = emptyList(),
    )

    private companion object {
        val FORBIDDEN_EXECUTABLE_FIELD_NAMES = setOf(
            "intent",
            "className",
            "methodName",
            "fieldName",
            "shellCommand",
        )
    }
}
