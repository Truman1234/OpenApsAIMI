package app.aaps.pump.danars.session

import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.keys.DanaStringComposedKey
import app.aaps.pump.dana.keys.DanaStringNonKey
import app.aaps.pump.danars.comm.DanaRSPacketBasalGetProfileNumber
import app.aaps.pump.danars.emulator.EmulatorBleTransport
import app.aaps.pump.danars.encryption.BleEncryption
import app.aaps.pump.danars.encryption.EncryptionType
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The shared [DanaRsSession] against the pump emulator, for the three handshakes.
 * The same emulator is used by the tests of the Android class `BLEComm`.
 */
class DanaRsSessionTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var decimalFormatter: DecimalFormatter
    @Mock lateinit var profileStoreProvider: dev.zacsweers.metro.Provider<ProfileStore>

    private class FakeHost(override val deviceName: String) : DanaRsHost {

        val notices = mutableListOf<DanaRsNotice>()
        var alarm: String? = null
        override fun hasBluetoothPermission() = true
        override fun text(text: DanaRsText, argument: String?) = "$text ${argument ?: ""}".trim()
        override fun show(notice: DanaRsNotice) {
            notices.add(notice)
        }

        override fun dismissWrongPumpPassword() {}
        override fun pumpCheckErrorText(errorFlags: Int?) = "pump error $errorFlags"
        override fun reportAlarm(text: String) {
            alarm = text
        }

        override fun changePump() {}
        override fun clearPairing() {}
        override fun restartApp(reason: String) {}
    }

    private val deviceName = "UHH00002TI"
    private val deviceAddress = "00:11:22:33:44:55"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var danaPump: DanaPump
    private lateinit var transport: EmulatorBleTransport
    private lateinit var host: FakeHost
    private lateinit var session: DanaRsSession

    @BeforeEach
    fun setup() {
        whenever(preferences.get(any<DanaStringNonKey>())).thenReturn("")
        whenever(preferences.get(any<StringComposedNonPreferenceKey>(), any())).thenReturn("")
        whenever(preferences.get(DanaStringNonKey.Password)).thenReturn("0000") // the emulator default
        danaPump = DanaPump(aapsLogger, preferences, dateUtil, decimalFormatter, profileStoreProvider)
        host = FakeHost(deviceName)
    }

    @AfterEach
    fun tearDown() {
        if (::transport.isInitialized) transport.awaitPendingCallbacks()
        scope.cancel()
    }

    private fun createSession(encryptionType: EncryptionType) {
        transport = EmulatorBleTransport(deviceName = deviceName, encryptionType = encryptionType).apply {
            pairingDelayMs = 0
            emulator.historyEventDelayMs = 0
        }
        session = DanaRsSession(
            aapsLogger = aapsLogger,
            rxBus = rxBus,
            danaPump = danaPump,
            bleEncryption = BleEncryption(),
            dateUtil = dateUtil,
            preferences = preferences,
            bleTransport = transport,
            host = host,
            findMessage = { null },
            scope = scope
        ).apply { messageTimeoutMs = 5000 }
    }

    private suspend fun connectAndWait() {
        assertThat(session.connect("test", deviceAddress)).isTrue()
        withTimeout(5000) { while (!session.isConnected) delay(10) }
        assertThat(session.isConnecting).isFalse()
    }

    @Test
    fun `v1 handshake with a stored pairing key connects`() = runBlocking {
        whenever(preferences.get(DanaStringComposedKey.ParingKey, deviceName)).thenReturn("ABCD")
        createSession(EncryptionType.ENCRYPTION_DEFAULT)

        connectAndWait()

        assertThat(danaPump.rsPassword).isEqualTo("0000")
        assertThat(host.notices).isEmpty()
    }

    @Test
    fun `BLE5 handshake connects`() = runBlocking {
        val key = "474632"
        whenever(preferences.get(DanaStringComposedKey.Ble5PairingKey, deviceName)).thenReturn(key)
        createSession(EncryptionType.ENCRYPTION_BLE5)
        transport.pumpState.hwModel = 0x09
        transport.pumpState.ble5PairingKey = key

        connectAndWait()

        assertThat(danaPump.hwModel).isEqualTo(0x09)
    }

    @Test
    fun `RSv3 handshake with stored keys connects`() = runBlocking {
        val encoder = java.util.Base64.getEncoder()
        whenever(preferences.get(DanaStringComposedKey.V3ParingKey, deviceName))
            .thenReturn(encoder.encodeToString(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)))
        whenever(preferences.get(DanaStringComposedKey.V3RandomParingKey, deviceName))
            .thenReturn(encoder.encodeToString(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())))
        whenever(preferences.get(DanaStringComposedKey.V3RandomSyncKey, deviceName)).thenReturn("42")
        createSession(EncryptionType.ENCRYPTION_RSv3)
        transport.pumpState.hwModel = 0x05

        connectAndWait()

        assertThat(danaPump.hwModel).isEqualTo(0x05)
    }

    @Test
    fun `a packet gets its reply after the handshake`() = runBlocking {
        whenever(preferences.get(DanaStringComposedKey.ParingKey, deviceName)).thenReturn("ABCD")
        createSession(EncryptionType.ENCRYPTION_DEFAULT)
        connectAndWait()

        transport.pumpState.activeProfileNumber = 2
        val packet = DanaRSPacketBasalGetProfileNumber(aapsLogger, danaPump)
        session.sendMessage(packet)

        assertThat(packet.isReceived).isTrue()
        assertThat(packet.failed).isFalse()
        assertThat(danaPump.activeProfile).isEqualTo(2)
    }

    @Test
    fun `a packet is ignored when there is no connection`() = runBlocking {
        createSession(EncryptionType.ENCRYPTION_DEFAULT)
        val packet = DanaRSPacketBasalGetProfileNumber(aapsLogger, danaPump)

        session.sendMessage(packet)

        assertThat(packet.isReceived).isFalse()
    }
}
