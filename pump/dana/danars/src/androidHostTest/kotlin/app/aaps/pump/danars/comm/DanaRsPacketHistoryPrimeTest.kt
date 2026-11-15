package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.dana.database.DanaHistoryRecordStore
import app.aaps.pump.danars.DanaRSTestBase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mock

class DanaRsPacketHistoryPrimeTest : DanaRSTestBase() {

    @Mock lateinit var danaHistoryRecordStore: DanaHistoryRecordStore
    @Mock lateinit var pumpSync: PumpSync

    @Test fun runTest() {
        val packet = DanaRSPacketHistoryPrime(aapsLogger, dateUtil, rxBus, danaHistoryRecordStore, pumpSync, danaPump).with(System.currentTimeMillis())
        Assertions.assertEquals("REVIEW__PRIME", packet.friendlyName)
    }
}