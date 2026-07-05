package com.korit.booxdashboard

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class IcsEvent(
    val summary: String,
    val isAllDay: Boolean,
    val timeZoneId: String,
    val dtStartMillis: Long,
    val dtEndMillis: Long?,
    val durationIso: String?,
    val rrule: String?
)

/**
 * 精簡的 RFC 5545 (iCalendar) 解析器，只處理我們用得到的欄位：
 * SUMMARY / DTSTART / DTEND / RRULE。RRULE 字串直接原封不動存進
 * CalendarContract.Events.RRULE，展開重複事件交給系統 CalendarProvider
 * 自己做（跟同步下來的 Google 事件用同一套機制），不必自己算遞迴規則。
 */
object IcsParser {

    private val DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parse(icsText: String): List<IcsEvent> {
        val lines = unfold(icsText)
        val events = mutableListOf<IcsEvent>()

        var inEvent = false
        var summary = ""
        var dtStart: ParsedDate? = null
        var dtEnd: ParsedDate? = null
        var rrule: String? = null

        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true
                    summary = ""
                    dtStart = null
                    dtEnd = null
                    rrule = null
                }
                line == "END:VEVENT" -> {
                    inEvent = false
                    val start = dtStart
                    if (start != null) {
                        val end = dtEnd
                        var durationIso: String? = null
                        var endMillis: Long? = end?.millis
                        if (rrule != null) {
                            val durationMillis = when {
                                end != null -> end.millis - start.millis
                                start.isAllDay -> 24 * 60 * 60 * 1000L
                                else -> 60 * 60 * 1000L
                            }
                            durationIso = Duration.ofMillis(durationMillis).toString()
                            endMillis = null
                        }
                        events.add(
                            IcsEvent(
                                summary = summary,
                                isAllDay = start.isAllDay,
                                timeZoneId = start.zoneId,
                                dtStartMillis = start.millis,
                                dtEndMillis = endMillis,
                                durationIso = durationIso,
                                rrule = rrule
                            )
                        )
                    }
                }
                inEvent && line.startsWith("SUMMARY") -> summary = valueOf(line)
                inEvent && line.startsWith("DTSTART") -> dtStart = runCatching { parseDate(line) }.getOrNull()
                inEvent && line.startsWith("DTEND") -> dtEnd = runCatching { parseDate(line) }.getOrNull()
                inEvent && line.startsWith("RRULE") -> rrule = valueOf(line)
            }
        }
        return events
    }

    /** RFC 5545 允許把一行文字折成多行接續，接續行以空白或 tab 開頭，要先接回同一行才能解析。 */
    private fun unfold(text: String): List<String> {
        val rawLines = text.split("\r\n", "\n")
        val result = mutableListOf<String>()
        for (raw in rawLines) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && result.isNotEmpty()) {
                result[result.size - 1] = result.last() + raw.substring(1)
            } else {
                result.add(raw)
            }
        }
        return result.map { it.trimEnd('\r') }
    }

    private fun valueOf(line: String): String {
        val idx = line.indexOf(':')
        val raw = if (idx >= 0) line.substring(idx + 1) else ""
        return unescape(raw)
    }

    private fun unescape(value: String): String =
        value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private data class ParsedDate(val millis: Long, val zoneId: String, val isAllDay: Boolean)

    private fun parseDate(line: String): ParsedDate {
        val colonIdx = line.indexOf(':')
        val params = line.substring(0, colonIdx)
        val value = line.substring(colonIdx + 1)

        val isAllDayParam = params.contains("VALUE=DATE") && !params.contains("VALUE=DATE-TIME")
        val tzId = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)

        return when {
            isAllDayParam || (value.length == 8 && !value.contains('T')) -> {
                val date = LocalDate.parse(value, DATE_ONLY)
                val millis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                ParsedDate(millis, "UTC", true)
            }
            value.endsWith("Z") -> {
                val dateTime = LocalDateTime.parse(value.dropLast(1), DATE_TIME)
                ParsedDate(dateTime.toInstant(ZoneOffset.UTC).toEpochMilli(), "UTC", false)
            }
            tzId != null -> {
                val dateTime = LocalDateTime.parse(value, DATE_TIME)
                val zone = runCatching { ZoneId.of(tzId) }.getOrDefault(ZoneId.systemDefault())
                ParsedDate(dateTime.atZone(zone).toInstant().toEpochMilli(), zone.id, false)
            }
            else -> {
                val dateTime = LocalDateTime.parse(value, DATE_TIME)
                val zone = ZoneId.systemDefault()
                ParsedDate(dateTime.atZone(zone).toInstant().toEpochMilli(), zone.id, false)
            }
        }
    }
}
