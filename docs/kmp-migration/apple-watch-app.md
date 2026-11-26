# Apple Watch app (plan)

Status: **plan agreed, nothing built yet.** Written on 2026-11-25. Read together with
`apple-watch-complications.md` (watch face complications, which come later and reuse the data path
from this plan) and `widget-live-activity.md`.

## Goal

A watchOS app for the iPhone app that:

1. **shows** the current state: glucose, IOB and the other values below;
2. **sends commands** to the iPhone: bolus (with the wizard), running modes, temp targets;
3. never lets the watch act on its own. The iPhone checks and runs every command, with the same
   rules as a command typed on the iPhone.

## What already exists

- **No watch app on any branch of the stack** (`kmp-aimi-migration-study` and the `_DKA`,
  `_DanaRS_iOS`, `_xdrip4ios_bgsource` branches). The Xcode project has the iPhone app, `AAPS2` and
  the Live Activity extension only.
- **Read-only on the watch today:** the Live Activity shows in the watchOS Smart Stack
  (`.supplementalActivityFamilies([.small])`). Nothing on the watch face, no commands.
- **The message format is shared code already:** `EventData` in
  `core/interfaces/src/commonMain/.../rx/weardata/EventData.kt`. It is `@Serializable`, with JSON
  and ProtoBuf. The Wear OS app uses it today. It has everything this plan needs:
    - display: `SingleBg`, `Status`, `GraphData`, `TreatmentData`, `LoopStatusResponse`;
    - bolus: `ActionBolusPreCheck` / `ActionWizardPreCheck` / `ActionQuickWizardPreCheck`, then
      `ConfirmAction` back to the watch, then `ActionBolusConfirmed` / `ActionWizardConfirmed`;
    - modes: `RunningModeRequest` → `RunningModeList` → `RunningModeSelected` →
      `RunningModeConfirmed` (`LOOP_OPEN`, `LOOP_LGS`, `LOOP_CLOSED`, `LOOP_DISABLE`,
      `LOOP_USER_SUSPEND`, `LOOP_RESUME`, `PUMP_DISCONNECT`, `PUMP_RECONNECT`, with durations);
    - temp target: `ActionTempTargetPreCheck` → `ConfirmAction` → `ActionTempTargetConfirmed`.
- **The phone side handler is Android only:** `DataHandlerMobile` in
  `plugins/sync/src/androidMain/.../wear/wearintegration/`. Its logic is platform free (checks,
  confirmation text, command queue). Only a few lines need Android: `Context`, `NotificationManager`,
  `Configuration` and `toArgb`, plus `sendToWear`, which goes to the Wear OS data layer.
  Its tests (`DataHandlerMobile*Test` in `androidHostTest`) already cover bolus, running modes,
  scenes and the profile info.

## What the watch shows

All of it comes from `SingleBg` and `Status`, which the phone already builds for Wear OS. Only the
DKA state is new.

| Value | Source | Notes |
|---|---|---|
| Glucose, unit, trend arrow | `SingleBg.sgvString`, `glucoseUnits`, `slopeArrow` | colour from `sgvLevel` (low / in range / high) |
| Delta and average delta | `SingleBg.delta`, `avgDelta` | |
| **Age of the reading** | `SingleBg.timeStamp` | counts by itself (`Text(date, style: .relative)`), struck out when stale. Most important safety point. |
| IOB (total and bolus/basal) | `Status.iobSum`, `iobDetail` | |
| COB | `Status.cob` | |
| Current basal | `Status.currentBasal` | shows a running temp basal too |
| Running mode and end time | `Status.loopMode`, `modeEndTime` | e.g. "Suspended, 42 min left" |
| Last loop run | `Status.openApsStatus` | warning colour when the loop has not run for a while |
| Temp target and time left | `Status.tempTarget`, `tempTargetDuration` | |
| Pump reservoir and battery | `Status.reservoirString`, `battery` | |
| BGI | `Status.bgi` | optional, second page |
| Small BG graph (3 h) | `GraphData`, `TreatmentData` | second page, phase 4 |
| **DKA risk and reason** | **new field** | see below |

