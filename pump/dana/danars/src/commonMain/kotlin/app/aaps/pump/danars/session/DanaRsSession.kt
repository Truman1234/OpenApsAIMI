package app.aaps.pump.danars.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.BleTransport
import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.PairingState
import app.aaps.core.interfaces.pump.ble.PairingStep
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventShowSnackbar
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.keys.DanaLongKey
import app.aaps.pump.dana.keys.DanaStringComposedKey
import app.aaps.pump.danars.comm.DanaRSPacket
import app.aaps.pump.danars.comm.DanaRSPacketEtcKeepConnection
import app.aaps.pump.danars.comm.DanaRsReadBuffer
import app.aaps.pump.danars.encryption.BleEncryption
import app.aaps.pump.danars.encryption.EncryptionType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The connection to a Dana RS / Dana-i pump: handshake, pairing, encryption and the exchange of packets.
 *
 * This is the shared version of the Android class `BLEComm`. It does the same steps in the same order, but
 * it has no thread, no `wait/notify` and no Android class, so it also runs on iOS:
 * - Everything the BLE layer reports goes into one queue and is handled one at a time.
 * - [sendMessage] waits for the reply with a coroutine and a timeout.
 * - Texts, notices and "forget this pump" go through [DanaRsHost].
 *
 * @param findMessage gives a new packet for a command the pump sends by itself (alarms, delivery progress)
 * @param scope where the event queue runs
 */
