package app.aaps.plugins.aps.openAPSAIMI.dka

data class FloorConfig(
    val enabled: Boolean = false,
    /** Normal floor: % of scheduled basal. Applied when BG is not low. Range 5-50. */
    val percent: Int = 10,
    /** The normal floor is not applied at or below this glucose value (mg/dL). */
    val minBgMgdl: Double = 100.0,
    /**
     * DKA override floor: a small % of scheduled basal applied even when BG is low,
     * but only when DKA risk is active (WATCH or higher).
     * Must be less than [percent]. Range 0-20. Set to 0 to disable.
     */
    val dkaMinPercent: Int = 5,
    /**
     * Hard stop: at or below this glucose value (mg/dL), now or predicted, no floor is applied at
     * all, not even the DKA override. Always at or below [minBgMgdl]. Range 50-90, same default as
     * `AimiDkaUnitDoubleKey.FloorHardStopBg`.
     */
    val hardStopBgMgdl: Double = 80.0
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

    /** Bounds of the hard stop in mg/dL, the same as `AimiDkaUnitDoubleKey.FloorHardStopBg`. */
    const val MIN_HARD_STOP_BG: Int = 50
    const val MAX_HARD_STOP_BG: Int = 90

    data class Result(val rateUph: Double, val applied: Boolean, val note: String?)

    fun apply(
        proposedRateUph: Double,
        scheduledBasalUph: Double,
        bgMgdl: Double?,
        eventualBgMgdl: Double?,
        cfg: FloorConfig,
        maxBasalUph: Double,
        dkaActive: Boolean = false,
        glucoseText: GlucoseText = mgdlText
    ): Result {
        if (!cfg.enabled || scheduledBasalUph <= 0.0 || !proposedRateUph.isFinite()) return Result(proposedRateUph, false, null)
        val floor = (scheduledBasalUph * cfg.percent.coerceIn(5, 50) / 100.0).coerceAtMost(maxBasalUph)
        if (proposedRateUph >= floor) return Result(proposedRateUph, false, null)
        if (bgMgdl == null || !bgMgdl.isFinite()) return Result(proposedRateUph, false, "Floor skipped: no current BG")

        // Hard stop first: below it nothing here adds insulin, so the DKA override below never sees
        // a glucose value this low. Kept at or below minBgMgdl, so it can only ever be the stricter
        // of the two.
        val hardStop = cfg.hardStopBgMgdl
            .coerceIn(MIN_HARD_STOP_BG.toDouble(), MAX_HARD_STOP_BG.toDouble())
            .coerceAtMost(cfg.minBgMgdl)
        val eventualBelowHardStop = eventualBgMgdl != null && eventualBgMgdl.isFinite() && eventualBgMgdl <= hardStop
        if (bgMgdl <= hardStop || eventualBelowHardStop) {
            val bgDesc = if (bgMgdl <= hardStop)
                "BG ${glucoseText(bgMgdl)}"
            else
                "eventual BG ${glucoseText(eventualBgMgdl!!)}"
            return Result(proposedRateUph, false, "Floor hard stop: $bgDesc <= ${glucoseText(hardStop)}, no floor applied")
        }

        val bgLow = bgMgdl <= cfg.minBgMgdl
        val eventualLow = eventualBgMgdl != null && eventualBgMgdl.isFinite() && eventualBgMgdl < cfg.minBgMgdl

        if (bgLow || eventualLow) {
            // DKA override: apply a small floor even when BG is low, to limit ketosis risk
            if (dkaActive && cfg.dkaMinPercent > 0) {
                val dkaFloor = (scheduledBasalUph * cfg.dkaMinPercent.coerceIn(0, 20) / 100.0).coerceAtMost(maxBasalUph)
                if (proposedRateUph < dkaFloor) {
                    val bgDesc = if (bgLow)
                        "BG ${glucoseText(bgMgdl)} low"
                    else
                        "eventual BG ${glucoseText(eventualBgMgdl!!)} low"
                    return Result(
                        dkaFloor, true,
                        "DKA min floor ($bgDesc): %.2f -> %.2f U/h (%d%% of %.2f)"
                            .format(java.util.Locale.US, proposedRateUph, dkaFloor, cfg.dkaMinPercent, scheduledBasalUph)
                    )
                }
            }
            val skipReason = if (bgLow)
                "Floor skipped: BG ${glucoseText(bgMgdl)} <= ${glucoseText(cfg.minBgMgdl)}"
            else
                "Floor skipped: eventual BG ${glucoseText(eventualBgMgdl!!)} is low"
            return Result(proposedRateUph, false, skipReason)
        }

        return Result(
            floor, true,
            "Semaglutide floor: %.2f -> %.2f U/h (%d%% of %.2f)".format(java.util.Locale.US, proposedRateUph, floor, cfg.percent, scheduledBasalUph)
        )
    }
}
