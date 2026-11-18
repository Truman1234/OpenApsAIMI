package app.aaps.pump.dana

import app.aaps.core.interfaces.resources.ResourceHelper

/*
 * The one text in DanaPump that needs Android resources, kept on the Android side.
 *
 * `DanaPump` itself lives in commonMain, so it cannot use `ResourceHelper`: that interface is
 * androidMain only. The three callers of this text - `DanaRPlugin`, `DanaRKoreanPlugin` and
 * `DanaRv2Plugin` - are all in `:pump:dana:danar`, which is Android only and has no iOS
 * counterpart, so nothing on iOS asks for this message.
 *
 * If an iOS Dana driver ever needs to say the same thing, the choice between the two messages
 * should move back into commonMain and return a `TextRef` instead, the way `DanaStrings` does it
 * for the Dana RS packets. There is no iOS caller today, so that indirection would buy nothing.
 */

/**
 * What the user is told about a bolus not given in full: [given] of [asked] U.
 *
 * Reads [DanaPump.bolusStoppedByPump] and [DanaPump.bolusStartErrorCode] to tell a bolus the pump
 * stopped from one that failed to start.
 */
fun DanaPump.bolusFailure(rh: ResourceHelper, asked: Double, given: Double): String =
    if (bolusStoppedByPump)
        rh.gs(app.aaps.core.ui.R.string.pump_stopped_bolus, rh.gs(app.aaps.core.interfaces.R.string.format_insulin_units, given))
    else
        rh.gs(R.string.boluserrorcode, asked, given, bolusStartErrorCode)