class DanaRsSession(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val danaPump: DanaPump,
    private val bleEncryption: BleEncryption,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val bleTransport: BleTransport,
    private val host: DanaRsHost,
    private val findMessage: (command: Int) -> DanaRSPacket?,
    scope: CoroutineScope
) : BleTransportListener {

    private sealed interface Event {

        data class ConnectionChanged(val connected: Boolean) : Event
        data class ServicesDiscovered(val success: Boolean) : Event
        data object DescriptorWritten : Event
        class Data(val bytes: ByteArray) : Event
        data object Written : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Guards all state below. Never held while waiting for the pump. */
    private val lock = Mutex()

    /** Only one request at a time is in flight */
    private val sendLock = Mutex()

    private val readBuffer = DanaRsReadBuffer(aapsLogger)
    private val writeQueue = ArrayDeque<ByteArray>()

    private var processedMessage: DanaRSPacket? = null
    private var pendingReply: CompletableDeferred<Unit>? = null
    private var connectDeviceName: String? = null
    private var connectAddress: String? = null

    private var encryption: EncryptionType = EncryptionType.ENCRYPTION_DEFAULT
        set(newValue) {
            bleEncryption.setEnhancedEncryption(newValue)
            field = newValue
        }

    @Volatile var isConnected = false
        private set

    @Volatile var isConnecting = false
        private set

    /** Time to wait for the reply of the pump in [sendMessage]. Tests may lower it. */
    var messageTimeoutMs: Long = 5000L

    private var encryptedDataRead = false
    private var encryptedCommandSent = false
    private var pumpCheckSent = false // no second ENCRYPTION__PUMP_CHECK in one connection
    private var notificationsEnabled = false
    private var consecutiveDecryptFailures = 0

    init {
        bleTransport.setListener(this)
        scope.launch {
            for (event in events) {
                lock.withLock { handle(event) }
            }
        }
    }

    // ---------------------------------------------------------------- BleTransportListener
    // These are called from the BLE thread. They only queue the event.

    override fun onConnectionStateChanged(connected: Boolean) {
        events.trySend(Event.ConnectionChanged(connected))
    }

    override fun onServicesDiscovered(success: Boolean) {
        events.trySend(Event.ServicesDiscovered(success))
    }

    override fun onDescriptorWritten() {
        events.trySend(Event.DescriptorWritten)
    }

    override fun onCharacteristicChanged(data: ByteArray) {
        events.trySend(Event.Data(data))
    }

    override fun onCharacteristicWritten() {
        events.trySend(Event.Written)
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.ConnectionChanged   -> onConnectionStateChange(event.connected)
            is Event.ServicesDiscovered  -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered")
                if (event.success && bleTransport.gatt.findCharacteristics()) bleTransport.gatt.enableNotifications()
            }

            Event.DescriptorWritten      -> onDescriptorWrittenHandled()
            is Event.Data                -> readData(event.bytes)
            Event.Written                -> {
                // After a part is written, send the next part of the same packet
                writeQueue.removeFirstOrNull()?.let { bleTransport.gatt.writeCharacteristic(it) }
            }
        }
    }

    // ---------------------------------------------------------------- connect and disconnect

    suspend fun connect(from: String, address: String?): Boolean = lock.withLock {
        if (!host.hasBluetoothPermission()) {
            host.show(DanaRsNotice.MISSING_BLUETOOTH_PERMISSION)
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return@withLock false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Initializing session.")
        if (address == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "unspecified address.")
            return@withLock false
        }
        val deviceName = bleTransport.adapter.getDeviceName(address)
        if (deviceName == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Device not found.  Unable to connect from: $from")
            return@withLock false
        }
        if (!bleTransport.adapter.isDeviceBonded(address)) {
            bleTransport.adapter.createBond(address)
            aapsLogger.debug(LTag.PUMPBTCOMM, "Bond requested, will retry after bonding completes from: $from")
            return@withLock false
        }

        resetConnectionState()
        encryption = EncryptionType.ENCRYPTION_DEFAULT
        isConnecting = true
        readBuffer.clear()
        bleTransport.updatePairingState(PairingState(step = PairingStep.CONNECTING))
        aapsLogger.debug(LTag.PUMPBTCOMM, "Trying to create a new connection from: $from")
        connectDeviceName = deviceName
        connectAddress = address

        if (!bleTransport.gatt.connect(address)) {
            aapsLogger.error(LTag.PUMPBTCOMM, "connectGatt failed from: $from")
            isConnecting = false
            return@withLock false
        }
        // Register for notifications early. A fast or cached connection can finish before the
        // registration after service discovery.
        bleTransport.gatt.enableNotifications()
        true
    }

    suspend fun stopConnecting() = lock.withLock { isConnecting = false }

    suspend fun disconnect(from: String) = lock.withLock { disconnectLocked(from) }

    suspend fun close() = lock.withLock {
        aapsLogger.debug(LTag.PUMPBTCOMM, "close")
        bleTransport.gatt.close()
    }

    private fun resetConnectionState() {
        isConnected = false
        encryptedDataRead = false
        encryptedCommandSent = false
        pumpCheckSent = false
        notificationsEnabled = false
    }

    private fun disconnectLocked(from: String) {
        if (!host.hasBluetoothPermission()) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")

        if (!encryptedDataRead && encryptedCommandSent && encryption == EncryptionType.ENCRYPTION_BLE5) {
            // The pump did not answer after the encryption started: the pairing keys are probably not valid
            val lastClearRequest = preferences.get(DanaLongKey.LastClearKeyRequest)
            if (lastClearRequest != 0L && dateUtil.isOlderThan(lastClearRequest, 5)) {
                rxBus.send(EventShowSnackbar(host.text(DanaRsText.INVALID_PAIRING), EventShowSnackbar.Type.Error))
                host.changePump()
                connectAddress?.let { bleTransport.adapter.removeBond(it) }
            } else if (lastClearRequest == 0L) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Clearing pairing keys postponed")
                preferences.put(DanaLongKey.LastClearKeyRequest, dateUtil.now())
            }
        }
        if (!encryptedDataRead && encryptedCommandSent && encryption == EncryptionType.ENCRYPTION_RSv3) {
            val lastClearRequest = preferences.get(DanaLongKey.LastClearKeyRequest)
            if (lastClearRequest != 0L && dateUtil.isOlderThan(lastClearRequest, 5)) {
                // Same as BLEComm: do not clear the keys, restart the app
                host.restartApp("Dana BLE encryption failed")
            } else if (lastClearRequest == 0L) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Clearing pairing keys postponed")
                preferences.put(DanaLongKey.LastClearKeyRequest, dateUtil.now())
            }
        }

        writeQueue.clear()
        bleTransport.gatt.disconnect()
        resetConnectionState()
    }

    private fun onConnectionStateChange(connected: Boolean) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange")
        if (connected) {
            bleTransport.gatt.discoverServices()
        } else {
            bleTransport.gatt.close()
            isConnecting = false
            encryption = EncryptionType.ENCRYPTION_DEFAULT
            resetConnectionState()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            bleTransport.updatePairingState(PairingState(step = PairingStep.IDLE))
            aapsLogger.debug(LTag.PUMPBTCOMM, "Device was disconnected")
        }
    }

    /**
     * Notification registration is done. [connect] registers early and the service discovery registers again,
     * so one connection gets two callbacks. Only the first starts the handshake: the second would set the pairing
     * step back to "in progress" while the app waits for the PIN.
     */
    private fun onDescriptorWrittenHandled() {
        if (isConnected) return
        if (notificationsEnabled) return
        notificationsEnabled = true
        bleTransport.updatePairingState(PairingState(step = PairingStep.HANDSHAKE_IN_PROGRESS))
        sendConnect()
    }

    // ---------------------------------------------------------------- receive

    private fun readData(received: ByteArray) {
        // After a successful connection the second encryption level is used
        val incoming =
            if (isConnected && (encryption == EncryptionType.ENCRYPTION_RSv3 || encryption == EncryptionType.ENCRYPTION_BLE5))
                bleEncryption.decryptSecondLevelPacket(received).also {
                    encryptedDataRead = true
                    preferences.put(DanaLongKey.LastClearKeyRequest, 0L)
                }
            else received
        readBuffer.add(incoming)

        while (true) {
            val packet = readBuffer.nextPacket() ?: break
            val decrypted = bleEncryption.getDecryptedPacket(packet)
            if (decrypted == null) {
                // The packet failed its own checks (length or CRC). It happens on a radio link. Drop it: the
                // command that waits for the reply times out and is repeated.
                consecutiveDecryptFailures++
                aapsLogger.error(LTag.PUMPBTCOMM, "Packet failed the integrity check, dropping it (failure #$consecutiveDecryptFailures)")
                if (consecutiveDecryptFailures == DECRYPT_FAILURE_WARN_THRESHOLD)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "$DECRYPT_FAILURE_WARN_THRESHOLD packets in a row failed to decrypt. The pairing keys probably no longer match the pump.")
                continue
            }
            consecutiveDecryptFailures = 0
            dispatch(decrypted)
        }
    }

    private fun dispatch(decrypted: ByteArray) {
        if (decrypted[0] == BleEncryption.DANAR_PACKET__TYPE_ENCRYPTION_RESPONSE.toByte()) {
            when (decrypted[1]) {
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__PUMP_CHECK.toByte()          -> processConnectResponse(decrypted)
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION.toByte()    -> processEncryptionResponse(decrypted)
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__CHECK_PASSKEY.toByte()       -> processPasskeyCheck(decrypted)
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_REQUEST.toByte()     -> processPairingRequest(decrypted)
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_RETURN.toByte()      -> processPairingReturn(decrypted)
                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__GET_PUMP_CHECK.toByte()      ->
                    // not easy mode: send the time. Easy mode: ask for the easy menu first.
                    if (decrypted[2] == 0x05.toByte()) sendTimeInfo() else sendEasyMenuCheck()

                BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__GET_EASY_MENU_CHECK.toByte() -> processEasyMenuCheck(decrypted)
            }
        } else {
            processMessage(decrypted)
        }
    }

    // ---------------------------------------------------------------- handshake

    private fun writeNow(bytes: ByteArray) {
        bleTransport.gatt.writeCharacteristic(bytes)
    }

    /** First packet for v1 and v3 */
    private fun sendConnect() {
        if (pumpCheckSent) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "ENCRYPTION__PUMP_CHECK already sent for this connection, ignoring duplicate call")
            return
        }
        val deviceName = connectDeviceName
        if (deviceName == null || deviceName == "") {
            host.show(DanaRsNotice.DEVICE_NOT_PAIRED)
            return
        }
        pumpCheckSent = true
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__PUMP_CHECK, null, deviceName)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__PUMP_CHECK (0x00) ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    private fun isAscii(buffer: ByteArray, from: Int, text: String): Boolean =
        text.indices.all { buffer[from + it] == text[it].code.toByte() }

    private fun processConnectResponse(decrypted: ByteArray) {
        // Read again on every connection by the easy menu check. Pumps without easy mode keep false.
        danaPump.isEasyModeEnabled = false
        danaPump.isConfigUD = false
        val name = host.deviceName
        val size = decrypted.size
        if (size == 4 && isAscii(decrypted, 2, "OK")) {
            // Dana RS, first protocol
            aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK (OK) ${DanaRSPacket.toHexString(decrypted)}")
            encryption = EncryptionType.ENCRYPTION_DEFAULT
            danaPump.ignoreUserPassword = false
            val pairingKey = preferences.get(DanaStringComposedKey.ParingKey, name)
            aapsLogger.debug(LTag.PUMPBTCOMM, if (pairingKey.isNotEmpty()) "Using stored pairing key" else "No stored pairing key")
            if (pairingKey.isNotEmpty()) sendPasskeyCheck(pairingKey) else sendPairingRequest()
        } else if (size == 9 && isAscii(decrypted, 2, "OK")) {
            // Dana RS v3
            encryption = EncryptionType.ENCRYPTION_RSv3
            danaPump.ignoreUserPassword = true
            danaPump.hwModel = decrypted[5].toInt()
            danaPump.protocol = decrypted[7].toInt()
            val syncKey = (decrypted[decrypted.size - 1].toInt() and 0xFF).toString(16).padStart(2, '0')
            preferences.put(DanaStringComposedKey.V3RandomSyncKey, name, value = syncKey)
            if (danaPump.hwModel == 0x05) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK V3 (OK) ${secret(decrypted)}")
                sendV3PairingInformation()
            } else if (danaPump.hwModel == 0x06) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK V3 EASY (OK) ${secret(decrypted)}")
                sendEasyMenuCheck()
            }
        } else if (size == 14 && isAscii(decrypted, 2, "OK")) {
            // BLE5: Dana-i and Dana-i2
            encryption = EncryptionType.ENCRYPTION_BLE5
            danaPump.ignoreUserPassword = true
            danaPump.hwModel = decrypted[5].toInt()
            danaPump.protocol = decrypted[7].toInt()
            val pairingKey = DanaRSPacket.asciiStringFromBuff(decrypted, 8, 6) // used while bonding
            if (decrypted[8] != 0.toByte())
                preferences.put(DanaStringComposedKey.Ble5PairingKey, name, value = pairingKey)
            val storedPairingKey = preferences.get(DanaStringComposedKey.Ble5PairingKey, name)
            if (storedPairingKey.isBlank()) {
                connectAddress?.let { bleTransport.adapter.removeBond(it) }
                disconnectLocked("Non existing pairing key")
                return
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK BLE5 (OK) model=${hex2(danaPump.hwModel)} protocol=${hex2(danaPump.protocol)} ${secret(decrypted)}")
            when (danaPump.hwModel) {
                // Dana-i BLE5 and Dana-i2 use the same key setup
                0x09, 0x0B -> {
                    bleEncryption.setBle5Key(storedPairingKey.encodeToByteArray())
                    sendBle5PairingInformation()
                }

                // Pumps with easy mode: ask first, processEasyMenuCheck continues
                0x0A, 0x0C -> {
                    bleEncryption.setBle5Key(storedPairingKey.encodeToByteArray())
                    sendEasyMenuCheck()
                }

                else       -> {
                    val message = host.text(DanaRsText.UNSUPPORTED_MODEL, hex2(danaPump.hwModel))
                    aapsLogger.error(LTag.PUMPBTCOMM, message)
                    bleTransport.updatePairingState(PairingState(step = PairingStep.ERROR, errorMessage = message))
                    writeQueue.clear()
                    rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED, message))
                }
            }
        } else if ((size == 6 || size == 7) && isAscii(decrypted, 2, "PUMP")) {
            // The pump reports an error. Dana-i2 adds one byte of error flags.
            val errorFlags = if (size == 7) decrypted[6].toInt() and 0xFF else null
            val errorText = host.pumpCheckErrorText(errorFlags)
            aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK (PUMP) $errorText ${DanaRSPacket.toHexString(decrypted)}")
            bleTransport.updatePairingState(PairingState(step = PairingStep.ERROR, errorMessage = errorText))
            writeQueue.clear()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED, errorText))
            host.reportAlarm(errorText)
        } else if (size == 6 && isAscii(decrypted, 2, "BUSY")) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK (BUSY) ${DanaRSPacket.toHexString(decrypted)}")
            val text = host.text(DanaRsText.PUMP_BUSY)
            bleTransport.updatePairingState(PairingState(step = PairingStep.ERROR, errorMessage = text))
            writeQueue.clear()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED, text))
        } else {
            // Wrong serial number
            aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PUMP_CHECK (ERROR) ${DanaRSPacket.toHexString(decrypted)}")
            val text = host.text(DanaRsText.CONNECTION_ERROR)
            bleTransport.updatePairingState(PairingState(step = PairingStep.ERROR, errorMessage = text))
            writeQueue.clear()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED, text))
            host.clearPairing()
            host.show(DanaRsNotice.WRONG_SERIAL_NUMBER)
        }
    }

    // second packet v1: check the stored pairing key
    private fun sendPasskeyCheck(pairingKey: String) {
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__CHECK_PASSKEY, DanaRSPacket.hexToBytes(pairingKey), null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__CHECK_PASSKEY ${secret(bytes)}")
        writeNow(bytes)
    }

    private fun processPasskeyCheck(decrypted: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__CHECK_PASSKEY ${DanaRSPacket.toHexString(decrypted)}")
        // 0 = the key is valid, send the time. Otherwise the pump wants a new pairing.
        if (decrypted[2] == 0x00.toByte()) sendTimeInfo() else sendPairingRequest()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(text: String): ByteArray = Base64.Mime.decode(text)

    // second packet v3: 0 = start encryption, 1 = request pairing
    private fun sendV3PairingInformation() {
        val name = host.deviceName
        val randomPairingKey = preferences.get(DanaStringComposedKey.V3RandomParingKey, name)
        val pairingKey = preferences.get(DanaStringComposedKey.V3ParingKey, name)
        if (randomPairingKey.isNotEmpty() && pairingKey.isNotEmpty()) {
            var randomSyncKey: Byte = 0
            val storedSyncKey = preferences.get(DanaStringComposedKey.V3RandomSyncKey, name)
            if (storedSyncKey.isNotEmpty()) randomSyncKey = storedSyncKey.toInt(16).toByte()
            bleEncryption.setPairingKeys(decodeBase64(pairingKey), decodeBase64(randomPairingKey), randomSyncKey)
            sendV3PairingInformation(0)
        } else {
            sendV3PairingInformation(1)
        }
    }

    private fun sendV3PairingInformation(requestNewPairing: Int) {
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION, byteArrayOf(requestNewPairing.toByte()), null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__TIME_INFORMATION ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    // second packet BLE5
    private fun sendBle5PairingInformation() {
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION, ByteArray(4), null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__TIME_INFORMATION BLE5 ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    private fun processEncryptionResponse(decrypted: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__TIME_INFORMATION ${secret(decrypted)}")
        if (encryption == EncryptionType.ENCRYPTION_BLE5) {
            setConnected()
        } else if (encryption == EncryptionType.ENCRYPTION_RSv3) {
            // decrypted[2]: 0x00 OK, 0x01 error (no pairing)
            if (decrypted[2] == 0x00.toByte()) {
                val name = host.deviceName
                val randomPairingKey = preferences.get(DanaStringComposedKey.V3RandomParingKey, name)
                val pairingKey = preferences.get(DanaStringComposedKey.V3ParingKey, name)
                if (randomPairingKey.isNotEmpty() && pairingKey.isNotEmpty()) {
                    setConnected()
                } else {
                    bleTransport.updatePairingState(PairingState(step = PairingStep.WAITING_FOR_PIN))
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Request pairing keys !!")
                }
            } else {
                sendV3PairingInformation(1)
            }
        } else {
            val size = decrypted.size
            var pass = ((decrypted[size - 1].toInt() and 0xFF) shl 8) + (decrypted[size - 2].toInt() and 0xFF)
            pass = pass xor 3463
            danaPump.rsPassword = pass.toString(16).uppercase().padStart(4, '0')
            aapsLogger.debug(LTag.PUMPBTCOMM, "Pump user password received")
            if (!danaPump.isRSPasswordOK) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Wrong pump password")
                host.show(DanaRsNotice.WRONG_PUMP_PASSWORD)
                bleTransport.updatePairingState(PairingState(step = PairingStep.WAITING_FOR_PASSWORD))
                disconnectLocked("WrongPassword")
            } else {
                host.dismissWrongPumpPassword()
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
                setConnected()
                aapsLogger.debug(LTag.PUMPBTCOMM, "RS connected and status read")
            }
        }
    }

    private fun setConnected() {
        isConnected = true
        isConnecting = false
        bleTransport.updatePairingState(PairingState(step = PairingStep.CONNECTED))
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connect !!")
    }

    // third packet v1 with an existing pairing
    private fun sendTimeInfo() {
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION, null, null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__TIME_INFORMATION ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    // v1 without a pairing: the pump shows a pairing request and waits for the user
    private fun sendPairingRequest() {
        bleTransport.updatePairingState(PairingState(step = PairingStep.WAITING_FOR_PAIRING_CONFIRM))
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_REQUEST, null, null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__PASSKEY_REQUEST ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    /** Third packet v3, only after the user entered the PIN codes of the pump */
    suspend fun finishV3Pairing() = lock.withLock {
        val name = host.deviceName
        val randomPairingKey = preferences.get(DanaStringComposedKey.V3RandomParingKey, name)
        val pairingKey = preferences.get(DanaStringComposedKey.V3ParingKey, name)
        check(randomPairingKey.isNotEmpty() && pairingKey.isNotEmpty()) { "This should not be reached" }
        bleEncryption.setPairingKeys(decodeBase64(pairingKey), decodeBase64(randomPairingKey), 0)
        sendV3PairingInformation(0)
    }

    private fun processPairingRequest(decrypted: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PASSKEY_REQUEST ${DanaRSPacket.toHexString(decrypted)}")
        if (decrypted[2] != 0x00.toByte()) disconnectLocked("passkey request failed")
    }

    private fun processPairingReturn(decrypted: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__PASSKEY_RETURN ${secret(decrypted)}")
        // The pairing worked. Send the time and store the pairing key.
        sendTimeInfo()
        val pairingKey = byteArrayOf(decrypted[2], decrypted[3])
        preferences.put(DanaStringComposedKey.ParingKey, host.deviceName, value = DanaRSPacket.bytesToHex(pairingKey))
        aapsLogger.debug(LTag.PUMPBTCOMM, "Got pairing key")
    }

    private fun sendEasyMenuCheck() {
        val bytes = bleEncryption.getEncryptedPacket(BleEncryption.DANAR_PACKET__OPCODE_ENCRYPTION__GET_EASY_MENU_CHECK, null, null)
        aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ENCRYPTION__GET_EASY_MENU_CHECK ${DanaRSPacket.toHexString(bytes)}")
        writeNow(bytes)
    }

    private fun processEasyMenuCheck(decrypted: ByteArray) {
        // Easy menu: 0x00 normal, 0x01 easy menu, 0x02 none. Basal unit: 0x00 U/h, 0x01 U/d.
        danaPump.isEasyModeEnabled = decrypted[2] == 0x01.toByte()
        danaPump.isConfigUD = decrypted[3] == 0x01.toByte()
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ENCRYPTION__GET_EASY_MENU_CHECK easyMode=${danaPump.isEasyModeEnabled} basalUnitUD=${danaPump.isConfigUD}")
        when (encryption) {
            EncryptionType.ENCRYPTION_RSv3 -> sendV3PairingInformation()
            EncryptionType.ENCRYPTION_BLE5 -> sendBle5PairingInformation()
            else                           -> sendTimeInfo()
        }
    }

    // ---------------------------------------------------------------- packets

    /**
     * Sends a packet and waits for the reply. After the timeout the packet is marked as not received and the
     * connection is closed. Only one packet is in flight at a time.
     */
    suspend fun sendMessage(message: DanaRSPacket) = sendLock.withLock {
        val reply = lock.withLock {
            encryptedCommandSent = true
            processedMessage = message
            val command = byteArrayOf(message.type.toByte(), message.opCode.toByte())
            val params = message.getRequestParams()
            if (!isConnected && !isConnecting) {
                aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> IGNORING (NOT CONNECTED) ${message.friendlyName} ${DanaRSPacket.toHexString(command)} ${DanaRSPacket.toHexString(params)}")
                return@withLock null
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> ${message.friendlyName} ${DanaRSPacket.toHexString(command)} ${DanaRSPacket.toHexString(params)}")
            var bytes = bleEncryption.getEncryptedPacket(message.opCode, params, null)
            if (encryption != EncryptionType.ENCRYPTION_DEFAULT) bytes = bleEncryption.encryptSecondLevelPacket(bytes)
            val deferred = CompletableDeferred<Unit>()
            pendingReply = deferred
            val parts = DanaRsReadBuffer.splitForWrite(bytes)
            if (writeQueue.isEmpty()) {
                // The first part goes out now, the rest after the "written" callbacks
                bleTransport.gatt.writeCharacteristic(parts.first())
                parts.drop(1).forEach { writeQueue.addLast(it) }
            } else {
                // Another packet is still being written: queue all parts
                parts.forEach { writeQueue.addLast(it) }
            }
            deferred
        } ?: return@withLock

        if (!message.isReceived) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "waiting for reply ${message.friendlyName}")
            withTimeoutOrNull(messageTimeoutMs) { reply.await() }
            aapsLogger.debug(LTag.PUMPBTCOMM, "wait finished ${message.friendlyName} isReceived=${message.isReceived}")
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "already received ${message.friendlyName} (no wait needed)")
        }
        lock.withLock {
            if (pendingReply === reply) pendingReply = null
            if (!message.isReceived) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Reply not received ${message.friendlyName}")
                message.handleMessageNotReceived()
                disconnectLocked("Reply not received")
            }
            if (message is DanaRSPacketEtcKeepConnection && !message.isReceived)
                disconnectLocked("KeepAlive not received")
        }
    }

    private fun processMessage(decrypted: ByteArray) {
        val originalCommand = processedMessage?.command ?: 0xFFFF
        val receivedCommand = DanaRSPacket().getCommand(decrypted)
        val message = if (originalCommand == receivedCommand) processedMessage else findMessage(receivedCommand)
        if (message == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Unknown message received ${DanaRSPacket.toHexString(decrypted)}")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<<< ${message.friendlyName} ${DanaRSPacket.toHexString(decrypted)}")
        message.handleMessage(decrypted)
        message.setReceived()
        aapsLogger.debug(LTag.PUMPBTCOMM, "handleMessage done ${message.friendlyName}, processedMessage===message: ${processedMessage === message}")
        if (message === processedMessage) pendingReply?.complete(Unit)
    }

    // ---------------------------------------------------------------- helpers

    private fun hex2(value: Int): String = "0x" + (value and 0xFF).toString(16).uppercase().padStart(2, '0')

    /** Stands in for a handshake packet with the pump password or pairing keys: they must not go into the log (#5205) */
    private fun secret(packet: ByteArray): String = "(${packet.size} bytes, not logged: holds pairing data)"

    companion object {

        private const val DECRYPT_FAILURE_WARN_THRESHOLD = 5
    }
}
