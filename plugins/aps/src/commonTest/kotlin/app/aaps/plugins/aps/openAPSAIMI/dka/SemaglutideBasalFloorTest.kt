package app.aaps.plugins.aps.openAPSAIMI.dka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class SemaglutideBasalFloorTest {

    private val cfg = FloorConfig(enabled = true, percent = 50, minBgMgdl = 100)

    private fun apply(
        proposed: Double = 0.0, scheduled: Double = 1.0, bg: Double? = 180.0, eventual: Double? = 150.0,
        c: FloorConfig = cfg, max: Double = 3.0
    ) = SemaglutideBasalFloor.apply(proposed, scheduled, bg, eventual, c, max)

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

    @Test fun floorNeverExceedsTheMaxBasal() {
        val r = apply(scheduled = 4.0, c = cfg.copy(percent = 100), max = 3.0)
        assertEquals(3.0, r.rateUph, 1e-9)
    }

    @Test fun percentBelow10IsClampedTo10() {
        val r = apply(c = cfg.copy(percent = 5))
        assertEquals(0.1, r.rateUph, 1e-9)
    }

    @Test fun disabledOrNoScheduledBasalChangesNothing() {
        assertFalse(apply(c = cfg.copy(enabled = false)).applied)
        assertFalse(apply(scheduled = 0.0).applied)
    }

    @Test fun nonFiniteProposalIsLeftAlone() {
        assertFalse(apply(proposed = Double.NaN).applied)
    }
}
