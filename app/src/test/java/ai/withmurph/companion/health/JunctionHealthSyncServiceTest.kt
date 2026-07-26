package ai.withmurph.companion.health

import ai.withmurph.companion.core.HealthConnectAvailability
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class JunctionHealthSyncServiceTest {
    @Test
    fun providerAvailabilityPreservesTheRecoveryOwner() {
        val expected = mapOf(
            ProviderAvailability.Installed to HealthConnectAvailability.Available,
            ProviderAvailability.NotInstalled to HealthConnectAvailability.InstallOrUpdateRequired,
            ProviderAvailability.OnboardingIncomplete to HealthConnectAvailability.OnboardingRequired,
            ProviderAvailability.AppNotAllowed to HealthConnectAvailability.AppNotAllowed,
            ProviderAvailability.ServiceUnavailable to HealthConnectAvailability.TemporarilyUnavailable,
            ProviderAvailability.NotSupportedSDK to HealthConnectAvailability.Unsupported,
        )

        expected.forEach { (provider, app) ->
            assertEquals(app, provider.toAppAvailability())
        }
    }
}
