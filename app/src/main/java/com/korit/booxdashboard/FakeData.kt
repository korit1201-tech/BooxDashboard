package com.korit.booxdashboard

import java.time.LocalDate
import java.time.YearMonth

/**
 * Phase 1 用假資料，供排版驗證。Phase 2 會替換為 CalendarContract / MediaStore 真實資料來源。
 */
object FakeData {

    fun buildCalendarData(displayedMonth: YearMonth = YearMonth.now(), today: LocalDate = LocalDate.now()): CalendarData {
        val firstOfMonth = displayedMonth.atDay(1)
        // 週一為每週第一天
        val leadingEmptyDays = (firstOfMonth.dayOfWeek.value + 6) % 7
        val daysInMonth = displayedMonth.lengthOfMonth()
        val totalCells = ((leadingEmptyDays + daysInMonth + 6) / 7) * 7
        val isDisplayingCurrentMonth = displayedMonth == YearMonth.from(today)

        val eventDays = setOf(3, 7, today.dayOfMonth, 22, 28).filter { it in 1..daysInMonth }.toSet()

        val days = (0 until totalCells).map { index ->
            val dayNumber = index - leadingEmptyDays + 1
            if (dayNumber in 1..daysInMonth) {
                CalendarDay(
                    dayOfMonth = dayNumber,
                    isCurrentMonth = true,
                    isToday = isDisplayingCurrentMonth && dayNumber == today.dayOfMonth,
                    hasEvent = isDisplayingCurrentMonth && dayNumber in eventDays
                )
            } else {
                CalendarDay(dayOfMonth = 0, isCurrentMonth = false, isToday = false, hasEvent = false)
            }
        }

        val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

        return CalendarData(
            year = displayedMonth.year,
            month = displayedMonth.monthValue,
            weekdayLabels = weekdayLabels,
            days = days
        )
    }

    /**
     * 沒有 CalendarContract 權限時的退回資料。只在查詢日期為今天時給假事項，
     * 其他日期一律視為無行程，避免讓使用者誤以為切換日期後查得到別天的假資料。
     */
    fun buildTodayEvents(date: LocalDate = LocalDate.now()): List<TodayEvent> {
        if (date != LocalDate.now()) return emptyList()
        return listOf(
            TodayEvent(time = "全天", title = "專案截止日提醒", isAllDay = true),
            TodayEvent(time = "09:00", title = "晨會", isAllDay = false),
            TodayEvent(time = "14:00", title = "客戶會議", isAllDay = false),
            TodayEvent(time = "19:30", title = "健身房", isAllDay = false)
        )
    }
}
