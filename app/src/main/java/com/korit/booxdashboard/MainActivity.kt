package com.korit.booxdashboard

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth

/**
 * 互動設計：
 * - 點擊左上月曆的翻頁箭頭：切換 displayedMonth（月曆顯示的月份），跟「今天」脫鉤。
 * - 點擊左上月曆的某一天：右上「事項」改顯示該天的事件（selectedDate）。
 * - 長按左上月曆區：開「行事曆設定」選單，可以「選擇要顯示的行事曆」（一個 Google 帳號
 *   常有多個行事曆，例如節日曆／家庭／個人）、「訂閱行事曆網址」（不需要登入任何帳號，
 *   直接用公開的 ICS 網址建立一個本機行事曆，見 CalendarSubscriptions／IcsParser）、
 *   「管理已訂閱的網址」、或「管理 Google 帳號」（開系統帳號設定頁面新增/重新登入/手動
 *   同步——App 本身不做 OAuth 登入，帳號登入永遠交給系統處理，我們只讀 CalendarContract）。
 * - 長按左下/右下的照片區：跳出選單選「資料夾」或「系統相片選擇器」。相片選擇器可以
 *   一次多選照片，來源包含 Google 相簿 App（Google Photos Library API 在 2025 年 3 月起
 *   已不再開放第三方讀取既有相簿/自動同步，改走系統相片選擇器是目前唯一可行的整合方式，
 *   代價是新照片要使用者自己重新選，不會自動同步）。挑好的照片會跟資料夾內容合併成同一個
 *   隨機池，每次刷新都可能抽到任何一張。
 * - 點擊/長按畫面其他地方：沿用 Phase 1 局部刷新（GU）／整頁全刷（GC）測試。
 * - 每小時自動重新整理一次（同時重新抓取所有訂閱的 ICS 網址）；若真實日期已跨天
 *   （例如整晚沒關過），會自動把 displayedMonth／selectedDate 都跳回新的「今天」。
 * - 每次 onResume（例如從帳號設定或資料夾選擇器返回）都會重新查詢一次資料，
 *   這樣使用者剛新增完 Google 帳號或改完同步設定，回到 App 就能立刻看到更新。
 * - 電量低於 20% 時，「事項」卡片標題列右側會顯示小小的電量警示文字。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dashboardImageView: ImageView

    private var calendarData: CalendarData = FakeData.buildCalendarData()
    private var displayedMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private var lastKnownToday: LocalDate = LocalDate.now()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val hourlyRefreshRunnable = object : Runnable {
        override fun run() {
            val rolledOver = applyDateRolloverIfNeeded()
            Thread {
                CalendarSubscriptions.syncAll(this@MainActivity)
                runOnUiThread {
                    renderDashboard()
                    // 每小時的例行整理不再強制整頁全刷（GC 對彩色面板是多輪波形的全域刷新，
                    // 耗電明顯比局部刷新高）；改用跟互動同一套 GU／自動升級全刷的邏輯，
                    // 一樣能定期恢復飽和度，但一天 24 次的「喚醒面板刷新」大部分變輕量，
                    // 只有真的跨天需要正確反映「今天」時才強制全刷。
                    if (rolledOver) EinkRefresh.full(dashboardImageView) else EinkRefresh.partial(dashboardImageView)
                }
            }.start()
            refreshHandler.postDelayed(this, HOURLY_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        renderDashboard()
        EinkRefresh.full(dashboardImageView)
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PhotoRepository.saveTreeUri(this, uri)
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
            Toast.makeText(this, "已設定照片資料夾", Toast.LENGTH_SHORT).show()
        }
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            PhotoRepository.savePickedUris(this, uris)
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
            Toast.makeText(this, "已選 ${uris.size} 張照片", Toast.LENGTH_SHORT).show()
        }
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress(e.x, e.y)
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        dashboardImageView = findViewById(R.id.dashboardImageView)
        dashboardImageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        dashboardImageView.post {
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
        }

        requestMissingPermissions()
        refreshHandler.postDelayed(hourlyRefreshRunnable, HOURLY_INTERVAL_MS)

        maybeShowFreezeReminderOnFirstLaunch()
    }

    /**
     * 這台 BOOX 的系統「凍結設置」預設會在「安裝 APP 後自動開啟凍結」，被凍結的 App 一旦離開
     * 前景就會被系統整個停用，導致儀表板停在舊畫面、不再自動更新（換照片／換行事曆）。
     * 剛裝好、App 還在前景時提醒最即時（此時還沒被凍），引導使用者去把凍結關掉。
     * 用 SharedPreferences 記一個旗標只在每次全新安裝後提醒一次；-r 更新安裝會保留旗標不重複打擾，
     * 而完整解除安裝重裝（也正是系統會重新自動凍結的時機）會清掉旗標、剛好重新提醒。
     */
    private fun maybeShowFreezeReminderOnFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FREEZE_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_FREEZE_HINT_SHOWN, true).apply()
        showFreezeReminderDialog()
    }

    /**
     * 說明如何把本 App 從 BOOX 系統凍結名單移除。可從首次啟動自動彈出，也可從月曆長按選單手動叫出。
     */
    private fun showFreezeReminderDialog() {
        AlertDialog.Builder(this)
            .setTitle("讓儀表板保持自動更新")
            .setMessage(
                "BOOX 系統預設會「凍結」背景 App，被凍結後儀表板會停在舊畫面、不再自動換照片與更新行事曆。\n\n" +
                    "請確認本 App 沒有被凍結：\n" +
                    "1. 回 BOOX 桌面 →「應用」\n" +
                    "2. 點右上角的「雪花 ❄️」圖示（凍結設置）\n" +
                    "3. 找到「BOOX Dashboard」，把它的開關切成 OFF（不凍結）\n\n" +
                    "提醒：每次重新安裝本 App 後，系統可能會再次自動凍結，需要重做一次；或直接關掉該頁最上面的「安裝 APP 後自動開啟凍結」。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val rolledOver = applyDateRolloverIfNeeded()
        renderDashboard()
        if (rolledOver) EinkRefresh.full(dashboardImageView) else EinkRefresh.partial(dashboardImageView)
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(hourlyRefreshRunnable)
        super.onDestroy()
    }

    private fun refreshIfDateRolledOver() {
        if (applyDateRolloverIfNeeded()) {
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
        }
    }

    private fun applyDateRolloverIfNeeded(): Boolean {
        val now = LocalDate.now()
        if (now == lastKnownToday) return false
        lastKnownToday = now
        displayedMonth = YearMonth.now()
        selectedDate = now
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val layout = DashboardRenderer.computeLayout(dashboardImageView.width, dashboardImageView.height)
        val headerButtons = DashboardRenderer.calendarHeaderButtons(layout.calendarRect)
        when {
            headerButtons.prevRect.contains(x, y) -> displayedMonth = displayedMonth.minusMonths(1)
            headerButtons.nextRect.contains(x, y) -> displayedMonth = displayedMonth.plusMonths(1)
            else -> {
                val day = DashboardRenderer.hitTestCalendarDay(layout.calendarRect, calendarData, x, y)
                if (day != null) {
                    selectedDate = LocalDate.of(calendarData.year, calendarData.month, day.dayOfMonth)
                }
            }
        }
        renderDashboard()
        EinkRefresh.partial(dashboardImageView)
    }

    private fun handleLongPress(x: Float, y: Float) {
        val layout = DashboardRenderer.computeLayout(dashboardImageView.width, dashboardImageView.height)
        when {
            layout.photoRect.contains(x, y) -> showPhotoSourceDialog()
            layout.calendarRect.contains(x, y) -> showCalendarMenu()
            else -> {
                renderDashboard()
                EinkRefresh.full(dashboardImageView)
            }
        }
    }

    private fun showCalendarMenu() {
        val options = arrayOf("選擇要顯示的行事曆", "訂閱行事曆網址（免登入）", "管理已訂閱的網址", "管理 Google 帳號", "背景凍結提醒（沒自動更新看這）")
        AlertDialog.Builder(this)
            .setTitle("行事曆設定")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCalendarPickerDialog()
                    1 -> showAddSubscriptionDialog()
                    2 -> showManageSubscriptionsDialog()
                    3 -> openAccountSettings()
                    4 -> showFreezeReminderDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 訂閱公開 ICS 網址不需要登入任何帳號——直接抓網址內容解析後寫進本機行事曆
     * （CalendarSubscriptions），跟要不要有 Google 帳號完全無關。
     */
    private fun showAddSubscriptionDialog() {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            Toast.makeText(this, "尚未取得行事曆寫入權限，無法訂閱", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val nameInput = EditText(this).apply { hint = "顯示名稱（例如：公司行事曆）" }
        val urlInput = EditText(this).apply {
            hint = "ICS 網址（https://...ics）"
            // 一般文字欄位預設會把第一個字母自動大寫，網址型別的鍵盤不會有這個行為，
            // 不然使用者沒注意到的話貼上的網址會被偷偷改成 "Https://..." 而讀取失敗。
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(nameInput)
            addView(urlInput)
        }

        AlertDialog.Builder(this)
            .setTitle("訂閱行事曆網址")
            .setView(layout)
            .setPositiveButton("訂閱") { _, _ ->
                val url = urlInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifEmpty { "訂閱行事曆" }
                if (url.isEmpty()) {
                    Toast.makeText(this, "請輸入網址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "正在抓取行事曆…", Toast.LENGTH_SHORT).show()
                Thread {
                    val result = CalendarSubscriptions.addSubscription(this, url, name)
                    runOnUiThread {
                        result.onSuccess {
                            renderDashboard()
                            EinkRefresh.full(dashboardImageView)
                            Toast.makeText(this, "訂閱成功：$name", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Toast.makeText(this, "訂閱失敗：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageSubscriptionsDialog() {
        val subs = CalendarSubscriptions.list(this)
        if (subs.isEmpty()) {
            Toast.makeText(this, "目前沒有訂閱任何網址", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = subs.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("點選要移除的訂閱")
            .setItems(labels) { _, which ->
                val sub = subs[which]
                AlertDialog.Builder(this)
                    .setMessage("要移除「${sub.displayName}」這個訂閱嗎？")
                    .setPositiveButton("移除") { _, _ ->
                        CalendarSubscriptions.removeSubscription(this, sub)
                        renderDashboard()
                        EinkRefresh.full(dashboardImageView)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun showPhotoSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("照片來源")
            .setItems(arrayOf("選擇資料夾", "多選相片（可從 Google 相簿等 App 選）")) { _, which ->
                when (which) {
                    0 -> directoryPickerLauncher.launch(null)
                    1 -> photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCalendarPickerDialog() {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            Toast.makeText(this, "尚未取得行事曆權限，無法選擇行事曆", Toast.LENGTH_SHORT).show()
            return
        }
        val calendars = CalendarRepository.listCalendars(contentResolver)
        if (calendars.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("找不到行事曆")
                .setMessage("裝置上還沒有同步任何 Google 帳號的行事曆，要現在去新增或管理帳號嗎？")
                .setPositiveButton("前往帳號設定") { _, _ -> openAccountSettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val enabledIds = CalendarPreferences.getEnabledIds(this) ?: calendars.map { it.id }.toSet()
        val labels = calendars.map { "${it.displayName}（${it.accountName}）" }.toTypedArray()
        val checkedItems = calendars.map { it.id in enabledIds }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("選擇要顯示的行事曆")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("確定") { _, _ ->
                val selected = calendars.filterIndexed { index, _ -> checkedItems[index] }.map { it.id }.toSet()
                CalendarPreferences.setEnabledIds(this, selected)
                renderDashboard()
                EinkRefresh.full(dashboardImageView)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * App 本身不做 Google OAuth 登入；新增帳號、重新登入、手動觸發同步都交給系統的
     * 帳號設定頁面處理。使用者從那邊回來後，onResume 會重新查詢 CalendarContract，
     * 新加的行事曆就會出現。
     */
    private fun openAccountSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟帳號設定頁面", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 用舊式的 ACTION_BATTERY_CHANGED sticky broadcast 讀電量，而不是較新的
     * BatteryManager.BATTERY_PROPERTY_CAPACITY——實測後者在這台 BOOX（Onyx 客製 ROM）
     * 上不可靠，時常回傳 -1，前者才是各家 ROM 都穩定支援的取值方式。
     */
    private fun getBatteryPercent(): Int? {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    private fun requestMissingPermissions() {
        val needed = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 主動要求 Android 立刻對這台裝置上的 Google 帳號做一次行事曆同步，不要被動等
     * Google 的推播通知（"tickle"）——實測發現這台裝置的行事曆同步幾乎都是靠推播觸發，
     * 一旦推播沒送達（例如這台 Onyx ROM 對 GCM/FCM 的支援不完全穩定），就只能等內建
     * 每天一次的排程週期，導致手機上編輯/新增的事項要等一整天才會出現在這台裝置上。
     * requestSync 只是「提出要求」，不保證立刻完成，但比被動等待可靠很多。
     */
    private fun requestCalendarSync() {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        CalendarRepository.listCalendars(contentResolver)
            .map { it.accountName }
            .distinct()
            .filter { it.contains("@") } // 排掉本機訂閱行事曆用的假帳號名稱，只對真正的 Google 帳號要求同步
            .forEach { accountName ->
                ContentResolver.requestSync(Account(accountName, "com.google"), CalendarContract.AUTHORITY, extras)
            }
    }

    private fun renderDashboard() {
        val width = dashboardImageView.width
        val height = dashboardImageView.height
        if (width == 0 || height == 0) return

        requestCalendarSync()

        val events: List<TodayEvent>
        if (hasPermission(Manifest.permission.READ_CALENDAR)) {
            val enabledCalendarIds = CalendarPreferences.getEnabledIds(this)
            calendarData = CalendarRepository.buildCalendarData(contentResolver, displayedMonth, enabledCalendarIds = enabledCalendarIds)
            events = CalendarRepository.buildTodayEvents(contentResolver, selectedDate, enabledCalendarIds = enabledCalendarIds)
        } else {
            calendarData = FakeData.buildCalendarData(displayedMonth)
            events = FakeData.buildTodayEvents(selectedDate)
        }

        val selectedDayOfMonth = if (selectedDate.year == calendarData.year && selectedDate.monthValue == calendarData.month) {
            selectedDate.dayOfMonth
        } else {
            null
        }
        val eventsSectionTitle = if (selectedDate == LocalDate.now()) {
            "今日事項"
        } else {
            "${selectedDate.monthValue}/${selectedDate.dayOfMonth} 事項"
        }

        val photo = PhotoRepository.pickRandomPhoto(this, width)

        val bitmap = DashboardRenderer.render(
            width = width,
            height = height,
            calendarData = calendarData,
            selectedDayOfMonth = selectedDayOfMonth,
            eventsSectionTitle = eventsSectionTitle,
            events = events,
            photo = photo,
            batteryPercent = getBatteryPercent()
        )
        dashboardImageView.setImageBitmap(bitmap)
    }

    companion object {
        private const val HOURLY_INTERVAL_MS = 60 * 60 * 1000L
        private const val MAX_PICKED_PHOTOS = 50
        private const val PREFS_NAME = "boox_dashboard_prefs"
        private const val KEY_FREEZE_HINT_SHOWN = "freeze_hint_shown"
    }
}
