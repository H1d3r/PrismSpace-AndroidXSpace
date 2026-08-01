package com.yzddmr6.prismspace.prism.service;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FileBridgeSelfTest {
    @Test
    public void roundTripsMarkerAcrossProfileBoundary() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FileBridgeSelfTestResult result = new FileBridgeService().runSelfTest(context);

        assertTrue(result.getMessage(), result.getSuccess());
        assertNotNull(result.getCloneUri());
        assertNotNull(result.getMainUri());
    }
}
