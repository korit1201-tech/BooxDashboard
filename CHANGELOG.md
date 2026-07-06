# Changelog

本專案的重要變更都記錄在這份檔案。格式參考 [Keep a Changelog](https://keepachangelog.com/)。

## [Unreleased]

### Added
- `EinkRefresh` 重構：互動觸發的局部刷新改用 `UpdateMode.REGAL`（官方文件說是殘影最少的局部模式，取代原本沿用 Phase 1 demo 的 `GU`），並新增自動計數——連續 5 次局部刷新後自動升級成一次 `GC` 全刷，藉此定期恢復 Kaleido Plus 彩色圖層的飽和度，不必等整點的 hourly 全刷或使用者手動長按
- 每小時的例行整理不再無條件強制整頁全刷（`GC` 是多輪波形的全域刷新，耗電明顯比局部刷新高）：改成跟互動共用同一套 REGAL／每 5 次自動升級全刷的邏輯，只有真的偵測到跨天時才強制全刷；一天 24 次「喚醒面板刷新」大部分變輕量，同時仍會定期升級成全刷維持色彩飽和度
- ICS 訂閱同步改用條件式 GET（帶上次拿到的 `Last-Modified`/`ETag`），伺服器回 304 時直接跳過整批刪除／重寫行事曆事件，省下每小時一次不必要的資料庫負擔（部分 ICS 主機不回這兩個標頭時，行為退回原本每次全抓，不會變差）；重新抓取的頻率維持每小時一次不變
- 電量低於 20% 時，「今日事項」卡片標題列右側顯示紅字電量警示
- 長按月曆區可勾選要顯示哪些行事曆（支援同一 Google 帳號下的多個行事曆），對話框內附「管理 Google 帳號」捷徑開啟系統帳號設定頁面
- 每次 App 從背景回到前景（onResume）都會重新查詢一次行事曆／照片資料
- 照片來源新增「系統相片選擇器多選」（`PickMultipleVisualMedia`），與資料夾內容合併成同一個隨機池；可從裝置上任何提供圖片的 App 選（含 Google 相簿 App，前提是該 App 已安裝）
- 新增「訂閱行事曆網址（免登入）」：用 `CalendarContract.ACCOUNT_TYPE_LOCAL` 建立本機行事曆，搭配自寫的精簡 ICS 解析器（`IcsParser`）抓取公開 ICS 網址內容並寫入事件，完全不需要登入任何帳號；重複事件的 `RRULE` 原封不動存進去，展開交給系統 `CalendarProvider`。已用 Taiwan 公開假日 ICS 實測，328 筆事件正確解析寫入，且訂閱的行事曆會出現在既有的「選擇要顯示的行事曆」清單中
- 「行事曆設定」長按選單新增「管理已訂閱的網址」，可移除不要的訂閱
- 每小時自動整理時一併重新抓取所有訂閱的 ICS 網址
- 下方照片區高度比例從 50% 調整為 70%，月曆／今日事項區從 50% 縮為 30%

### Fixed
- 電量讀取改用 `ACTION_BATTERY_CHANGED` sticky broadcast，因為較新的 `BatteryManager.BATTERY_PROPERTY_CAPACITY` 在這台 BOOX 的 Onyx 客製 ROM 上不可靠（常回傳 -1）
- 訂閱行事曆網址輸入框改用 URI 專用鍵盤（`TYPE_TEXT_VARIATION_URI`），避免預設文字鍵盤自動把首字母大寫，把貼上的 `https://` 悄悄改成 `Https://` 導致讀取失敗
- 照片區比例從 70% 調成 60%：實測使用者相片全部是 4:3 橫向（用 Pillow 檢查裝置上 7 張真實照片，尺寸落在 6560x4928／4096x3072，比例 1.33），而照片區寬度本來就固定滿版，70% 高度下 fit/contain 會在上下留下約 220px 空白；60% 讓框的長寬比幾乎完全貼合 4:3，空白降到 30px 內，且照片實際顯示尺寸完全不變（本來就是寬度吃滿）

### Changed
- 原本規劃走 Google Photos Library API 整合相簿，調查後發現 Google 已於 2025 年 3 月收回第三方讀取既有相簿/自動同步的權限，故改採 Android 系統相片選擇器，效果為一次性多選而非持續同步

### 已知限制
- 曾嘗試新增 `FrontLightManager`（App 啟動／每小時整理時呼叫 Onyx SDK `Device.currentDevice().closeFrontLight()` 自動關前光），實機測試後移除：這台 BOOX Nova Air C 的 Android 11 ROM 上，`Device.currentDevice()` 內部靠 hidden API 反射（`android.onyx.hardware.DeviceController`）偵測硬體能力，這些反射呼叫全部被系統的 hidden-API 黑名單擋掉（`blacklist, reflection, denied`），偵測 fallback 成抓不到任何控制能力的通用裝置，`isLightOn()`/`closeFrontLight()` 實際上是空的，不會崩潰但也不會真的關燈。裝置上也查不到能繞開 SDK、直接寫入的前光 `Settings` key。若要真的關前光，目前只能請使用者自己在系統面板手動關閉
- `UpdateMode.REGAL` 是否真的按預期套用到彩色圖層，同樣受上述 hidden API 限制影響（`EpdController` 底層也走同一套裝置偵測），且 screencap 只能截到 framebuffer 內容、無法反映 E Ink 實際刷新時的殘影/色彩光學效果，需要使用者盯著實機肉眼比對 REGAL 前後的觀感才能確認

## [0.1.0] - 2026-07-06

### Added
- Phase 1：假資料靜態排版驗證（月曆／今日事項／照片三區塊合成單一 Bitmap 渲染）
- 整合 Onyx `onyxsdk-device`：點擊畫面觸發局部刷新（GU），長按觸發整頁全刷（GC）
- Phase 2：串接真實資料——`CalendarContract` 讀取系統行事曆、掃描 `/sdcard/DCIM/Frame/` 讀取照片
- 版面改為上半部（月曆＋今日事項左右對分）／下半部（照片）
- 點擊月曆任一天可切換右側「今日事項」顯示該天的事件
- 月曆有事項的日子加上明顯色塊標示（原本的小圓點改為粗底線色塊）
- 週六／週日依星期上色；照片區長按可用系統資料夾選擇器（SAF）指定照片來源，並持久化保存所選路徑
- 三區塊改為圓角卡片邊框；照片改用 fit/contain（依原比例縮放、不裁切）；週六／週日改用形狀差異化（外框圓圈／實心反白圓），因彩色面板飽和度低，單靠色相不夠明顯；月曆標題置中並加上左右翻頁箭頭（可瀏覽上下月，與「今天」脫鉤）；每小時自動整頁刷新，並在偵測到日期跨天時自動跳回新的「今天」

### Fixed
- 修正「今日事項」在事項筆數很少時，字級隨列高暴增導致時間欄位把標題文字蓋住的排版錯誤

### Infrastructure
- 初次建立 git 版控並推上 GitHub（private repo `korit1201-tech/BooxDashboard`）
