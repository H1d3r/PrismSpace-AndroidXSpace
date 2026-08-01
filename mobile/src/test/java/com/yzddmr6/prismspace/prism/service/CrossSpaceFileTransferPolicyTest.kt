package com.yzddmr6.prismspace.prism.service

import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CrossSpaceFileTransferPolicyTest {
    @Test fun imageAndDocumentDestinationsAreDeterministic() {
        assertEquals("Pictures/PrismSpace/", CrossSpaceFileTransferPolicy.destination("image/png").relativePath)
        assertEquals("Download/PrismSpace/", CrossSpaceFileTransferPolicy.destination("application/pdf").relativePath)
        assertFalse(CrossSpaceFileTransferPolicy.destination(null).isImage)
    }

    @Test fun bridgeFailuresCollapseToFourUserRelevantClasses() {
        assertEquals(FileTransferFailureReason.SpaceUnavailable, crossSpaceFailureReason(ProfileBridgeResult.SpaceMissing))
        assertEquals(FileTransferFailureReason.SpaceUnavailable, crossSpaceFailureReason(ProfileBridgeResult.SpaceInactive("quiet")))
        assertEquals(FileTransferFailureReason.BridgeNotReady, crossSpaceFailureReason(
            ProfileBridgeResult.BridgeNotReady(ShuttleNotReadyCause.PermissionDenied)))
        assertEquals(FileTransferFailureReason.BridgeNotReady, crossSpaceFailureReason(ProfileBridgeResult.TimedOut))
        assertEquals(FileTransferFailureReason.TargetWriteFailed, crossSpaceFailureReason(
            ProfileBridgeResult.Failed(IllegalStateException())))
    }

    @Test fun directionWireFormatIsBackwardCompatible() {
        assertNull(TransferDirection.fromWireValue(null))
        assertNull(TransferDirection.fromWireValue(""))
        assertEquals(TransferDirection.ToMain, TransferDirection.fromWireValue("toMain"))
        assertEquals(TransferDirection.ToProfile, TransferDirection.fromWireValue("toProfile"))
    }

    @Test fun blockCopyReportsProgress() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var lastProgress = 0L
        val copied = copyCancellable(ByteArrayInputStream(bytes), output, TransferCancellationSignal()) {
            lastProgress = it
        }
        assertEquals(bytes.size.toLong(), copied)
        assertEquals(copied, lastProgress)
        assertEquals(bytes.toList(), output.toByteArray().toList())
    }
}
