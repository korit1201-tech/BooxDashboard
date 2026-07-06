package com.korit.booxdashboard

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class IcsSubscription(
    val url: String,
    val displayName: String,
    val calendarId: Long,
    val lastModified: String? = null,
    val etag: String? = null
)

/**
 * 訂閱公開的 ICS 行事曆網址，完全不需要登入任何帳號：
 * 用 CalendarContract.ACCOUNT_TYPE_LOCAL 建一個「本機」行事曆（不綁定任何系統帳號，
 * 不會被同步/登出影響），抓下來的 ICS 事件直接寫進這個本機行事曆裡。
 * 寫進去之後這個行事曆會跟 Google 帳號的行事曆一樣，出現在「選擇要顯示的行事曆」清單中。
 */
object CalendarSubscriptions {

    private const val PREFS_NAME = "boox_dashboard_prefs"
    private const val KEY_SUBSCRIPTIONS = "ics_subscriptions"
    private const val ACCOUNT_NAME = "BOOX Dashboard 訂閱行事曆"
    private const val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL

    fun list(context: Context): List<IcsSubscription> {
        val raw = prefs(context).getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            IcsSubscription(
                o.getString("url"),
                o.getString("displayName"),
                o.getLong("calendarId"),
                lastModified = if (o.isNull("lastModified")) null else o.optString("lastModified"),
                etag = if (o.isNull("etag")) null else o.optString("etag")
            )
        }
    }

    /** 新增訂閱：建立本機行事曆並馬上抓一次 ICS 內容。要在背景執行緒呼叫（會做網路存取）。 */
    fun addSubscription(context: Context, url: String, displayName: String): Result<IcsSubscription> {
        return try {
            val resolver = context.contentResolver
            val calendarId = createLocalCalendar(resolver, displayName)
            val sub = IcsSubscription(url, displayName, calendarId)
            val synced = syncSubscription(resolver, sub)
            save(context, list(context) + synced)
            Result.success(synced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removeSubscription(context: Context, sub: IcsSubscription) {
        val calendarUri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, sub.calendarId)
        context.contentResolver.delete(calendarUri, null, null)
        save(context, list(context).filterNot { it.calendarId == sub.calendarId })
    }

    /**
     * 重新抓取所有已訂閱網址的最新內容。要在背景執行緒呼叫；單一訂閱失敗不會擋住其他的。
     * 每次都會帶上次抓到的 Last-Modified/ETag 做條件式 GET（304 代表內容沒變），
     * ICS 內容沒變就跳過整批刪除／重新寫入行事曆事件的動作，省下每小時一次不必要的網路和資料庫負擔。
     */
    fun syncAll(context: Context) {
        val resolver = context.contentResolver
        val updated = list(context).map { sub ->
            try {
                syncSubscription(resolver, sub)
            } catch (e: Exception) {
                // 忽略單一訂閱的同步失敗（網路暫時不通、網址失效等），下次排程再試
                sub
            }
        }
        save(context, updated)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun save(context: Context, subs: List<IcsSubscription>) {
        val arr = JSONArray()
        subs.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("url", s.url)
                    put("displayName", s.displayName)
                    put("calendarId", s.calendarId)
                    put("lastModified", s.lastModified ?: JSONObject.NULL)
                    put("etag", s.etag ?: JSONObject.NULL)
                }
            )
        }
        prefs(context).edit().putString(KEY_SUBSCRIPTIONS, arr.toString()).apply()
    }

    private fun localSyncAdapterUri(baseUri: android.net.Uri) = baseUri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    private fun createLocalCalendar(resolver: ContentResolver, displayName: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF607D8B.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(localSyncAdapterUri(CalendarContract.Calendars.CONTENT_URI), values)
            ?: error("無法建立本機行事曆")
        return ContentUris.parseId(uri)
    }

    /** 回傳更新過 lastModified/etag 的訂閱物件；ICS 內容沒變（304）時直接跳過資料庫寫入。 */
    private fun syncSubscription(resolver: ContentResolver, sub: IcsSubscription): IcsSubscription {
        val fetched = fetchIfChanged(sub) ?: return sub
        val events = IcsParser.parse(fetched.text)

        // 整批換新：刪掉這個行事曆下所有舊事件，插入這次抓到的內容
        val eventsUri = localSyncAdapterUri(CalendarContract.Events.CONTENT_URI)
        resolver.delete(eventsUri, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(sub.calendarId.toString()))

        events.forEach { event ->
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, sub.calendarId)
                put(CalendarContract.Events.TITLE, event.summary)
                put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZoneId)
                put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
                put(CalendarContract.Events.DTSTART, event.dtStartMillis)
                if (event.rrule != null) {
                    put(CalendarContract.Events.RRULE, event.rrule)
                    put(CalendarContract.Events.DURATION, event.durationIso)
                } else {
                    put(CalendarContract.Events.DTEND, event.dtEndMillis ?: event.dtStartMillis)
                }
            }
            resolver.insert(eventsUri, values)
        }

        return sub.copy(lastModified = fetched.lastModified, etag = fetched.etag)
    }

    private class FetchResult(val text: String, val lastModified: String?, val etag: String?)

    /**
     * 帶上次抓到的 Last-Modified/ETag 做條件式 GET；伺服器回 304 代表內容沒變，
     * 回傳 null 讓呼叫端跳過整批刪除／重新寫入。不是每個 ICS 主機都會回這兩個標頭，
     * 沒有的話就退回每次都全抓，行為跟改動前一樣，不會變差。
     */
    private fun fetchIfChanged(sub: IcsSubscription): FetchResult? {
        val connection = URL(sub.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        sub.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
        sub.etag?.let { connection.setRequestProperty("If-None-Match", it) }

        if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            connection.disconnect()
            return null
        }
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return FetchResult(text, connection.getHeaderField("Last-Modified"), connection.getHeaderField("ETag"))
    }
}
