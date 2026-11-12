package app.aaps.plugins.aps.openAPSAIMI.dka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


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

    @Test fun flat280For6HDespiteInsulinWarnsAndFlagsDeliveryFailure() {
        val d = DkaGuard().evaluate(input(series(360) { 280.0 }))
        assertEquals(DkaRisk.WARNING, d.risk)
        assertTrue((d.reasons.joinToString()).contains("No glucose response"))
        assertTrue(d.allowSmb) // alerts only by default
    }

    @Test fun smbPauseOnlyWhenEnabledAndDeliveryFailureSuspected() {
        val bg = series(360) { 280.0 }
        val on = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = true)).evaluate(input(bg))
        assertFalse(on.allowSmb)
        assertEquals(0.0, on.smbFactor)
        val off = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = false)).evaluate(input(bg))
        assertTrue(off.allowSmb)
    }

    @Test fun highBgThatIsFallingIsNotADeliveryFailure() {
        // 400 mg/dL six hours ago, falling 40 mg/dL per hour to 160 now
        val bg = series(360) { ago -> 160.0 + ago / 60.0 * 40.0 }
        val d = DkaGuard(DkaConfig(restrictSmbOnSuspectedFailure = true)).evaluate(input(bg))
        assertEquals(DkaRisk.NONE, d.risk)
        assertTrue(d.allowSmb)
    }

    @Test fun noFailureSuspectedWhenLittleInsulinWasDelivered() {
        // Not delivered != failed: nothing to conclude about the cannula
        val d = DkaGuard().evaluate(input(series(360) { 280.0 }, delivered3h = 0.1))
        assertFalse((d.reasons.joinToString()).contains("No glucose response"))
    }

    @Test fun ketones3Point2AreCriticalEvenWithNormalGlucose() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 3.2))
        assertEquals(DkaRisk.CRITICAL, d.risk)
    }

    @Test fun ketoneBands() {
        fun risk(k: Double) = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = k)).risk
        assertEquals(DkaRisk.NONE, risk(0.4))
        assertEquals(DkaRisk.WATCH, risk(0.8))
        assertEquals(DkaRisk.WARNING, risk(1.6))
        assertEquals(DkaRisk.CRITICAL, risk(3.0))
    }

    @Test fun euglycemicDkaPatternWithSglt2InhibitorIsCritical() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 1.6, sglt2 = true))
        assertEquals(DkaRisk.CRITICAL, d.risk)
        assertTrue((d.reasons.joinToString()).contains("euglycemic"))
    }

    @Test fun staleKetoneValueIsIgnored() {
        val d = DkaGuard().evaluate(input(series(360) { 110.0 }, ketone = 3.5, ketoneAgoMin = 5 * 60))
        assertEquals(DkaRisk.NONE, d.risk)
    }

    @Test fun glp1UserWithRaisedGlucoseAndNoKetoneValueIsAskedToMeasure() {
        val d = DkaGuard().evaluate(input(series(360) { 200.0 }, glp1 = true))
        assertEquals(DkaRisk.WATCH, d.risk)
        assertTrue((d.reasons.joinToString()).contains("GLP-1"))
    }

    @Test fun sparseCgmDataIsNotJudged() {
        val sparse = listOf(BgPoint(now, 300.0), BgPoint(now - 60 * min, 300.0), BgPoint(now - 110 * min, 300.0))
        val d = DkaGuard().evaluate(input(sparse))
        assertEquals(DkaRisk.NONE, d.risk)
        assertTrue((d.reasons.joinToString()).contains("sparse"))
    }

    @Test fun t400MgPerDlIsCritical() {
        val d = DkaGuard().evaluate(input(series(360) { 410.0 }))
        assertEquals(DkaRisk.CRITICAL, d.risk)
    }

    @Test fun disabledGuardStaysSilent() {
        val d = DkaGuard(DkaConfig(enabled = false)).evaluate(input(series(360) { 410.0 }, ketone = 4.0))
        assertEquals(DkaRisk.NONE, d.risk)
        assertTrue(d.allowSmb)
        assertTrue((d.messages).isEmpty())
    }
}
