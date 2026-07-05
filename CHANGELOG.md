# Changelog

本專案的重要變更都記錄在這份檔案。格式參考 [Keep a Changelog](https://keepachangelog.com/)。

## [Unreleased]

### Added
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

### Changed
- 原本規劃走 Google Photos Library API 整合相簿，調查後發現 Google 已於 2025 年 3 月收回第三方讀取既有相簿/自動同步的權限，故改採 Android 系統相片選擇器，效果為一次性多選而非持續同步

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
