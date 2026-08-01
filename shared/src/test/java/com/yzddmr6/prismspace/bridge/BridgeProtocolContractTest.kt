package com.yzddmr6.prismspace.bridge

import android.content.Context
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
        val handlers = BridgeHandlers(FakeAppControlPort)

        assertTrue(handlers.missing().isEmpty())
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
}
