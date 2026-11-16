package app.aaps.plugins.source.xdrip

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleRestoreIdentifier
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral

/**
 * A read-only BLE "heartbeat": connects to the same transmitter xDrip4iOS itself is already
 * talking to, purely to be woken by its BLE traffic, same idea as iAPS's `HeartBeatManager.swift`/
 * `BluetoothTransmitter.swift`.
 *
 * This central never parses what the transmitter sends - [onHeartbeat] only says that something
 * happened, so the caller can re-read the App Group's shared glucose values a few seconds later
 * (xDrip4iOS needs that long to finish writing after its own read). Opened with its own
 * [BleRestoreIdentifier], so an iOS background wake on this link never collides with a pump
 * driver's central.
 */
internal class XdripHeartbeatListener(
    private val aapsLogger: AAPSLogger
) {

    /** Called on every BLE event worth re-reading the shared data for. May run off the main thread. */
    var onHeartbeat: (() -> Unit)? = null

    private val central = CoreBluetoothCentral(BleRestoreIdentifier.XDRIP_HEARTBEAT)
    private var deviceId: String? = null
    private var serviceUuid: String? = null
    private var receiveCharacteristicUuid: String? = null

    init {
        central.events = ::onEvent
    }

    /** Idempotent: calling again with the same [deviceAddress] does nothing. */
    fun start(deviceAddress: String, serviceUuid: String, receiveCharacteristicUuid: String) {
        if (deviceId == deviceAddress) return
        stop()
        central.open()
        deviceId = deviceAddress
        this.serviceUuid = serviceUuid
        this.receiveCharacteristicUuid = receiveCharacteristicUuid
        if (!central.connect(deviceAddress)) {
            aapsLogger.warn(
                LTag.BGSOURCE,
                "XDRIP_HEARTBEAT: connect($deviceAddress) failed, CoreBluetooth does not know this peripheral yet"
            )
        }
    }

    fun stop() {
        deviceId?.let { id ->
            central.cancel(id)
            central.release(id)
        }
        deviceId = null
        serviceUuid = null
        receiveCharacteristicUuid = null
    }

    private fun onEvent(event: BleCentralEvent) {
        when (event) {
            is BleCentralEvent.Link            -> if (event.id == deviceId) {
                if (event.connected) central.discoverServices(event.id)
                // A disconnect is itself worth a re-read: that is exactly when xDrip4iOS was using
                // the transmitter, same as `BluetoothTransmitter.swift`'s `didDisconnectPeripheral`.
                else fireHeartbeat()
            }

            is BleCentralEvent.Services        -> if (event.id == deviceId) {
                val wantedService = serviceUuid ?: return
                event.services.filter { sameUuidLoosely(it.uuid, wantedService) }
                    .forEach { central.discoverCharacteristics(event.id, it.uuid) }
            }

            is BleCentralEvent.Characteristics  -> if (event.id == deviceId) {
                val wantedCharacteristic = receiveCharacteristicUuid ?: return
                event.characteristics.firstOrNull { sameUuidLoosely(it.uuid, wantedCharacteristic) }
                    ?.let { central.enableNotify(event.id, it.uuid, indicate = false) }
            }

            is BleCentralEvent.Value            -> if (event.id == deviceId) fireHeartbeat()

            else                                 -> Unit
        }
    }

    private fun fireHeartbeat() {
        aapsLogger.debug(LTag.BGSOURCE, "XDRIP_HEARTBEAT: woken by BLE activity on the xDrip4iOS transmitter")
        onHeartbeat?.invoke()
    }
}

/**
 * Loose match between a CoreBluetooth-reported characteristic/service UUID (always the full 128
 * bit form - see `CoreBluetoothCentral.expandUuid`) and whatever shorter form xDrip4iOS stored in
 * the shared `NSUserDefaults`. Duplicated rather than shared: the matching original is `internal`
 * to the `core:interfaces` module and not visible from here.
 */
private fun sameUuidLoosely(expanded: String, stored: String): Boolean =
    expanded.lowercase() == expandUuidForCompare(stored)

private fun expandUuidForCompare(raw: String): String {
    val hex = raw.trim().removePrefix("0x").lowercase()
    return when (hex.length) {
        4 -> "0000$hex-0000-1000-8000-00805f9b34fb"
        8 -> "$hex-0000-1000-8000-00805f9b34fb"
        else -> hex
    }
}
