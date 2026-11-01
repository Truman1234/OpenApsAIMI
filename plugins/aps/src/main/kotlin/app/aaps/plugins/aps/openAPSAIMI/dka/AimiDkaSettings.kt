package app.aaps.plugins.aps.openAPSAIMI.dka

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences

data class KetoneReading(val mmol: Double, val timestampMs: Long)

/** Manually entered ketone value + notification state. Transient state, not a user preference. */
object DkaKetoneStore {
    private const val FILE = "aimi_dka_state"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(c: Context, mmol: Double, ts: Long = System.currentTimeMillis()) =
        sp(c).edit().putFloat("ketone_mmol", mmol.toFloat()).putLong("ketone_ts", ts).apply()

    fun clear(c: Context) = sp(c).edit().remove("ketone_mmol").remove("ketone_ts").apply()

    fun read(c: Context): KetoneReading? {
        val ts = sp(c).getLong("ketone_ts", 0L)
        return if (ts == 0L) null else KetoneReading(sp(c).getFloat("ketone_mmol", 0f).toDouble(), ts)
    }

    internal fun state(c: Context) = sp(c)
}

/** Reads the typed DKA settings. */
class AimiDkaSettings(private val preferences: Preferences, private val context: Context) {

    fun config(): DkaConfig {
        val high = preferences.get(AimiDkaIntKey.HighBg).toDouble()
        val veryHigh = preferences.get(AimiDkaIntKey.VeryHighBg).toDouble().coerceAtLeast(high + 10.0)
        return DkaConfig(
            enabled = preferences.get(AimiDkaBooleanKey.DkaEnabled),
            restrictSmbOnSuspectedFailure = preferences.get(AimiDkaBooleanKey.DkaRestrictSmb),
            highBg = high,
            veryHighBg = veryHigh,
            extremeBg = maxOf(400.0, veryHigh + 50.0)
        )
    }

    fun notifyOnWatch() = preferences.get(AimiDkaBooleanKey.DkaNotifyWatch)

    fun floor() = FloorConfig(
        enabled = preferences.get(AimiDkaBooleanKey.DkaFloorEnabled),
        percent = preferences.get(AimiDkaIntKey.FloorPercent).coerceIn(10, 100),
        minBgMgdl = preferences.get(AimiDkaIntKey.FloorMinBg).coerceIn(70, 180)
    )

    fun buildInput(
        nowMs: Long, bgHistory: List<BgPoint>, insulinDelivered3h: Double, iob: Double, carbs2h: Double,
        lastSiteChangeMs: Long?, pumpSuspendedMin3h: Int, tdd: Double
    ): DkaInput {
        val k = DkaKetoneStore.read(context)
        return DkaInput(
            nowMs = nowMs, bgHistory = bgHistory, insulinDelivered3h = insulinDelivered3h, iob = iob, carbs2h = carbs2h,
            ketoneMmol = k?.mmol, ketoneTimestampMs = k?.timestampMs, lastSiteChangeMs = lastSiteChangeMs,
            sickDay = preferences.get(AimiDkaBooleanKey.DkaSickDay),
            onSglt2Inhibitor = preferences.get(AimiDkaBooleanKey.DkaSglt2),
            onGlp1Ra = preferences.get(AimiDkaBooleanKey.DkaGlp1),
            pumpSuspendedMin3h = pumpSuspendedMin3h, tdd = tdd
        )
    }
}
