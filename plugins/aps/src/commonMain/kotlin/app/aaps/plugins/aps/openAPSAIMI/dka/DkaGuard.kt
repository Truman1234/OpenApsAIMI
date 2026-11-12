package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1

/**
 * DkaGuard - Ketoacidosis (DKA) protection layer for AIMI.
 *
 * EXPERIMENTAL. Not a medical device. It does NOT replace ketone testing,
 * medical advice, or manual insulin delivery by pen/syringe.
 *
 * What it detects (conservatively):
 *  1. Suspected insulin delivery failure: sustained high BG with no response
 *     despite insulin having been delivered (occlusion, bad cannula, expired
 *     insulin, pump failure).
 *  2. Prolonged severe hyperglycemia.
 *  3. Elevated blood ketones (manual entry), including euglycemic DKA risk
 *     (SGLT2 inhibitors, illness, fasting).
 *
 * What it does:
 *  - Raises a risk level (NONE / WATCH / WARNING / CRITICAL) with actions.
 *  - Optionally restricts SMB stacking when delivery failure is suspected
 *    (default OFF: alerts only).
 *
 * All glucose values are mg/dL internally. Convert mmol/L * 18.0 before use.
 *
 * Integration points in AIMI (adapt names to your fork):
 *  - Call evaluate() once per loop cycle, before SMB/basal decisions.
 *  - If decision.allowSmb == false, skip SMB; apply decision.smbFactor.
 *  - Post decision.messages as a persistent notification (Level.URGENT for CRITICAL).
 *  - Feed ketone values from a treatment/careportal note or a dedicated dialog.
 */

enum class DkaRisk { NONE, WATCH, WARNING, CRITICAL }

data class BgPoint(val timestampMs: Long, val mgdl: Double)

data class DkaInput(
    val nowMs: Long,
    /** Recent CGM readings, any order. Should cover at least the last 6 h. */
    val bgHistory: List<BgPoint>,
    /** Total insulin (bolus + SMB + delivered basal) in the last 3 h, in U. */
    val insulinDelivered3h: Double,
    /** Current insulin on board, in U. */
    val iob: Double,
    /** Carbs entered in the last 2 h, in g. */
    val carbs2h: Double,
    /** Last manually entered blood ketone value in mmol/L, or null. */
    val ketoneMmol: Double?,
    val ketoneTimestampMs: Long?,
    /** Timestamp of last infusion set / cannula change, or null if unknown. */
    val lastSiteChangeMs: Long?,
    /** User flags. */
    val sickDay: Boolean = false,
    val onSglt2Inhibitor: Boolean = false,
    /** GLP-1 receptor agonist (e.g. semaglutide): reduced appetite/insulin need, vomiting can mask DKA. */
    val onGlp1Ra: Boolean = false,
    /** Pump suspended / disconnected time in last 3 h, minutes. */
    val pumpSuspendedMin3h: Int = 0,
    /** Typical total daily dose, used to sanity check delivered insulin. */
    val tdd: Double
)

data class DkaConfig(
    val enabled: Boolean = true,
    val restrictSmbOnSuspectedFailure: Boolean = false, // default: alerts only
    val highBg: Double = 250.0,        // mg/dL, ~13.9 mmol/L
    val veryHighBg: Double = 300.0,    // mg/dL, ~16.7 mmol/L
    val extremeBg: Double = 400.0,     // mg/dL, ~22.2 mmol/L
    val minSensorCoverage: Double = 0.7, // fraction of expected readings required
    val ketoneValidityMs: Long = 4 * 3_600_000L,
    val siteMaxAgeMs: Long = 4L * 24 * 3_600_000L // 4 days
)

data class DkaDecision(
    val risk: DkaRisk,
    val allowSmb: Boolean,
    /** Multiply SMB size by this (1.0 = unchanged). */
    val smbFactor: Double,
    val messages: List<String>,
    val reasons: List<String>
)

class DkaGuard(private val cfg: DkaConfig = DkaConfig()) {

