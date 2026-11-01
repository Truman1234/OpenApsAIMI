package app.aaps.plugins.aps.openAPSAIMI.dka

data class FloorConfig(val enabled: Boolean = false, val percent: Int = 50, val minBgMgdl: Int = 100)

/**
 * Semaglutide / GLP-1 minimum insulin: keeps the loop's basal at or above a percentage of the
 * scheduled basal rate, so insulin is not driven to ~0 for hours (ketosis/DKA risk in type 1).
 *
 * Hypoglycemia always wins: the floor is not applied when BG is low, predicted low, or unknown.
 */
object SemaglutideBasalFloor {
    data class Result(val rateUph: Double, val applied: Boolean, val note: String?)

    fun apply(
        proposedRateUph: Double,
        scheduledBasalUph: Double,
        bgMgdl: Double?,
        eventualBgMgdl: Double?,
        cfg: FloorConfig,
        maxBasalUph: Double
    ): Result {
        if (!cfg.enabled || scheduledBasalUph <= 0.0 || !proposedRateUph.isFinite()) return Result(proposedRateUph, false, null)
        val floor = (scheduledBasalUph * cfg.percent.coerceIn(10, 100) / 100.0).coerceAtMost(maxBasalUph)
        if (proposedRateUph >= floor) return Result(proposedRateUph, false, null)
        if (bgMgdl == null || !bgMgdl.isFinite()) return Result(proposedRateUph, false, "Floor skipped: no current BG")
        if (bgMgdl <= cfg.minBgMgdl) return Result(proposedRateUph, false, "Floor skipped: BG ${bgMgdl.toInt()} <= ${cfg.minBgMgdl} mg/dL")
        if (eventualBgMgdl != null && eventualBgMgdl.isFinite() && eventualBgMgdl < cfg.minBgMgdl)
            return Result(proposedRateUph, false, "Floor skipped: eventual BG ${eventualBgMgdl.toInt()} mg/dL is low")
        return Result(
            floor, true,
            "Semaglutide floor: %.2f -> %.2f U/h (%d%% of %.2f)".format(java.util.Locale.US, proposedRateUph, floor, cfg.percent, scheduledBasalUph)
        )
    }
}
