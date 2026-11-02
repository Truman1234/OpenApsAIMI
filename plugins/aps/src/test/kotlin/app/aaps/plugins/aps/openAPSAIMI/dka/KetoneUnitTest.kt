package app.aaps.plugins.aps.openAPSAIMI.dka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KetoneUnitTest {

    @Test
    fun `mmol value stays the same`() {
        assertThat(KetoneUnit.MMOL.toMmol(1.5)).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `mg per dl is converted to mmol per l`() {
        assertThat(KetoneUnit.MGDL.toMmol(31.23)).isWithin(0.01).of(3.0)
        assertThat(KetoneUnit.MGDL.toMmol(15.6)).isWithin(0.01).of(1.5)
    }

    @Test
    fun `mmol per l is converted to mg per dl`() {
        assertThat(KetoneUnit.MGDL.fromMmol(8.0)).isWithin(0.1).of(83.3)
    }
}
