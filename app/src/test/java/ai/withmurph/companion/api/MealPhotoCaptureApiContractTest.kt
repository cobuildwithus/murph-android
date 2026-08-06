package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.MealPhotoCaptureEnrollmentRequest
import ai.withmurph.companion.core.MealPhotoCaptureRevocationRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class MealPhotoCaptureApiContractTest {
    @Test
    fun schemaV2IdentityBodiesCarryThePositiveAuthorityRevision() {
        val enrollment = MealPhotoCaptureApiContract.enrollmentBody(
            MealPhotoCaptureEnrollmentRequest(
                appInstallationId = INSTALLATION_ID,
                appVersion = "0.1.0",
                authorityRevision = 7,
            ),
        )
        val revocation = MealPhotoCaptureApiContract.revocationBody(
            MealPhotoCaptureRevocationRequest(
                appInstallationId = INSTALLATION_ID,
                authorityRevision = 8,
            ),
        )

        assertEquals(setOf("schemaVersion", "appInstallationId", "appVersion", "authorityRevision"), enrollment.keys)
        assertEquals(2, enrollment["schemaVersion"])
        assertEquals(7L, enrollment["authorityRevision"])
        assertEquals(setOf("schemaVersion", "appInstallationId", "authorityRevision"), revocation.keys)
        assertEquals(2, revocation["schemaVersion"])
        assertEquals(8L, revocation["authorityRevision"])
    }

    @Test
    fun parsesOnlyStrictScopedEnrollmentCredentials() {
        val parsed = MealPhotoCaptureApiContract.parseEnrollment(
            uploadToken = "murph_meal_photo_${"A".repeat(43)}",
            idempotencySecret = "B".repeat(43),
            expiresAt = "2026-09-01T12:00:00Z",
        )

        assertEquals("murph_meal_photo_${"A".repeat(43)}", parsed.uploadToken)
        assertEquals("B".repeat(43), parsed.idempotencySecret)
        assertEquals(Instant.parse("2026-09-01T12:00:00Z"), parsed.expiresAt)

        listOf<() -> Unit>(
            { parse(uploadToken = "identity-token") },
            { parse(idempotencySecret = "short") },
            { parse(expiresAt = "tomorrow") },
            { parse(uploadToken = null) },
            { parse(expiresAt = 1_700_000_000) },
        ).forEach(::assertInvalid)
    }

    @Test
    fun parsesOnlyStrictRevocationBooleans() {
        assertEquals(true, MealPhotoCaptureApiContract.parseRevocation(true))
        assertEquals(false, MealPhotoCaptureApiContract.parseRevocation(false))

        listOf<Any?>(null, "true", 1, JSONObject.NULL).forEach { value ->
            assertInvalid { MealPhotoCaptureApiContract.parseRevocation(value) }
        }
    }

    private fun parse(
        uploadToken: Any? = "murph_meal_photo_${"A".repeat(43)}",
        idempotencySecret: Any? = "B".repeat(43),
        expiresAt: Any? = "2026-09-01T12:00:00Z",
    ) {
        MealPhotoCaptureApiContract.parseEnrollment(
            uploadToken,
            idempotencySecret,
            expiresAt,
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

    private companion object {
        const val INSTALLATION_ID = "00000000-0000-4000-8000-000000000001"
    }
}
