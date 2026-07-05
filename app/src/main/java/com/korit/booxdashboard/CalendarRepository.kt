package com.korit.booxdashboard

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * 透過 CalendarContract.Instances 讀取系統行事曆（含已同步的 Google 帳號行事曆）。
 * 全天事件在 Provider 內一律以 UTC 儲存，換算日期時要用 UTC 而非裝置時區，
 * 否則跨時區/日界線時全天事件的日期會偏移一天。
 */
object CalendarRepository {

    fun buildCalendarData(
        resolver: ContentResolver,
        displayedMonth: YearMonth = YearMonth.now(),
        today: LocalDate = LocalDate.now()
    ): CalendarData {
        val firstOfMonth = displayedMonth.atDay(1)
        val leadingEmptyDays = (firstOfMonth.dayOfWeek.value + 6) % 7
        val daysInMonth = displayedMonth.lengthOfMonth()
        val totalCells = ((leadingEmptyDays + daysInMonth + 6) / 7) * 7

        val eventDays = queryEventDaysInMonth(resolver, displayedMonth)
        val isDisplayingCurrentMonth = displayedMonth == YearMonth.from(today)

        val days = (0 until totalCells).map { index ->
            val dayNumber = index - leadingEmptyDays + 1
            if (dayNumber in 1..daysInMonth) {
                CalendarDay(
                    dayOfMonth = dayNumber,
                    isCurrentMonth = true,
                    isToday = isDisplayingCurrentMonth && dayNumber == today.dayOfMonth,
                    hasEvent = dayNumber in eventDays
                )
            } else {
                CalendarDay(dayOfMonth = 0, isCurrentMonth = false, isToday = false, hasEvent = false)
            }
        }

        return CalendarData(
            year = displayedMonth.year,
            month = displayedMonth.monthValue,
            weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日"),
            days = days
        )
    }

    fun buildTodayEvents(resolver: ContentResolver, today: LocalDate = LocalDate.now()): List<TodayEvent> {
        val zone = ZoneId.systemDefault()
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = mutableListOf<TodayEvent>()
        queryInstances(resolver, startMillis, endMillis) { begin, isAllDay, title ->
            val time = if (isAllDay) {
                "全天"
            } else {
                val localTime = Instant.ofEpochMilli(begin).atZone(zone).toLocalTime()
                String.format(Locale.getDefault(), "%02d:%02d", localTime.hour, localTime.minute)
            }
            events.add(TodayEvent(time = time, title = title, isAllDay = isAllDay))
        }
        return events.sortedWith(compareBy({ !it.isAllDay }, { it.time }))
    }

    private fun queryEventDaysInMonth(resolver: ContentResolver, yearMonth: YearMonth): Set<Int> {
        val zone = ZoneId.systemDefault()
        val startMillis = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = yearMonth.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val result = mutableSetOf<Int>()
        queryInstances(resolver, startMillis, endMillis) { begin, isAllDay, _ ->
            val dateZone = if (isAllDay) ZoneOffset.UTC else zone
            val date = Instant.ofEpochMilli(begin).atZone(dateZone).toLocalDate()
            if (YearMonth.from(date) == yearMonth) {
                result.add(date.dayOfMonth)
            }
        }
        return result
    }

    private inline fun queryInstances(
        resolver: ContentResolver,
        startMillis: Long,
        endMillis: Long,
        onRow: (begin: Long, isAllDay: Boolean, title: String) -> Unit
    ) {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMillis)
        ContentUris.appendId(uriBuilder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY
        )

        resolver.query(uriBuilder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            while (cursor.moveToNext()) {
                val begin = cursor.getLong(beginIdx)
                val title = cursor.getString(titleIdx) ?: "(無標題)"
                val isAllDay = cursor.getInt(allDayIdx) != 0
                onRow(begin, isAllDay, title)
            }
        }
    }
}
