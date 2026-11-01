package app.aaps.plugins.aps.openAPSAIMI.dka

import android.content.Context
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences

/**
 * Post-processes the AIMI decision ([RT]) once per loop tick:
 *  1. evaluates DKA risk and posts/updates the notification,
 *  2. optionally pauses the SMB when insulin delivery looks ineffective,
 *  3. enforces the semaglutide minimum basal (percentage of the scheduled basal).
 *
 * The caller wraps [process] in runCatching: a failure here must never stop the loop.
 */
class AimiDkaCoordinator(
    context: Context,
    preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {
    private val settings = AimiDkaSettings(preferences, context)
    private val notifier = DkaGuardNotifier(context)

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
        // 1) DKA risk + notification
        val dka = DkaGuard(settings.config()).evaluate(
            settings.buildInput(i.nowMs, i.bgHistory, i.insulinDelivered3h, i.iob, i.cobGrams, i.lastSiteChangeMs, 0, i.tdd)
        )
        notifier.update(dka, settings.notifyOnWatch(), i.nowMs)
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
        val cfg = settings.floor()
        if (cfg.enabled) {
            // rt.rate == null means "keep the running temp". A running zero/low temp must still be
            // raised to the floor, otherwise the loop could leave insulin at zero for hours.
            val proposed = rt.rate ?: i.runningTempRateUph.takeIf { i.runningTempMinutes > 0 }
            if (proposed != null) {
                val f = SemaglutideBasalFloor.apply(proposed, i.scheduledBasalUph, i.bgMgdl, rt.eventualBG, cfg, i.maxBasalUph)
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
}
