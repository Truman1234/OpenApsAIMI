package app.aaps.pump.danars.ios

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings

/**
 * The one screen this plugin offers in its settings on an iPhone: the pairing wizard.
 *
 * `iosMain`, so this is not in the Android preference schema. On Android the pairing wizard is
 * reached from the overview screen (`DanaOverviewEvent.StartPairWizard`); the Dana overview does
 * not exist yet on iOS, so a settings entry is the interim way in, same as `IosMedtrumPlugin`
 * does it for `PickPump`/`ChangePatch`.
 */
enum class DanaRsIosIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.CLICK,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    PairPump(
        key = "danars_ios_pair_pump",
        title = CoreUiStrings.pairing
    )
}
