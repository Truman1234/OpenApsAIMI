package app.aaps.pump.danars.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.ble.PairingState
import app.aaps.core.interfaces.pump.ble.PairingStep
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.hexStringToByteArray
import app.aaps.core.ui.CoreUiStrings
import app.aaps.pump.dana.DanaStrings
import app.aaps.pump.dana.keys.DanaStringComposedKey
import app.aaps.pump.dana.keys.DanaStringNonKey
import app.aaps.pump.danars.session.DanaRsHost
import app.aaps.pump.danars.session.DanaRsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.experimental.xor

enum class DanaRsWizardStep {
    BLE_SCAN,
    PAIRING_PROGRESS,
    ENTER_PASSWORD,
    ENTER_PIN,
    COMPLETE,
    ERROR
}

data class DanaRsPairWizardUiState(
    val step: DanaRsWizardStep = DanaRsWizardStep.BLE_SCAN,
    val devices: List<ScannedDevice> = emptyList(),
    val selectedDevice: ScannedDevice? = null,
    val errorMessage: String? = null,
    val password: String = "",
    val pin1: String = "",
    val pin2: String = ""
)

sealed class DanaRsPairWizardEvent {
    data object Finish : DanaRsPairWizardEvent()
}

/**
 * The Dana RS / Dana-i pairing wizard, shared by Android and iOS.
 *
 * Same steps as Android's `DanaRSPairWizardViewModel`, but built as a plain class instead of a
 * `ViewModel`: the caller owns a [CoroutineScope] and constructs one of these per wizard screen,
 * the same way `IosMedtrumPlugin`'s `MedtrumPatchWizardModel` does. The Android screen keeps its
 * own thin `ViewModel` wrapper around this class; this file carries the actual logic.
 *
 * One real difference from Android, found by comparing against DanaKit (the iOS/Loop driver this
 * protocol was cross-checked against): there is no "await bond" step here. Android has to poll
 * its own Bluetooth bond state before it can open an encrypted connection. iOS has no such step to
 * poll - CoreBluetooth pairs by itself the first time an encrypted characteristic is touched, for
 * every Dana RS model, not just Dana-i - so [connect] goes straight to [DanaRsSession.connect] and
 * reacts to [DanaRsSession.bleTransport]'s pairing-state flow. Dana-i (BLE5) needs no PIN or
 * password screen at all: its pairing key arrives automatically in the first pump-check reply once
 * the system pairing (the one dialog iOS itself shows) has gone through, so for that model this
 * wizard only ever shows [DanaRsWizardStep.BLE_SCAN], [DanaRsWizardStep.PAIRING_PROGRESS] and
 * [DanaRsWizardStep.COMPLETE]/[DanaRsWizardStep.ERROR] - the same steps generic to every model.
 */