### New: DKA state on the watch

`DkaGuard` (`plugins/aps/.../openAPSAIMI/dka/DkaGuard.kt`) has `DkaRisk { NONE, WATCH, WARNING,
CRITICAL }`. Add two fields at the **end** of `EventData.Status` (the ProtoBuf format numbers fields
by their order, see the comment in the class):

```kotlin
val dkaRisk: String? = null,    // DkaRisk name; null = older sender or no DKA guard
val dkaReason: String? = null,  // short text, e.g. "GLP-1 RA without recent ketone measurement"
```

`String` and not the enum, because `core:interfaces` must not depend on `plugins:aps`. The watch
shows nothing for `NONE`, a small badge for `WATCH`, and a coloured banner for `WARNING` and
`CRITICAL`. The Wear OS app ignores the new fields (`ignoreUnknownKeys`).

## Commands

| Command | Flow | Notes |
|---|---|---|
| Bolus | PreCheck → phone checks constraints and builds `ConfirmAction` → watch shows it → user confirms → phone runs it | the phone limits it, as on the phone (max bolus, DKA guard, pump state) |
| Wizard / QuickWizard | same | carbs, percentage |
| Running mode | Request → list from the phone (only the modes that are allowed now) → choose with duration → confirm | |
| Temp target | PreCheck → confirm | presets eating soon / activity / hypo |

Rules for all commands:

- **The watch never decides.** It only sends a request. The phone checks it, builds the confirmation
  text and runs it through the command queue. That is the same path as on the phone, so constraints
  and the DKA guard apply without extra code.
- **The confirmation is one-time.** `ConfirmAction` carries an id (`bolusId`), and the phone runs a
  confirmation only for the request it belongs to. An old or repeated confirmation does nothing.
  `DataHandlerMobile` already works this way.
- **Insulin-lowering modes show the DKA state first.** `LOOP_USER_SUSPEND`, `LOOP_DISABLE` and
  `PUMP_DISCONNECT` lower insulin. When the DKA risk is `WATCH` or higher, the confirmation screen
  shows it, and the phone adds it to the `ConfirmAction` lines. This is a small change in the
  running-mode branch of the handler.
- **No answer means not done.** When the iPhone is not reachable, the watch says so and does not
  queue the command for later. A bolus delivered an hour late is dangerous.

### Not in this plan: manual temp basal

`EventData` has no temp basal command, and AAPS left it out on purpose:

- In the closed loop, the next loop run replaces a manual temp basal, so the command has no
  reliable effect.
- A manual 0 % temp basal from the watch is a silent insulin stop, which is exactly what the DKA
  guard exists to catch.
- The same needs are covered more safely by running modes (suspend, LGS, disconnect, each with a
  duration and an end time), temp targets and profile percentage.

If it is wanted later: only in open loop, with the same limits and DKA checks as on the phone, and
as its own reviewed change.

## Model: the iAPS watch app

The watch app of iAPS (`Truman1234/iAPS`, folder `FreeAPSWatch WatchKit Extension`) is a good model
for the **watch side**. It is small (about 1600 lines of SwiftUI) and already does most of what is
wanted here:

| iAPS file | What it does | Use here |
|---|---|---|
| `Views/MainView.swift` | BG, trend, delta, age, IOB, COB, last loop, eventual BG | layout for the main page |
| `Views/BolusView.swift`, `BolusConfirmationView.swift` | amount with the Digital Crown, in pump steps, up to max bolus | input and confirmation screens |
| `Views/CarbsView.swift` | carbs (and fat/protein) | wizard / carbs page |
| `Views/TempTargetsView.swift` | temp target presets | temp target page |
| `Views/OverridesView.swift` | override presets | becomes the running mode list (AAPS has no overrides) |
| `WatchStateModel.swift`, `DataFlow.swift` | `WCSession`, the state the watch shows | shape of `WatchState` |
| `ComplicationController.swift` | ClockKit complication | not used: ClockKit is deprecated, see `apple-watch-complications.md` |

