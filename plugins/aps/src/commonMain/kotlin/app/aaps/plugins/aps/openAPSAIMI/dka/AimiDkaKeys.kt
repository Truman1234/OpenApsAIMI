package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
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

/** Numeric settings. Glucose values are always mg/dL. */
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

    HighBg("aimi_dka_high_bg", 250, 180, 350, ApsStrings.aimi_dka_high_bg_title, ApsStrings.aimi_dka_high_bg_summary, AimiDkaBooleanKey.DkaEnabled),

    /** Range 200..400 = 200 steps, the most the slider can show. */
    VeryHighBg("aimi_dka_very_high_bg", 300, 200, 400, ApsStrings.aimi_dka_very_high_bg_title, ApsStrings.aimi_dka_very_high_bg_summary, AimiDkaBooleanKey.DkaEnabled),

    /** Minimum temp basal as % of the scheduled basal rate. */
    FloorPercent("aimi_dka_floor_percent", 50, 10, 100, ApsStrings.aimi_dka_floor_percent_title, ApsStrings.aimi_dka_floor_percent_summary, AimiDkaBooleanKey.DkaFloorEnabled),

    /** The floor is never applied at or below this BG (mg/dL). */
    FloorMinBg("aimi_dka_floor_min_bg", 100, 70, 180, ApsStrings.aimi_dka_floor_min_bg_title, ApsStrings.aimi_dka_floor_min_bg_summary, AimiDkaBooleanKey.DkaFloorEnabled),
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
