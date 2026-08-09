package ai.withmurph.companion.health

import ai.withmurph.companion.core.HealthConnectAvailability
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import org.junit.Assert.assertEquals
import org.junit.Test

class JunctionHealthSyncServiceTest {
    @Test
    fun readResourcesCoverTheCompletePinnedVitalHealthConnectSurface() {
        val expected = VitalResource.values().map { it.name }.toSet()
        val actual = healthConnectReadResources.map { it.name }.toSet()

        assertEquals(expected, actual)
        assertEquals(21, actual.size)
    }

    @Test
    fun configuredResourcesPreserveOnlyTheGrantedReviewedSubset() {
        val granted = setOf(
            VitalResource.Sleep,
            VitalResource.BloodPressure,
            VitalResource.Meal,
        )

        assertEquals(granted, configuredHealthConnectReadResources(granted))
        assertEquals(
            emptySet<VitalResource>(),
            configuredHealthConnectReadResources(emptySet()),
        )
    }

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