What we do **not** take from iAPS:

- **The messages.** iAPS sends plain dictionaries (`["bolus": amount]`, `["tempTarget": id]`), and
  the phone runs the bolus after the watch confirmed it. Here the watch speaks `EventData` instead,
  so the phone builds the confirmation (`ConfirmAction`) and runs only that confirmed request.
  This keeps one set of checks for the phone, Wear OS and the Apple Watch, and the existing
  `DataHandlerMobile` tests cover it.
- **The phone side** (`FreeAPS/Sources/Services/WatchManager/WatchManager.swift`). Its job is done
  here by the shared Kotlin handler from phase 1.

**License:** iAPS is MIT (Copyright (c) 2021 Ivan Valkou). Code taken over from it must keep that
copyright line and the MIT text, for example in a header in each file that came from iAPS.
MIT code may be used in this AGPL project.

## How it is built

```
 watchOS app (SwiftUI)                         iPhone app
 ---------------------                         ----------
 views  <--  WatchState  <--  WCSession  <==>  WCSession  -->  SwiftAppleWatchCompanion (Swift)
                                                                 |  EventData as JSON
                                                                 v
                                               AppleWatchPlugin + AppleWatchLink (Kotlin, iosMain)
                                                                 |  rxBus
                                                                 v
                                               WatchUpdates + DataHandlerMobile (Kotlin, commonMain)
                                                                 |  same checks as the phone
                                                                 v
                                               command queue / constraints / DKA guard
```

- **Transport:** JSON (`EventData.serialize()` / `deserialize()`). Readable when debugging, and the
  messages are small. ProtoBuf only if size becomes a problem. The JSON field `type` is the full
  Kotlin class name, for example `app.aaps.core.interfaces.rx.weardata.EventData.SingleBg`.
- **Phone to watch, data (`STATE`):** `SingleBg`, `GraphData`, `TreatmentData`, `Status`,
  `Preferences`, `QuickWizard`, `UserAction`, `SceneList`, `ActiveSceneState`. The phone keeps the
  newest JSON of each kind and sends all of them together with `updateApplicationContext`, one key
  per kind (the class name, e.g. `Status`). Only the newest context counts, so nothing is queued.
  This does not wake the watch app, but the values are fresh as soon as it is opened.
- **Phone to watch, answers (`LIVE`):** everything else (`ConfirmAction`, `BolusProgress`,
  `RunningModeList`, `OpenLoopRequest`, errors ...). `sendMessage` under the key `event`, only while
  the watch app is reachable, otherwise dropped. Never queued: an old confirmation must not show up
  later.
- **Not sent:** the custom watchface messages of Wear OS.
- **Watch to phone, commands:** `sendMessage` with one `EventData` as JSON under the key `event`. The
  reply handler only ends the call; the answer comes as its own `LIVE` message, as on Wear OS. This
  works only while both sides can be reached. That is fine, because the user has the app open while
  giving a command, and it gives the "no answer means not done" rule for free. A message that
  reaches the iPhone before the Kotlin plugin has started is kept for at most 30 seconds.
- **Watch size limit:** the application context has a size limit. If `GraphData` is too big, Swift
  logs "Application context not sent". Check this in phase 4 and shorten the graph if needed.
- **Complications** later, with `transferCurrentComplicationUserInfo` and a daily budget, as written
  in `apple-watch-complications.md`.

## Phases

