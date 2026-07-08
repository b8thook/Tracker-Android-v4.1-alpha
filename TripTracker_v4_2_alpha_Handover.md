# TRIP TRACKER ANDROID
## v4.2-alpha · Developer Handover Document
**8 July 2026 · Roy / Baithook Driver**

---

## ⚠ HARD RULES — READ FIRST, NEVER VIOLATE

### Roy Does Not Write Code
Roy has zero coding background. All instructions must be GUI-only.

- **NEVER** suggest: terminal commands, bash, curl, adb, command line, Android Studio, or any text file editing
- **ALWAYS** use: GitHub website (browser) for file uploads, WinSCP drag-drop for VPS files

### File Delivery Rules
- Always produce complete replacement files — never give snippets unless explicitly asked
- Claude must read all relevant source files from GitHub before writing any changes — non-negotiable
- A debug/verification run must pass before any final code is committed to GitHub
- Roy downloads files from Claude and uploads via GitHub browser UI

### Build Process Rules
- Always uninstall old APK before installing new build
- Bypass Play Protect when prompted — expected for debug APKs (tap More details → Install anyway)
- Never build without reading all existing source files first
- Run the 22-point verification checklist before every commit — no exceptions

### Infrastructure Isolation

| Service | Port | Trip Tracker May Touch? |
|---|---|---|
| tcpbot.service | 8080–8085, 8888 | ❌ NEVER |
| central-store.service | 8090 | ✅ Yes — Trip Tracker only |
| tripmerge.service (Phase 5) | 8091 | ✅ Yes — when built |
| ARIA / Journal | tcp.neatfolk.co | ❌ Never touch |

### Handover Format Rule
All handover documents must be produced in **Markdown format (.md files)** — never .docx or any other format.

---

## 1. Repository & Access

| Item | Value |
|---|---|
| GitHub Repo | `b8thook/Tracker-Android-v4.1-alpha` |
| Current Branch | main |
| Current Version | v4.2-alpha (versionCode 2) |
| Build System | GitHub Actions — auto-triggers on every commit to main |
| Build Time | Approx. 1m 45s |
| APK Type | Debug (unsigned, sideloaded) |
| APK Download | GitHub → Actions tab → latest run → Artifacts → TripTracker-v4.1-alpha-Debug (ZIP) |
| GitHub API Access | Personal Access Token available — Claude can fetch/commit files directly via bash |
| Test Device | Samsung Galaxy S23 Ultra · Android 16 · One UI 8 |

> ⚠ **IMPORTANT:** The correct repo name is `b8thook/Tracker-Android-v4.1-alpha` — not `Trip-Tracker---Android` or any other variant. Confirm via API before every session.

---

## 2. Session Start Protocol — Every New Session

Follow this before writing any code, every session without exception.

| Step | Action |
|---|---|
| 1 | Read this handover document in full |
| 2 | Search project chat history for any prior sessions |
| 3 | Confirm correct repo name via GitHub API: `b8thook/Tracker-Android-v4.1-alpha` |
| 4 | Fetch and read ALL relevant source files from GitHub before writing any code |
| 5 | Run pre-build checks: ic_car.xml (zero circles), FloatingOverlayService.kt (pill safe calls), build.yml artifact name |
| 6 | Write code changes |
| 7 | Run full 22-point verification checklist — ALL must pass |
| 8 | Commit to GitHub via API — confirm success SHA returned |
| 9 | Confirm GitHub Actions build triggered and completed successfully |

---

## 3. Pre-Build Verification Checklist — 22 Points

> ⚠ Run ALL checks before committing any file. Never skip — a single missed check has caused bad builds before.

| File | Pattern to Verify | Why |
|---|---|---|
| GrabAccessibilityService.kt | `fun toast` | Overlay toast helper must be present |
| GrabAccessibilityService.kt | `AS connected` | onServiceConnected overlay message |
| GrabAccessibilityService.kt | `Booking screen detected` | Screen 1 overlay message |
| GrabAccessibilityService.kt | `Post-trip screen detected` | Post-trip overlay message |
| GrabAccessibilityService.kt | `WindowManager` / `TYPE_APPLICATION_OVERLAY` | v4.2 overlay approach — not Toast.makeText |
| GrabAccessibilityService.kt | `currentToastView` | Overlay dedup variable |
| AccessibilityScreenParser.kt | `isHistoryScreen` | History screen exclusion |
| AccessibilityScreenParser.kt | `Route taken` | Job Details exclusion |
| FloatingOverlayService.kt | `pendingAutoFare` | Timing fix for auto-fill |
| FloatingOverlayService.kt | `applyFareToDialog` | Fare application helper |
| FloatingOverlayService.kt | `pill?.` | Safe call — no bare `pill.` anywhere |
| MainActivity.kt | `btnFilterDay` | Filter buttons bound |
| MainActivity.kt | `DatePickerDialog` | Calendar picker in Add Trip |
| MainActivity.kt | `confirmClearMetadata` | Reset AS data option |
| MainActivity.kt | `TripTracker_Dialog` | Dialog theme applied |
| MainActivity.kt | `sortedByDescending` | Sort fallback for startMs=0 |
| SwipeBar.kt | `0.88` | Raised cancel threshold |
| TripMetadataDao.kt | `deleteAll` | Reset AS data query |
| AndroidManifest.xml | `POST_NOTIFICATIONS` | Toast/notification permission |
| build.gradle | `4.1-alpha` | Version tag |
| overlay_widget.xml | `160dp` | Wider SwipeBar |
| activity_main.xml | `btnAddTrip` / `btnFilterDay` | Add Trip button + filter bar present |

