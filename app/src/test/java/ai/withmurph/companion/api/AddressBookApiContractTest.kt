package ai.withmurph.companion.api

import ai.withmurph.companion.core.AddressBookDeletionRequest
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.AddressBookProjection
import ai.withmurph.companion.core.AddressBookReplacementRequest
import ai.withmurph.companion.core.AddressBookServerStatus
import ai.withmurph.companion.core.AddressBookWriteCapability
import ai.withmurph.companion.core.CompanionApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AddressBookApiContractTest {
    @Test
    fun parsesOnlySupportedStrictServerStatusValues() {
        assertEquals(
            AddressBookServerStatus(
                writeCapability = AddressBookWriteCapability.Enabled,
                enabled = true,
                revision = 7,
                storedContactCount = 12,
            ),
            AddressBookApiContract.parseStatus(
                schemaVersion = 1,
                writeCapability = "enabled",
                enabled = true,
                revision = 7L,
                storedContactCount = 12,
            ),
        )

        listOf<() -> Unit>(
            { parse(schemaVersion = 2) },
            { parse(writeCapability = "future") },
            { parse(enabled = "true") },
            { parse(revision = 1.0) },
            { parse(revision = -1) },
            { parse(revision = Long.MAX_VALUE) },
            { parse(storedContactCount = 1_001) },
            { parse(enabled = false, storedContactCount = 1) },
            { parse(schemaVersion = null) },
        ).forEach(::assertInvalidResponse)
    }

    @Test
    fun buildsExactReplacementAndDeletionBodies() {
        val mutation = AddressBookMutation(4, MUTATION_ONE)
        val replacement = AddressBookReplacementRequest(
            mutation = mutation,
            contacts = listOf(
                AddressBookProjection("+12125550101", "Anna S."),
                AddressBookProjection("+442079460018", "Ben J."),
            ),
        )

        assertEquals(
            mapOf(
                "schemaVersion" to 1,
                "baseRevision" to 4,
                "mutationId" to MUTATION_ONE,
                "contacts" to listOf(
                    mapOf("phoneNumber" to "+12125550101", "advisoryName" to "Anna S."),
                    mapOf("phoneNumber" to "+442079460018", "advisoryName" to "Ben J."),
                ),
            ),
            AddressBookApiContract.replacementBody(replacement),
        )
        assertEquals(
            mapOf(
                "schemaVersion" to 1,
                "baseRevision" to 4,
                "mutationId" to MUTATION_ONE,
            ),
            AddressBookApiContract.deletionBody(AddressBookDeletionRequest(mutation)),
        )

    }

    @Test
    fun acceptsReplacementReplayResultEvenWhenFreshContactCountDiffers() {
        val request = AddressBookReplacementRequest(
            mutation = AddressBookMutation(8, MUTATION_ONE),
            contacts = listOf(
                AddressBookProjection("+12125550101", "Anna S."),
                AddressBookProjection("+12125550102", "Ben J."),
            ),
        )
        val replay = status(enabled = true, revision = 9, count = 1)

        assertSame(replay, AddressBookApiContract.validateReplacementResponse(request, replay))
        assertInvalidResponse {
            AddressBookApiContract.validateReplacementResponse(
                request,
                status(enabled = false, revision = 9, count = 0),
            )
        }
        assertInvalidResponse {
            AddressBookApiContract.validateReplacementResponse(
                request,
                status(enabled = true, revision = 8, count = 2),
            )
        }
    }

    @Test
    fun validatesDeletionResponseAndScopesRevisionConflicts() {
        val request = AddressBookDeletionRequest(AddressBookMutation(9, MUTATION_ONE))
        val deleted = status(enabled = false, revision = 10, count = 0)
        assertSame(deleted, AddressBookApiContract.validateDeletionResponse(request, deleted))

        assertInvalidResponse {
            AddressBookApiContract.validateDeletionResponse(
                request,
                status(enabled = true, revision = 10, count = 1),
            )
        }
        assertInvalidResponse {
            AddressBookApiContract.validateDeletionResponse(
                request,
                status(enabled = false, revision = 9, count = 0),
            )
        }

        assertSame(
            CompanionApiException.Conflict,
            mapCompanionApiErrorCode(409, errorCode = null, revisionConflict = true),
        )
        assertEquals(
            CompanionApiException.Server(409),
            mapCompanionApiErrorCode(409, errorCode = null, revisionConflict = false),
        )
        assertSame(
            CompanionApiException.Conflict,
            mapCompanionApiErrorCode(
                409,
                errorCode = "SDK_SIGN_IN_RECONNECT_REQUIRED",
                revisionConflict = true,
            ),
        )
        assertSame(
            CompanionApiException.ReconnectRequired,
            mapCompanionApiErrorCode(
                409,
                errorCode = "SDK_SIGN_IN_RECONNECT_REQUIRED",
                revisionConflict = false,
            ),
        )
        assertSame(
            CompanionApiException.ConsentRequired,
            mapCompanionApiErrorCode(403, "HOSTED_CONSENT_REQUIRED"),
        )
        assertSame(
            CompanionApiException.NoAccount,
            mapCompanionApiErrorCode(403, "HOSTED_MEMBER_NOT_FOUND"),
        )
        assertSame(
            CompanionApiException.Unauthorized,
            mapCompanionApiErrorCode(401, null),
        )
    }

    private fun parse(
        schemaVersion: Any? = 1,
        writeCapability: Any? = "enabled",
        enabled: Any? = true,
        revision: Any? = 1,
        storedContactCount: Any? = 1,
    ) {
        AddressBookApiContract.parseStatus(
            schemaVersion,
            writeCapability,
            enabled,
            revision,
            storedContactCount,
        )
    }

    private fun status(
        enabled: Boolean,
        revision: Int,
        count: Int,
    ) = AddressBookServerStatus(
        writeCapability = AddressBookWriteCapability.Enabled,
        enabled = enabled,
        revision = revision,
        storedContactCount = count,
    )

    private fun assertInvalidResponse(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid response")
        } catch (error: CompanionApiException.InvalidResponse) {
            // Expected.
        }
    }

    private companion object {
        const val MUTATION_ONE = "00000000-0000-4000-8000-000000000001"
    }
}
