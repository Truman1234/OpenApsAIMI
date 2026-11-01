package app.aaps.plugins.aps.openAPSAIMI.dka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SemaglutideBasalFloorTest {

    private val cfg = FloorConfig(enabled = true, percent = 50, minBgMgdl = 100)

    private fun apply(
        proposed: Double = 0.0, scheduled: Double = 1.0, bg: Double? = 180.0, eventual: Double? = 150.0,
        c: FloorConfig = cfg, max: Double = 3.0
    ) = SemaglutideBasalFloor.apply(proposed, scheduled, bg, eventual, c, max)

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

    @Test fun `percent below 10 is clamped to 10`() {
        val r = apply(c = cfg.copy(percent = 5))
        assertThat(r.rateUph).isWithin(1e-9).of(0.1)
    }

    @Test fun `disabled or no scheduled basal changes nothing`() {
        assertThat(apply(c = cfg.copy(enabled = false)).applied).isFalse()
        assertThat(apply(scheduled = 0.0).applied).isFalse()
    }

    @Test fun `non finite proposal is left alone`() {
        assertThat(apply(proposed = Double.NaN).applied).isFalse()
    }
}