1. **Handler to commonMain.** Move `DataHandlerMobile` to `commonMain`, under the same name, and put
   the Android lines behind `WatchHandlerPlatform` (dark theme check, cancel the open loop
   notification; Android: `AndroidWatchHandlerPlatform`). No transport interface is needed: the
   handler already sends through `rxBus.send(EventMobileToWear(...))`, which is shared code, so the
   iOS bridge listens to that event. Java date and number formatting is replaced by `DateUtil`,
   `DecimalFormatter` and `kotlin.time.Clock`. The existing `DataHandlerMobile*Test` stay in
   `androidHostTest` and now test the shared code. No behaviour change in this phase, except that
   the day in the TDD list on the watch is now in the phone's short date format
   (`DateUtil.dateStringShort`) instead of a fixed `dd.MM.`.
2. **DKA fields.** The DKA guard writes each result to `DkaStatus` (`core:interfaces`, kept in memory by
   `DkaStatusImpl`; a result older than 15 minutes counts as none). `DataHandlerMobile` sends it as
   `dkaRisk` / `dkaReason` at the end of `EventData.Status`, and puts a `WARNING` line
   ("DKA risk: WATCH") first in the confirmation of `LOOP_USER_SUSPEND`, `LOOP_DISABLE` and
   `PUMP_DISCONNECT` while the risk is raised. On an AAPS client the line does not show, because the
   client runs no DKA guard of its own.
3. **iOS bridge.** `WearPlugin` stays an Android plugin: its settings page (`WearComposeContent`,
   custom watchface import) is Android Compose. So iOS gets its own plugin, as for Garmin:
    - `WatchUpdates` (commonMain, new): when the watch gets new data (bolus progress, changed watch
      settings, the debounced resend after BG / loop / NS / temp target / running mode / eat
      reminder, scenes, tiles). Pulled out of `WearPlugin` without a change in behaviour; Android
      `WearPlugin` now calls it. The resend tests moved with it (`WatchUpdatesTest`).
    - `AppleWatchPlugin` (iosMain, SYNC, slot 350): starts `WatchUpdates` and the bridge. It uses the
      Wear settings (`WearControl`, the wizard switches, notify on SMB), so a settings file from an
      Android phone keeps them. "Wear control" is off by default, so the watch can only show data
      until the user switches it on.
    - `AppleWatchLink` (iosMain): the routing rules above, tested in `AppleWatchLinkTest` (iosTest).
      Commands from the watch go on the bus only while the plugin is on, with the sender
      `apple-watch`; `DataHandlerMobile` then handles them like any Wear OS command.
    - `AppleWatchCompanion` / `AppleWatchCallback` / `installAppleWatchCompanion()` (iosMain) and
      `SwiftAppleWatchCompanion` (`ios/app/Shared/AppleWatchBridge.swift`), installed in
      `AapsApp.swift` for both targets. The session is activated at launch, because iOS may start the
      app in the background for a watch message.
    - `IosWatchHandlerPlatform`: dark colours always (the watch is always black), and it removes the
      `aaps-loop` notification after the watch accepted an open loop suggestion.
