package com.yzddmr6.prismspace.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Parcel;
import com.yzddmr6.prismspace.bridge.RequestAppUninstall;
import com.yzddmr6.prismspace.bridge.UninstallLaunchDto;
import com.yzddmr6.prismspace.bridge.UninstallLaunchKind;
import com.yzddmr6.prismspace.util.UserHandles;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.yzddmr6.prismspace.bridge.Bridge;
import com.yzddmr6.prismspace.bridge.BridgeTargets;
import com.yzddmr6.prismspace.bridge.CloseDiagnosticsSnapshot;
import com.yzddmr6.prismspace.bridge.DiagnosticsChunkDto;
import com.yzddmr6.prismspace.bridge.DiagnosticsChunkResultDto;
import com.yzddmr6.prismspace.bridge.DiagnosticsCommandsKt;
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotInvalid;
import com.yzddmr6.prismspace.bridge.DiagnosticsSnapshotSessionDto;
import com.yzddmr6.prismspace.bridge.FileBridgeCommandsKt;
import com.yzddmr6.prismspace.bridge.OpenDiagnosticsSnapshot;
import com.yzddmr6.prismspace.bridge.ProfileAppEntry;
import com.yzddmr6.prismspace.bridge.ProfileAppPage;
import com.yzddmr6.prismspace.bridge.ProfileTarget;
import com.yzddmr6.prismspace.bridge.QueryProfileAppsPage;
import com.yzddmr6.prismspace.bridge.ReadDiagnosticsChunk;
import com.yzddmr6.prismspace.bridge.SetAppFrozen;
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository;
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome;
import com.yzddmr6.prismspace.space.SpaceState;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class CoreDeviceRegressionTest {
    private static final String PRISM_PACKAGE = "com.yzddmr6.prismspace";
    private static final String PROBE_PACKAGE = "com.yzddmr6.prismprobe";
    private static final int MAX_APP_PAGES = 100;

    @Test
    public void uninstallConfirmationIsOwnedByTargetProfileAndSurvivesParcel() {
        ProfileTarget target = requireProfileTarget();
        RequestAppUninstall command = new RequestAppUninstall(PROBE_PACKAGE);
        UninstallLaunchDto result = requireValue(
                Bridge.INSTANCE.inProfile(targetContext(), target).execute(command), "prepare uninstall");
        assertEquals(UninstallLaunchKind.Prepared, result.getKind());
        PendingIntent confirmation = result.getConfirmation();
        assertNotNull(confirmation);
        try {
            assertEquals(PRISM_PACKAGE, confirmation.getCreatorPackage());
            assertEquals(UserHandles.of(target.getUserId()), confirmation.getCreatorUserHandle());
            Bundle encoded = new Bundle();
            command.encodeResult(result, encoded);
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeBundle(encoded);
                parcel.setDataPosition(0);
                UninstallLaunchDto decoded = command.decodeResult(parcel.readBundle(command.getClass().getClassLoader()));
                assertEquals(confirmation, decoded.getConfirmation());
            } finally {
                parcel.recycle();
            }
        } finally {
            // This test never sends the token; user-confirmed removal is tested through the app UI.
            confirmation.cancel();
        }
        UninstallLaunchDto self = requireValue(Bridge.INSTANCE.inProfile(targetContext(), target)
                .execute(new RequestAppUninstall(PRISM_PACKAGE)), "refuse self uninstall");
        assertEquals(UninstallLaunchKind.Failed, self.getKind());
    }

    @Test
    public void productionStateIsHealthyForBridgeTarget() {
        Context context = targetContext();
        ProfileTarget target = requireProfileTarget();
        SpaceState state = new SpaceStateRepository(context).preflightCreateBlocking();

        assertNotNull("Production space-state refresh failed", state);
        assertTrue("Expected Healthy but was " + state, state instanceof SpaceState.Healthy);
        assertEquals(target.getUserId(), ((SpaceState.Healthy) state).getUserId().intValue());
    }

    @Test
    public void profileAppsAreCompleteBoundedAndUnique() {
        Map<String, ProfileAppEntry> apps = queryAllProfileApps();

        assertTrue("PrismSpace missing from profile app enumeration", apps.containsKey(PRISM_PACKAGE));
        assertTrue("Probe missing from profile app enumeration", apps.containsKey(PROBE_PACKAGE));
    }

    @Test
    public void probeFrozenStateRoundTripsAndRestores() throws Throwable {
        Context context = targetContext();
        ProfileTarget target = requireProfileTarget();
        ProfileAppEntry original = requireApp(queryAllProfileApps(), PROBE_PACKAGE);
        boolean originalFrozen = original.getHidden();
        boolean requestedFrozen = !originalFrozen;
        boolean mutationAttempted = false;
        Throwable primaryFailure = null;

        try {
            mutationAttempted = true;
            assertTrue(
                    "Probe freeze mutation returned false",
                    requireValue(
                            Bridge.INSTANCE.inProfile(context, target)
                                    .execute(new SetAppFrozen(PROBE_PACKAGE, requestedFrozen)),
                            "set Probe frozen=" + requestedFrozen));
            assertFrozenStateObserved(
                    queryAllProfileApps(),
                    PROBE_PACKAGE,
                    requestedFrozen,
                    "after mutation");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (mutationAttempted) {
                try {
                    assertTrue(
                            "Probe frozen-state restoration returned false",
                            requireValue(
                                    Bridge.INSTANCE.inProfile(context, target)
                                            .execute(new SetAppFrozen(PROBE_PACKAGE, originalFrozen)),
                                    "restore Probe frozen=" + originalFrozen));
                    assertFrozenStateObserved(
                            queryAllProfileApps(),
                            PROBE_PACKAGE,
                            originalFrozen,
                            "after restoration");
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

    @Test
    public void diagnosticSnapshotIsBoundedCompleteAndClosed() throws Throwable {
        Context context = targetContext();
        ProfileTarget target = requireProfileTarget();
        DiagnosticsSnapshotSessionDto session = requireValue(
                Bridge.INSTANCE.inProfile(context, target).execute(OpenDiagnosticsSnapshot.INSTANCE),
                "open diagnostics snapshot");
        assertTrue("Negative diagnostic snapshot length", session.getTotalLength() >= 0);

        Throwable primaryFailure = null;
        try {
            long offset = 0;
            boolean eof = false;
            long expectedReads = (session.getTotalLength() + DiagnosticsCommandsKt.DIAGNOSTICS_CHUNK_BYTES - 1L)
                    / DiagnosticsCommandsKt.DIAGNOSTICS_CHUNK_BYTES + 1L;
            assertTrue("Diagnostic snapshot requires too many reads", expectedReads <= Integer.MAX_VALUE);

            for (int read = 0; read < (int) expectedReads; read++) {
                DiagnosticsChunkResultDto result = requireValue(
                        Bridge.INSTANCE.inProfile(context, target).execute(
                                new ReadDiagnosticsChunk(session.getToken(), offset)),
                        "read diagnostics at offset " + offset);
                assertFalse("Diagnostic snapshot token became invalid", result instanceof DiagnosticsSnapshotInvalid);
                assertTrue("Unknown diagnostic chunk result: " + result, result instanceof DiagnosticsChunkDto);
                DiagnosticsChunkDto chunk = (DiagnosticsChunkDto) result;
                byte[] bytes = chunk.getBytes();
                assertTrue(
                        "Diagnostic chunk exceeds protocol limit: " + bytes.length,
                        bytes.length <= DiagnosticsCommandsKt.DIAGNOSTICS_CHUNK_BYTES);
                assertFalse("Non-terminal diagnostic chunk was empty", bytes.length == 0 && !chunk.getEof());
                assertTrue(
                        "Diagnostic bytes exceed declared snapshot length",
                        offset + bytes.length <= session.getTotalLength());
                offset += bytes.length;
                if (chunk.getEof()) {
                    eof = true;
                    break;
                }
            }

            assertTrue("Diagnostic snapshot did not reach EOF within its bound", eof);
            assertEquals("Diagnostic snapshot length differed from declaration", session.getTotalLength(), offset);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                requireSuccessful(
                        Bridge.INSTANCE.inProfile(context, target)
                                .execute(new CloseDiagnosticsSnapshot(session.getToken())),
                        "close diagnostics snapshot");
            } catch (Throwable cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static Map<String, ProfileAppEntry> queryAllProfileApps() {
        Context context = targetContext();
        ProfileTarget target = requireProfileTarget();
        Map<String, ProfileAppEntry> apps = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();

        for (int pageIndex = 0; pageIndex < MAX_APP_PAGES; pageIndex++) {
            ProfileAppPage page = requireValue(
                    Bridge.INSTANCE.inProfile(context, target).execute(
                            new QueryProfileAppsPage(pageIndex, FileBridgeCommandsKt.MAX_PROFILE_APP_PAGE_SIZE)),
                    "query profile app page " + pageIndex);
            List<ProfileAppEntry> entries = page.getEntries();
            assertTrue(
                    "Profile app page exceeds handler maximum: " + entries.size(),
                    entries.size() <= FileBridgeCommandsKt.MAX_PROFILE_APP_PAGE_SIZE);
            for (ProfileAppEntry entry : entries) {
                int count = occurrences.containsKey(entry.getPackageName())
                        ? occurrences.get(entry.getPackageName()) + 1
                        : 1;
                occurrences.put(entry.getPackageName(), count);
                assertEquals("Duplicate profile package " + entry.getPackageName(), 1, count);
                apps.put(entry.getPackageName(), entry);
            }
            if (!page.getHasMore()) {
                return apps;
            }
        }

        fail("Profile app pagination exceeded " + MAX_APP_PAGES + " pages");
        return apps;
    }

    private static ProfileAppEntry requireApp(Map<String, ProfileAppEntry> apps, String packageName) {
        ProfileAppEntry app = apps.get(packageName);
        assertNotNull("Missing profile app " + packageName, app);
        return app;
    }

    private static void assertFrozenStateObserved(
            Map<String, ProfileAppEntry> apps,
            String packageName,
            boolean expectedFrozen,
            String phase) {
        ProfileAppEntry app = apps.get(packageName);
        if (expectedFrozen) {
            // HyperOS removes DPM-hidden packages from PackageManager enumeration even with
            // MATCH_UNINSTALLED_PACKAGES. Either absence or an explicit hidden flag proves the
            // frozen state; a visible unhidden entry does not.
            assertTrue(
                    "Profile app remained visible and unfrozen " + phase,
                    app == null || app.getHidden());
        } else {
            assertNotNull("Profile app did not reappear " + phase, app);
            assertFalse("Profile app remained hidden " + phase, app.getHidden());
        }
    }

    private static ProfileTarget requireProfileTarget() {
        ProfileTarget target = BridgeTargets.INSTANCE.profile();
        assertNotNull("No validated PrismSpace profile target", target);
        return target;
    }

    private static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @SuppressWarnings("unchecked")
    private static <T> T requireValue(ShuttleOutcome<? extends T> outcome, String operation) {
        requireSuccessful(outcome, operation);
        Object value = ((ShuttleOutcome.Value<?>) outcome).getValue();
        assertNotNull(operation + " returned null", value);
        return (T) value;
    }

    private static void requireSuccessful(ShuttleOutcome<?> outcome, String operation) {
        assertTrue(
                operation + " failed: " + outcome.diagnosticValue(),
                outcome instanceof ShuttleOutcome.Value);
    }
}
