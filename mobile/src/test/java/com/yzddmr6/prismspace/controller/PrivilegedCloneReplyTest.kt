package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCloneReplyTest {

    @Test fun structuredReasonKeepsExceptionTypeAndSanitizedMessage() {
        val reply = PrivilegedCloneReply(-1, "java.lang.SecurityException", " denied\nfor user ")

        assertEquals("SecurityException: denied for user", reply.userFacingReason())
    }

    @Test fun missingRemoteDetailsFallsBackToStableResultCode() {
        assertEquals("result_0", PrivilegedCloneReply(0, null, null).userFacingReason())
    }

    @Test fun remoteMessageIsBoundedForUserFacingFeedback() {
        val reason = PrivilegedCloneReply(-1, null, "x".repeat(500)).userFacingReason()

        assertTrue(reason.length <= 120)
    }
}
