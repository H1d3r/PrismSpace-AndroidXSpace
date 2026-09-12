package com.yzddmr6.prismspace.device;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult;
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult;
import com.yzddmr6.prismspace.prism.compose.space.SpaceDeletionCoordinator;
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Destructive drivers selected only by the release-gate host scenario. Host Android-user and DPM
 * facts remain authoritative because deleting a user or installing the target package may kill
 * this process before AndroidJUnit can report its result.
 */
@RunWith(AndroidJUnit4.class)
public final class ProfileLifecycleDriverTest {
    @Test
    public void deleteCurrentProfileThroughProductionPath() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeleteSpaceResult result = SpaceDeletionCoordinator.deleteCurrentProfile(context);
        assertTrue("Profile self-deletion returned " + result, result == DeleteSpaceResult.Success.INSTANCE);
    }

    @Test
    public void createProfileThroughProductionPath() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CreateSpaceResult result = SpaceProvisioningEngine.createSpaceBlocking(context);
        assertTrue("Root profile creation returned " + result, result instanceof CreateSpaceResult.Success);
    }
}
