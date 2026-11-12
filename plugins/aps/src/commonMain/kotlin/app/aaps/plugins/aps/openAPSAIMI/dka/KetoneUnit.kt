package app.aaps.plugins.aps.openAPSAIMI.dka

/** Unit for entering blood ketones (BHB). The loop always works in mmol/L. */
enum class KetoneUnit(val mgdlPerMmol: Double) {

    MMOL(1.0),
    MGDL(10.41);

    fun toMmol(value: Double): Double = value / mgdlPerMmol

    fun fromMmol(mmol: Double): Double = mmol * mgdlPerMmol
}
