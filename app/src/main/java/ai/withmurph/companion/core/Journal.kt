package ai.withmurph.companion.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Private read-only projection. It belongs to one admitted session, in memory only. */
sealed interface JournalState {
    data object Idle : JournalState
    data object Loading : JournalState
    data class Ready(val response: JournalResponse) : JournalState
    data object Failed : JournalState
}

data class JournalResponse(val journal: Journal?, val freshness: String)
data class Journal(val days: List<JournalDay>, val windowDays: Int)
data class JournalDay(val date: LocalDate, val events: List<JournalEvent>)
data class JournalEvent(
    val id: String,
    val date: LocalDate,
    val title: String,
    val kind: String,
    val summary: String?,
    val details: List<String>,
    val occurredAt: String,
    val timing: String,
    val timeZone: String?,
    val metrics: Map<String, Double>,
    val records: List<JournalRecord>,
) {
    val timeLabel: String get() = when (timing) {
        "night" -> "Night"
        "all_day" -> "All day"
        "morning" -> "Morning"
        "afternoon" -> "Afternoon"
        "evening" -> "Evening"
        "timed" -> JournalPresentation.time(occurredAt, timeZone)
        else -> ""
    }
}

data class JournalRecord(
    val id: String,
    val label: String,
    val occurredAt: String,
    val source: String?,
    val summary: String?,
    val timeZone: String?,
)

object JournalPresentation {
    fun text(raw: String): String {
        var result = raw
        listOf(
            "\\bjunction[ _-]+sleep\\b" to "Sleep duration",
            "\\bapple[ _-]health(?:[ _-]kit)?\\b" to "Apple Health",
            "\\bhealth[ _-]connect\\b" to "Health Connect",
            "\\bwhoop(?:[ _-]v2)?\\b" to "WHOOP",
            "\\bjunction\\b" to "Connected device",
        ).forEach { (pattern, replacement) ->
            result = result.replace(Regex(pattern, RegexOption.IGNORE_CASE), replacement)
        }
        return result
    }

    fun duration(minutes: Double): String {
        if (!minutes.isFinite() || minutes < 0 || minutes >= 1_000_000) return "Unavailable"
        val rounded = minutes.roundToInt()
        return if (rounded >= 60) "${rounded / 60}h ${rounded % 60}m" else "${rounded}m"
    }

    fun number(value: Double): String = java.text.NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 1
    }.format(value)

    fun summary(raw: String?): String? {
        raw ?: return null
        val match = Regex("^\\s*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))\\s*(.*?)\\s*$").matchEntire(raw)
            ?: return text(raw)
        val value = match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return text(raw)
        val unit = match.groupValues[2]
        return when (unit.lowercase(Locale.ROOT)) {
            "min", "mins", "minute", "minutes" -> if (value >= 0) duration(value) else text(raw)
            "percent", "%" -> number(value) + "%"
            "score", "" -> number(value)
            "ms", "bpm", "breaths/min", "/min" -> number(value) + " " + unit
            else -> text(raw)
        }
    }

    fun time(raw: String, timeZone: String?): String = try {
        val zone = try { timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault() }
        catch (_: Exception) { ZoneId.systemDefault() }
        Instant.parse(raw).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } catch (_: Exception) { "Time not recorded" }

    fun metrics(values: Map<String, Double>): List<Pair<String, String>> = buildList {
        fun addMetric(key: String, label: String, suffix: String = "", formatDuration: Boolean = false) {
            val value = values[key]?.takeIf(Double::isFinite) ?: return
            if (key == "activityMinutes" && value <= 0) return
            add(label to if (formatDuration) duration(value) else number(value) + suffix)
        }
        addMetric("sleepMinutes", "Sleep duration", formatDuration = true)
        addMetric("activityMinutes", "Activity", formatDuration = true)
        addMetric("sleepScore", "Sleep score")
        addMetric("sleepEfficiencyPercent", "Sleep efficiency", "%")
        addMetric("deepSleepMinutes", "Deep sleep", formatDuration = true)
        addMetric("remSleepMinutes", "REM sleep", formatDuration = true)
        addMetric("hrvMs", "HRV", " ms")
        addMetric("restingHeartRateBpm", "Resting heart rate", " bpm")
        addMetric("respiratoryRate", "Respiratory rate", " /min")
        addMetric("spo2Percent", "Blood oxygen", "%")
        addMetric("readinessScore", "Readiness")
        addMetric("recoveryScore", "Recovery")
    }
}

fun journalDates(end: LocalDate, count: Int, earliest: LocalDate): List<LocalDate> =
    (0 until count.coerceIn(0, 120)).map { end.minusDays(it.toLong()) }.takeWhile { it >= earliest }
