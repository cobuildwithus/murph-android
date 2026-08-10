package ai.withmurph.companion.health

import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthPermissionRequestResult
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
    fun optionalWorkoutDetailsRequireTheWorkoutBasePermission() {
        assertEquals(
            HealthPermissionRequestResult.MissingWorkoutBase,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Sleep),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SLEEP",
                    "android.permission.health.READ_POWER",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Workout),
                grantedPermissions = setOf(
                    "android.permission.health.READ_EXERCISE",
                    "android.permission.health.READ_ELEVATION_GAINED",
                    "android.permission.health.READ_POWER",
                    "android.permission.health.READ_SPEED",
                ),
            ),
        )
    }

    @Test
    fun optionalMenstrualDetailsRequireTheMenstruationBasePermission() {
        assertEquals(
            HealthPermissionRequestResult.MissingMenstrualBase,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Activity),
                grantedPermissions = setOf(
                    "android.permission.health.READ_STEPS",
                    "android.permission.health.READ_SEXUAL_ACTIVITY",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.MenstrualCycle),
                grantedPermissions = setOf(
                    "android.permission.health.READ_MENSTRUATION",
                    "android.permission.health.READ_CERVICAL_MUCUS",
                    "android.permission.health.READ_INTERMENSTRUAL_BLEEDING",
                    "android.permission.health.READ_OVULATION_TEST",
                    "android.permission.health.READ_SEXUAL_ACTIVITY",
                ),
            ),
        )
    }

    @Test
    fun bothMissingBasesWinOverAnUnrelatedActiveResource() {
        assertEquals(
            HealthPermissionRequestResult.MissingWorkoutAndMenstrualBases,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Sleep),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SLEEP",
                    "android.permission.health.READ_SPEED",
                    "android.permission.health.READ_OVULATION_TEST",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.NoActiveResource,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = emptySet(),
            ),
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
