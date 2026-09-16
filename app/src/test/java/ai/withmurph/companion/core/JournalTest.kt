package ai.withmurph.companion.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    @Test fun calendarDaysStayContiguousAcrossDaylightSavingAndRespectTheServerWindow() {
        val end = LocalDate.parse("2026-03-10")
        assertEquals(listOf("2026-03-10", "2026-03-09", "2026-03-08", "2026-03-07"),
            journalDates(end, 7, LocalDate.parse("2026-03-07")).map { it.toString() })
        assertEquals(120, journalDates(end, 1000, end.minusDays(200)).size)
    }
    @Test fun presentationPreservesFreeformAndMixedUnitMeanings() {
        assertEquals("1h 30m", JournalPresentation.summary("90 min"))
        assertEquals("90 min after dinner", JournalPresentation.summary("90 min after dinner"))
        assertEquals("7h 42m", JournalPresentation.summary("7h 42m"))
        assertEquals("Sleep duration from WHOOP", JournalPresentation.text("junction_sleep from whoop_v2"))
        assertEquals("Unavailable", JournalPresentation.duration(Double.NaN))
        assertEquals("Unavailable", JournalPresentation.duration(-1.0))
    }
    @Test fun timedEntriesUseTheRecordedTimezoneAndInvalidTimeIsExplicit() {
        assertTrue(JournalPresentation.time("2026-09-15T16:30:00Z", "UTC").contains("4:30") ||
            JournalPresentation.time("2026-09-15T16:30:00Z", "UTC").contains("16:30"))
        assertEquals("Time not recorded", JournalPresentation.time("missing", "UTC"))
    }
}