**Additional mandatory checks before writing any code:**
- `ic_car.xml` must contain **zero** `<circle>` elements — replace all with `<path>` equivalents
- `build.yml` artifact name must match target version string
- All source files must be fetched from GitHub and read before writing any changes

---

## 4. Current Build State — v4.2-alpha

### What Changed in v4.2-alpha (8 July 2026)

**Single file changed: `GrabAccessibilityService.kt`**

| Change | Detail |
|---|---|
| Toast → WindowManager overlay | Replaced `Toast.makeText()` with `TYPE_APPLICATION_OVERLAY` drawn at `Gravity.TOP` (y=150px). Android 11+ ignores `Toast.setGravity()` from background services — toasts were appearing mid-screen blocking Grab booking and Rate Your Experience content. |
| `currentToastView` tracking | Added field to dismiss previous overlay before showing next — prevents stacking if events fire within 3-second display window. |
| `onDestroy()` cleanup | Added overlay removal in `onDestroy()` in case service is killed while a message is visible. |
| Import cleanup | Removed `android.widget.Toast`. Added `Color`, `PixelFormat`, `GradientDrawable`, `Gravity`, `WindowManager`, `TextView`. |

### All Files — Current State

| File | Path | Status |
|---|---|---|
| GrabAccessibilityService.kt | java/co/neatfolk/triptracker/ | ⚠ UPDATED v4.2-alpha |
| AccessibilityScreenParser.kt | java/co/neatfolk/triptracker/ | Unchanged from v4.1-alpha |
| FloatingOverlayService.kt | java/co/neatfolk/triptracker/ | Unchanged from v4.1-alpha |
| GrabNotificationListener.kt | java/co/neatfolk/triptracker/ | Unchanged from v4.1-alpha |
| MainActivity.kt | java/co/neatfolk/triptracker/ | Unchanged from v4.1-alpha |
| SwipeBar.kt | java/co/neatfolk/triptracker/ | Unchanged from v4.1-alpha |
| TripMetadataDao.kt | java/.../data/ | Unchanged from v4.1-alpha |
| AndroidManifest.xml | app/src/main/ | Unchanged from v4.1-alpha |
| activity_main.xml | res/layout/ | Unchanged from v4.1-alpha |
| overlay_widget.xml | res/layout/ | Unchanged from v4.1-alpha |
| themes.xml + values-night/ | res/values/ | Unchanged from v4.1-alpha |

---

## 5. Reinstall Checklist — After Every APK Update

> ⚠ Every new APK install resets **both** Accessibility Service **and** Notification Access. Both must be re-enabled or nothing will work.

| Step | Action |
|---|---|
| 1 | Uninstall current APK: Settings → Apps → Trip Tracker → Uninstall |
| 2 | Install new APK: My Files → Downloads → app-debug.apk → Install → tap **More details** → **Install anyway** (bypasses Play Protect) |
| 3 | Enable Accessibility Service: Settings → Accessibility → Installed Apps → Trip Tracker Auto Capture → toggle ON → Allow |
| 4 | **Enable Notification Access: Settings → Apps → ⋮ → Special access → Notification access → Trip Tracker → toggle ON** |
| 5 | If toggle greyed out: Settings → Apps → Trip Tracker → ⋮ → Allow restricted settings → then toggle ON |
| 6 | Add to Never Sleeping Apps: Settings → Battery → Background usage limits → Never sleeping apps → + → Trip Tracker |
| 7 | Open Trip Tracker → grant POST_NOTIFICATIONS when prompted → Allow |
| 8 | Confirm: Open Grab Driver — overlay message should appear at **top of screen** within 3 seconds: `AS connected - Trip Tracker monitoring Grab` |

> ⚠ **Auto-start not working after reinstall = Notification Access was not re-enabled. This is the #1 missed step. Always check this first before assuming a code bug.**

---

## 6. Auto-Start Architecture

Two completely independent services. Both need their own permission re-enabled after every reinstall.

| Component | Role | Permission Required |
|---|---|---|
| GrabNotificationListener.kt | Catches Grab booking notifications → fires `ACTION_GRAB_BOOKING` → FloatingOverlayService starts trip | Notification Access |
| GrabAccessibilityService.kt | Monitors Grab screen content → captures fare/addresses → fires top-screen overlays | Accessibility Service |
| FloatingOverlayService.kt | Receives `ACTION_GRAB_BOOKING` via `grabBookingReceiver` → calls `startTrip(autoStarted=true)` | Runs as foreground service |

**Diagnostic guide:**
- Auto-start broken but AS overlay still fires → **Notification Access** is the problem
- Neither works → check both permissions

