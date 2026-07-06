# BOOX Dashboard

常駐前景執行的 Android 儀表板 App，畫面同時顯示月曆、當日事項、隨機照片。針對 **BOOX Nova Air C**（Android 11、7.8" Kaleido Plus 彩色 E Ink、3GB RAM）設計。

完整規劃見 [`BOOX-Dashboard-App-開發規劃.md`](../BOOX-Dashboard-App-開發規劃.md)。

## 設計原則

不倚賴 Android View 樹各自重繪，而是把月曆／今日事項／照片三個區塊一次畫進同一張 `Bitmap`（見 `DashboardRenderer`），畫面只做一次性的 `setImageBitmap`，再交給 Onyx `onyxsdk-device` 的 `EpdController` 做整頁或局部刷新。

## 畫面配置

```
┌──────────────┬──────────────┐
│   月曆（左）   │  今日事項（右） │   ← 上半部，佔 40% 高度
│  可翻頁上下月   │  隨點擊的日期換 │
├──────────────┴──────────────┤
│                              │
│           隨機照片            │   ← 下半部，佔 60% 高度，長按可選來源
│                              │
└──────────────────────────────┘
```

照片區的 60% 不是隨便訂的：照片區寬度固定滿版，只要照片是常見的 4:3 橫向（手機拍照最常見的比例），60% 這個高度下 fit/contain 出來的框剛好跟 4:3 吻合，上下幾乎不會留白；往上調高比例並不會讓照片變大（寬度已經頂到滿版），只會留更多用不到的白邊。

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
- **E Ink 刷新**：點擊畫面／切換日期等互動＝局部刷新（`UpdateMode.GU`），長按畫面（非照片/月曆區）＝整頁全刷（`UpdateMode.GC`）；連續 5 次局部刷新後會自動升級成一次全刷，定期恢復 Kaleido Plus 彩色圖層的飽和度，不用等整點或使用者手動長按（見 `EinkRefresh`）
- **自動更新**：每小時整頁重繪一次（同時重新抓取所有訂閱的 ICS 網址，改用條件式 GET，內容沒變就跳過重寫），但只有真的偵測到日期跨天時才強制整頁全刷，其餘小時走跟互動同一套 GU／自動升級全刷邏輯，減少頻繁「喚醒面板做全域刷新」的耗電
- **行事曆即時自動刷新**：註冊 `CalendarContract` 的 `ContentObserver`，只要系統行事曆資料庫一有變動（Google 同步落地、或任何 App 改了行事曆），畫面立刻自動重畫，不必等整點、也不必手動點。每次互動／回前景還會主動 `requestSync` 要求 Google 立即同步，讓手機上的編輯更快出現（同步是非同步的，落地後由這個 observer 負責自動刷新畫面）

### 關於 Google 相簿

Google 在 2025 年 3 月起收回了 Photos Library API 讀取使用者既有相簿／自動同步的權限（`photoslibrary.readonly` 等 scope 已失效），第三方 App 無法再做到「勾選相簿、之後自動同步新照片」。目前用 Android 系統相片選擇器（`PickMultipleVisualMedia`）取代，效果是「一次多選照片」而非「訂閱相簿」，新照片需要使用者自己重新選過。若裝置沒安裝 Google 相簿 App，選擇器裡就不會出現 Google 相簿這個來源選項。

### 關於免登入訂閱行事曆

跟 Google 相簿不同，行事曆這邊不需要繞任何 API 限制：`CalendarContract` 本來就允許任何 App 建立 `ACCOUNT_TYPE_LOCAL` 的本機行事曆並直接寫入事件，不必綁定系統帳號。訂閱網址時會抓取該 ICS 內容、解析 `SUMMARY`/`DTSTART`/`DTEND`/`RRULE` 等欄位寫入本機行事曆；重複事件的 `RRULE` 原封不動存進去，展開重複規則交給系統 `CalendarProvider`（跟 Google 同步下來的事件用同一套機制）。已用 Taiwan 公開假日 ICS 實測過，328 筆事件正確解析寫入。

## ⚠️ 重要：讓儀表板保持自動更新（BOOX 背景凍結）

BOOX 系統的「凍結設置」預設會**在安裝任何 App 後自動把它加入凍結名單**。被凍結的 App 一旦離開前景就會被系統整個停用，導致這個儀表板停在舊畫面、不再自動換照片與更新行事曆（**這正是「放著不動就不更新、要手動點才會動」的根本原因，與 App 程式碼無關**）。

解法（每次全新安裝後做一次）：

1. 回 BOOX 桌面 →「應用」
2. 點右上角的「雪花 ❄️」圖示（凍結設置）
3. 找到「BOOX Dashboard」，把它的開關切成 **OFF（不凍結）**

若不想每次重裝都重做，可直接關掉該頁最上面的「安裝 APP 後自動開啟凍結」。App 首次啟動時也會自動跳出這段提醒（之後可從長按月曆的「行事曆設定」選單裡的「背景凍結提醒」再叫出來）。

## 已知限制

- 曾試過寫一個 `FrontLightManager`，App 啟動／每小時整理時呼叫 Onyx SDK 關閉前光藉此省電，實機測試後移除：這台裝置的 ROM 對 Onyx SDK 硬體偵測用的 hidden API 反射全部回傳 blacklist/denied，SDK fallback 成通用裝置，關不了燈，裝置上也查不到能繞開 SDK 直接寫的前光 `Settings` key。要真的省這段電，目前只能請使用者自己去系統的前光控制面板手動關閉
- 曾短暫改用 `UpdateMode.REGAL` 取代 `GU` 做局部刷新，實機肉眼確認後發現面板根本沒有真的刷新（Bitmap 內容有更新、screencap 截得到，但 E Ink 光學上沒反應）——原因跟前光失效一樣，這台 ROM 對 `EpdController` 硬體偵測用的 hidden API 反射也被系統黑名單擋掉，REGAL 因此靜默失效。已改回 `GU`（見 0.2.1），之後不要再嘗試 REGAL
- 曾試過用「前景服務 + `PARTIAL_WAKE_LOCK`」防止 App 被凍結，實機無效並已移除：這台 ROM 直接把 WakeLock 標記為 `forbidden`，且 App 是被 Onyx 的 `ApplicationFreezeHelper` 從系統層整個停用（不是標準 Android Doze），App 端程式碼無法否決。正解是上面「重要」段落的裝置端凍結設定，不要再嘗試前景服務／WakeLock

## 尚未實作（見規劃書 Phase 3/4）

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
