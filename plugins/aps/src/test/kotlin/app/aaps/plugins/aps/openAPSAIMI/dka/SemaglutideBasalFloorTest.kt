package app.aaps.plugins.aps.openAPSAIMI.dka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SemaglutideBasalFloorTest {

    // The hard stop is pinned here rather than taken from the default: it is a tuning value that may
    // change, and the tests below say what the code does, not what today's default happens to be.
    private val cfg = FloorConfig(enabled = true, percent = 50, minBgMgdl = 100.0, dkaMinPercent = 5, hardStopBgMgdl = 70.0)

    private fun apply(
        proposed: Double = 0.0, scheduled: Double = 1.0, bg: Double? = 180.0, eventual: Double? = 150.0,
        c: FloorConfig = cfg, max: Double = 3.0, dkaActive: Boolean = false
    ) = SemaglutideBasalFloor.apply(proposed, scheduled, bg, eventual, c, max, dkaActive)

    @Test fun `zero temp is raised to the percentage of scheduled basal`() {
        val r = apply()
        assertThat(r.applied).isTrue()
        assertThat(r.rateUph).isWithin(1e-9).of(0.5)
        assertThat(r.note).contains("50%")
    }

    @Test fun `rate above the floor is untouched`() {
        val r = apply(proposed = 0.8)
        assertThat(r.applied).isFalse()
        assertThat(r.rateUph).isEqualTo(0.8)
        assertThat(r.note).isNull()
    }

    @Test fun `low glucose always wins over the floor`() {
        val r = apply(bg = 95.0)
        assertThat(r.applied).isFalse()
        assertThat(r.rateUph).isEqualTo(0.0)
        assertThat(r.note).contains("skipped")
    }

    @Test fun `glucose exactly at the limit is treated as low`() {
        assertThat(apply(bg = 100.0).applied).isFalse()
        assertThat(apply(bg = 101.0).applied).isTrue()
    }

    @Test fun `predicted low glucose skips the floor`() {
        val r = apply(eventual = 80.0)
        assertThat(r.applied).isFalse()
        assertThat(r.note).contains("eventual")
    }

    @Test fun `missing glucose skips the floor`() {
        assertThat(apply(bg = null).applied).isFalse()
    }

    @Test fun `missing eventual glucose does not block the floor`() {
        assertThat(apply(eventual = null).applied).isTrue()
    }

    @Test fun `floor never exceeds the max basal`() {
        val r = apply(scheduled = 4.0, c = cfg.copy(percent = 100), max = 3.0)
        assertThat(r.rateUph).isWithin(1e-9).of(3.0)
    }

    @Test fun `percent below 5 is clamped to 5`() {
        val r = apply(c = cfg.copy(percent = 2))
        assertThat(r.rateUph).isWithin(1e-9).of(0.05)
    }

    @Test fun `percent above 50 is clamped to 50`() {
        val r = apply(c = cfg.copy(percent = 80))
        assertThat(r.rateUph).isWithin(1e-9).of(0.5)
    }

    @Test fun `disabled or no scheduled basal changes nothing`() {
        assertThat(apply(c = cfg.copy(enabled = false)).applied).isFalse()
        assertThat(apply(scheduled = 0.0).applied).isFalse()
    }

    @Test fun `non finite proposal is left alone`() {
        assertThat(apply(proposed = Double.NaN).applied).isFalse()
    }

    // --- DKA override tests ---

    @Test fun `low glucose with dkaActive applies the DKA min floor`() {
        val r = apply(bg = 85.0, dkaActive = true)
        assertThat(r.applied).isTrue()
        assertThat(r.rateUph).isWithin(1e-9).of(0.05)  // 5% of 1.0
        assertThat(r.note).contains("DKA min floor")
        assertThat(r.note).contains("85")
    }

    @Test fun `low glucose without dkaActive still skips the floor`() {
        val r = apply(bg = 85.0, dkaActive = false)
        assertThat(r.applied).isFalse()
        assertThat(r.note).contains("skipped")
    }

    @Test fun `predicted low glucose with dkaActive applies the DKA min floor`() {
        val r = apply(eventual = 75.0, dkaActive = true)
        assertThat(r.applied).isTrue()
        assertThat(r.rateUph).isWithin(1e-9).of(0.05)
        assertThat(r.note).contains("eventual")
    }

    @Test fun `dka min floor of zero disables the override`() {
        val r = apply(bg = 85.0, dkaActive = true, c = cfg.copy(dkaMinPercent = 0))
        assertThat(r.applied).isFalse()
        assertThat(r.note).contains("skipped")
    }

    @Test fun `dkaActive does not raise rate already above the DKA min floor`() {
        // proposed = 0.1 is above the DKA floor (0.05), so nothing changes
        val r = apply(proposed = 0.1, bg = 85.0, dkaActive = true)
        assertThat(r.applied).isFalse()
        assertThat(r.rateUph).isEqualTo(0.1)
    }
    // --- Hard stop ---

    /**
     * The point of the hard stop. Before it, the DKA override had no lower limit: it asked for its
     * percentage at any glucose value, however low, as long as DKA risk was active.
     */
    @Test fun `the DKA override is abandoned below the hard stop`() {
        val r = apply(bg = 55.0, dkaActive = true)
        assertThat(r.applied).isFalse()
        assertThat(r.rateUph).isEqualTo(0.0)
        assertThat(r.note).contains("hard stop")
    }

    @Test fun `glucose exactly at the hard stop is already too low`() {
        assertThat(apply(bg = 70.0, dkaActive = true).applied).isFalse()
        assertThat(apply(bg = 71.0, dkaActive = true).applied).isTrue()
    }

    @Test fun `a predicted value below the hard stop also stops the DKA override`() {
        val r = apply(bg = 150.0, eventual = 60.0, dkaActive = true)
        assertThat(r.applied).isFalse()
        assertThat(r.note).contains("eventual")
        assertThat(r.note).contains("hard stop")
    }

    /** The hard stop is the stricter of the two, so a stored value above the low threshold is capped at it. */
    @Test fun `a hard stop above the low threshold is capped at it`() {
        // Uncapped a hard stop of 90 would block at BG 85 and nothing would be applied.
        val r = apply(bg = 85.0, dkaActive = true, c = cfg.copy(minBgMgdl = 80.0, hardStopBgMgdl = 90.0))
        assertThat(r.applied).isTrue()
        assertThat(r.note).doesNotContain("hard stop")
    }

    @Test fun `a hard stop outside the key range is clamped`() {
        // 10 is below the range so it is clamped to 50 and 55 still passes while 45 does not.
        val c = cfg.copy(hardStopBgMgdl = 10.0)
        assertThat(apply(bg = 55.0, dkaActive = true, c = c).applied).isTrue()
        assertThat(apply(bg = 45.0, dkaActive = true, c = c).applied).isFalse()
    }

    @Test fun `the hard stop key range matches the bounds the floor clamps with`() {
        assertThat(AimiDkaUnitDoubleKey.FloorHardStopBg.minMgdl).isEqualTo(SemaglutideBasalFloor.MIN_HARD_STOP_BG)
        assertThat(AimiDkaUnitDoubleKey.FloorHardStopBg.maxMgdl).isEqualTo(SemaglutideBasalFloor.MAX_HARD_STOP_BG)
    }

    /** The shipped defaults, stated once so a change to either is a deliberate edit here. */
    @Test fun `the shipped defaults are the tuned ones`() {
        assertThat(AimiDkaUnitDoubleKey.FloorMinBg.defaultValue).isEqualTo(100.0)
        assertThat(AimiDkaUnitDoubleKey.FloorHardStopBg.defaultValue).isEqualTo(80.0)
        assertThat(FloorConfig().hardStopBgMgdl).isEqualTo(AimiDkaUnitDoubleKey.FloorHardStopBg.defaultValue)
    }

    /**
     * The glucose thresholds are stored in mg/dL and the loop compares against mg/dL, so the
     * settings reader must use `getRaw`. If someone switches it to plain `get`, a mmol/L user gets
     * 5.6 where 100 is meant, every reading is then above the threshold, and the floor would run
     * through a hypo. This states the expected scale so that mistake has a test to fail.
     */
    @Test fun `the glucose thresholds are stored in mg per dL`() {
        AimiDkaUnitDoubleKey.entries.forEach {
            assertThat(it.defaultValue).isAtLeast(36.0)
            assertThat(it.defaultValue).isAtLeast(it.minMgdl.toDouble())
            assertThat(it.defaultValue).isAtMost(it.maxMgdl.toDouble())
        }
    }

    /** The note follows the user's unit and nothing else in the result does. */
    @Test fun `the note uses the formatter it is given`() {
        val mmol: GlucoseText = { "${(it / 18.0 * 10).toInt() / 10.0} mmol/L" }
        val r = SemaglutideBasalFloor.apply(0.0, 1.0, 65.0, 150.0, cfg, 3.0, true, mmol)
        assertThat(r.applied).isFalse()
        assertThat(r.note).contains("mmol/L")
        assertThat(r.note).doesNotContain("mg/dL")
    }
}
