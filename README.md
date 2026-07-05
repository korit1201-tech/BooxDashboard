# BOOX Dashboard

常駐前景執行的 Android 儀表板 App，畫面同時顯示月曆、當日事項、隨機照片。針對 **BOOX Nova Air C**（Android 11、7.8" Kaleido Plus 彩色 E Ink、3GB RAM）設計。

完整規劃見 [`BOOX-Dashboard-App-開發規劃.md`](../BOOX-Dashboard-App-開發規劃.md)。

## 設計原則

不倚賴 Android View 樹各自重繪，而是把月曆／今日事項／照片三個區塊一次畫進同一張 `Bitmap`（見 `DashboardRenderer`），畫面只做一次性的 `setImageBitmap`，再交給 Onyx `onyxsdk-device` 的 `EpdController` 做整頁或局部刷新。

## 畫面配置

```
┌──────────────┬──────────────┐
│   月曆（左）   │  今日事項（右） │   ← 上半部，佔 30% 高度
│  可翻頁上下月   │  隨點擊的日期換 │
├──────────────┴──────────────┤
│                              │
│           隨機照片            │   ← 下半部，佔 70% 高度，長按可選來源
│                              │
└──────────────────────────────┘
```

## 功能現況

- **月曆**：當月月曆，今天粗框標示，有事項的日子用色塊標示；週六外框圓圈／週日實心反白圓（形狀差異化，不靠色相，適應低飽和度彩色面板）；標題置中，左右箭頭可翻上一月/下一月
- **今日事項**：點擊月曆任一天，右側改顯示該天的事件；資料來源為系統 `CalendarContract`（需 `READ_CALENDAR` 權限，未授權時退回假資料）
- **多行事曆勾選**：長按月曆區開「行事曆設定」選單：
  - 「選擇要顯示的行事曆」：挑選要顯示哪些行事曆（一個 Google 帳號常有多個，如節日曆／家庭／個人）
  - 「訂閱行事曆網址（免登入）」：不需要任何帳號登入，直接用公開的 ICS 網址建立一個本機行事曆（`CalendarSubscriptions` + `IcsParser`），跟 Google 帳號的行事曆一樣可以勾選顯示/隱藏
  - 「管理已訂閱的網址」：移除不要的訂閱
  - 「管理 Google 帳號」：直達系統帳號設定（新增/重新登入/手動同步都在那邊，App 本身不做 OAuth）
- **照片**：長按照片區可選「資料夾（SAF）」或「系統相片選擇器多選」，兩種來源會合併成同一個隨機池。相片選擇器可從任何有提供圖片的 App 選（含 Google 相簿 App，前提是裝置上有裝該 App）；抓不到照片時顯示引導文字而非假圖；圖片一律用 fit/contain 依原比例縮放，不裁切
- **電量提示**：低於 20% 時「今日事項」卡片標題列右側顯示紅字警示
- **E Ink 刷新測試**：點擊畫面＝局部刷新（`UpdateMode.GU`），長按畫面（非照片/月曆區）＝整頁全刷（`UpdateMode.GC`）
- **自動更新**：每小時整頁刷新一次（同時重新抓取所有訂閱的 ICS 網址），並在偵測到真實日期跨天時自動跳回新的「今天」；每次 App 從背景回到前景也會重新查詢一次資料

### 關於 Google 相簿

Google 在 2025 年 3 月起收回了 Photos Library API 讀取使用者既有相簿／自動同步的權限（`photoslibrary.readonly` 等 scope 已失效），第三方 App 無法再做到「勾選相簿、之後自動同步新照片」。目前用 Android 系統相片選擇器（`PickMultipleVisualMedia`）取代，效果是「一次多選照片」而非「訂閱相簿」，新照片需要使用者自己重新選過。若裝置沒安裝 Google 相簿 App，選擇器裡就不會出現 Google 相簿這個來源選項。

### 關於免登入訂閱行事曆

跟 Google 相簿不同，行事曆這邊不需要繞任何 API 限制：`CalendarContract` 本來就允許任何 App 建立 `ACCOUNT_TYPE_LOCAL` 的本機行事曆並直接寫入事件，不必綁定系統帳號。訂閱網址時會抓取該 ICS 內容、解析 `SUMMARY`/`DTSTART`/`DTEND`/`RRULE` 等欄位寫入本機行事曆；重複事件的 `RRULE` 原封不動存進去，展開重複規則交給系統 `CalendarProvider`（跟 Google 同步下來的事件用同一套機制）。已用 Taiwan 公開假日 ICS 實測過，328 筆事件正確解析寫入。

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
| `WRITE_CALENDAR` | 建立/更新訂閱 ICS 網址用的本機行事曆與事件 |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | 掃描舊版路徑 `/sdcard/DCIM/Frame/` 的照片（SAF 選過資料夾或用相片選擇器後非必要） |
| `INTERNET` | 抓取訂閱的 ICS 網址內容 |

首次啟動會跳系統權限請求視窗。
