package com.yzddmr6.prismspace.provisioning;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrismProvisioningRevisionTest {
    @Test public void oldAndIncompleteStatesRunMigration() {
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(0, 10));
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(3, 10));
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(9, 10));
    }

    @Test public void currentOrFutureStateSkipsMigration() {
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(10, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(11, 10));
    }
}
