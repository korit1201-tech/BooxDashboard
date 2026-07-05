package com.korit.booxdashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * E Ink 儀表板的核心繪製邏輯。
 *
 * 設計原則：不倚賴 Android View 樹各自重繪，而是把月曆／當日事項／照片三個區塊
 * 一次畫進同一張 Bitmap，畫面只做一次性的 setImageBitmap（未來再交給 Onyx SDK
 * 做整頁或局部刷新）。所有座標都是相對於輸出的 width/height 計算，不寫死尺寸。
 *
 * computeLayout()／hitTestCalendarDay()／calendarHeaderButtons() 讓 MainActivity
 * 可以用同一套座標邏輯判斷觸控點落在哪個區塊、哪一天、有沒有點到翻頁按鈕，
 * 畫面跟點擊判定才不會兩邊各寫一套算法而兜不起來。
 */
object DashboardRenderer {

    // 下半部（照片）佔 60% 高度、上半部（月曆＋今日事項）佔 40%；
    // 上半部再左右對半分給月曆／今日事項。
    // 60% 不是隨便挑的：實測使用者相片幾乎都是 4:3 橫向（6560x4928／4096x3072），
    // 而照片區寬度已經固定滿版，此比例下 fit/contain 出來的照片框跟 4:3 幾乎完全吻合，
    // 上下留白趨近於零；再往上調（例如 70%）並不會讓照片變大（寬度已經頂滿），
    // 只會多出更多沒用到的空白。
    private const val TOP_HEIGHT_RATIO = 0.4f

    private const val SECTION_MARGIN_RATIO = 0.02f
    private const val CARD_STROKE_WIDTH_RATIO = 0.005f
    private const val CARD_CORNER_RADIUS_RATIO = 0.025f
    private const val CONTENT_PADDING_RATIO = 0.035f
    private const val LOW_BATTERY_THRESHOLD = 20

    private val COLOR_CARD_BORDER = Color.rgb(80, 80, 80)
    // 週六／週日在低飽和度彩色面板上跟平日的色差很小，所以主要差異化改用「形狀」
    // （平日：無裝飾／週六：外框圓圈／週日：實心反白圓），色彩只是次要輔助。
    private val COLOR_SATURDAY = Color.rgb(20, 70, 150)
    private val COLOR_SUNDAY_FILL = Color.rgb(160, 25, 25)

    data class Layout(
        val calendarRect: RectF,
        val eventsRect: RectF,
        val photoRect: RectF
    )

    data class CalendarHeaderButtons(val prevRect: RectF, val nextRect: RectF)

    private data class CalendarGridMetrics(
        val gridTop: Float,
        val headerHeight: Float,
        val colWidth: Float,
        val rowHeight: Float,
        val rowCount: Int
    )

    fun computeLayout(width: Int, height: Int): Layout {
        val margin = width * SECTION_MARGIN_RATIO
        val topBottom = height * TOP_HEIGHT_RATIO
        val midX = width / 2f

        val calendarRect = RectF(margin, margin, midX - margin / 2f, topBottom)
        val eventsRect = RectF(midX + margin / 2f, margin, width - margin, topBottom)
        val photoRect = RectF(margin, topBottom + margin, width - margin, height - margin)
        return Layout(calendarRect, eventsRect, photoRect)
    }

    /** 月曆左右翻頁箭頭的觸控熱區。傳入的是外層卡片矩形（跟 Layout.calendarRect 一樣）。 */
    fun calendarHeaderButtons(outerCalendarRect: RectF): CalendarHeaderButtons =
        calendarHeaderButtonsForContent(insetForContent(outerCalendarRect))

    /**
     * 依畫面座標判斷點擊落在月曆網格的哪一天。只有本月的格子才會回傳，
     * 上/下個月補位的空白格一律回傳 null（沒有實際日期可切換）。
     */
    fun hitTestCalendarDay(outerCalendarRect: RectF, data: CalendarData, x: Float, y: Float): CalendarDay? {
        val rect = insetForContent(outerCalendarRect)
        if (x < rect.left || x > rect.right) return null
        val metrics = calendarGridMetrics(rect, data)
        if (y < metrics.gridTop || y > rect.bottom) return null

        val col = ((x - rect.left) / metrics.colWidth).toInt().coerceIn(0, 6)
        val row = ((y - metrics.gridTop) / metrics.rowHeight).toInt()
        if (row < 0 || row >= metrics.rowCount) return null

        val day = data.days.getOrNull(row * 7 + col) ?: return null
        return if (day.isCurrentMonth) day else null
    }

    fun render(
        width: Int,
        height: Int,
        calendarData: CalendarData,
        selectedDayOfMonth: Int?,
        eventsSectionTitle: String,
        events: List<TodayEvent>,
        photo: Bitmap?,
        batteryPercent: Int? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val layout = computeLayout(width, height)

        drawCardBorder(canvas, layout.calendarRect, width)
        drawCardBorder(canvas, layout.eventsRect, width)
        drawCardBorder(canvas, layout.photoRect, width)

        drawCalendarSection(canvas, insetForContent(layout.calendarRect), calendarData, selectedDayOfMonth)
        drawEventsSection(canvas, insetForContent(layout.eventsRect), eventsSectionTitle, events, batteryPercent)
        drawPhotoSection(canvas, insetForContent(layout.photoRect), photo)

        return bitmap
    }

