package app.aaps.plugins.aps.openAPSAIMI.dka

import kotlin.test.Test
import kotlin.test.assertEquals


class KetoneUnitTest {

    @Test
    fun mmolValueStaysTheSame() {
        assertEquals(1.5, KetoneUnit.MMOL.toMmol(1.5), 1e-9)
    }

    @Test
    fun mgPerDlIsConvertedToMmolPerL() {
        assertEquals(3.0, KetoneUnit.MGDL.toMmol(31.23), 0.01)
        assertEquals(1.5, KetoneUnit.MGDL.toMmol(15.6), 0.01)
    }

    @Test
    fun mmolPerLIsConvertedToMgPerDl() {
        assertEquals(83.3, KetoneUnit.MGDL.fromMmol(8.0), 0.1)
    }
}
