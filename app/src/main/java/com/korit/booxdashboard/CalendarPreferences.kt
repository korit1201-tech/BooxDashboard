package com.korit.booxdashboard

import android.content.Context

/**
 * 記住使用者在「選擇要顯示的行事曆」對話框裡勾選的 calendar_id 集合。
 * 尚未設定過（null）時代表不過濾、所有行事曆都顯示，維持原本行為。
 */
object CalendarPreferences {

    private const val PREFS_NAME = "boox_dashboard_prefs"
    private const val KEY_ENABLED_CALENDAR_IDS = "enabled_calendar_ids"

    fun getEnabledIds(context: Context): Set<Long>? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED_CALENDAR_IDS, null)
        return stored?.mapNotNull { it.toLongOrNull() }?.toSet()
    }

    fun setEnabledIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ENABLED_CALENDAR_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }
}
