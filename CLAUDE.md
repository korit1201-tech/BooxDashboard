# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android dashboard app for the **BOOX Nova Air C** (Android 11, 7.8" Kaleido Plus color E Ink, 3GB RAM) that shows a calendar, today's events, and a rotating photo, meant to run full-screen in the foreground indefinitely. Full product spec/rationale is in `../BOOX-Dashboard-App-開發規劃.md` (one level up from this repo). `README.md` and `CHANGELOG.md` in this repo track current feature status and history — **keep both updated on every commit/push** (standing user requirement, not optional).

## Build & run

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

There is no other JDK on this machine besides Android Studio's bundled one — `JAVA_HOME` must be set inline like above or `gradlew` fails with "JAVA_HOME is not set".

Install/launch on the physical device via adb (`C:\Users\Korit\AppData\Local\Android\Sdk\platform-tools\adb.exe`):

```bash
ADB="C:\Users\Korit\AppData\Local\Android\Sdk\platform-tools\adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell pm enable com.korit.booxdashboard   # BOOX's app-freeze/battery optimizer can disable the package between sessions
"$ADB" shell am force-stop com.korit.booxdashboard
"$ADB" shell am start -n com.korit.booxdashboard/.MainActivity
```

Runtime permissions (`READ_CALENDAR`, `WRITE_CALENDAR`, `READ_EXTERNAL_STORAGE`) can be pre-granted for testing instead of tapping through the system dialog: `"$ADB" shell pm grant com.korit.booxdashboard android.permission.READ_CALENDAR` (etc).

There are no automated tests in this project. Verification is done by installing on the real device and screenshotting.

### Screenshotting the device (git-bash quirks)

- Prefix any device-absolute path (`/sdcard/...`) with `MSYS_NO_PATHCONV=1` or git-bash mangles it into a Windows path.
- This device's `screencap` binary is a custom Onyx build that misparses arguments when adb passes them as separate argv entries. Always wrap the whole command as one shell string:
  `MSYS_NO_PATHCONV=1 "$ADB" shell "screencap -p /sdcard/x.png"` — then `"$ADB" pull /sdcard/x.png <local path>`.
- Typed CJK text via `adb shell input text "中文"` crashes the `input` command on this device (NullPointerException in the injector) — ASCII only.

## Architecture

**Everything is one composited Bitmap, not a View tree.** `DashboardRenderer.render()` draws the calendar, events, and photo cards onto a single `Bitmap` sized exactly to the `ImageView`; `MainActivity` just calls `setImageBitmap` and then `EinkRefresh.partial/full` (wrapping Onyx's `EpdController`) to trigger the actual E Ink update. There is no per-widget invalidation — a change to any section means re-rendering the whole bitmap.

**`DashboardRenderer` is the single source of layout truth for both drawing and touch.** `computeLayout()`, `calendarHeaderButtons()`, and `hitTestCalendarDay()` are called by `MainActivity`'s `GestureDetector` callbacks to figure out what was tapped/long-pressed, using the *exact same* rect math the renderer uses to draw — if you add a new tappable region, add its geometry here, not as a separate calculation in `MainActivity`.

**Data layer follows a real-source → fallback pattern**, all read fresh on every render (no caching):
- `CalendarRepository` queries `CalendarContract.Instances`/`Calendars` (requires `READ_CALENDAR`); `FakeData` is the fallback when that permission is missing. Both take a `displayedMonth` (what the calendar grid shows, browsable via prev/next) decoupled from the real "today" (used only to mark the today-cell and to decide if fallback fake events apply).
- `CalendarPreferences` persists which `calendar_id`s the user has checked to actually display, filtered client-side in `CalendarRepository`'s instance queries.
- `CalendarSubscriptions` + `IcsParser` let the user subscribe to a public ICS URL **without any account login**, by creating a local (`CalendarContract.ACCOUNT_TYPE_LOCAL`) calendar and inserting parsed `VEVENT`s directly. Recurring events pass `RRULE` straight through to `CalendarContract.Events.RRULE` — recurrence expansion is left to the system `CalendarProvider`'s `Instances` materialization (same mechanism as synced Google events), not computed in Kotlin.
- `PhotoRepository.pickRandomPhoto()` builds one merged pool of *lazy* candidates — user-picked `content://` URIs (via the system photo picker), a SAF tree folder, or the legacy `/sdcard/DCIM/Frame/` — and only decodes the one randomly chosen, to avoid decoding every candidate on a 3GB-RAM device.

**Google account integration is deliberately shallow.** The app never does OAuth; it only reads/writes `CalendarContract`, which the OS already populates from any signed-in Google account. "Manage account" actions just launch `Settings.ACTION_SYNC_SETTINGS`. Google's Photos Library API stopped allowing third-party album access in March 2025, so photo "album" support is the system multi-select photo picker (`PickMultipleVisualMedia`), not a Google API integration — see the README section "關於 Google 相簿" before attempting anything fancier here.

**Gesture map (all in `MainActivity`)**: tap calendar day → select date for the events panel; tap the calendar's `<`/`>` → change `displayedMonth`; long-press calendar → calendar settings menu (pick visible calendars / subscribe ICS URL / manage subscriptions / open account settings); long-press photo area → choose folder (SAF) vs. multi-select picker; tap/long-press elsewhere → partial/full E Ink refresh test (left over from early SDK bring-up, harmless to keep).

**Refresh triggers**: hourly `Handler` re-render (also re-syncs ICS subscriptions), `onResume` re-render, and a date-rollover check that snaps `displayedMonth`/`selectedDate` back to the real today if the wall-clock date has advanced. There is no Foreground Service/WakeLock yet (see README "尚未實作") — the hourly timer only fires reliably while the process is alive.
