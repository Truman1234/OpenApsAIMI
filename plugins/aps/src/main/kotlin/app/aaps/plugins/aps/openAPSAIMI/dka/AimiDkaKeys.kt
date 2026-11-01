package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
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

    HighBg("aimi_dka_high_bg", 250, 180, 350, R.string.aimi_dka_high_bg_title, R.string.aimi_dka_high_bg_summary, dependency = AimiDkaBooleanKey.DkaEnabled),
    VeryHighBg("aimi_dka_very_high_bg", 300, 200, 450, R.string.aimi_dka_very_high_bg_title, R.string.aimi_dka_very_high_bg_summary, dependency = AimiDkaBooleanKey.DkaEnabled),

    /** Minimum temp basal as % of the current basal rate. */
    FloorPercent("aimi_dka_floor_percent", 50, 10, 100, R.string.aimi_dka_floor_percent_title, R.string.aimi_dka_floor_percent_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),

    /** The floor is never applied at or below this BG (mg/dL). */
    FloorMinBg("aimi_dka_floor_min_bg", 100, 70, 180, R.string.aimi_dka_floor_min_bg_title, R.string.aimi_dka_floor_min_bg_summary, dependency = AimiDkaBooleanKey.DkaFloorEnabled),
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
