package com.yzddmr6.prismspace.prism.compose.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedTransportTest {

    @Test
    fun `authorized Shizuku always wins`() {
        assertEquals(PrivilegedTransport.SHIZUKU, chooseTransport(shizukuAuthorized = true) { true })
        assertEquals(PrivilegedTransport.SHIZUKU, chooseTransport(shizukuAuthorized = true) { false })
    }

    @Test
    fun `su probe is lazy and never runs while Shizuku is authorized`() {
        var probed = false
        chooseTransport(shizukuAuthorized = true) { probed = true; true }
        assertFalse(probed)
    }

    @Test
    fun `su is the fallback when Shizuku is absent or unauthorized`() {
        assertEquals(PrivilegedTransport.SU, chooseTransport(shizukuAuthorized = false) { true })
    }

    @Test
    fun `no transport yields null for the unified unavailable failure`() {
        assertNull(chooseTransport(shizukuAuthorized = false) { false })
    }
}
