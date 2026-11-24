# xDrip4iOS as a BG source on iOS

Status: shipped (`kmp-aimi-migration-study_xdrip4ios_bgsource`). Code only - the two manual Xcode
steps below are the maintainer's own setup, not something this app can do for them.

## How it works

xDrip4iOS, Loop, Trio and iAPS all share glucose through one iOS mechanism: an **App Group**, a
shared `NSUserDefaults` suite both apps can read and write. xDrip4iOS writes its readings there;
`XdripSourceIosPlugin` (`:plugins:source`, `iosMain`) reads them back. No network, no Bluetooth
pairing between the two apps.

- `XdripAppGroupStore` opens the suite and decodes the `latestReadings` JSON array (timestamp,
  mg/dL, trend, source app name) xDrip4iOS writes.
- `XdripHeartbeatListener` owns a second, read-only `CoreBluetoothCentral`
  (`BleRestoreIdentifier.XDRIP_HEARTBEAT`) that connects to the same transmitter xDrip4iOS is
  already talking to, purely to be woken by its BLE traffic - CoreBluetooth peripheral identifiers
  are stable per device, not per app, so this works without xDrip4iOS's cooperation. On a wakeup it
  waits 5 s (matching xDrip4iOS's own `HeartBeatManager.swift` delay) and re-reads the store.
- `XdripSourceIosPlugin.onStart()` reads the App Group ID from this app's own Info.plist key
  `"AppGroupID"` (exactly like iAPS does) and posts `NotificationId.XDRIP_IOS_APP_GROUP_MISSING`
  if that key is missing or blank.
- Glucose values are inserted with `SourceSensor.UNKNOWN`, not a guessed sensor type: xDrip4iOS's
  shared JSON carries no reliable sensor identity, and AIMI's lag compensation is keyed on this
  enum. A wrong guess (e.g. `DEXCOM_G6`) would misapply dosing-relevant compensation.

The App Group ID contains the Apple Developer Team id: xDrip4iOS uses
`group.com.$(DEVELOPMENT_TEAM).loopkit.LoopGroup`, like the `Truman1234/iAPS` reference build. So
it cannot be one fixed string for everybody, but it can be built from the team id at build time.

## Setup

This app already does the Xcode side. `ios/app/Signing.xcconfig` sets

    AAPS_XDRIP_APP_GROUP_ID = group.com.$(DEVELOPMENT_TEAM).loopkit.LoopGroup

`Device.entitlements` and `Simulator.entitlements` add that group next to the widget group, and
`Shared/Info.plist` hands it to the plugin as the `AppGroupID` key. Do NOT add the group again with
"+ Capability" in Xcode: that creates a second entitlements file and points the target at it.

What you have to do:

1. Sign this app with the **same Apple Developer Team** as your xDrip4iOS build
   (`DEVELOPMENT_TEAM` in `ios/app/Signing.local.xcconfig`).
2. Check that your xDrip4iOS build has the same group. In the xDrip4iOS project it is in
   `xdrip/xdripDebug.entitlements` and `xdrip/xDrip.entitlements`, under App Groups.
3. If xDrip4iOS uses a different group, set `AAPS_XDRIP_APP_GROUP_ID` to it in
   `Signing.local.xcconfig`.

A free Personal Team has no App Groups (`Personal.entitlements`), so xDrip4iOS cannot be used as a
source with such an account. Use Nightscout instead.

If the `AppGroupID` key is missing or blank, the app posts a notification telling the person to add
it - it does not silently fail.

## Nightscout as a BG source

Already works on iOS, unchanged: `NSClientSourcePlugin` (`:plugins:source`, `commonMain`) has no
Android-only code. No port was needed.
