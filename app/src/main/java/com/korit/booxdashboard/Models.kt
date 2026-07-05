package com.korit.booxdashboard

data class CalendarDay(
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val hasEvent: Boolean
)

data class CalendarData(
    val year: Int,
    val month: Int, // 1-12
    val weekdayLabels: List<String>,
    val days: List<CalendarDay> // always a multiple of 7, includes leading/trailing days from adjacent months
)

data class TodayEvent(
    val time: String, // e.g. "09:00" or "全天"
    val title: String,
    val isAllDay: Boolean
)
