package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

data class FloorConfig(
    val enabled: Boolean = false,
    /** Normal floor: % of scheduled basal. Applied when BG is not low. Range 5-50. */
    val percent: Int = 10,
    /** The normal floor is not applied at or below this BG (mg/dL). */
    val minBgMgdl: Int = 100,
    /**
     * DKA override floor: a small % of scheduled basal applied even when BG is low,
     * but only when DKA risk is active (WATCH or higher).
     * Must be less than [percent]. Range 0-20. Set to 0 to disable.
     */
    val dkaMinPercent: Int = 5
)

/**
 * Semaglutide / GLP-1 minimum insulin: keeps the loop's basal at or above a percentage of the
 * scheduled basal rate, so insulin is not driven to ~0 for hours (ketosis/DKA risk in type 1).
 *
 * Normal behaviour: hypoglycemia wins — the floor is skipped when BG is low or predicted low.
 *
 * DKA override: when [dkaActive] is true (DKA risk WATCH or higher), a smaller floor
 * ([FloorConfig.dkaMinPercent]) is applied even when BG is at or below [FloorConfig.minBgMgdl],
 * as a compromise between hypo risk and ketosis risk.
 */
object SemaglutideBasalFloor {

    /**
     * The range the floor percentage is allowed to be, and the most the DKA override may ask for.
     *
     * These are the same bounds as the keys (`AimiDkaIntKey.FloorPercent` and
     * `AimiDkaIntKey.FloorDkaMinPercent`), kept here because a stored value can sit outside them: an
     * older build let the floor percentage go up to 100, and a 100 % floor would stop the loop ever
     * going below the scheduled basal, which blocks hypo protection. `AimiDkaFloorPercentMigration`
     * writes such a value back into range; until it runs, this clamp is what keeps dosing sane.
     */
    const val MIN_PERCENT: Int = 5
    const val MAX_PERCENT: Int = 50
    const val MAX_DKA_MIN_PERCENT: Int = 20

    data class Result(val rateUph: Double, val applied: Boolean, val note: String?)

    fun apply(
        proposedRateUph: Double,
        scheduledBasalUph: Double,
        bgMgdl: Double?,
        eventualBgMgdl: Double?,
        cfg: FloorConfig,
        maxBasalUph: Double,
        dkaActive: Boolean = false
    ): Result {
        if (!cfg.enabled || scheduledBasalUph <= 0.0 || !proposedRateUph.isFinite()) return Result(proposedRateUph, false, null)
        // Clamped once and used for both the rate and the note: a stored value outside the key range
        // (an older build allowed up to 100) must not make the note claim a percentage that was not used.
        val percent = cfg.percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val floor = (scheduledBasalUph * percent / 100.0).coerceAtMost(maxBasalUph)
        if (proposedRateUph >= floor) return Result(proposedRateUph, false, null)
        if (bgMgdl == null || !bgMgdl.isFinite()) return Result(proposedRateUph, false, "Floor skipped: no current BG")

        val bgLow = bgMgdl <= cfg.minBgMgdl
        val eventualLow = eventualBgMgdl != null && eventualBgMgdl.isFinite() && eventualBgMgdl < cfg.minBgMgdl

        if (bgLow || eventualLow) {
            // DKA override: apply a small floor even when BG is low, to limit ketosis risk
            if (dkaActive && cfg.dkaMinPercent > 0) {
                val dkaPercent = cfg.dkaMinPercent.coerceIn(0, MAX_DKA_MIN_PERCENT)
                val dkaFloor = (scheduledBasalUph * dkaPercent / 100.0).coerceAtMost(maxBasalUph)
                if (proposedRateUph < dkaFloor) {
                    val bgDesc = if (bgLow)
                        "BG ${bgMgdl.toInt()} mg/dL low"
                    else
                        "eventual BG ${eventualBgMgdl!!.toInt()} mg/dL low"
                    return Result(
                        dkaFloor, true,
                        "DKA min floor ($bgDesc): ${aimiFmt2(proposedRateUph)} -> ${aimiFmt2(dkaFloor)} U/h ($dkaPercent% of ${aimiFmt2(scheduledBasalUph)})"
                    )
                }
            }
            val skipReason = if (bgLow)
                "Floor skipped: BG ${bgMgdl.toInt()} <= ${cfg.minBgMgdl} mg/dL"
            else
                "Floor skipped: eventual BG ${eventualBgMgdl!!.toInt()} mg/dL is low"
            return Result(proposedRateUph, false, skipReason)
        }

        return Result(
            floor, true,
            "Semaglutide floor: ${aimiFmt2(proposedRateUph)} -> ${aimiFmt2(floor)} U/h ($percent% of ${aimiFmt2(scheduledBasalUph)})"
        )
    }
}
