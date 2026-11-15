package app.aaps.pump.dana.database

import app.aaps.core.ui.compose.pump.PumpHistoryRecord

/**
 * One Dana history record, in a plain form that works on every platform.
 *
 * This is the commonMain counterpart of the Room entity `DanaHistoryRecord` (androidMain only, since
 * Room is Android only here). [DanaHistoryRecordStore] converts between the two on Android. Keep the
 * fields and defaults the same as `DanaHistoryRecord`, so the conversion is a straight copy.
 */
data class DanaHistoryRecordData(
    override var timestamp: Long,
    override var code: Byte = 0x0F,
    override var value: Double = 0.0,
    override var bolusType: String = "None",
    override var stringValue: String = "",
    var duration: Long = 0,
    override var dailyBasal: Double = 0.0,
    override var dailyBolus: Double = 0.0,
    override var alarm: String = ""
) : PumpHistoryRecord {

    override val durationMinutes: Int get() = duration.toInt()
}