4. **watchOS app.** Target `AapsWatch` (`ios/app/AapsWatch/`, SwiftUI, watchOS 26), written by hand
   into `project.pbxproj` like the Live Activity extension. Bundle id `$(AAPS_BUNDLE_ID).watchkitapp`,
   signed through `Signing.xcconfig`, no entitlements (WatchConnectivity needs none). Only the AAPS
   target embeds it ("Embed Watch Content" into `AAPS.app/Watch`). The layout follows the iAPS watch
   app; the code is our own, nothing was copied, so no MIT header is needed. The icon is the AAPS
   app icon cut to its dark disc and filled to the corners, so the round watch mask shows no rim.
    - `WatchModels.swift`: the `EventData` JSON the watch reads, and `Command`, which writes commands
      with `type` as the first field. The return command of a confirmation is kept as raw JSON and sent
      back unchanged.
    - `WatchSession.swift`: the `WCSession` on the watch. Reads the application context and the
      `event` messages, sends commands only while the iPhone is reachable (otherwise an error screen,
      never a queue), asks for `ActionResendData` when the app comes to the front.
    - Pages, swiped left and right (the Digital Crown stays for scrolling and amounts): glucose (BG, arrow, delta, age, IOB, COB, mode, last loop, DKA banner), details
      (3 h chart, basal, temp target, IOB detail, COB, BGI, reservoir, battery), actions.
    - Actions: bolus (Digital Crown, 0.05 U, up to max bolus), wizard (carbs, the phone's bolus
      percentage), temp target presets (activity, eating soon, hypo, cancel), running mode (the
      list the phone allows now, with durations). Each sends only the pre-check; the phone answers
      with its `ConfirmAction`, and "Confirm" sends the phone's return command back.
    - AIMI modes (breakfast, lunch, dinner, snack, high carb, meal, sport, sleep, stop): new
      `EventData.ActionAimiModePreCheck(modeId)` / `ActionAimiModeConfirmed(bolusId)`. The phone
      (`DataHandlerMobile.handleAimiModePreCheck`) takes the `ModePreset` of the AIMI Remote Control,
      its own duration (stop: 1 min), and parks a NOTE with the keyword through `BatchExecutor`
      (`BatchAction.TherapyEvent`), the same note the Garmin widget writes. A meal mode starts the
      P1/P2 prebolus, so it always goes through the phone's confirmation. Other modes are refused.
    - Each action opens as a sheet with the system close button, and closes itself after sending.
      The running mode list shows "No answer from the iPhone" after 15 s instead of a spinner.
    - Bolus progress with "Stop bolus".
    - The running AIMI mode: `EventData.Status` carries `aimiMode` (the translated name) and
      `aimiModeEndsAt`. The phone takes the newest running mode from
      `RemoteControlCommands.activeModes` (stop is not shown). The glucose page shows
      "Lunch · 45 min" and counts down on the watch. A new note sends the status again at once
      (`WatchUpdates` watches TE changes), so the watch does not wait for the next loop.
    - After a running mode change, the watch asks for the list again by itself after 4 s, silently,
      so an open list shows the modes that are allowed now.
    - Custom AIMI mode: a "Custom" button opens a sheet. Swipe left and right between the 8 modes
      (not stop), scroll a wheel up and down for the duration (15 min to 12 h, 15 min steps, starting
      at the preset duration). `ActionAimiModePreCheck.durationMinutes` carries it (0 = the preset's
      duration, so older watches still work). The phone refuses values outside 15..720 min and shows
      the duration in its confirmation.
    - Custom temp target: one sheet with two wheels side by side, target (72 to 180 mg/dL or 4.0 to
      10.0 mmol/L, in the phone's unit) and duration (15 min to 12 h). It sends the existing
      `ActionTempTargetPreCheck` `MANUAL` command with low = high; the phone checks the limits.
    - Both custom sheets: the system X (top left) cancels, ✓ (top right) sends, then the phone's normal
      confirmation follows. Wheels, not one swipe per step: a 12 h duration would need 48 swipes.
    - The phone's glass dashboard shows the same AIMI mode line (`aimiActiveModeLine`, shared with
      the AIMI dashboard).
5. **Complications** (separate, `apple-watch-complications.md`).

Phases 1 and 2 also help Wear OS (DKA state on Android watches) and can be tested on both machines.
Phases 3 and 4 need the Mac, and phase 4 also needs a watch or the watch simulator.

## Decisions

Made by the owner on 2026-11-25:

- **Minimum watchOS 26.** Apple went from watchOS 11 straight to 26 (year based numbers), so there is
  no 25. The test watch runs 27.2.1.
- **AAPS only.** The watch app is embedded in the AAPS target, not in AAPS2 (the second copy of the
  app with its own bundle id). AAPS2 can get one later the same way.
- **The phone decides at DKA `CRITICAL`.** The watch does not block or allow anything by itself. It
  shows the confirmation or the refusal the phone sends.

## Open questions

- Units and strings: the watch shows the texts the phone sends (already formatted and translated),
  so the watch app needs very few strings of its own. Check this when the first page is built.
