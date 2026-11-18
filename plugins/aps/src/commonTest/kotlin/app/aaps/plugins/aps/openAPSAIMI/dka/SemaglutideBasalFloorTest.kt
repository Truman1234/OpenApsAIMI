package app.aaps.plugins.aps.openAPSAIMI.dka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class SemaglutideBasalFloorTest {

    private val cfg = FloorConfig(enabled = true, percent = 50, minBgMgdl = 100, dkaMinPercent = 5)

    private fun apply(
        proposed: Double = 0.0, scheduled: Double = 1.0, bg: Double? = 180.0, eventual: Double? = 150.0,
        c: FloorConfig = cfg, max: Double = 3.0, dkaActive: Boolean = false
    ) = SemaglutideBasalFloor.apply(proposed, scheduled, bg, eventual, c, max, dkaActive)

    @Test fun zeroTempIsRaisedToThePercentageOfScheduledBasal() {
        val r = apply()
        assertTrue(r.applied)
        assertEquals(0.5, r.rateUph, 1e-9)
        assertTrue((r.note).contains("50%"))
    }

    @Test fun rateAboveTheFloorIsUntouched() {
        val r = apply(proposed = 0.8)
        assertFalse(r.applied)
        assertEquals(0.8, r.rateUph)
        assertNull(r.note)
    }

    @Test fun lowGlucoseAlwaysWinsOverTheFloor() {
        val r = apply(bg = 95.0)
        assertFalse(r.applied)
        assertEquals(0.0, r.rateUph)
        assertTrue((r.note).contains("skipped"))
    }

    @Test fun glucoseExactlyAtTheLimitIsTreatedAsLow() {
        assertFalse(apply(bg = 100.0).applied)
        assertTrue(apply(bg = 101.0).applied)
    }

    @Test fun predictedLowGlucoseSkipsTheFloor() {
        val r = apply(eventual = 80.0)
        assertFalse(r.applied)
        assertTrue((r.note).contains("eventual"))
    }

    @Test fun missingGlucoseSkipsTheFloor() {
        assertFalse(apply(bg = null).applied)
    }

    @Test fun missingEventualGlucoseDoesNotBlockTheFloor() {
        assertTrue(apply(eventual = null).applied)
    }

    // 50 % is the most the floor can ask for, so the cap only shows above a 6 U/h scheduled basal.
    // This used to pass percent = 100, which the clamp now turns into 50: the floor came out at 2.0
    // U/h, below the max, so the test checked nothing.
    @Test fun floorNeverExceedsTheMaxBasal() {
        val r = apply(scheduled = 8.0, c = cfg.copy(percent = 50), max = 3.0)
        assertEquals(3.0, r.rateUph, 1e-9)
    }

    /**
     * A stored value above the key range is clamped, and the note says what was really used.
     *
     * An older build let the floor percentage go up to 100. The note used to print the stored value
     * while the rate came from the clamped one, so it claimed a percentage the loop never applied.
     */
    @Test fun aPercentAboveTheRangeIsReportedAsTheClampedOne() {
        val r = apply(scheduled = 1.0, c = cfg.copy(percent = 100))
        assertEquals(0.5, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("50%"), r.note!!)
        assertFalse(r.note!!.contains("100%"), r.note!!)
    }

    /** The same for a value below the range, and for the DKA override's own percentage. */
    @Test fun aPercentBelowTheRangeIsReportedAsTheClampedOne() {
        val r = apply(c = cfg.copy(percent = 2))
        assertTrue(r.note!!.contains("5%"), r.note!!)
        assertFalse(r.note!!.contains("2%"), r.note!!)
    }

    @Test fun aDkaMinPercentAboveTheRangeIsReportedAsTheClampedOne() {
        val r = apply(bg = 90.0, dkaActive = true, c = cfg.copy(dkaMinPercent = 90))
        assertEquals(0.2, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("20%"), r.note!!)
        assertFalse(r.note!!.contains("90%"), r.note!!)
    }

    /**
     * The bounds the dosing path clamps with are the bounds the settings screen offers.
     *
     * `AimiDkaFloorPercentMigration` caps a stored value at the key's own maximum. If that stopped
     * matching what [SemaglutideBasalFloor.apply] clamps with, a value the screen accepts could still
     * be clamped by the loop, which is the disagreement the migration exists to end.
     */
    @Test fun theKeyRangeMatchesTheBoundsTheFloorClampsWith() {
        assertEquals(SemaglutideBasalFloor.MIN_PERCENT, AimiDkaIntKey.FloorPercent.min)
        assertEquals(SemaglutideBasalFloor.MAX_PERCENT, AimiDkaIntKey.FloorPercent.max)
        assertEquals(SemaglutideBasalFloor.MAX_DKA_MIN_PERCENT, AimiDkaIntKey.FloorDkaMinPercent.max)
    }

    @Test fun percentBelow5IsClampedTo5() {
        val r = apply(c = cfg.copy(percent = 2))
        assertEquals(0.05, r.rateUph, 1e-9)
    }

    @Test fun percentAbove50IsClampedTo50() {
        val r = apply(c = cfg.copy(percent = 80))
        assertEquals(0.5, r.rateUph, 1e-9)
    }

    // --- DKA override tests ---

    @Test fun dkaActiveWithLowBgAppliesDkaFloor() {
        // BG = 90 (low), DKA active, dkaMinPercent = 5 → floor = 1.0 * 5% = 0.05
        val r = apply(bg = 90.0, dkaActive = true)
        assertTrue(r.applied)
        assertEquals(0.05, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("DKA min floor"))
    }

    @Test fun dkaInactiveWithLowBgSkipsFloor() {
        // Same BG but DKA not active → floor skipped
        val r = apply(bg = 90.0, dkaActive = false)
        assertFalse(r.applied)
        assertEquals(0.0, r.rateUph)
        assertTrue(r.note!!.contains("skipped"))
    }

    @Test fun dkaActiveWithPredictedLowAppliesDkaFloor() {
        // BG ok, eventual BG low, DKA active → DKA floor
        val r = apply(bg = 150.0, eventual = 80.0, dkaActive = true)
        assertTrue(r.applied)
        assertEquals(0.05, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("eventual"))
    }

    @Test fun dkaMinPercentZeroDisablesDkaFloor() {
        // dkaMinPercent = 0 → DKA override disabled, floor still skipped
        val r = apply(bg = 90.0, dkaActive = true, c = cfg.copy(dkaMinPercent = 0))
        assertFalse(r.applied)
        assertTrue(r.note!!.contains("skipped"))
    }

    @Test fun rateAlreadyAboveDkaFloorIsUntouched() {
        // proposed 0.1 >= dkaFloor 0.05, BG low, DKA active → no change
        val r = apply(proposed = 0.1, bg = 90.0, dkaActive = true)
        assertFalse(r.applied)
        assertEquals(0.1, r.rateUph)
    }

    @Test fun disabledOrNoScheduledBasalChangesNothing() {
        assertFalse(apply(c = cfg.copy(enabled = false)).applied)
        assertFalse(apply(scheduled = 0.0).applied)
    }

    @Test fun nonFiniteProposalIsLeftAlone() {
        assertFalse(apply(proposed = Double.NaN).applied)
    }
}
