package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthSyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthStatusSummaryTest {
    @Test
    fun recencyUsesServerObservationRegardlessOfTheDeviceClock() {
        for (year in listOf(2000, 2099)) {
            val receipt = Instant.parse("${year}-01-01T00:00:00Z")
            val state = AppUiState(healthSync = HealthSyncState.Synced(receipt),
                healthStatusObservedAt = receipt.plusSeconds(3600))
            assertEquals("Synced · 1 hr ago", healthStatusSummaryText(state))
        }
    }

    @Test
    fun missingObservationDoesNotAssertRecency() {
        val state = AppUiState(healthSync = HealthSyncState.Synced(Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals("Synced", healthStatusSummaryText(state))
    }

    @Test
    fun staleObservationAndReconnectKeepTheirRecoveryLabels() {
        val receipt = Instant.parse("2000-01-01T00:00:00Z")
        val state = AppUiState(healthSync = HealthSyncState.Synced(receipt),
            healthStatusObservedAt = receipt.plusSeconds(3600), healthStatusIsStale = true)
        assertEquals("Last checked online", healthStatusSummaryText(state))
        assertEquals("Reconnect to resume syncing", healthStatusSummaryText(state.copy(healthReconnectRequired = true)))
    }
}
