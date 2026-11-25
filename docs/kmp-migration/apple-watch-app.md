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
 views  <--  WatchState  <--  WCSession  <==>  WCSession  -->  IosWatchTransport (Swift)
                                                                 |  EventData as JSON
                                                                 v
                                               WatchDataHandler (Kotlin, commonMain)
                                                                 |  same checks as the phone
                                                                 v
                                               command queue / constraints / DKA guard
```

- **Transport:** JSON (`EventData.serialize()` / `deserialize()`). Readable when debugging, and the
  messages are small. ProtoBuf only if size becomes a problem.
- **Phone to watch, data:** `updateApplicationContext` for the latest `SingleBg` + `Status` (only the
  newest state counts, so it is replaced, not queued). This does not wake the watch app, but the
  values are fresh as soon as it is opened.
- **Watch to phone, commands, and the answers:** `sendMessage` with a reply handler. It works only
  while both sides can be reached. That is fine, because the user has the app open while giving a
  command, and it gives the "no answer means not done" rule for free.
- **Complications** later, with `transferCurrentComplicationUserInfo` and a daily budget, as written
  in `apple-watch-complications.md`.

## Phases

1. **Handler to commonMain.** Move `DataHandlerMobile` to `commonMain` as `WatchDataHandler` and put
   the Android lines behind interfaces: `WatchTransport` (replaces `sendToWear`), the notification
   call, the dark-mode check and the colour conversion. Android keeps working through an
   `androidMain` implementation that does what the code does today. Move the existing
   `DataHandlerMobile*Test` with it, so they also run on `iosSimulatorArm64Test`. No behaviour
   change in this phase.
2. **DKA fields.** Add `dkaRisk` / `dkaReason` to `EventData.Status`, fill them from the DKA guard,
   and add the DKA lines to the running-mode confirmation. Tests for both.
3. **iOS bridge.** `IosWatchTransport` in Swift (`WCSession` delegate) and its registration in the
   iOS graph. Testable without a watch: the bridge is thin, the logic is in phase 1.
4. **watchOS app.** New Xcode target (SwiftUI, watchOS 26+), same team and App Group setup. Start
   from the iAPS views (see "Model" above) and replace their messages with `EventData`. Pages:
    - main: BG, arrow, delta, age, IOB, COB, mode, DKA banner;
    - details: basal, temp target, reservoir, battery, last loop run, BGI, small graph;
    - actions: bolus / wizard, running mode, temp target, each with the confirmation from the phone.
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
