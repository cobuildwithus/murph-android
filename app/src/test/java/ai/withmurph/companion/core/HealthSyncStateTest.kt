package ai.withmurph.companion.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSyncStateTest {
    @Test
    fun partialFailureRequiresANewerReceiptForEveryFailedResource() {
        val floor = Instant.parse("2026-07-25T18:00:00Z")
        val pending = PendingHealthSyncFailure(
            resourceKeys = setOf("activity", "sleep"),
            receiptFloorAt = InstantValue(floor.toEpochMilli()),
        )

        assertFalse(
            pending.isConfirmedBy(
                CompanionSyncStatus(
                    lastDataReceivedAt = floor.plusSeconds(60),
                    observedAt = floor.plusSeconds(120),
                    resources = mapOf(
                        "sleep" to CompanionSyncStatus.ResourceStatus(floor.plusSeconds(60)),
                    ),
                ),
            ),
        )
        assertTrue(
            pending.isConfirmedBy(
                CompanionSyncStatus(
                    lastDataReceivedAt = floor.plusSeconds(60),
                    observedAt = floor.plusSeconds(120),
                    resources = mapOf(
                        "activity" to CompanionSyncStatus.ResourceStatus(floor.plusSeconds(1)),
                        "sleep" to CompanionSyncStatus.ResourceStatus(floor.plusSeconds(60)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun partialFailuresMergeOnlyEachOwnersNewestFloorAndRetainGrantedResources() {
        val first = PendingHealthSyncFailure(
            resourceKeys = setOf("activity", "sleep"),
            receiptFloorAt = InstantValue(100),
        )
        val merged = first.mergedWith(
            PendingHealthSyncFailure(
                resourceKeys = setOf("body"),
                receiptFloorAt = InstantValue(200),
            ),
        )

        assertEquals(setOf("activity", "sleep", "body"), merged.resourceKeys)
        assertEquals(
            mapOf(
                "activity" to InstantValue(100),
                "sleep" to InstantValue(100),
                "body" to InstantValue(200),
            ),
            merged.receiptFloorsByResource,
        )
        assertEquals(setOf("sleep"), merged.retainingGranted(setOf("sleep"))?.resourceKeys)
        assertEquals(null, merged.retainingGranted(emptySet()))
    }

    @Test
    fun laterFailureDoesNotResurrectAnOwnerAlreadyConfirmedByBackendEvidence() {
        val firstFloor = Instant.parse("2026-07-25T18:00:00Z")
        val bodyReceipt = firstFloor.plusSeconds(60)
        val secondFloor = firstFloor.plusSeconds(120)
        val sleepReceipt = secondFloor.plusSeconds(60)
        val firstFailure = PendingHealthSyncFailure(
            setOf("body", "sleep"),
            InstantValue(firstFloor.toEpochMilli()),
        )

        val afterBodyConfirmation = firstFailure.retainingUnconfirmed(
            CompanionSyncStatus(
                bodyReceipt,
                secondFloor,
                mapOf("body" to CompanionSyncStatus.ResourceStatus(bodyReceipt)),
            ),
        )

        assertEquals(setOf("sleep"), afterBodyConfirmation?.resourceKeys)
        val afterLaterSleepFailure = requireNotNull(afterBodyConfirmation).mergedWith(
            PendingHealthSyncFailure(
                setOf("sleep"),
                InstantValue(secondFloor.toEpochMilli()),
            ),
        )
        assertEquals(
            mapOf("sleep" to InstantValue(secondFloor.toEpochMilli())),
            afterLaterSleepFailure.receiptFloorsByResource,
        )
        assertEquals(
            null,
            afterLaterSleepFailure.retainingUnconfirmed(
                CompanionSyncStatus(
                    sleepReceipt,
                    sleepReceipt.plusSeconds(60),
                    mapOf("sleep" to CompanionSyncStatus.ResourceStatus(sleepReceipt)),
                ),
            ),
        )
    }

    @Test
    fun unknownFailureCannotBeConfirmedByAnUnrelatedSourceWideReceipt() {
        val floor = Instant.parse("2026-07-25T18:00:00Z")
        val pending = PendingHealthSyncFailure(
            resourceKeys = setOf(UNKNOWN_HEALTH_RESOURCE_KEY),
            receiptFloorAt = InstantValue(floor.toEpochMilli()),
        )

        assertFalse(
            pending.isConfirmedBy(
                CompanionSyncStatus(null, floor.plusSeconds(60), emptyMap()),
            ),
        )
        assertFalse(
            pending.isConfirmedBy(
                CompanionSyncStatus(
                    floor.plusSeconds(1),
                    floor.plusSeconds(60),
                    emptyMap(),
                ),
            ),
        )
    }

    @Test
    fun temperatureOwnerIsConfirmedByEitherBackendSubtypeButNotAnotherResource() {
        val floor = Instant.parse("2026-07-25T18:00:00Z")
        val pending = PendingHealthSyncFailure(
            resourceKeys = setOf(TEMPERATURE_HEALTH_RESOURCE_OWNER_KEY),
            receiptFloorAt = InstantValue(floor.toEpochMilli()),
        )

        assertFalse(
            pending.isConfirmedBy(
                CompanionSyncStatus(
                    floor.plusSeconds(60),
                    floor.plusSeconds(120),
                    mapOf(
                        "activity" to CompanionSyncStatus.ResourceStatus(floor.plusSeconds(60)),
                    ),
                ),
            ),
        )
        listOf("body_temperature", "basal_body_temperature").forEach { receiptKey ->
            assertTrue(
                pending.isConfirmedBy(
                    CompanionSyncStatus(
                        floor.plusSeconds(60),
                        floor.plusSeconds(120),
                        mapOf(
                            receiptKey to
                                CompanionSyncStatus.ResourceStatus(floor.plusSeconds(60)),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun revokingTemperatureRetainsOtherPendingOwners() {
        val pending = PendingHealthSyncFailure(
            resourceKeys = setOf(TEMPERATURE_HEALTH_RESOURCE_OWNER_KEY, "sleep"),
            receiptFloorAt = InstantValue(100),
        )

        assertEquals(
            setOf("sleep"),
            pending.retainingGranted(setOf("sleep"))?.resourceKeys,
        )
    }

    private val now = Instant.parse("2026-07-25T12:00:00Z")

    @Test
    fun notRequestedIsNotConnected() {
        assertEquals(
            HealthSyncState.NotConnected,
            HealthSyncState.derive(requestedAt = null, status = null),
        )
    }

    @Test
    fun recentRequestWithoutReceiptWaitsForFirstData() {
        assertEquals(
            HealthSyncState.AwaitingFirstData,
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(71 * 3_600),
                status = CompanionSyncStatus(null, now, emptyMap()),
            ),
        )
    }

    @Test
    fun requestWithoutReceiptNeedsAttentionAtTheExistingThreshold() {
        assertEquals(
            HealthSyncState.NeedsAttention(lastDataReceivedAt = null),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(72 * 3_600),
                status = CompanionSyncStatus(null, now, emptyMap()),
            ),
        )
    }

    @Test
    fun recentReceiptIsSynced() {
        val received = Instant.parse("2026-07-25T11:00:00Z")
        assertEquals(
            HealthSyncState.Synced(received),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(3_600),
                status = CompanionSyncStatus(received, now, emptyMap()),
            ),
        )
    }

    @Test
    fun delayedAndAttentionThresholdsApplyToCurrentSetupReceipts() {
        val requestedAt = now.minusSeconds(5 * 24 * 3_600)
        val delayed = now.minusSeconds(36 * 3_600)
        val attention = now.minusSeconds(72 * 3_600)

        assertEquals(
            HealthSyncState.Delayed(delayed),
            HealthSyncState.derive(
                requestedAt = requestedAt,
                status = CompanionSyncStatus(delayed, now, emptyMap()),
            ),
        )
        assertEquals(
            HealthSyncState.NeedsAttention(attention),
            HealthSyncState.derive(
                requestedAt = requestedAt,
                status = CompanionSyncStatus(attention, now, emptyMap()),
            ),
        )
    }

    @Test
    fun oldReceiptClimbsStalenessLadder() {
        val delayed = Instant.parse("2026-07-23T23:00:00Z")
        val attention = Instant.parse("2026-07-22T11:00:00Z")
        assertEquals(
            HealthSyncState.Delayed(delayed),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(4 * 24 * 3_600),
                status = CompanionSyncStatus(delayed, now, emptyMap()),
            ),
        )
        assertEquals(
            HealthSyncState.NeedsAttention(attention),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(4 * 24 * 3_600),
                status = CompanionSyncStatus(attention, now, emptyMap()),
            ),
        )
    }

    @Test
    fun serverObservationTimeOwnsFreshnessInsteadOfDeviceTime() {
        val received = Instant.parse("2026-07-25T12:10:00Z")
        val serverObservedAt = Instant.parse("2026-07-27T12:10:00Z")
        assertEquals(
            HealthSyncState.Delayed(received),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(3_600),
                status = CompanionSyncStatus(received, serverObservedAt, emptyMap()),
            ),
        )
    }

    @Test
    fun reminderRemainingUsesServerObservationAndOnlyCurrentSetupEvidence() {
        val requestedAt = Instant.parse("2026-07-24T12:00:00Z")
        val staleReceipt = requestedAt.minusSeconds(1)
        val currentReceipt = requestedAt.plusSeconds(2 * 3_600)
        val observedAt = requestedAt.plusSeconds(24 * 3_600)

        assertEquals(
            Duration.ofHours(48),
            HealthSyncState.attentionRemaining(requestedAt, staleReceipt, observedAt),
        )
        assertEquals(
            Duration.ofHours(50),
            HealthSyncState.attentionRemaining(requestedAt, currentReceipt, observedAt),
        )
        assertEquals(
            null,
            HealthSyncState.attentionRemaining(null, currentReceipt, observedAt),
        )
        assertEquals(
            null,
            HealthSyncState.attentionRemaining(requestedAt, currentReceipt, null),
        )
    }
}
