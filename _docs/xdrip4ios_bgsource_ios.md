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

There is **no fixed default App Group ID**. In the `Truman1234/iAPS` reference build it is
`group.com.$(DEVELOPMENT_TEAM).loopkit.LoopGroup`, templated per Apple developer account at build
time - so it cannot be hardcoded here.

## Setup (per Xcode project, once)

Both apps must be built with the **same** App Group ID, under the **same Apple Developer Team**:

1. This app's Xcode project: Signing & Capabilities -> add "App Groups" -> add the group ID.
2. This app's `Info.plist`: add key `AppGroupID` (String) with that same group ID as its value.
3. xDrip4iOS's own Xcode project: same App Group capability, same group ID.

If step 2 is missing or blank, the app posts a notification telling the person to add the
`AppGroupID` key - it does not silently fail.

## Nightscout as a BG source

Already works on iOS, unchanged: `NSClientSourcePlugin` (`:plugins:source`, `commonMain`) has no
Android-only code. No port was needed.