class DanaRsPairWizardModel(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val session: DanaRsSession,
    private val host: DanaRsHost,
    private val preferences: Preferences,
    private val pumpSync: PumpSync,
    private val commandQueue: CommandQueue,
    private val scope: CoroutineScope
) {

    companion object {

        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val snPattern = Regex("^[a-zA-Z]{3}[0-9]{5}[a-zA-Z]{2}$")

    private val _uiState = MutableStateFlow(DanaRsPairWizardUiState())
    val uiState: StateFlow<DanaRsPairWizardUiState> = _uiState

    private val _events = MutableSharedFlow<DanaRsPairWizardEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<DanaRsPairWizardEvent> = _events

    private var pairingTimeoutJob: Job? = null

    /** Same guard as Android's: the Done button can be pressed again while the screen is closing. */
    private var finished = false

    private var pairingStateJob: Job? = null
    private var scannedDevicesJob: Job? = null

    /** Starts observing the session's pairing state and scan results. Call once per wizard screen instance. */
    fun open() {
        if (pairingStateJob != null) return
        pairingStateJob = scope.launch {
            session.bleTransport.pairingState.collect { state -> onPairingStateChanged(state) }
        }
        scannedDevicesJob = scope.launch {
            session.bleTransport.scanner.scannedDevices.collect { device ->
                if (device.name.isNotEmpty() && snPattern.matches(device.name)) {
                    _uiState.update { current ->
                        if (current.devices.any { it.address == device.address }) current
                        else current.copy(devices = current.devices + device)
                    }
                }
            }
        }
    }

    fun startScan() {
        aapsLogger.debug(LTag.PUMP, "PairWizard: startScan()")
        _uiState.update { it.copy(devices = emptyList(), step = DanaRsWizardStep.BLE_SCAN) }
        session.bleTransport.scanner.startScan()
    }

    fun stopScan() {
        session.bleTransport.scanner.stopScan()
    }

    fun selectDevice(device: ScannedDevice) {
        stopScan()
        _uiState.update { it.copy(selectedDevice = device, step = DanaRsWizardStep.PAIRING_PROGRESS) }

        // Held in memory until pairing succeeds; see DanaRsHost.deviceName's doc.
        host.deviceName = device.name

        commandQueue.clear()
        scope.launch { session.connect("PairWizard", device.address) }
    }

    fun updatePassword(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun submitPassword() {
        val password = _uiState.value.password
        if (password.length != 4 || !password.all { it.isHexDigit() }) return

        preferences.put(DanaStringNonKey.Password, password.uppercase())
        _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS, errorMessage = null) }

        val device = _uiState.value.selectedDevice ?: return
        scope.launch { session.connect("PairWizardPasswordRetry", device.address) }
    }

    fun updatePin1(value: String) {
        _uiState.update { it.copy(pin1 = value) }
    }

    fun updatePin2(value: String) {
        _uiState.update { it.copy(pin2 = value) }
    }

    fun submitPin() {
        val state = _uiState.value
        val pin1 = state.pin1
        val pin2 = state.pin2

        if (pin1.length != 12 || pin2.length != 8) return
        if (!pin1.all { it.isHexDigit() } || !pin2.all { it.isHexDigit() }) return

        val pairingKey = pin1.hexStringToByteArray()
        val randomPairingKey = pin2.substring(0, 6).hexStringToByteArray()
        val checksum = pin2.substring(6, 8).hexStringToByteArray()

        if (!checkPairingCheckSum(pairingKey, randomPairingKey, checksum)) {
            _uiState.update { it.copy(errorMessage = rh.gs(CoreUiStrings.invalid_input)) }
            return
        }

        _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS, errorMessage = null) }
        scope.launch { session.finishV3Pairing() }
    }

    fun finishWizard() {
        if (finished) return
        val device = _uiState.value.selectedDevice ?: return
        finished = true
        _events.tryEmit(DanaRsPairWizardEvent.Finish)

        scope.launch {
            preferences.put(DanaStringNonKey.MacAddress, device.address)
            preferences.put(DanaStringNonKey.RsName, device.name)
            session.bleTransport.adapter.createBond(device.address)
            pumpSync.connectNewPump()
            host.changePump()
        }
    }

    fun cancel() {
        cancelPairingTimeout()
        stopScan()
        scope.launch { session.stopConnecting() }
        session.bleTransport.updatePairingState(PairingState(step = PairingStep.IDLE))
        _events.tryEmit(DanaRsPairWizardEvent.Finish)
    }

    fun retry() {
        val device = _uiState.value.selectedDevice
        if (device != null) {
            _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS, errorMessage = null) }
            scope.launch { session.connect("PairWizardRetry", device.address) }
        } else {
            _uiState.update { it.copy(step = DanaRsWizardStep.BLE_SCAN, errorMessage = null) }
            startScan()
        }
    }

    /** Call when the wizard screen leaves composition. */
    fun close() {
        cancelPairingTimeout()
        pairingStateJob?.cancel()
        pairingStateJob = null
        scannedDevicesJob?.cancel()
        scannedDevicesJob = null
        stopScan()
    }

    private fun onPairingStateChanged(state: PairingState) {
        when (state.step) {
            PairingStep.IDLE                        ->
                if (_uiState.value.step == DanaRsWizardStep.PAIRING_PROGRESS) {
                    cancelPairingTimeout()
                    _uiState.update { it.copy(step = DanaRsWizardStep.ERROR, errorMessage = rh.gs(CoreUiStrings.connection_error)) }
                }

            PairingStep.CONNECTING                  -> {
                _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS) }
                startPairingTimeout()
            }

            PairingStep.HANDSHAKE_IN_PROGRESS       -> _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS) }
            PairingStep.WAITING_FOR_PAIRING_CONFIRM -> _uiState.update { it.copy(step = DanaRsWizardStep.PAIRING_PROGRESS) }

            PairingStep.WAITING_FOR_PASSWORD        -> {
                cancelPairingTimeout()
                _uiState.update { it.copy(step = DanaRsWizardStep.ENTER_PASSWORD) }
            }

            PairingStep.WAITING_FOR_PIN             -> {
                cancelPairingTimeout()
                _uiState.update { it.copy(step = DanaRsWizardStep.ENTER_PIN) }
            }

            PairingStep.CONNECTED                   -> {
                cancelPairingTimeout()
                _uiState.update { it.copy(step = DanaRsWizardStep.COMPLETE) }
            }

            PairingStep.ERROR                       -> {
                cancelPairingTimeout()
                _uiState.update { it.copy(step = DanaRsWizardStep.ERROR, errorMessage = state.errorMessage) }
            }
        }
    }

    private fun startPairingTimeout() {
        cancelPairingTimeout()
        pairingTimeoutJob = scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            aapsLogger.warn(LTag.PUMP, "PairWizard: pairing timeout after ${PAIRING_TIMEOUT_MS}ms")
            _uiState.update {
                it.copy(step = DanaRsWizardStep.ERROR, errorMessage = rh.gs(DanaStrings.danars_pairingtimedout))
            }
        }
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun checkPairingCheckSum(pairingKey: ByteArray, randomPairingKey: ByteArray, checksum: ByteArray): Boolean {
        var pairingKeyCheckSum: Byte = 0
        for (b in pairingKey) pairingKeyCheckSum = pairingKeyCheckSum xor b
        preferences.put(DanaStringComposedKey.V3ParingKey, host.deviceName, value = Base64.Default.encode(pairingKey))

        for (b in randomPairingKey) pairingKeyCheckSum = pairingKeyCheckSum xor b
        preferences.put(DanaStringComposedKey.V3RandomParingKey, host.deviceName, value = Base64.Default.encode(randomPairingKey))

        return checksum[0] == pairingKeyCheckSum
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
