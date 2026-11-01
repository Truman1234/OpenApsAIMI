package app.aaps.plugins.aps.openAPSAIMI.dka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DkaGuardTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    /** One reading every 5 minutes, newest at `now`, [value] maps minutes-ago to mg/dL. */
    private fun series(minutes: Int, value: (Int) -> Double): List<BgPoint> =
        (0..minutes step 5).map { ago -> BgPoint(now - ago * min, value(ago)) }

    private fun input(
        bg: List<BgPoint>,
        delivered3h: Double = 3.0,
        carbs: Double = 0.0,
        ketone: Double? = null,
        ketoneAgoMin: Int = 10,
        sick: Boolean = false,
        sglt2: Boolean = false,
        glp1: Boolean = false,
        tdd: Double = 40.0
    ) = DkaInput(
        nowMs = now, bgHistory = bg, insulinDelivered3h = delivered3h, iob = 1.0, carbs2h = carbs,
        ketoneMmol = ketone, ketoneTimestampMs = ketone?.let { now - ketoneAgoMin * min },
        lastSiteChangeMs = null, sickDay = sick, onSglt2Inhibitor = sglt2, onGlp1Ra = glp1,
        pumpSuspendedMin3h = 0, tdd = tdd
    )

    @Test fun `flat 280 for 6 h despite insulin warns and flags delivery failure`() {
        val d = DkaGuard().evaluate(input(series(360) { 280.0 }))
        assertThat(d.risk).isEqualTo(DkaRisk.WARNING)
        assertThat(d.reasons.joinToString()).contains("No glucose response")
        assertThat(d.allowSmb).isTrue() // alerts only by default
    }

    @Test fun `SMB pause only when enabled and delivery failure suspected`() {
        val bg = series(360) { 280.0 }
        val on = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = true)).evaluate(input(bg))
        assertThat(on.allowSmb).isFalse()
        assertThat(on.smbFactor).isEqualTo(0.0)
        val off = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = false)).evaluate(input(bg))
        assertThat(off.allowSmb).isTrue()
    }

    @Test fun `high BG that is falling is not a delivery failure`() {
        // 400 mg/dL six hours ago, falling 40 mg/dL per hour to 160 now
        val bg = series(360) { ago -> 160.0 + ago / 60.0 * 40.0 }
        val d = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = true)).evaluate(input(bg))
        assertThat(d.risk).isEqualTo(DkaRisk.NONE)
        assertThat(d.allowSmb).isTrue()
    }

    @Test fun `no failure suspected when little insulin was delivered`() {
        // Not delivered != failed: nothing to conclude about the cannula
        val d = DkaGuard().evaluate(input(series(360) { 280.0 }, delivered3h = 0.1))
        assertThat(d.reasons.joinToString()).doesNotContain("No glucose response")
    }

    @Test fun `ketones 3 point 2 are critical even with normal glucose`() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 3.2))
        assertThat(d.risk).isEqualTo(DkaRisk.CRITICAL)
    }

    @Test fun `ketone bands`() {
        fun risk(k: Double) = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = k)).risk
        assertThat(risk(0.4)).isEqualTo(DkaRisk.NONE)
        assertThat(risk(0.8)).isEqualTo(DkaRisk.WATCH)
        assertThat(risk(1.6)).isEqualTo(DkaRisk.WARNING)
        assertThat(risk(3.0)).isEqualTo(DkaRisk.CRITICAL)
    }

    @Test fun `euglycemic DKA pattern with SGLT2 inhibitor is critical`() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 1.6, sglt2 = true))
        assertThat(d.risk).isEqualTo(DkaRisk.CRITICAL)
        assertThat(d.reasons.joinToString()).contains("euglycemic")
    }

    @Test fun `stale ketone value is ignored`() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 3.5, ketoneAgoMin = 5 * 60))
        assertThat(d.risk).isEqualTo(DkaRisk.NONE)
    }

    @Test fun `GLP-1 user with raised glucose and no ketone value is asked to measure`() {
        val d = DkaGuard().evaluate(input(series(360) { 200.0 }, glp1 = true))
        assertThat(d.risk).isEqualTo(DkaRisk.WATCH)
        assertThat(d.reasons.joinToString()).contains("GLP-1")
    }

    @Test fun `sparse CGM data is not judged`() {
        val sparse = listOf(BgPoint(now, 300.0), BgPoint(now - 60 * min, 300.0), BgPoint(now - 110 * min, 300.0))
        val d = DkaGuard().evaluate(input(sparse))
        assertThat(d.risk).isEqualTo(DkaRisk.NONE)
        assertThat(d.reasons.joinToString()).contains("sparse")
    }

    @Test fun `400 mg per dL is critical`() {
        val d = DkaGuard().evaluate(input(series(360) { 410.0 }))
        assertThat(d.risk).isEqualTo(DkaRisk.CRITICAL)
    }

    @Test fun `disabled guard stays silent`() {
        val d = DkaGuard(DkaConfig(enabled = false)).evaluate(input(series(360) { 410.0 }, ketone = 4.0))
        assertThat(d.risk).isEqualTo(DkaRisk.NONE)
        assertThat(d.allowSmb).isTrue()
        assertThat(d.messages).isEmpty()
    }
}
