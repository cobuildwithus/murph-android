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
            HealthSyncState.derive(requested = false, status = null, now = now),
        )
    }

    @Test
    fun requestedWithoutReceiptWaitsForFirstData() {
        assertEquals(
            HealthSyncState.AwaitingFirstData,
            HealthSyncState.derive(requested = true, status = null, now = now),
        )
    }

    @Test
    fun recentReceiptIsSynced() {
        val received = Instant.parse("2026-07-25T11:00:00Z")
        assertEquals(
            HealthSyncState.Synced(received),
            HealthSyncState.derive(
                requested = true,
                status = CompanionSyncStatus(received, emptyMap()),
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
                requested = true,
                status = CompanionSyncStatus(delayed, emptyMap()),
                now = now,
            ),
        )
        assertEquals(
            HealthSyncState.NeedsAttention(attention),
            HealthSyncState.derive(
                requested = true,
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
                requested = true,
                status = CompanionSyncStatus(future, emptyMap()),
                now = now,
            ),
        )
    }
}
