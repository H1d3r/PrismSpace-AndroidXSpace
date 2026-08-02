package com.yzddmr6.prismspace.prism.service;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FileBridgeSelfTest {
    @Test
    public void roundTripsMarkerAcrossProfileBoundary() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FileBridgeSelfTestResult result = null;
        Throwable primaryFailure = null;
        try {
            result = new FileBridgeService().runSelfTest(context);

            assertTrue(result.getMessage(), result.getSuccess());
            assertNotNull(result.getCloneUri());
            assertNotNull(result.getMainUri());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (result != null && result.getMainUri() != null) {
                try {
                    assertTrue(
                            "Unable to delete parent file-bridge self-test object: " + result.getMainUri(),
                            context.getContentResolver().delete(Uri.parse(result.getMainUri()), null, null) > 0);
                } catch (Throwable cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }
}
