package app.aaps.plugins.aps.openAPSAIMI.dka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class SemaglutideBasalFloorTest {

    // The hard stop is pinned here rather than taken from the default: it is a tuning value that may
    // change, and the tests below say what the code does, not what today's default happens to be.
    private val cfg = FloorConfig(enabled = true, percent = 50, minBgMgdl = 100.0, dkaMinPercent = 5, hardStopBgMgdl = 70.0)

    private fun apply(
        proposed: Double = 0.0, scheduled: Double = 1.0, bg: Double? = 180.0, eventual: Double? = 150.0,
        c: FloorConfig = cfg, max: Double = 3.0, dkaActive: Boolean = false
    ) = SemaglutideBasalFloor.apply(proposed, scheduled, bg, eventual, c, max, dkaActive)

    @Test fun zeroTempIsRaisedToThePercentageOfScheduledBasal() {
        val r = apply()
        assertTrue(r.applied)
        assertEquals(0.5, r.rateUph, 1e-9)
        assertTrue(r.note?.contains("50%") == true)
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
        assertTrue(r.note?.contains("skipped") == true)
    }

    @Test fun glucoseExactlyAtTheLimitIsTreatedAsLow() {
        assertFalse(apply(bg = 100.0).applied)
        assertTrue(apply(bg = 101.0).applied)
    }

    @Test fun predictedLowGlucoseSkipsTheFloor() {
        val r = apply(eventual = 80.0)
        assertFalse(r.applied)
        assertTrue(r.note?.contains("eventual") == true)
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

    // A 4 U/h basal keeps 5 % (0.2 U/h) above the pump minimum, so the clamp itself is what shows.
    @Test fun percentBelow5IsClampedTo5() {
        val r = apply(scheduled = 4.0, c = cfg.copy(percent = 2))
        assertEquals(0.2, r.rateUph, 1e-9)
    }

    @Test fun percentAbove50IsClampedTo50() {
        val r = apply(c = cfg.copy(percent = 80))
        assertEquals(0.5, r.rateUph, 1e-9)
    }

    // --- DKA override tests ---

    @Test fun dkaActiveWithLowBgAppliesDkaFloor() {
        // BG = 90 (low), DKA active, dkaMinPercent = 5 → floor = 4.0 * 5% = 0.2
        val r = apply(scheduled = 4.0, bg = 90.0, dkaActive = true)
        assertTrue(r.applied)
        assertEquals(0.2, r.rateUph, 1e-9)
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
        val r = apply(scheduled = 4.0, bg = 150.0, eventual = 80.0, dkaActive = true)
        assertTrue(r.applied)
        assertEquals(0.2, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("eventual"))
    }

    @Test fun dkaMinPercentZeroDisablesDkaFloor() {
        // dkaMinPercent = 0 → DKA override disabled, floor still skipped
        val r = apply(bg = 90.0, dkaActive = true, c = cfg.copy(dkaMinPercent = 0))
        assertFalse(r.applied)
        assertTrue(r.note!!.contains("skipped"))
    }

    @Test fun rateAlreadyAboveDkaFloorIsUntouched() {
        // proposed 0.2 >= dkaFloor 0.1 (5 % of 1.0, raised to the pump minimum), BG low, DKA active → no change
        val r = apply(proposed = 0.2, bg = 90.0, dkaActive = true)
        assertFalse(r.applied)
        assertEquals(0.2, r.rateUph)
    }

    // --- Pump minimum tests ---

    /**
     * 10 % of 0.8 U/h is 0.08 U/h. The Dana driver sets anything under 0.10 U/h as a zero temp, so
     * this floor reached the pump as 0 % (seen in Nightscout, 2026-11-25 to 27). It must be raised.
     */
    @Test fun aFloorUnderThePumpMinimumIsRaisedToIt() {
        val r = apply(scheduled = 0.8, c = cfg.copy(percent = 10))
        assertTrue(r.applied)
        assertEquals(SemaglutideBasalFloor.MIN_FLOOR_UPH, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("10%"), r.note!!)
        assertTrue(r.note!!.contains("raised"), r.note!!)
    }

    @Test fun theDkaOverrideIsRaisedToThePumpMinimumToo() {
        val r = apply(scheduled = 0.8, bg = 90.0, dkaActive = true)
        assertTrue(r.applied)
        assertEquals(SemaglutideBasalFloor.MIN_FLOOR_UPH, r.rateUph, 1e-9)
        assertTrue(r.note!!.contains("raised"), r.note!!)
    }

    @Test fun aProposalJustUnderThePumpMinimumIsRaised() {
        val r = apply(proposed = 0.09, scheduled = 0.8, c = cfg.copy(percent = 10))
        assertTrue(r.applied)
        assertEquals(SemaglutideBasalFloor.MIN_FLOOR_UPH, r.rateUph, 1e-9)
    }

    @Test fun aFloorAboveThePumpMinimumSaysNothingAboutIt() {
        val r = apply()
        assertFalse(r.note!!.contains("raised"), r.note!!)
    }

    @Test fun thePumpMinimumNeverGoesOverTheMaxBasal() {
        val r = apply(scheduled = 0.8, c = cfg.copy(percent = 10), max = 0.05)
        assertEquals(0.05, r.rateUph, 1e-9)
    }

    // --- Hard stop tests ---

    /**
     * The point of the hard stop. Before it, the DKA override had no lower limit: it asked for its
     * percentage at any glucose value, however low, as long as DKA risk was active.
     */
    @Test fun dkaOverrideIsAbandonedBelowTheHardStop() {
        val r = apply(bg = 55.0, dkaActive = true)
        assertFalse(r.applied)
        assertEquals(0.0, r.rateUph)
        assertTrue(r.note!!.contains("hard stop"), r.note!!)
    }

    @Test fun glucoseExactlyAtTheHardStopIsAlreadyTooLow() {
        assertFalse(apply(bg = 70.0, dkaActive = true).applied)
        assertTrue(apply(bg = 71.0, dkaActive = true).applied)
    }

    @Test fun aPredictedValueBelowTheHardStopAlsoStopsTheDkaOverride() {
        val r = apply(bg = 150.0, eventual = 60.0, dkaActive = true)
        assertFalse(r.applied)
        assertTrue(r.note!!.contains("eventual"), r.note!!)
        assertTrue(r.note!!.contains("hard stop"), r.note!!)
    }

    /** The hard stop is the stricter of the two, so a stored value above the low threshold is capped at it. */
    @Test fun aHardStopAboveTheLowThresholdIsCappedAtIt() {
        // Uncapped, a hard stop of 90 would block at BG 85 and nothing would be applied.
        val r = apply(bg = 85.0, dkaActive = true, c = cfg.copy(minBgMgdl = 80.0, hardStopBgMgdl = 90.0))
        assertTrue(r.applied)
        assertFalse(r.note!!.contains("hard stop"), r.note!!)
    }

    @Test fun aHardStopOutsideTheKeyRangeIsClamped() {
        // 10 is below the range: clamped to 50, so 55 still passes and 45 does not.
        val c = cfg.copy(hardStopBgMgdl = 10.0)
        assertTrue(apply(bg = 55.0, dkaActive = true, c = c).applied)
        assertFalse(apply(bg = 45.0, dkaActive = true, c = c).applied)
    }

    @Test fun theHardStopKeyRangeMatchesTheBoundsTheFloorClampsWith() {
        assertEquals(SemaglutideBasalFloor.MIN_HARD_STOP_BG, AimiDkaUnitDoubleKey.FloorHardStopBg.minMgdl)
        assertEquals(SemaglutideBasalFloor.MAX_HARD_STOP_BG, AimiDkaUnitDoubleKey.FloorHardStopBg.maxMgdl)
    }

    /**
     * The glucose thresholds are stored in mg/dL and the loop compares against mg/dL, so the
     * coordinator must read them with `getRaw`. If someone switches it to plain `get`, a mmol/L
     * user gets 5.6 where 100 is meant, every reading is then above the threshold, and the floor
     * would run through a hypo. This states the expected scale so that mistake has a test to fail.
     */
    @Test fun theGlucoseThresholdsAreStoredInMgdl() {
        AimiDkaUnitDoubleKey.entries.forEach {
            assertTrue(it.defaultValue >= 36.0, "${it.key} looks like mmol/L: ${it.defaultValue}")
            assertTrue(it.defaultValue >= it.minMgdl && it.defaultValue <= it.maxMgdl, it.key)
        }
    }

    /** The note follows the user's unit, and nothing else in the result does. */
    @Test fun theNoteUsesTheFormatterItIsGiven() {
        val mmol: GlucoseText = { "${(it / 18.0 * 10).toInt() / 10.0} mmol/L" }
        val r = SemaglutideBasalFloor.apply(0.0, 1.0, 65.0, 150.0, cfg, 3.0, true, mmol)
        assertFalse(r.applied)
        assertTrue(r.note!!.contains("mmol/L"), r.note!!)
        assertFalse(r.note!!.contains("mg/dL"), r.note!!)
    }

    /** The shipped defaults, stated once so a change to either is a deliberate edit here. */
    @Test fun theShippedDefaultsAreTheTunedOnes() {
        assertEquals(100.0, AimiDkaUnitDoubleKey.FloorMinBg.defaultValue)
        assertEquals(80.0, AimiDkaUnitDoubleKey.FloorHardStopBg.defaultValue)
        assertEquals(AimiDkaUnitDoubleKey.FloorHardStopBg.defaultValue, FloorConfig().hardStopBgMgdl)
    }

    @Test fun disabledOrNoScheduledBasalChangesNothing() {
        assertFalse(apply(c = cfg.copy(enabled = false)).applied)
        assertFalse(apply(scheduled = 0.0).applied)
    }

    @Test fun nonFiniteProposalIsLeftAlone() {
        assertFalse(apply(proposed = Double.NaN).applied)
    }
}
