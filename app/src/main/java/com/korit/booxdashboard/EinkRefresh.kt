package com.korit.booxdashboard

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * 包一層 Onyx EpdController，讓刷新模式的呼叫點集中管理。
 * GC：整頁全刷（清殘影，同時重置彩色圖層飽和度）。
 * GU：局部刷新（換照片/事項用，不閃爍全頁）。
 *
 * 曾經改用官方文件說殘影更少的 UpdateMode.REGAL，但實機驗證後發現面板並沒有真的跟著刷新
 * （Bitmap 資料本身正確更新，screencap 也截得到新內容，只是 E Ink 面板光學上沒有反應）——
 * 這台 BOOX Nova Air C 的 ROM 對 EpdController 硬體偵測用的 hidden API 反射一樣被系統
 * 黑名單擋掉（跟 FrontLightManager 關前光失效同個成因），REGAL 因此靜默失效、不會拋例外。
 * 所以局部刷新固定用 GU，不要再換成 REGAL。
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
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.GU)
        EpdController.invalidate(view, UpdateMode.GU)
    }

    fun full(view: View) {
        partialRefreshesSinceFull = 0
        EpdController.setViewDefaultUpdateMode(view, UpdateMode.GC)
        EpdController.invalidate(view, UpdateMode.GC)
    }
}
