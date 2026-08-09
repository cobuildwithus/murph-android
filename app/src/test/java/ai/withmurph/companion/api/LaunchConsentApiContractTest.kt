package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

class LaunchConsentApiContractTest {
    @Test
    fun parsesStrictLaunchConsentStatusAndResolvesSameOriginLinks() {
        val status = LaunchConsentApiContract.parseStatus(
            schema = "murph.hosted-consent-status.v1",
            launchGranted = false,
            documents = listOf(
                document("legal", "Terms", "2026-07-01", "/legal/terms"),
                document("health", "Health Notice", "2026-07-01", "notices/health"),
            ),
            launchScopes = listOf(
                scope("launch.legal", granted = true, missing = emptyList()),
                scope(
                    "launch.health-data",
                    granted = false,
                    missing = listOf(
                        document("health", "Health Notice", "2026-07-01", "notices/health"),
                    ),
                ),
            ),
            scopes = listOf(
                scope(
                    "launch.legal",
                    granted = true,
                    missing = emptyList(),
                    documents = listOf(
                        document("legal", "Terms", "2026-07-01", "/legal/terms"),
                    ),
                ),
                scope(
                    "launch.health-data",
                    granted = false,
                    missing = listOf(
                        document("health", "Health Notice", "2026-07-01", "notices/health"),
                    ),
                ),
            ),
            backendOrigin = ORIGIN,
        )

        assertEquals(false, status.launchGranted)
        assertEquals(
            "https://www.withmurph.ai/legal/terms",
            status.documents.first().href,
        )
        assertEquals(
            "https://www.withmurph.ai/notices/health",
            status.missingLaunchScopes.single().missingDocuments.single().href,
        )
    }

    @Test
    fun rejectsInvalidStatusShapesAndUnsafeLinks() {
        listOf<() -> Unit>(
            { parse(schema = "future") },
            { parse(launchGranted = "false") },
            {
                parse(
                    launchScopes = listOf(scope("launch.legal"), scope("launch.legal")),
                )
            },
            {
                parse(
                    launchScopes = listOf(scope("launch.legal"), scope("launch.future")),
                )
            },
            {
                parse(
                    launchGranted = true,
                    launchScopes = listOf(
                        scope("launch.legal", granted = true, missing = emptyList()),
                        scope("launch.health-data", granted = false),
                    ),
                )
            },
            {
                parse(
                    launchScopes = listOf(
                        scope("launch.legal", granted = true, missing = listOf(document())),
                        scope("launch.health-data", granted = false),
                    ),
                )
            },
            {
                parse(documents = listOf(document(), document(title = "Duplicate")))
            },
            {
                parse(
                    launchScopes = listOf(
                        scope(
                            "launch.legal",
                            missing = listOf(document(id = "unknown")),
                        ),
                        scope("launch.health-data"),
                    ),
                )
            },
            {
                parse(
                    launchScopes = listOf(
                        scope(
                            "launch.legal",
                            missing = listOf(document(version = "2026-07-02")),
                        ),
                        scope("launch.health-data"),
                    ),
                )
            },
            {
                parse(
                    launchScopes = listOf(
                        scope(
                            "launch.legal",
                            missing = listOf(document(), document()),
                        ),
                        scope("launch.health-data"),
                    ),
                )
            },
            { parse(documents = listOf(document(href = "http://www.withmurph.ai/legal"))) },
            { parse(documents = listOf(document(href = "https://example.com/legal"))) },
            { parse(documents = listOf(document(href = "https://www.withmurph.ai:444/legal"))) },
            { parse(documents = listOf(document(href = "https://user@www.withmurph.ai/legal"))) },
            { parse(documents = listOf(document(href = "mailto:support@withmurph.ai"))) },
        ).forEach(::assertInvalidResponse)
    }