    fun evaluate(i: DkaInput): DkaDecision {
        if (!cfg.enabled) return DkaDecision(DkaRisk.NONE, true, 1.0, emptyList(), emptyList())

        val reasons = mutableListOf<String>()
        val actions = linkedSetOf<String>()
        var risk = DkaRisk.NONE
        var suspectedFailure = false

        fun raise(r: DkaRisk) { if (r.ordinal > risk.ordinal) risk = r }

        val bg = i.bgHistory.filter { it.timestampMs <= i.nowMs }.sortedBy { it.timestampMs }
        val current = bg.lastOrNull()?.takeIf { i.nowMs - it.timestampMs <= 15 * 60_000L }

        // ---- 1. Sustained hyperglycemia analysis (needs reliable CGM data) ----
        if (current != null) {
            val aboveHigh = minutesContinuouslyAbove(bg, i.nowMs, cfg.highBg)
            val aboveVeryHigh = minutesContinuouslyAbove(bg, i.nowMs, cfg.veryHighBg)
            val slope1h = slopePerHour(bg, i.nowMs, 60)
            val coverage = coverage(bg, i.nowMs, 120)
            val reliable = coverage >= cfg.minSensorCoverage

            if (reliable) {
                if (aboveHigh >= 120) {
                    reasons += "BG > ${cfg.highBg.toInt()} mg/dL for ${aboveHigh} min"
                    raise(DkaRisk.WATCH)
                }
                if (aboveVeryHigh >= 120) {
                    reasons += "BG > ${cfg.veryHighBg.toInt()} mg/dL for ${aboveVeryHigh} min"
                    raise(DkaRisk.WARNING)
                }
                if (aboveVeryHigh >= 240 || current.mgdl >= cfg.extremeBg) {
                    reasons += "Severe/prolonged hyperglycemia (${current.mgdl.toInt()} mg/dL)"
                    raise(DkaRisk.CRITICAL)
                }

                // ---- 2. Suspected delivery failure ----
                // High BG, not falling, despite meaningful insulin and no recent carbs.
                val expectedMinInsulin = maxOf(0.5, i.tdd / 24.0 * 3 * 0.5) // >= half of normal 3 h need
                val insulinWasDelivered = i.insulinDelivered3h >= expectedMinInsulin
                val notResponding = slope1h >= -5.0 // < 5 mg/dL/h drop
                if (aboveHigh >= 120 && insulinWasDelivered && notResponding && i.carbs2h < 10.0) {
                    suspectedFailure = true
                    reasons += "No glucose response to ${aimiFmt1(i.insulinDelivered3h)} U delivered in 3 h"
                    raise(DkaRisk.WARNING)
                }
                // Pump suspended a long time while high.
                if (i.pumpSuspendedMin3h >= 60 && current.mgdl > cfg.highBg) {
                    reasons += "Pump suspended ${i.pumpSuspendedMin3h} min while BG high"
                    raise(DkaRisk.WARNING)
                }
                // Old infusion site makes failure more likely.
                val siteAge = i.lastSiteChangeMs?.let { i.nowMs - it }
                if (suspectedFailure && siteAge != null && siteAge > cfg.siteMaxAgeMs) {
                    reasons += "Infusion site older than ${cfg.siteMaxAgeMs / 86_400_000L} days"
                }
            } else {
                reasons += "CGM data too sparse for reliable DKA assessment"
            }
        }

        // ---- 3. Ketones ----
        val ketoneFresh = i.ketoneMmol != null && i.ketoneTimestampMs != null &&
            i.nowMs - i.ketoneTimestampMs <= cfg.ketoneValidityMs
        if (ketoneFresh) {
            val k = i.ketoneMmol!!
            when {
                k >= 3.0 -> { reasons += "Ketones ${aimiFmt1(k)} mmol/L (>= 3.0)"; raise(DkaRisk.CRITICAL) }
                k >= 1.5 -> { reasons += "Ketones ${aimiFmt1(k)} mmol/L (>= 1.5)"; raise(DkaRisk.WARNING) }
                k >= 0.6 -> { reasons += "Ketones ${aimiFmt1(k)} mmol/L (>= 0.6)"; raise(DkaRisk.WATCH) }
            }
            // Euglycemic DKA: ketones matter even when BG is normal.
            if (k >= 1.5 && (i.onSglt2Inhibitor || i.onGlp1Ra || i.sickDay) && (current?.mgdl ?: 999.0) < cfg.highBg) {
                reasons += "Elevated ketones with normal BG (SGLT2i/GLP-1 RA/illness): euglycemic DKA possible"
                raise(DkaRisk.CRITICAL)
            }
        }

        // Sick day or SGLT2i with high BG and no ketone measurement: ask for one.
        if ((i.sickDay || i.onSglt2Inhibitor || i.onGlp1Ra) && !ketoneFresh &&
            (current?.mgdl ?: 0.0) > 180.0 && risk.ordinal < DkaRisk.WATCH.ordinal) {
            reasons += "Sick day / SGLT2i / GLP-1 RA without recent ketone measurement"
            raise(DkaRisk.WATCH)
        }

        // ---- Actions per level ----
        when (risk) {
            DkaRisk.NONE -> {}
            DkaRisk.WATCH -> {
                actions += "Check blood ketones."
                actions += "Drink water; recheck BG in 1 h."
            }
            DkaRisk.WARNING -> {
                actions += "Check blood ketones now."
                actions += "Change infusion set and insulin reservoir."
                actions += "Take a correction dose by pen/syringe (not via pump) per your doctor's plan."
                actions += "Hydrate; recheck BG and ketones in 1-2 h."
            }
            DkaRisk.CRITICAL -> {
                actions += "URGENT: check ketones immediately."
                actions += "Follow your sick-day/DKA plan; inject insulin by pen/syringe."
                actions += "Contact your diabetes team or emergency services now if ketones >= 3.0 mmol/L, " +
                    "or if you have vomiting, abdominal pain, fast breathing or confusion."
            }
        }

        // ---- Loop behavior ----
        var allowSmb = true
        var smbFactor = 1.0
        if (cfg.restrictSmbOnSuspectedFailure && suspectedFailure) {
            // If delivery is failing, more SMBs only build a hidden insulin depot
            // that may cause hypoglycemia once the new set works.
            allowSmb = false
            smbFactor = 0.0
            actions += "AIMI SMB paused: insulin delivery appears ineffective (avoid insulin stacking)."
        }

        return DkaDecision(risk, allowSmb, smbFactor, actions.toList(), reasons)
    }

