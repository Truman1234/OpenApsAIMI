# Dana RS / Dana-i on iOS - analysis and plan

Status: analysis only. No code was moved yet. Nothing here was built or run.
Reference: DanaKit by bastiaanv (MIT license, Dana-i and Dana-RS, Swift, works in iAPS/Loop).
Keep the MIT notice in every file that is ported from it.

## Scope

- In: `:pump:dana:common` and `:pump:dana:danars` (Dana RS v1, v3 and Dana-i).
- Out: `:pump:dana:danar` (old DanaR, 87 files, classic Bluetooth, no iOS equivalent).

## What the code looks like today

All three modules are Android-only (`src/main`). Android coupling is small:

| Part | Files | Android coupling |
|---|---|---|
| `danars/comm` (packets, 47 files) | pure Kotlin | none found, move as is |
| `common/.../encryption/BleEncryption.kt` (686 lines) | only Metro DI | none, move as is |
| `common/.../DanaPump.kt` and state | plain Kotlin | check `java.util.*` use |
| `danars/services/BLEComm.kt` (892 lines) | protocol + Android BLE | `Context`, permission check, `android.util.Base64`, `Thread`, `synchronized`, `ScheduledFuture` |
| `danars/services/BleTransportImpl.kt` (300) | Android BLE | this is the Android side of `BleTransport` |
| `danars/services/DanaRSService.kt` (614) | Android `Service` | `Intent`, `IBinder`, `MetroService` |
| `danars/DanaRSPlugin.kt` (624) | plugin | `Context`, Android pump registration |
| `danars/compose/*`, `common/.../compose/*` | Compose screens | Android-only imports (`androidx.lifecycle`, `Context`, `stringResource`) |
| `common/.../database/*` | Room history DB | `androidx.room` |

A common `BleTransport` interface (scan, GATT, bonding, listener) already exists in
`:core:interfaces`. Medtrum and Omnipod Dash already implement it for iOS
(`IosMedtrumBleTransport` 327 lines, `IosDashBleTransport` 124 lines). That is the template.

## What DanaKit gives us

Use as reference for the parts that are different on iOS:
- `PeripheralManager.swift` / `InteractiveBluetoothManager.swift`: scan, connect, service and
  characteristic discovery, write queue, reconnect handling.
- Pairing without explicit bonding API (iOS bonds when an encrypted characteristic is accessed),
  RSv3 pairing with the 12 digit code and the 6+ digit PIN codes.
- `DanaKit/Encryption` and `DanaKitTests/Encryption`: test vectors to cross-check
  `BleEncryption.kt` (same inputs must give the same bytes).
- `DanaKit/Packets`: cross-check of opcodes and field layout for each packet.

Not used: `DanaKitUI`, `DanaKitPlugin`, Loop types (`PumpManager`, `DoseEntry`, ...).

## Plan (each step compiles on its own)

1. Convert `:pump:dana:common` to Kotlin Multiplatform (androidMain / commonMain / iosMain,
   same build setup as `:pump:medtrum`). Move `DanaPump`, encryption, constants to commonMain.
   History database: keep Room on Android; on iOS use a store behind an interface
   (start with an in-memory or key-value store, history is not needed for dosing).
2. Convert `:pump:dana:danars`: move `comm/*` to commonMain. Add commonTest with packet and
   encryption tests, using DanaKit test vectors.
3. Split `BLEComm`: protocol state machine to commonMain, depends only on `BleTransport`,
   a clock and a Base64 helper (`kotlin.io.encoding.Base64`). Replace `Thread`/`synchronized`
   by coroutines and a `Mutex`. Android keeps its transport (`BleTransportImpl`).
4. Replace `DanaRSService` logic that is not Android specific by a shared session class.
   The Android `Service` stays as a thin wrapper.
5. iOS: `IosDanaRSBleTransport` (CoreBluetooth), scanner, state store, driver host and plugin,
   modelled on `IosMedtrum*`. Pairing flow for v1 / v3 / Dana-i.
6. Shared Compose screens: move pair wizard, overview and user options to commonMain
   (strings through the key-string generator, like Medtrum).
7. Register the pump in the iOS shell.

## Safety rules for this work

- The Android driver must keep working unchanged after every step. Existing Dana tests
  (`danars/src/test`, emulator) must stay green.
- iOS dosing stays off (read-only / status only) until it was tested on a real pump.
  Bolus and temp basal on iOS only after an explicit switch by the maintainer.
- Every ported file keeps the DanaKit MIT notice where code was derived from it.

## Risks

- Cannot be built or run in the authoring environment: Android Studio and a Mac are needed.
- CoreBluetooth behaviour (bonding, reconnect in background, write timing) can only be
  verified with a real Dana pump.
- Dana-i and RSv3 pairing differ from RS v1; test each model separately.

## What I need from the maintainer

- Which model(s) can be tested: Dana RS v1, RS v3, Dana-i.
- A Mac with Xcode for the iOS target, and a pump for step 5.

## Reservoir refill and priming (finding)

- AAPS Android (Dana RS): no guided refill or prime flow. The user fills and primes on the pump;
  AAPS only logs cannula/insulin change events. `danars` has no prime code.
- DanaKit (iAPS/Loop): guided flow in three steps:
  1. Reservoir amount: sent with `PacketGeneralSetUserOption` (field `refillAmount`), no insulin moves.
  2. Prime tube: a normal bolus with the "priming" flag, so the loop does not count it as dosing.
  3. Prime cannula: same, with the cannula volume.
- Consequence: step 1 is safe and needs only the existing user-option packet
  (`DanaRSPacketOptionSetUserOption`). Steps 2 and 3 deliver insulin through the bolus command,
  so they follow the iOS dosing switch (off until tested on a real pump) and need a confirm dialog.
