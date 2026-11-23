package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings

/**
 * Post-processes the AIMI decision ([RT]) once per loop tick (shared by Android and iOS):
 *  1. takes over a ketone value typed into the settings,
 *  2. evaluates DKA risk and posts/updates the notification,
 *  3. optionally pauses the SMB when insulin delivery looks ineffective,
 *  4. enforces the semaglutide minimum basal (percentage of the scheduled basal).
 *
 * The caller wraps [process] in runCatching: a failure here must never stop the loop.
 */
class AimiDkaCoordinator(
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val notificationManager: NotificationManager,
    private val aapsLogger: AAPSLogger,
    private val profileUtil: ProfileUtil
) {

    /**
     * Glucose values in the reason text follow the unit the user has set.
     * Only the text: every comparison below stays in mg/dL.
     */
    private val glucoseText: GlucoseText = { profileUtil.fromMgdlToStringWithUnits(it) }

    data class Inputs(
        val nowMs: Long,
        val bgMgdl: Double?,
        val bgHistory: List<BgPoint>,
        val insulinDelivered3h: Double,
        val iob: Double,
        val cobGrams: Double,
        val tdd: Double,
        val lastSiteChangeMs: Long?,
        /** Scheduled basal rate right now (U/h), including profile percentage. */
        val scheduledBasalUph: Double,
        /** Upper limit for the floor (U/h). */
        val maxBasalUph: Double,
        /** Currently running temp basal: rate (U/h) and minutes left; 0 when none. */
        val runningTempRateUph: Double = 0.0,
        val runningTempMinutes: Int = 0
    )

    fun process(rt: RT, i: Inputs) {
        takeOverKetoneEntry(i.nowMs)

        // 1) DKA risk + notification
        val dka = DkaGuard(config(), glucoseText).evaluate(buildInput(i))
        notify(dka, i.nowMs)
        if (dka.risk != DkaRisk.NONE) {
            aapsLogger.info(LTag.APS, "AIMI DKA ${dka.risk}: ${dka.reasons.joinToString("; ")}")
            rt.reason.append("\nDKA ${dka.risk}: ${dka.reasons.joinToString("; ")}")
        }

        // 2) SMB pause (opt-in)
        if (!dka.allowSmb) {
            if ((rt.units ?: 0.0) > 0.0) rt.reason.append("\nDKA guard: SMB paused (insulin seems ineffective)")
            rt.units = 0.0
        }

        // 3) Semaglutide minimum basal
        val cfg = floor()
        if (cfg.enabled) {
            // rt.rate == null means "keep the running temp". A running zero/low temp must still be
            // raised to the floor, otherwise the loop could leave insulin at zero for hours.
            val proposed = rt.rate ?: i.runningTempRateUph.takeIf { i.runningTempMinutes > 0 }
            if (proposed != null) {
                val dkaActive = dka.risk != DkaRisk.NONE
                val f = SemaglutideBasalFloor.apply(proposed, i.scheduledBasalUph, i.bgMgdl, rt.eventualBG, cfg, i.maxBasalUph, dkaActive, glucoseText)
                if (f.applied) {
                    rt.rate = f.rateUph
                    if ((rt.duration ?: 0) <= 0) rt.duration = 30
                }
                f.note?.let {
                    rt.reason.append("\n$it")
                    aapsLogger.debug(LTag.APS, it)
                }
            }
        }
    }

    /** Reads the typed ketone value, stores it in mmol/L with the time, and clears the field. */
    internal fun takeOverKetoneEntry(nowMs: Long) {
        val raw = preferences.get(AimiDkaStringKey.KetoneEntry).trim()
        if (raw.isEmpty()) return
        preferences.put(AimiDkaStringKey.KetoneEntry, "")
        val unit = ketoneUnit()
        val mmol = raw.replace(',', '.').toDoubleOrNull()?.let { unit.toMmol(it) }
        if (mmol == null || !mmol.isFinite() || mmol < 0.0 || mmol > MAX_KETONE_MMOL) {
            aapsLogger.warn(LTag.APS, "AIMI DKA: ketone entry ignored: '$raw'")
            notificationManager.post(
                NotificationId.DKA_WATCH,
                rh.gs(ApsStrings.aimi_dka_ketone_invalid),
                validMinutes = 60,
                actions = listOf(enterKetonesAction())
            )
            return
        }
        preferences.put(AimiDkaDoubleStateKey.KetoneMmol, mmol)
        preferences.put(AimiDkaLongStateKey.KetoneTimestamp, nowMs)
        // Force a fresh alert for the new value.
        preferences.put(AimiDkaIntStateKey.LastRisk, 0)
        aapsLogger.info(LTag.APS, "AIMI DKA: ketone value taken over")
    }

    private fun ketoneUnit(): KetoneUnit =
        if (preferences.get(AimiDkaStringKey.KetoneUnitChoice) == KetoneUnit.MGDL.name) KetoneUnit.MGDL else KetoneUnit.MMOL

    private fun config(): DkaConfig {
        // getRaw, not get: these are compared against glucose in mg/dL. Plain get() would answer in
        // the user's display unit, so a mmol/L user would get 13.9 where the guard expects 250.
        val high = preferences.getRaw(AimiDkaUnitDoubleKey.HighBg)
        val veryHigh = preferences.getRaw(AimiDkaUnitDoubleKey.VeryHighBg).coerceAtLeast(high + 10.0)
        return DkaConfig(
            enabled = preferences.get(AimiDkaBooleanKey.DkaEnabled),
            restrictSmbOnSuspectedFailure = preferences.get(AimiDkaBooleanKey.DkaRestrictSmb),
            highBg = high,
            veryHighBg = veryHigh,
            extremeBg = maxOf(400.0, veryHigh + 50.0)
        )
    }

    private fun floor(): FloorConfig {
        val percent = preferences.get(AimiDkaIntKey.FloorPercent)
            .coerceIn(SemaglutideBasalFloor.MIN_PERCENT, SemaglutideBasalFloor.MAX_PERCENT)
        val dkaMin = preferences.get(AimiDkaIntKey.FloorDkaMinPercent)
            .coerceIn(0, SemaglutideBasalFloor.MAX_DKA_MIN_PERCENT)
            .coerceAtMost(percent - 1)  // DKA floor must always be below the normal floor
        // getRaw for the same reason as in config(): the floor compares against mg/dL.
        val minBg = preferences.getRaw(AimiDkaUnitDoubleKey.FloorMinBg).coerceIn(70.0, 180.0)
        return FloorConfig(
            enabled = preferences.get(AimiDkaBooleanKey.DkaFloorEnabled),
            percent = percent,
            minBgMgdl = minBg,
            dkaMinPercent = dkaMin,
            hardStopBgMgdl = preferences.getRaw(AimiDkaUnitDoubleKey.FloorHardStopBg)
                .coerceIn(SemaglutideBasalFloor.MIN_HARD_STOP_BG.toDouble(), SemaglutideBasalFloor.MAX_HARD_STOP_BG.toDouble())
                .coerceAtMost(minBg)  // the hard stop can only ever be the stricter of the two
        )
    }

    private fun buildInput(i: Inputs): DkaInput {
        val ts = preferences.get(AimiDkaLongStateKey.KetoneTimestamp)
        val hasKetone = ts > 0L
        return DkaInput(
            nowMs = i.nowMs,
            bgHistory = i.bgHistory,
            insulinDelivered3h = i.insulinDelivered3h,
            iob = i.iob,
            carbs2h = i.cobGrams,
            ketoneMmol = if (hasKetone) preferences.get(AimiDkaDoubleStateKey.KetoneMmol) else null,
            ketoneTimestampMs = if (hasKetone) ts else null,
            lastSiteChangeMs = i.lastSiteChangeMs,
            sickDay = preferences.get(AimiDkaBooleanKey.DkaSickDay),
            onSglt2Inhibitor = preferences.get(AimiDkaBooleanKey.DkaSglt2),
            onGlp1Ra = preferences.get(AimiDkaBooleanKey.DkaGlp1),
            pumpSuspendedMin3h = 0,
            tdd = i.tdd
        )
    }

    /** Posts at most one DKA notification; repeats it on escalation or after a risk dependent pause. */
    private fun notify(d: DkaDecision, nowMs: Long) {
        val notifyOnWatch = preferences.get(AimiDkaBooleanKey.DkaNotifyWatch)
        if (d.risk == DkaRisk.NONE || (d.risk == DkaRisk.WATCH && !notifyOnWatch)) {
            dismissAll()
            preferences.put(AimiDkaIntStateKey.LastRisk, 0)
            return
        }
        val lastRisk = DkaRisk.entries.getOrElse(preferences.get(AimiDkaIntStateKey.LastRisk)) { DkaRisk.NONE }
        val lastAlert = preferences.get(AimiDkaLongStateKey.LastAlert)
        val escalated = d.risk.ordinal > lastRisk.ordinal
        val due = nowMs - lastAlert >= repeatMs(d.risk)
        if (!escalated && !due) return

        val (id, title) = when (d.risk) {
            DkaRisk.CRITICAL -> NotificationId.DKA_CRITICAL to ApsStrings.dka_notif_title_critical
            DkaRisk.WARNING -> NotificationId.DKA_WARNING to ApsStrings.dka_notif_title_warning
            else -> NotificationId.DKA_WATCH to ApsStrings.dka_notif_title_watch
        }
        dismissAll()
        val reasons = d.reasons.joinToString("\n") { "• $it" }
        val actions = d.messages.joinToString("\n") { "→ $it" }
        val body = listOf(reasons, actions).filter { it.isNotBlank() }.joinToString("\n\n")
        notificationManager.post(id, rh.gs(title) + "\n" + body, validMinutes = 180, actions = listOf(enterKetonesAction()))
        preferences.put(AimiDkaIntStateKey.LastRisk, d.risk.ordinal)
        preferences.put(AimiDkaLongStateKey.LastAlert, nowMs)
    }

    /**
     * Button on every DKA notification. The closure itself does nothing: the id-based navigation in
     * `ElementNavigation.handleNotificationAction` is what actually opens the ketone entry field, the
     * same split `IDENTIFICATION_NOT_SET` and `MASTER_PASSWORD_NOT_SET` already use. Kept here only
     * because the button text has to travel with the notification.
     */
    private fun enterKetonesAction() = NotificationAction(ApsStrings.aimi_dka_enter_ketones_action) {}

    private fun dismissAll() {
        notificationManager.dismiss(NotificationId.DKA_WATCH)
        notificationManager.dismiss(NotificationId.DKA_WARNING)
        notificationManager.dismiss(NotificationId.DKA_CRITICAL)
    }

    private fun repeatMs(r: DkaRisk) = when (r) {
        DkaRisk.CRITICAL -> 15 * 60_000L
        DkaRisk.WARNING -> 60 * 60_000L
        else -> 120 * 60_000L
    }

    private companion object {
        /** About 83 mg/dL. Higher values are typing errors. */
        const val MAX_KETONE_MMOL = 8.0
    }
}