    // ---------- helpers ----------

    /** Minutes the glucose has been continuously above [threshold] up to now. */
    private fun minutesContinuouslyAbove(bg: List<BgPoint>, now: Long, threshold: Double): Int {
        var start: Long? = null
        for (p in bg.asReversed()) {
            if (p.mgdl > threshold) start = p.timestampMs else break
        }
        return start?.let { ((now - it) / 60_000L).toInt() } ?: 0
    }

    /** Linear-regression slope in mg/dL per hour over the last [windowMin] minutes. */
    private fun slopePerHour(bg: List<BgPoint>, now: Long, windowMin: Int): Double {
        val pts = bg.filter { now - it.timestampMs <= windowMin * 60_000L }
        if (pts.size < 4) return 0.0
        val t0 = pts.first().timestampMs
        val xs = pts.map { (it.timestampMs - t0) / 3_600_000.0 }
        val ys = pts.map { it.mgdl }
        val mx = xs.average(); val my = ys.average()
        val denom = xs.sumOf { (it - mx) * (it - mx) }
        if (denom == 0.0) return 0.0
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / denom
    }

    /** Fraction of expected 5-min readings present in the last [windowMin] minutes. */
    private fun coverage(bg: List<BgPoint>, now: Long, windowMin: Int): Double {
        val n = bg.count { now - it.timestampMs <= windowMin * 60_000L }
        return n / (windowMin / 5.0)
    }
}
