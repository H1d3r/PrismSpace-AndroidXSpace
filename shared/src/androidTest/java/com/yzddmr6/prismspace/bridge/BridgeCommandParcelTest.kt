package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        val writePipe = ParcelFileDescriptor.createPipe()
        val readPipe = ParcelFileDescriptor.createPipe()
        try {
            assertRoundTrip(
                OpenWriteSession(BridgeFileStore.Downloads, "name", "type", "path"),
                WriteSessionDto("content://write", writePipe[1]),
            )
            assertRoundTrip(
                FinishWriteSession(
                    BridgeFileStore.Media,
                    "content://write",
                    TransferHistoryDto("name", "Pictures", true, BridgeTransferDirection.ToProfile),
                ),
                "content://write",
            )
            assertRoundTrip(AbortWriteSession(BridgeFileStore.Media, "content://write"), Unit)
            assertRoundTrip(
                ImportApkSet(listOf("/base.apk", "/split.apk"), "Label", "pkg", "Downloads"),
                "content://apk",
            )
            assertRoundTrip(
                QueryLatestVisibleImage,
                ProfileMediaEntryDto("image.jpg", "image/jpeg", "content://image"),
            )
            assertRoundTrip(OpenImagePickerInProfile, true)
            assertRoundTrip(
                OpenLatestForRead(BridgeFileStore.Downloads),
                ReadSessionDto("name", "type", readPipe[0]),
            )
            assertRoundTrip(WritePerAppShareMarker("pkg"), "content://marker")
            assertRoundTrip(DeletePerAppShareMarker("pkg"), true)
            assertRoundTrip(RunBridgeSelfTest(byteArrayOf(1, 2)), SelfTestResultDto(byteArrayOf(2, 1), "location"))
            assertRoundTrip(
                InstallCrossProfileForwarding(CrossProfileForwardingKind.ProfileDownloads),
                true,
            )
            assertRoundTrip(
                QueryProfileAppsPage(1, Int.MAX_VALUE),
                ProfileAppPage(listOf(sampleProfileApp()), hasMore = false),
            )
            assertRoundTrip(RequestPinShortcutInProfile("pkg", true), true)
            assertRoundTrip(UpdateAllShortcutsInProfile(false), true)
            assertRoundTrip(RemoveShortcutsInParent("pkg", 10), true)
            assertRoundTrip(RefreshShortcutInParent("pkg", 10), false)
            assertRoundTrip(QueryDynamicShortcutLabelEnabled, true)
            assertRoundTrip(
                QueryProfileProvisioningFacts,
                ProfileProvisioningFactsDto(profileOwner = true, provisionComplete = false),
            )
            assertRoundTrip(TriggerIncrementalProvisioning, true)
            assertRoundTrip(WipeProfile, false)
            assertRoundTrip(QueryParentIsProfileOwner, true)
            assertRoundTrip(SaveProfileName(10, "Work"), true)
            assertRoundTrip(EstablishBackwardGrant, Unit)
            assertRoundTrip(SetAppOpMode("pkg", 1, 2, 1_000_001), Unit)
            assertRoundTrip(NotifyPackageRestarted("pkg", 1_000_001, 123L), true)
            assertRoundTrip(StartProfileDeactivation(10), Unit)
            assertRoundTrip(
                UnfreezeAndLaunchApp("pkg"),
                LaunchOutcomeDto(LaunchOutcomeKind.Unknown, "reason"),
            )
            assertRoundTrip(LaunchAppInProfile("pkg", true), true)
            assertRoundTrip(OpenAppDetailsInProfile("pkg"), Unit)
        } finally {
            writePipe.forEach(ParcelFileDescriptor::close)
            readPipe.forEach(ParcelFileDescriptor::close)
        }
    }

    private fun <R> assertRoundTrip(command: BridgeCommand<R>, result: R) {
        val restoredCommand = roundTripParcelable(command)
        assertEquals(command.id, restoredCommand.id)
        if (command is RunBridgeSelfTest && restoredCommand is RunBridgeSelfTest) {
            assertArrayEquals(command.marker, restoredCommand.marker)
        } else {
            assertEquals(command, restoredCommand)
        }

        val encoded = Bundle().also { command.encodeResult(result, it) }
        val restoredBundle = roundTripBundle(encoded)
        val decoded = command.decodeResult(restoredBundle)
        assertResultEquals(result, decoded)
    }

    private fun <T : android.os.Parcelable> roundTripParcelable(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            parcel.readParcelable<T>(BridgeCommand::class.java.classLoader) as T
        } finally {
            parcel.recycle()
        }
    }

    private fun roundTripBundle(value: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(value)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            requireNotNull(parcel.readBundle(BridgeCommand::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun assertResultEquals(expected: Any?, actual: Any?) {
        when {
            expected is Array<*> && actual is Array<*> -> assertArrayEquals(expected, actual)
            expected is SelfTestResultDto && actual is SelfTestResultDto -> {
                assertArrayEquals(expected.bytes, actual.bytes)
                assertEquals(expected.location, actual.location)
            }
            expected is WriteSessionDto && actual is WriteSessionDto -> {
                assertEquals(expected.uri, actual.uri)
                assertNotNull(actual.descriptor)
                actual.descriptor.close()
            }
            expected is ReadSessionDto && actual is ReadSessionDto -> {
                assertEquals(expected.displayName, actual.displayName)
                assertEquals(expected.mimeType, actual.mimeType)
                assertNotNull(actual.descriptor)
                actual.descriptor.close()
            }
            else -> assertEquals(expected, actual)
        }
    }

    private fun sampleProfileApp() = ProfileAppEntry(
        packageName = "pkg",
        uid = 1,
        flags = 2,
        hidden = true,
        enabled = false,
        targetSdkVersion = 35,
        label = "Label",
        iconResource = 3,
        sourceDir = "/base.apk",
        publicSourceDir = "/base.apk",
        splitSourceDirs = listOf("/split.apk"),
    )
}
