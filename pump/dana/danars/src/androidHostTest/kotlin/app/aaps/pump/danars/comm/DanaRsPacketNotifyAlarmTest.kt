package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.dana.DanaStringsValues
import app.aaps.pump.danars.DanaRSTestBase
import app.aaps.pump.danars.services.DanaRSAlarmReporter
import app.aaps.shared.tests.generatedTextResolver
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

class DanaRsPacketNotifyAlarmTest : DanaRSTestBase() {

    @Mock lateinit var pumpSync: PumpSync

    /**
     * The real English text, not the mocked [rh]: "dana" is not one of the five owners the mock
     * resolves (see [generatedTextResolver]), and stubbing [rh] here would hit a Mockito trap - the
     * general [app.aaps.shared.tests.stubTextRefResolution] answer for any [app.aaps.core.keys.interfaces.TextRef]
     * is already active, so `whenever(rh.gs(...))` would call that answer first and throw before the
     * new stub is recorded.
     */
    private val danaRh = generatedTextResolver("dana" to DanaStringsValues::textOf)

    @Test
    fun runTest() {
        val packet = DanaRSPacketNotifyAlarm(aapsLogger, danaRh, DanaRSAlarmReporter(danaRh, notificationManager, pumpSync, danaPump))
        // test params
        Assertions.assertEquals(0, packet.getRequestParams().size)
        // test message decoding
        packet.handleMessage(createArray(17, 0x01.toByte()))
        Assertions.assertEquals(false, packet.failed)
        // no error
        packet.handleMessage(createArray(17, 0.toByte()))
        Assertions.assertEquals(true, packet.failed)
        Assertions.assertEquals("NOTIFY__ALARM", packet.friendlyName)
    }

    /** Alarm codes from the Dana-i protocol spec, Notify 0x03 */
    @ParameterizedTest
    @CsvSource("2, Pump Error", "3, Occlusion", "4, Low Battery", "5, Pump Shutdown")
    fun alarmCodeShowsTheTextFromTheSpec(code: Int, text: String) {
        val packet = DanaRSPacketNotifyAlarm(aapsLogger, danaRh, DanaRSAlarmReporter(danaRh, notificationManager, pumpSync, danaPump))

        packet.handleMessage(byteArrayOf(0xC3.toByte(), 0x03, code.toByte()))

        verify(notificationManager).post(eq(NotificationId.DANA_PUMP_ALARM), eq(text), any(), any<Int>(), anyOrNull(), any(), anyOrNull())
    }
}
