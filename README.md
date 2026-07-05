# BOOX Dashboard

常駐前景執行的 Android 儀表板 App，畫面同時顯示月曆、當日事項、隨機照片。針對 **BOOX Nova Air C**（Android 11、7.8" Kaleido Plus 彩色 E Ink、3GB RAM）設計。

完整規劃見 [`BOOX-Dashboard-App-開發規劃.md`](../BOOX-Dashboard-App-開發規劃.md)。

## 設計原則

不倚賴 Android View 樹各自重繪，而是把月曆／今日事項／照片三個區塊一次畫進同一張 `Bitmap`（見 `DashboardRenderer`），畫面只做一次性的 `setImageBitmap`，再交給 Onyx `onyxsdk-device` 的 `EpdController` 做整頁或局部刷新。

## 畫面配置

```
┌──────────────┬──────────────┐
│   月曆（左）   │  今日事項（右） │   ← 上半部
│  可翻頁上下月   │  隨點擊的日期換 │
├──────────────┴──────────────┤
│           隨機照片            │   ← 下半部，長按可選資料夾
└──────────────────────────────┘
```

## 功能現況

- **月曆**：當月月曆，今天粗框標示，有事項的日子用色塊標示；週六外框圓圈／週日實心反白圓（形狀差異化，不靠色相，適應低飽和度彩色面板）；標題置中，左右箭頭可翻上一月/下一月
- **今日事項**：點擊月曆任一天，右側改顯示該天的事件；資料來源為系統 `CalendarContract`（需 `READ_CALENDAR` 權限，未授權時退回假資料）
- **照片**：長按照片區可透過系統資料夾選擇器（SAF）指定照片來源，選取的資料夾會持久化保存；抓不到照片時顯示引導文字而非假圖；圖片一律用 fit/contain 依原比例縮放，不裁切
- **E Ink 刷新測試**：點擊畫面＝局部刷新（`UpdateMode.GU`），長按畫面（非照片區）＝整頁全刷（`UpdateMode.GC`）
- **自動更新**：每小時整頁刷新一次，並在偵測到真實日期跨天時自動跳回新的「今天」

## 尚未實作（見規劃書 Phase 3/4）

- Foreground Service + `PARTIAL_WAKE_LOCK` 常駐（目前僅靠 App 內 `Handler` 計時，螢幕深度休眠/Doze 下不保證準時）
- 背景凍結白名單引導 UI
- 刷新頻率/殘影的實機長期調校

## 建置

```bash
JAVA_HOME="/path/to/jdk" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 Android SDK；`onyxsdk-device` 依賴來自 Onyx 官方 Maven repo（見 `settings.gradle.kts`）。

## 權限

| 權限 | 用途 |
|---|---|
| `READ_CALENDAR` | 讀取系統行事曆事件 |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | 掃描舊版路徑 `/sdcard/DCIM/Frame/` 的照片（SAF 選過資料夾後非必要） |

首次啟動會跳系統權限請求視窗。
