package com.korit.booxdashboard

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * 包一層 Onyx EpdController，讓刷新模式的呼叫點集中管理。
 * GC：整頁全刷（清殘影），GU：局部刷新（換照片/事項用，不閃爍全頁）。
 */
object EinkRefresh {

    fun partial(view: View) {
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.GU)
        EpdController.invalidate(view, UpdateMode.GU)
    }

    fun full(view: View) {
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.GC)
        EpdController.invalidate(view, UpdateMode.GC)
    }
}
