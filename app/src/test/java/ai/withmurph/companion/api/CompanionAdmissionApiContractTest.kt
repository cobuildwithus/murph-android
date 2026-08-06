package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CompanionAdmissionApiContractTest {
    @Test
    fun acceptsOnlyTheExactPositiveAdmissionResponse() {
        CompanionAdmissionApiContract.validateResponse(setOf("ok"), true)

        listOf(
            emptySet(),
            setOf("ok", "environment"),
        ).forEach { keys ->
            assertInvalid { CompanionAdmissionApiContract.validateResponse(keys, true) }
        }
        listOf<Any?>(false, "true", 1, null).forEach { value ->
            assertInvalid { CompanionAdmissionApiContract.validateResponse(setOf("ok"), value) }
        }
    }

    @Test
    fun mapsOnlyThePublishedAdmissionRecoveryCodes() {
        assertSame(
            CompanionApiException.AccessRequired,
            mapCompanionApiErrorCode(403, "HOSTED_ACCESS_REQUIRED"),
        )
        assertSame(
            CompanionApiException.MemberSuspended,
            mapCompanionApiErrorCode(403, "HOSTED_MEMBER_SUSPENDED"),
        )
        assertSame(
            CompanionApiException.AdmissionRetryable,
            mapCompanionApiErrorCode(503, "COMPANION_ADMISSION_RETRYABLE"),
        )
        assertSame(
            CompanionApiException.AdmissionSupportRequired,
            mapCompanionApiErrorCode(409, "COMPANION_ADMISSION_SUPPORT_REQUIRED"),
        )

        assertEquals(
            CompanionApiException.Server(403),
            mapCompanionApiErrorCode(403, "HOSTED_ACCESS_UNKNOWN"),
        )
        assertEquals(
            CompanionApiException.Server(503),
            mapCompanionApiErrorCode(503, "COMPANION_ADMISSION_UNKNOWN"),
        )
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid response")
        } catch (_: CompanionApiException.InvalidResponse) {
            // Expected.
        }
    }
}
