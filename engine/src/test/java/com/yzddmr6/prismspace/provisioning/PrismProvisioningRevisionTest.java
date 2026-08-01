package com.yzddmr6.prismspace.provisioning;

import android.app.admin.DeviceAdminReceiver;
import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrismProvisioningRevisionTest {
    @Test public void freshAndIncompleteStatesWaitForInitialProvisioning() {
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(0, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(1, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(2, 10));
    }

    @Test public void completedOldStatesRunMigration() {
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(3, 10));
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(9, 10));
    }

    @Test public void currentOrFutureStateSkipsMigration() {
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(10, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(11, 10));
    }

    @Test public void firstManagedCompletionRunsPostProvisioning() {
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 0, 10));
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 9, 10));
    }

    @Test public void duplicateManagedCompletionSkipsPostProvisioning() {
        assertFalse(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 10, 10));
        assertFalse(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 11, 10));
    }

    @Test public void manualRecoveryAlwaysRunsPostProvisioning() {
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(Intent.ACTION_USER_INITIALIZE, 10, 10));
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(null, 10, 10));
    }
}