    private fun insetForContent(rect: RectF): RectF {
        val pad = rect.width() * CONTENT_PADDING_RATIO
        return RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
    }

    private fun drawCardBorder(canvas: Canvas, rect: RectF, width: Int) {
        val strokeWidth = width * CARD_STROKE_WIDTH_RATIO
        val corner = width * CARD_CORNER_RADIUS_RATIO
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CARD_BORDER
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val inset = strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            corner,
            corner,
            paint
        )
    }

    private fun calendarGridMetrics(rect: RectF, data: CalendarData): CalendarGridMetrics {
        val titleTextSize = rect.height() * 0.09f
        val headerHeight = rect.height() * 0.14f
        val gridTop = rect.top + titleTextSize * 1.5f + headerHeight
        val gridHeight = rect.bottom - gridTop
        val colWidth = rect.width() / 7f
        val rowCount = data.days.size / 7
        val rowHeight = gridHeight / rowCount
        return CalendarGridMetrics(gridTop, headerHeight, colWidth, rowHeight, rowCount)
    }

    private fun calendarHeaderButtonsForContent(rect: RectF): CalendarHeaderButtons {
        val titleTextSize = rect.height() * 0.09f
        val buttonSize = titleTextSize * 1.4f
        val prevRect = RectF(rect.left, rect.top, rect.left + buttonSize, rect.top + buttonSize)
        val nextRect = RectF(rect.right - buttonSize, rect.top, rect.right, rect.top + buttonSize)
        return CalendarHeaderButtons(prevRect, nextRect)
    }

    private fun drawCalendarSection(canvas: Canvas, rect: RectF, data: CalendarData, selectedDayOfMonth: Int?) {
        val titleTextSize = rect.height() * 0.09f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = titleTextSize
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val titleBaseline = rect.top + titleTextSize
        canvas.drawText("${data.year} 年 ${data.month} 月", rect.centerX(), titleBaseline, titlePaint)

        val buttons = calendarHeaderButtonsForContent(rect)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = titleTextSize
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("<", buttons.prevRect.centerX(), titleBaseline, arrowPaint)
        canvas.drawText(">", buttons.nextRect.centerX(), titleBaseline, arrowPaint)

        val metrics = calendarGridMetrics(rect, data)
        val gridTop = metrics.gridTop
        val colWidth = metrics.colWidth
        val rowHeight = metrics.rowHeight
        val headerHeight = metrics.headerHeight

        val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = headerHeight * 0.55f
            textAlign = Paint.Align.CENTER
        }
        data.weekdayLabels.forEachIndexed { col, label ->
            val cx = rect.left + colWidth * col + colWidth / 2f
            weekdayPaint.color = when (col) {
                6 -> COLOR_SUNDAY_FILL
                5 -> COLOR_SATURDAY
                else -> Color.DKGRAY
            }
            weekdayPaint.isFakeBoldText = col == 5 || col == 6
            canvas.drawText(label, cx, gridTop - headerHeight * 0.35f, weekdayPaint)
        }

        val gridLinePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = rowHeight * 0.4f
            textAlign = Paint.Align.CENTER
        }
        val todayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = rowHeight * 0.06f
        }
        val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 220, 220)
            style = Paint.Style.FILL
        }
        val eventMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(230, 120, 20)
            style = Paint.Style.FILL
        }
        val sundayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SUNDAY_FILL
            style = Paint.Style.FILL
        }
        val saturdayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SATURDAY
            style = Paint.Style.STROKE
            strokeWidth = rowHeight * 0.05f
        }

        data.days.forEachIndexed { index, day ->
            val col = index % 7
            val row = index / 7
            val cellLeft = rect.left + colWidth * col
            val cellTop = gridTop + rowHeight * row
            val cellRect = RectF(cellLeft, cellTop, cellLeft + colWidth, cellTop + rowHeight)

            canvas.drawRect(cellRect, gridLinePaint.also { it.style = Paint.Style.STROKE })

            if (!day.isCurrentMonth) return@forEachIndexed

            val isSelected = day.dayOfMonth == selectedDayOfMonth && !day.isToday
            if (isSelected) {
                val inset = rowHeight * 0.08f
                canvas.drawRect(
                    cellRect.left + inset,
                    cellRect.top + inset,
                    cellRect.right - inset,
                    cellRect.bottom - inset,
                    selectedFillPaint
                )
            }

            val cx = cellRect.centerX()
            val cy = cellRect.centerY()
            val badgeRadius = minOf(colWidth, rowHeight) * 0.36f

            // 形狀差異化：週日＝實心反白圓（最高對比，色彩不準也看得出來），
            // 週六＝外框圓圈，平日＝完全不裝飾，三者一眼就能分辨，不靠色相。
            when (col) {
                6 -> {
                    canvas.drawCircle(cx, cy, badgeRadius, sundayFillPaint)
                    dayPaint.color = Color.WHITE
                }
                5 -> {
                    canvas.drawCircle(cx, cy, badgeRadius, saturdayRingPaint)
                    dayPaint.color = COLOR_SATURDAY
                }
                else -> dayPaint.color = Color.BLACK
            }

            val textY = cy - (dayPaint.ascent() + dayPaint.descent()) / 2f
            canvas.drawText(day.dayOfMonth.toString(), cx, textY, dayPaint)

            if (day.isToday) {
                val inset = rowHeight * 0.08f
                canvas.drawRect(
                    cellRect.left + inset,
                    cellRect.top + inset,
                    cellRect.right - inset,
                    cellRect.bottom - inset,
                    todayBorderPaint
                )
            }

            if (day.hasEvent) {
                val barWidth = colWidth * 0.55f
                val barTop = cellRect.bottom - rowHeight * 0.20f
                val barBottom = cellRect.bottom - rowHeight * 0.09f
                canvas.drawRoundRect(
                    RectF(cx - barWidth / 2f, barTop, cx + barWidth / 2f, barBottom),
                    barBottom - barTop,
                    barBottom - barTop,
                    eventMarkPaint
                )
            }
        }
    }

    private fun drawEventsSection(
        canvas: Canvas,
        rect: RectF,
        title: String,
        events: List<TodayEvent>,
        batteryPercent: Int?
    ) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = rect.height() * 0.10f
            isFakeBoldText = true
        }
        canvas.drawText(title, rect.left, rect.top + titlePaint.textSize, titlePaint)

        // 電量吃緊時在標題列右側放個小警示，不佔額外版面、不搶「今日事項」的視覺重點。
        if (batteryPercent != null && batteryPercent < LOW_BATTERY_THRESHOLD) {
            val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(180, 30, 30)
                textSize = titlePaint.textSize * 0.5f
                textAlign = Paint.Align.RIGHT
                isFakeBoldText = true
            }
            canvas.drawText("電量低 $batteryPercent%", rect.right, rect.top + batteryPaint.textSize, batteryPaint)
        }

        val contentTop = rect.top + titlePaint.textSize * 1.6f

        if (events.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = rect.height() * 0.12f
                textAlign = Paint.Align.CENTER
            }
            val cy = (contentTop + rect.bottom) / 2f - (emptyPaint.ascent() + emptyPaint.descent()) / 2f
            canvas.drawText("無行程", rect.centerX(), cy, emptyPaint)
            return
        }

        // rowHeight 用可用空間平均分配，但設上限：事項很少時（例如只有 1 筆）
        // 不能讓單一列撐滿整個區塊，否則字級會跟著暴增，反而把時間欄位撐爆、蓋到標題。
        val maxRowHeight = rect.height() * 0.22f
        val rowHeight = ((rect.bottom - contentTop) / events.size).coerceAtMost(maxRowHeight)
        val timeColumnWidth = rect.width() * 0.22f

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = rowHeight * 0.38f
            isFakeBoldText = true
        }
        // 時間欄位寬度固定，字級還要能塞進 timeColumnWidth（以最長的 "00:00" 為準），
        // 避免行數少、rowHeight 大時字級把時間欄位撐爆而蓋到標題文字。
        val maxTimeText = "00:00"
        val timeTextWidth = timePaint.measureText(maxTimeText)
        if (timeTextWidth > timeColumnWidth * 0.85f) {
            timePaint.textSize *= (timeColumnWidth * 0.85f) / timeTextWidth
        }
        val titlePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = timePaint.textSize
        }

        events.forEachIndexed { index, event ->
            val rowTop = contentTop + rowHeight * index
            val baseline = rowTop + rowHeight * 0.65f

            canvas.drawText(event.time, rect.left, baseline, timePaint)
            canvas.drawText(event.title, rect.left + timeColumnWidth, baseline, titlePaint2)
        }
    }

    private fun drawPhotoSection(canvas: Canvas, rect: RectF, photo: Bitmap?) {
        if (photo == null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = rect.height() * 0.06f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "長按此區選擇照片資料夾",
                rect.centerX(),
                rect.centerY() - (paint.ascent() + paint.descent()) / 2f,
                paint
            )
            return
        }

        // fit/contain：完整保留原圖比例，依最大能塞入的長或寬縮放，不裁切、置中顯示。
        val scale = minOf(rect.width() / photo.width.toFloat(), rect.height() / photo.height.toFloat())
        val drawWidth = photo.width * scale
        val drawHeight = photo.height * scale
        val left = rect.left + (rect.width() - drawWidth) / 2f
        val top = rect.top + (rect.height() - drawHeight) / 2f
        val destRect = RectF(left, top, left + drawWidth, top + drawHeight)

        canvas.drawBitmap(photo, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG))
    }
}
