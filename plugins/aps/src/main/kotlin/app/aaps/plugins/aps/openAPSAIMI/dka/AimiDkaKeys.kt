package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.plugins.aps.R

/**
 * Settings of the AIMI ketoacidosis (DKA) protection.
 * Register each enum class once: `preferences.registerPreferences(AimiDkaBooleanKey::class.java)`.
 */
enum class AimiDkaBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.SWITCH,
    override val calculatedDefaultValue: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS
) : BooleanPreferenceKey {

    DkaEnabled("aimi_dka_enabled", true, R.string.aimi_dka_enabled_title, R.string.aimi_dka_enabled_summary),
    DkaNotifyWatch("aimi_dka_notify_watch", true, R.string.aimi_dka_notify_watch_title, R.string.aimi_dka_notify_watch_summary, dependency = null),
    DkaRestrictSmb("aimi_dka_restrict_smb", false, R.string.aimi_dka_restrict_smb_title, R.string.aimi_dka_restrict_smb_summary),
    DkaSickDay("aimi_dka_sick_day", false, R.string.aimi_dka_sick_day_title, R.string.aimi_dka_sick_day_summary),
    DkaSglt2("aimi_dka_sglt2", false, R.string.aimi_dka_sglt2_title, R.string.aimi_dka_sglt2_summary),
    DkaGlp1("aimi_dka_glp1", false, R.string.aimi_dka_glp1_title, R.string.aimi_dka_glp1_summary),

    /** Semaglutide / GLP-1 minimum insulin (basal floor). Off by default. */
    DkaFloorEnabled("aimi_dka_floor_enabled", false, R.string.aimi_dka_floor_enabled_title, R.string.aimi_dka_floor_enabled_summary),
}

/** Numeric settings. Glucose values are always mg/dL. */
enum class AimiDkaIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<Int, Int> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val unitType: UnitType = UnitType.NONE
) : IntPreferenceKey {

    /** Normal floor: minimum temp basal as % of scheduled basal. Range 5-50. */
    FloorPercent("aimi_dka_floor_percent", 10, 5, 50, R.string.aimi_dka_floor_percent_title, R.string.aimi_dka_floor_percent_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),

    /**
     * DKA override floor: applied even when BG is low, but only when DKA risk is active.
     * Always kept below [FloorPercent]. Range 0-20.
     */
    FloorDkaMinPercent("aimi_dka_floor_dka_min_percent", 5, 0, 20, R.string.aimi_dka_floor_dka_min_percent_title, R.string.aimi_dka_floor_dka_min_percent_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),
}

/**
 * Glucose thresholds. The value is stored in mg/dL, and the settings screen shows and takes it in
 * whichever unit the user has set, mg/dL or mmol/L, with the slider bounds converted too.
 *
 * Read these with `preferences.getRaw(...)` anywhere the value is compared against a glucose
 * reading. Plain `get(...)` returns the value in the user's display units, so for a mmol/L user it
 * would answer 5.6 where the loop expects 100, and every comparison in [DkaGuard] and
 * [SemaglutideBasalFloor] works in mg/dL.
 *
 * The key strings do not match the old int keys of the same settings. That is deliberate: an int
 * written by an older build cannot then be read back as a double. Anybody who had set one of these
 * gets the default instead, which is far better than a misread threshold in a safety setting.
 */
enum class AimiDkaUnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val sync: SyncSpec? = null
) : UnitDoublePreferenceKey {

    HighBg("aimi_dka_high_glucose", 250.0, 180, 350, R.string.aimi_dka_high_bg_title, R.string.aimi_dka_high_bg_summary, dependency = AimiDkaBooleanKey.DkaEnabled),

    VeryHighBg("aimi_dka_very_high_glucose", 300.0, 200, 400, R.string.aimi_dka_very_high_bg_title, R.string.aimi_dka_very_high_bg_summary, dependency = AimiDkaBooleanKey.DkaEnabled),

    /** The normal floor is not applied at or below this glucose value. */
    FloorMinBg("aimi_dka_floor_min_glucose", 100.0, 70, 180, R.string.aimi_dka_floor_min_bg_title, R.string.aimi_dka_floor_min_bg_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),

    /**
     * Hard stop. At or below this glucose value, now or predicted, no floor is applied at all, not
     * even [AimiDkaIntKey.FloorDkaMinPercent]. Kept at or below [FloorMinBg] by the settings reader.
     *
     * The default is 80 rather than the 70 you might expect. Between this value and [FloorMinBg]
     * the only thing that can run is the small DKA override, and only while DKA risk is active, so
     * moving the bound costs almost no insulin either way. Given that, the conservative end is the
     * better default: a loop cannot treat ketosis at a low glucose anyway, because that needs
     * carbohydrate as well, so it should stand aside and leave it to the person.
     */
    FloorHardStopBg("aimi_dka_floor_hard_stop_glucose", 80.0, 50, 90, R.string.aimi_dka_floor_hard_stop_bg_title, R.string.aimi_dka_floor_hard_stop_bg_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),
}

/** Opens the ketone entry dialog from the settings screen. */
enum class AimiDkaIntentKey(
    override val key: String,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.ACTIVITY,
    override val urlResId: Int? = null,
    override val activityClass: Class<*>? = null,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    EnterKetone(
        key = "aimi_dka_enter_ketone",
        titleResId = R.string.aimi_dka_enter_ketone_title,
        summaryResId = R.string.aimi_dka_enter_ketone_summary,
        activityClass = KetoneEntryActivity::class.java
    ),
}