    @Test
    fun buildsExactAcceptanceBody() {
        assertEquals(
            mapOf(
                "scope" to "launch.health-data",
                "acceptedDocumentVersions" to mapOf(
                    "health" to "2026-07-01",
                    "privacy" to "2026-07-02",
                ),
                "source" to "android-companion",
            ),
            LaunchConsentApiContract.acceptanceBody(
                LaunchConsentAcceptanceRequest(
                    scope = LaunchConsentScope.HealthData,
                    acceptedDocumentVersions = mapOf(
                        "health" to "2026-07-01",
                        "privacy" to "2026-07-02",
                    ),
                ),
            ),
        )
    }

    @Test
    fun mapsOnlyCanonicalStaleConsentDocumentCodeToReloadException() {
        // Local JVM tests use Android's stubbed JSONObject. Exercise the pure raw-code
        // contract that production calls after reading the nested error.code value.
        assertSame(
            CompanionApiException.StaleConsentDocuments,
            mapCompanionApiErrorCode(
                status = 409,
                errorCode = normalizeCompanionApiErrorCode(
                    "CONSENT_DOCUMENT_VERSIONS_STALE",
                ),
            ),
        )
        listOf(
            "HOSTED_CONSENT_DOCUMENT_VERSION_STALE",
            "HOSTED_CONSENT_DOCUMENT_STALE",
            "HOSTED_CONSENT_STALE_DOCUMENT",
            "HOSTED_CONSENT_REQUIRED",
        ).forEach { inventedCode ->
            assertEquals(
                CompanionApiException.Server(409),
                mapCompanionApiErrorCode(
                    409,
                    normalizeCompanionApiErrorCode(inventedCode),
                ),
            )
        }
        listOf<Any?>(123, true, "", "   ", null).forEach { malformedCode ->
            assertEquals(
                CompanionApiException.Server(409),
                mapCompanionApiErrorCode(
                    409,
                    normalizeCompanionApiErrorCode(malformedCode),
                ),
            )
        }
    }

    @Test
    fun mapsOnlyCanonicalAccountConflictCodes() {
        listOf("PRIVY_IDENTITY_CONFLICT", "PRIVY_USER_MISMATCH").forEach { code ->
            assertSame(
                CompanionApiException.AccountConflict,
                mapCompanionApiErrorCode(409, code),
            )
            assertSame(
                CompanionApiException.AccountConflict,
                mapCompanionApiErrorCode(409, code, revisionConflict = true),
            )
        }
        assertEquals(
            CompanionApiException.Server(409),
            mapCompanionApiErrorCode(409, "PRIVY_CONFLICT"),
        )
    }

    private fun parse(
        schema: Any? = "murph.hosted-consent-status.v1",
        launchGranted: Any? = false,
        documents: List<Map<String, Any?>> = listOf(document()),
        launchScopes: List<Map<String, Any?>> = listOf(
            scope("launch.legal"),
            scope("launch.health-data"),
        ),
        scopes: List<Map<String, Any?>> = launchScopes,
    ) {
        LaunchConsentApiContract.parseStatus(
            schema = schema,
            launchGranted = launchGranted,
            documents = documents,
            launchScopes = launchScopes,
            scopes = scopes,
            backendOrigin = ORIGIN,
        )
    }

    private fun document(
        id: String = "terms",
        title: String = "Terms",
        version: String = "2026-07-01",
        href: String = "/legal/terms",
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "version" to version,
        "href" to href,
        "pdfHref" to null,
    )

    private fun scope(
        value: String,
        granted: Boolean = false,
        missing: List<Map<String, Any?>> = listOf(document()),
        documents: List<Map<String, Any?>> = if (missing.isEmpty()) {
            listOf(document())
        } else {
            missing
        },
    ): Map<String, Any?> = mapOf(
        "scope" to value,
        "granted" to granted,
        "documents" to documents,
        "missingDocuments" to missing,
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
        val ORIGIN: URI = URI("https://www.withmurph.ai")
    }
}
