package com.yzddmr6.prismspace.shuttle

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureCaptureValidatorTest {

    @Test fun acceptsSafeScalarsAndRecursiveContainers() {
        val text = "package.name"
        val count = 10
        val array = arrayOf("base.apk", "split.apk")
        val list = arrayListOf("one", "two")
        val block: Context.() -> Int = { text.length + count + array.size + list.size }

        assertNull(ClosureCaptureValidator.findViolation(block))
    }

    @Test fun acceptsNestedClosureWhenItsCapturesAreSafe() {
        val packageName = "com.example"
        val nested = { packageName.length }
        val block: Context.() -> Int = { nested() }

        assertNull(ClosureCaptureValidator.findViolation(block))
    }

    @Test fun rejectsCustomObjectWithActionablePathAndTypes() {
        val app = UnsafeAppInfo("com.example")
        val block: Context.() -> String = { app.packageName }

        val violation = ClosureCaptureValidator.findViolation(block)

        assertNotNull(violation)
        assertTrue(violation!!.path.contains("app"))
        assertTrue(violation.declaredType.contains("UnsafeAppInfo"))
        assertTrue(violation.runtimeType.contains("UnsafeAppInfo"))
    }

    @Test fun rejectsUnsafeValueInsideRecursiveContainer() {
        val apps = arrayListOf(UnsafeAppInfo("com.example"))
        val block: Context.() -> Int = { apps.size }

        val violation = ClosureCaptureValidator.findViolation(block)

        assertNotNull(violation)
        assertTrue(violation!!.path.contains("[0]"))
    }

    private data class UnsafeAppInfo(val packageName: String)
}
