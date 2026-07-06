package com.korit.booxdashboard

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * 包一層 Onyx EpdController，讓刷新模式的呼叫點集中管理。
 * GC：整頁全刷（清殘影，同時重置彩色圖層飽和度）。
 * REGAL：官方文件說是殘影最少的局部更新模式、適合淺色背景，取代原本 Phase 1 沿用 demo 的 GU。
 *
 * Kaleido Plus 彩色面板連續做局部刷新（不會完整刷新彩色濾光層）幾次之後，色彩會逐漸變淡，
 * 需要定期插入一次 GC 全刷才能讓飽和度恢復；這裡用次數計數自動觸發，不必等到整點的
 * hourly 全刷，也不用使用者自己長按觸發全刷。
 */
object EinkRefresh {

    private const val PARTIAL_REFRESHES_BEFORE_FORCED_FULL = 5

    private var partialRefreshesSinceFull = 0

    fun partial(view: View) {
        partialRefreshesSinceFull++
        if (partialRefreshesSinceFull >= PARTIAL_REFRESHES_BEFORE_FORCED_FULL) {
            full(view)
            return
        }
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.REGAL)
        EpdController.invalidate(view, UpdateMode.REGAL)
    }

    fun full(view: View) {
        partialRefreshesSinceFull = 0
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.GC)
        EpdController.invalidate(view, UpdateMode.GC)
    }
}