---

## 7. Known Issues & Status

| Issue | Status | Notes |
|---|---|---|
| AS overlay position | ✅ Fixed v4.2 | WindowManager at Gravity.TOP (y=150px). Was mid-screen blocking Grab content on Android 11+. |
| Auto-start on booking | ✅ Working | Requires Notification Access re-enabled after every reinstall. |
| Screen 3 capture (Route Details) | ⚠ Needs field validation | Toast fires but parser may not extract addresses/passenger correctly. Validate across 5+ real trips. |
| Auto-fill fare dialog | ⚠ Needs field validation | `pendingAutoFare` fix in place but not field-confirmed. Roy must swipe right on pill to open fare dialog. |
| trip_metadata corrupted data | ⚠ Reset required | Records from 25 May 2026 testing are corrupted. Use More → Reset AS capture data. trips table unaffected. |
| Screen 3 data not in trip list | ⏳ Phase 5 | trip_metadata captured but not merged into trips table. Requires VPS tripmerge.service. |

---

## 8. Known Bugs & Fix Log

| Bug | Trigger | Fix Applied | Version |
|---|---|---|---|
| ic_car.xml crash on build | Android vector doesn't support `<circle>` | Replace all `<circle>` with `<path>` equivalents only | All versions |
| `pill.` null crash | Every rewrite of FloatingOverlayService.kt | Always use `pill?.` safe call operator | v3.8+ |
| Toast blocked mid-screen | Android 11+ ignores `Toast.setGravity()` from background | WindowManager `TYPE_APPLICATION_OVERLAY` at `Gravity.TOP` | v4.2-alpha |
| AS toast stacking | Multiple AS events within 3-second window | `currentToastView` tracking — remove previous before showing next | v4.2-alpha |
| AS firing on Job Details screen | Manual Grab history browse triggers `isPostTrip()` | `isPostTrip()` excludes `Route taken` / `Job Details` text | v4.1-alpha |
| Auto-fill fare timing | AS broadcast arrives after fare dialog opens | `pendingAutoFare` stores fare and applies when dialog opens | v4.1-alpha |
| GrabAccessibilityService.kt omitted from zip | Rebuild from zip silently overwrites previously fixed file | 22-point verification checklist — run before every zip | v4.1-alpha |
| Auto-start stops after reinstall | Notification Access reset on every APK install | Re-enable Notification Access AND Accessibility Service after every install | All versions |
| Dark mode dialog unreadable | Dialog theme missing for dark mode | `TripTracker.Dialog` style added to themes.xml and values-night/ | v4.1-alpha |
| Database migration crash | Room schema version mismatch on reinstall | `fallbackToDestructiveMigration()` — trips backed up to Central Store | All versions |

---

## 9. Development Roadmap

| Phase | Focus | Status |
|---|---|---|
| Phase 1 | Foundation — overlay, GPS, Room DB, sync | ✅ COMPLETE |
| Phase 2 | Mid-trip buttons, collapsed pill, SwipeBar | ✅ COMPLETE |
| Phase 3 | Accessibility Service — autonomous data capture | 🔄 IN PROGRESS — v4.2-alpha |
| Phase 4 | Idle GPS, URA zone mapping, analytics dashboard, zone recommendations | ⏳ PENDING |
| Phase 5 | VPS tripmerge (port 8091), merge trip_metadata into trips, OneDrive Graph API, Telegram daily summary | ⏳ PENDING |
| Phase 6 | Back-to-back booking engine | ⏸ DEFERRED |
| Phase 7 | Commercial launch / Play Store | 🔮 FUTURE |

### Immediate Next Priorities (Phase 3 completion)
- Field-validate AS Screen 3 parser across 5+ real trips — confirm addresses and passenger names capture correctly
- Field-validate auto-fill fare dialog — confirm `pendingAutoFare` applies correctly
- Field-validate post-trip detection — confirm Job Details screen no longer triggers false positives
- Remove debug overlay calls once AS confirmed stable — or convert to optional debug toggle in Settings
- **Do NOT start Phase 4 until Screen 3 and post-trip capture confirmed reliable across 5+ real trips**

---

## 10. Infrastructure Reference

| Resource | Value |
|---|---|
| GitHub Repo | `b8thook/Tracker-Android-v4.1-alpha` ← verify this before every session |
| VPS IP | 207.148.72.180 (Vultr Singapore) |
| Central Store | central-store.service — port 8090 |
| Trip Merge (Phase 5) | tripmerge.service — port 8091 — NOT YET BUILT |
| App Package | co.neatfolk.triptracker |
| Grab Driver Package | com.grabtaxi.driver2 |
| Domain | tcp.neatfolk.co / neatfolk.co (Cloudflare DNS) |
| Room DB Name | trip_tracker_db |
| Room DB Version | 3 |
| Min SDK | API 26 (Android 8.0) |
| Target/Compile SDK | API 36 (Android 16) |

---

*Document generated: 8 July 2026 · Trip Tracker Android Project · Roy / Baithook Driver*
*Next handover to be generated when v4.0-beta is complete or significant changes are made.*
