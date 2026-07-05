package com.korit.booxdashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth

/**
 * 互動設計：
 * - 點擊左上月曆的翻頁箭頭：切換 displayedMonth（月曆顯示的月份），跟「今天」脫鉤。
 * - 點擊左上月曆的某一天：右上「事項」改顯示該天的事件（selectedDate）。
 * - 長按左下/右下的照片區：開系統的資料夾選擇器（SAF），記住使用者選的照片來源。
 * - 點擊/長按畫面其他地方：沿用 Phase 1 局部刷新（GU）／整頁全刷（GC）測試。
 * - 每小時自動重新整理一次；若真實日期已跨天（例如整晚沒關過），會自動把
 *   displayedMonth／selectedDate 都跳回新的「今天」，而不是停留在舊日期上。
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
            refreshIfDateRolledOver()
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
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
    }

    override fun onResume() {
        super.onResume()
        refreshIfDateRolledOver()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(hourlyRefreshRunnable)
        super.onDestroy()
    }

    private fun refreshIfDateRolledOver() {
        val now = LocalDate.now()
        if (now != lastKnownToday) {
            lastKnownToday = now
            displayedMonth = YearMonth.now()
            selectedDate = now
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
        }
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
        if (layout.photoRect.contains(x, y)) {
            directoryPickerLauncher.launch(null)
        } else {
            renderDashboard()
            EinkRefresh.full(dashboardImageView)
        }
    }

    private fun requestMissingPermissions() {
        val needed = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun renderDashboard() {
        val width = dashboardImageView.width
        val height = dashboardImageView.height
        if (width == 0 || height == 0) return

        val events: List<TodayEvent>
        if (hasPermission(Manifest.permission.READ_CALENDAR)) {
            calendarData = CalendarRepository.buildCalendarData(contentResolver, displayedMonth)
            events = CalendarRepository.buildTodayEvents(contentResolver, selectedDate)
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
            photo = photo
        )
        dashboardImageView.setImageBitmap(bitmap)
    }

    companion object {
        private const val HOURLY_INTERVAL_MS = 60 * 60 * 1000L
    }
}
