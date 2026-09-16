package ai.withmurph.companion.api

import ai.withmurph.companion.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object JournalApiJson {
    fun parse(response: JSONObject): JournalResponse = try {
        val freshness = response.getString("freshness")
        require(freshness in setOf("fresh", "stale"))
        val journal = if (response.isNull("journal")) null else response.getJSONObject("journal").let { data ->
            val window = data.getInt("windowDays")
            require(window in 1..120)
            val days = data.getJSONArray("days").objects(120).map { day ->
                JournalDay(LocalDate.parse(day.getString("date")), day.getJSONArray("events").objects(1000).map(::event))
            }
            require(days.map { it.date }.distinct().size == days.size)
            Journal(days, window)
        }
        JournalResponse(journal, freshness)
    } catch (_: Exception) { throw CompanionApiException.InvalidResponse }

    private fun event(value: JSONObject): JournalEvent {
        val metrics = value.getJSONObject("metrics").let { fields ->
            fields.keys().asSequence().filter { !fields.isNull(it) }.associateWith {
                fields.getDouble(it).also { number -> require(number.isFinite()) }
            }
        }
        val details = value.getJSONArray("details")
        require(details.length() <= 1000)
        val records = value.getJSONArray("records").objects(1000).map {
            JournalRecord(it.getString("id"), it.getString("label"), it.getString("occurredAt"),
                it.nullableString("source"), it.nullableString("summary"), it.nullableString("timeZone"))
        }
        require(records.map { it.id }.distinct().size == records.size)
        return JournalEvent(
            value.getString("id"), LocalDate.parse(value.getString("date")), value.getString("title"),
            value.getString("kind"), value.nullableString("summary"),
            (0 until details.length()).map(details::getString), value.getString("occurredAt"),
            value.getString("timing"), value.nullableString("timeZone"), metrics, records,
        )
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONArray.objects(limit: Int): List<JSONObject> {
        require(length() <= limit)
        return (0 until length()).map(::getJSONObject)
    }
}