- Proposal: shared (commonMain) refill wizard in this order, same screens on Android and iOS;
  on Android it stays optional and does not change the current behaviour.

## Progress (state of the branch)

Done and checked by the maintainer (Android tests green, iOS compile green):
- 3b: `pump/dana/danars` is a KMP module (androidMain/commonMain/androidHostTest).
- 3c/3d-a: `DanaRSPacket` and 21 packets in commonMain (Joda replaced by kotlinx.datetime).
- 1a: `DanaRsReadBuffer` (frame reassembly) + `splitForWrite`; Dana pairing keys in commonMain.
- 1b: `DanaRsSession` (handshake v1/RSv3/BLE5, packet exchange, coroutines) + `DanaRsHost`; `BLEComm` unchanged and still used on Android.
- 3: `DanaRsBleProfile` (FFF0/FFF1/FFF2, write without response) + `createIosDanaRsBleTransport()`; `requiresBond=false` for iOS.
- 4a: all packets that needed `R.string` texts (NotifyAlarm, DanaRSAlarmReporter, BasalGetBasalRate,
  BolusGetBolusOption, BolusSetStepBolusStop, GeneralGetPumpCheck, GeneralInitialScreenInformation) and
  the 9 history packets (`DanaRSPacketHistory` + Alarm/Basal/BloodGlucose/Bolus/Carbohydrate/Daily/Prime/
  Refill/Suspend) are in commonMain. Texts go through `DanaStrings` (new Dana string owner in
  `buildSrc/StringOwnerModules.kt`, `pumpDriver = true`); history records go through a new
  `DanaHistoryRecordStore` interface (commonMain) + `DanaHistoryRecordData` (plain, no Room), with
  `DanaHistoryRecordStoreImpl` converting to the Room entity on Android. The Room entity, DAO and
  `DanaHistoryViewModel` are unchanged.
- `DanaRSMessageHashTable` into commonMain (no code change beyond dropping `@JvmSuppressWildcards`,
  a JVM-only Java interop hint that does not exist on iOS).

4a is done: every Dana RS packet and helper is now in commonMain.

- 4b: `IosDanaRsHost` (iOS implementation of `DanaRsHost`) is done and checked by the maintainer
  (iOS compile green, full test suite green). Same texts (`CoreUiStrings`/`DanaStrings`) and
  notices (`NotificationManager`) as Android's `BLEComm`/`DanaRSPlugin`. `pumpCheckErrorText`/
  `reportAlarm` delegate to the existing commonMain `DanaRSAlarmReporter`. `changePump()` and
  `restartApp(reason)` are simplified for now (no iOS pump driver yet to reconnect or restart);
  filled in properly with 4c.

- 4c: `IosDanaRsPump` + `IosDanaRsDriverHost` + `IosDanaRsPlugin` are done and checked by the
  maintainer (iOS compile green, full test suite green). Read-only driver: status fields come
  from the shared `DanaPump`, `getPumpStatus()` sends 5 packets (keep connection, shipping info,
  pump check, basal rate, initial screen info) to read serial/firmware/basal/reservoir/battery.
  Every dosing method fails closed until the maintainer enables it after a real pump test.
  `@MetroIntKey(1040)`, same as Android's `DanaRSPlugin`. No preference screen yet (comes with 4d),
  so `isConfigured()` stays false for everyone until then.
  `DanaRsSession` (and the `IosBleTransport` under it, which opens CoreBluetooth in its own
  constructor) is built lazily inside `IosDanaRsDriverHost`, only on the first real `connect()`
  attempt - not eagerly in the `@Inject` host, which would have asked every iOS user for the
  Bluetooth permission just by starting the app.
  Along the way, found and fixed a real compile bug: the `Set<DanaRSPacket>` binding (used by
  `DanaRSMessageHashTable` to find unsolicited packets) lived in `DanaRSBindings`, androidMain
  only. Moved the packet-set part to a new commonMain `DanaRSCommonBindings` (the 4 packets in it
  have no Android-specific part); `DanaRSBindings` keeps only the Bluetooth adapter provider,
  which is genuinely Android-specific. No behavior change on Android.

- 4d: done and checked by the maintainer (iOS compile green, Android/JVM tests green, iOS
  simulator tests green).
  - Pairing wizard (`DanaRsPairWizardModel`/`DanaRsPairWizardScreen`, commonMain): BLE scan, PIN
    entry for RSv3, password entry, shared with Android once wired there too. Reached from the
    iOS settings screen (`DanaRsIosIntentKey.PairPump`) and from the overview's "Pair" action.
  - Overview/pump-status screen (`DanaRsOverviewContent`, iosMain): the same info rows Android's
    overview shows, built on the shared `buildDanaOverviewUiState()`.
  - Refill/prime wizard (`DanaRsRefillWizardModel`/`DanaRsRefillWizardScreen`, commonMain): new
    behavior, not a port - Android has no guided refill/prime flow either. Reservoir amount via
    `DanaRSPacketOptionSetUserOption` (no insulin moves); prime tube/cannula via
    `commandQueue.bolus(bolusType = BS.Type.PRIMING)`, the same generic path Omnipod/ComboV2 use.
    No separate priming switch: priming reuses the existing iOS dosing guard in `IosDanaRsPump`,
    so it fails closed the same way a real bolus would until the maintainer lifts that guard.
    Reached only from the iOS settings screen (`DanaRsIosIntentKey.RefillPrime`) for now.

Still to do before an iOS driver can be used:
- The maintainer lifting the iOS dosing guard in `IosDanaRsPump` after real-pump testing.
