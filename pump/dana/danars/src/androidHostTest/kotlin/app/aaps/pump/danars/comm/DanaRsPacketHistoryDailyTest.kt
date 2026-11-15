package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.dana.database.DanaHistoryRecordStore
import app.aaps.pump.danars.DanaRSTestBase
import com.google.common.truth.Truth.assertThat
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalDateTime
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mock

class DanaRsPacketHistoryDailyTest : DanaRSTestBase() {

    @Mock lateinit var danaHistoryRecordStore: DanaHistoryRecordStore
    @Mock lateinit var pumpSync: PumpSync

    @Test fun runTest() {
        val packet = DanaRSPacketHistoryDaily(aapsLogger, dateUtil, rxBus, danaHistoryRecordStore, pumpSync, danaPump).with(System.currentTimeMillis())
        Assertions.assertEquals("REVIEW__DAILY", packet.friendlyName)
    }

    /** A daily total has only a date. Where the clock goes forward at midnight, that midnight does not exist */
    @Test fun dailyTotalOnTheDayTheClockChangesAtMidnightIsRead() {
        withZone("America/Havana") {
            val havana = DateTimeZone.forID("America/Havana")
            // Cuba moved the clock from 00:00 to 01:00 on 2026-03-08. A past date on purpose: the rules for
            // future years change with the time zone data (joda-time 2.15.1 dropped the change of March 2027).
            assertThat(havana.isLocalDateTimeGap(LocalDateTime(2026, 3, 8, 0, 0))).isTrue()
            val packet = DanaRSPacketHistoryDaily(aapsLogger, dateUtil, rxBus, danaHistoryRecordStore, pumpSync, danaPump).with(0)

            // Code 3, 2026-03-08, daily basal and bolus 0
            packet.handleMessage(byteArrayOf(0xB2.toByte(), 0x14, 0x03, 26, 3, 8, 0, 0, 0, 0, 0, 0))

            assertThat(packet.danaRHistoryRecord.timestamp).isEqualTo(DateTime(2026, 3, 8, 1, 0, 0, havana).millis)
        }
    }

    /** The packet code reads the zone of the JVM, the expected values use the Joda zone. Both are set to the same zone. */
    private fun withZone(id: String, block: () -> Unit) {
        val jodaZone = DateTimeZone.getDefault()
        val jvmZone = java.util.TimeZone.getDefault()
        try {
            DateTimeZone.setDefault(DateTimeZone.forID(id))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
            block()
        } finally {
            DateTimeZone.setDefault(jodaZone)
            java.util.TimeZone.setDefault(jvmZone)
        }
    }
}
