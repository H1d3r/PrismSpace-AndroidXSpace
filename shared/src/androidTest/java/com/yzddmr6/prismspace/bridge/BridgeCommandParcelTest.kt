package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeCommandParcelTest {

    @Test fun everyCommandAndResultRoundTrips() {
        assertRoundTrip(Ping, true)
        assertRoundTrip(SetAppFrozen("pkg", true), true)
        assertRoundTrip(EnsureAppHiddenState("pkg", false), false)
        assertRoundTrip(SetPackageSuspended("pkg", true), true)
        assertRoundTrip(SetPackagesSuspended(listOf("a", "b"), false), arrayOf("b"))
        assertRoundTrip(SetPackagesFrozen(listOf("a", "b"), true), arrayOf("a"))
        assertRoundTrip(EnsureAppFreeToLaunch("pkg"), "reason")
        assertRoundTrip(MarkClonedSystemApp("pkg"), true)
        assertRoundTrip(EnableSystemApp("pkg"), false)
    }

    private fun <R> assertRoundTrip(command: BridgeCommand<R>, result: R) {
        val parcel = Parcel.obtain()
        val restored: BridgeCommand<R>
        try {
            parcel.writeParcelable(command, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            restored = parcel.readParcelable<BridgeCommand<*>>(BridgeCommand::class.java.classLoader) as BridgeCommand<R>
        } finally {
            parcel.recycle()
        }
        assertEquals(command, restored)

        val encoded = Bundle().also { command.encodeResult(result, it) }
        val decoded = command.decodeResult(encoded)
        if (result is Array<*> && decoded is Array<*>) assertArrayEquals(result, decoded)
        else assertEquals(result, decoded)
    }
}
