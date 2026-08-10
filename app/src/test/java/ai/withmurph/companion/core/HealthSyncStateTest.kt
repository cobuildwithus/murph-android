package ai.withmurph.companion.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncStateTest {
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
