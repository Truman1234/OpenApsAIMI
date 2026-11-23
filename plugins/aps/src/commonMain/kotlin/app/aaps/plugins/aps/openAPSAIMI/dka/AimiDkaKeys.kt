package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.plugins.aps.ApsStrings

/**
 * Settings of the AIMI ketoacidosis (DKA) protection.
 * Shared code: the same keys drive the Android and the iOS settings screen.
 * Register each enum once: `preferences.registerPreferences(AimiDkaBooleanKey.entries)`.
 */
enum class AimiDkaBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val dependency: BooleanPreferenceKey? = null,
    override val preferenceType: PreferenceType = PreferenceType.SWITCH,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false
) : BooleanPreferenceKey {

    DkaEnabled("aimi_dka_enabled", true, ApsStrings.aimi_dka_enabled_title, ApsStrings.aimi_dka_enabled_summary),
    DkaNotifyWatch("aimi_dka_notify_watch", true, ApsStrings.aimi_dka_notify_watch_title, ApsStrings.aimi_dka_notify_watch_summary),
    DkaRestrictSmb("aimi_dka_restrict_smb", false, ApsStrings.aimi_dka_restrict_smb_title, ApsStrings.aimi_dka_restrict_smb_summary),
    DkaSickDay("aimi_dka_sick_day", false, ApsStrings.aimi_dka_sick_day_title, ApsStrings.aimi_dka_sick_day_summary),
    DkaSglt2("aimi_dka_sglt2", false, ApsStrings.aimi_dka_sglt2_title, ApsStrings.aimi_dka_sglt2_summary),
    DkaGlp1("aimi_dka_glp1", false, ApsStrings.aimi_dka_glp1_title, ApsStrings.aimi_dka_glp1_summary),

    /** Semaglutide / GLP-1 minimum insulin (basal floor). Off by default. */
    DkaFloorEnabled("aimi_dka_floor_enabled", false, ApsStrings.aimi_dka_floor_enabled_title, ApsStrings.aimi_dka_floor_enabled_summary),
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
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val dependency: BooleanPreferenceKey? = null,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false
) : UnitDoublePreferenceKey {

    HighBg("aimi_dka_high_glucose", 250.0, 180, 350, ApsStrings.aimi_dka_high_bg_title, ApsStrings.aimi_dka_high_bg_summary, AimiDkaBooleanKey.DkaEnabled),

    VeryHighBg("aimi_dka_very_high_glucose", 300.0, 200, 400, ApsStrings.aimi_dka_very_high_bg_title, ApsStrings.aimi_dka_very_high_bg_summary, AimiDkaBooleanKey.DkaEnabled),

    /** The normal floor is not applied at or below this glucose value. */
    FloorMinBg("aimi_dka_floor_min_glucose", 100.0, 70, 180, ApsStrings.aimi_dka_floor_min_bg_title, ApsStrings.aimi_dka_floor_min_bg_summary, AimiDkaBooleanKey.DkaFloorEnabled),

    /**
     * Hard stop. At or below this glucose value, now or predicted, no floor is applied at all, not
     * even [AimiDkaIntKey.FloorDkaMinPercent]. Kept at or below [FloorMinBg] by the coordinator.
     *
     * The default is 80 rather than the 70 you might expect. Between this value and [FloorMinBg]
     * the only thing that can run is the small DKA override, and only while DKA risk is active, so
     * moving the bound costs almost no insulin either way. Given that, the conservative end is the
     * better default: a loop cannot treat ketosis at a low glucose anyway, because that needs
     * carbohydrate as well, so it should stand aside and leave it to the person.
     */
    FloorHardStopBg("aimi_dka_floor_hard_stop_glucose", 80.0, 50, 90, ApsStrings.aimi_dka_floor_hard_stop_bg_title, ApsStrings.aimi_dka_floor_hard_stop_bg_summary, AimiDkaBooleanKey.DkaFloorEnabled),
}

/** Numeric settings that are not glucose values. */
enum class AimiDkaIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val dependency: BooleanPreferenceKey? = null,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false
) : IntPreferenceKey {

    /** Normal floor: minimum temp basal as % of scheduled basal. Range 5-50. */
    FloorPercent("aimi_dka_floor_percent", 10, 5, 50, ApsStrings.aimi_dka_floor_percent_title, ApsStrings.aimi_dka_floor_percent_summary, AimiDkaBooleanKey.DkaFloorEnabled),

    /**
     * DKA override floor: applied even when BG is low, but only when DKA risk is active.
     * Always kept below [FloorPercent]. Range 0-20.
     */
    FloorDkaMinPercent("aimi_dka_floor_dka_min_percent", 5, 0, 20, ApsStrings.aimi_dka_floor_dka_min_percent_title, ApsStrings.aimi_dka_floor_dka_min_percent_summary, AimiDkaBooleanKey.DkaFloorEnabled),
}

/** Text settings: the ketone unit and the one-shot ketone entry field. */
enum class AimiDkaStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, TextRef> = emptyMap(),
    override val exportable: Boolean = true,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false
) : StringPreferenceKey {

    /** Value is the [KetoneUnit] name. */
    KetoneUnitChoice(
        key = "aimi_dka_ketone_unit",
        defaultValue = "MMOL",
        title = ApsStrings.aimi_dka_ketone_unit_title,
        summary = ApsStrings.aimi_dka_ketone_unit_summary,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "MMOL" to ApsStrings.aimi_dka_ketone_unit_mmol,
            "MGDL" to ApsStrings.aimi_dka_ketone_unit_mgdl
        )
    ),

    /**
     * One-shot entry field. The coordinator reads it at the next loop run, stores the value with a
     * time stamp and clears the field again. Not exported: a restored backup must never replay a value.
     */
    KetoneEntry(
        key = "aimi_dka_ketone_entry",
        defaultValue = "",
        title = ApsStrings.aimi_dka_ketone_entry_title,
        summary = ApsStrings.aimi_dka_ketone_entry_summary,
        exportable = false
    ),
}

/** Last entered ketone value and its time. Transient state, not a user preference, never exported. */
enum class AimiDkaDoubleStateKey(override val key: String, override val defaultValue: Double) : DoubleNonPreferenceKey {

    KetoneMmol("aimi_dka_state_ketone_mmol", 0.0);

    override val exportable: Boolean get() = false
}

/** Time stamps of the ketone value and of the notification handling. Never exported. */
enum class AimiDkaLongStateKey(override val key: String, override val defaultValue: Long) : LongNonPreferenceKey {

    KetoneTimestamp("aimi_dka_state_ketone_ts", 0L),
    LastAlert("aimi_dka_state_last_alert", 0L);

    override val exportable: Boolean get() = false
}

/** Risk level of the last loop run, as [DkaRisk] ordinal. Never exported. */
enum class AimiDkaIntStateKey(override val key: String, override val defaultValue: Int) : IntNonPreferenceKey {

    LastRisk("aimi_dka_state_last_risk", 0);

    override val exportable: Boolean get() = false
}
