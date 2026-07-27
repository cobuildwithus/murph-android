package ai.withmurph.companion.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncStateTest {
    private val now = Instant.parse("2026-07-25T12:00:00Z")

    @Test
    fun notRequestedIsNotConnected() {
        assertEquals(
            HealthSyncState.NotConnected,
            HealthSyncState.derive(requestedAt = null, status = null, now = now),
        )
    }

    @Test
    fun recentRequestWithoutReceiptWaitsForFirstData() {
        assertEquals(
            HealthSyncState.AwaitingFirstData,
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(71 * 3_600),
                status = null,
                now = now,
            ),
        )
    }

    @Test
    fun requestWithoutReceiptNeedsAttentionAtTheExistingThreshold() {
        assertEquals(
            HealthSyncState.NeedsAttention(lastDataReceivedAt = null),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(72 * 3_600),
                status = null,
                now = now,
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
                status = CompanionSyncStatus(received, emptyMap()),
                now = now,
            ),
        )
    }

    @Test
    fun receiptBeforeCurrentSetupWaitsForCurrentData() {
        assertEquals(
            HealthSyncState.AwaitingFirstData,
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(60),
                status = CompanionSyncStatus(now.minusSeconds(61), emptyMap()),
                now = now,
            ),
        )
    }

    @Test
    fun receiptAtCurrentSetupBoundaryIsSynced() {
        val requestedAt = now.minusSeconds(60)
        assertEquals(
            HealthSyncState.Synced(requestedAt),
            HealthSyncState.derive(
                requestedAt = requestedAt,
                status = CompanionSyncStatus(requestedAt, emptyMap()),
                now = now,
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
                status = CompanionSyncStatus(delayed, emptyMap()),
                now = now,
            ),
        )
        assertEquals(
            HealthSyncState.NeedsAttention(attention),
            HealthSyncState.derive(
                requestedAt = requestedAt,
                status = CompanionSyncStatus(attention, emptyMap()),
                now = now,
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
                status = CompanionSyncStatus(delayed, emptyMap()),
                now = now,
            ),
        )
        assertEquals(
            HealthSyncState.NeedsAttention(attention),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(4 * 24 * 3_600),
                status = CompanionSyncStatus(attention, emptyMap()),
                now = now,
            ),
        )
    }

    @Test
    fun futureClockSkewIsFresh() {
        val future = Instant.parse("2026-07-25T12:10:00Z")
        assertEquals(
            HealthSyncState.Synced(future),
            HealthSyncState.derive(
                requestedAt = now.minusSeconds(3_600),
                status = CompanionSyncStatus(future, emptyMap()),
                now = now,
            ),
        )
    }
}
