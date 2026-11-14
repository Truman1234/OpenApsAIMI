package app.aaps.pump.danars.session

import app.aaps.core.interfaces.pump.ble.session.BleProfile
import app.aaps.core.interfaces.pump.ble.session.WriteMode

/**
 * The Bluetooth layout of a Dana RS / Dana-i pump: one service with one characteristic to read
 * (notifications) and one to write. The Android driver uses the same numbers in `BleTransportImpl`
 * (`0000fff1-…` and `0000fff2-…`), and DanaKit uses `FFF0`, `FFF1` and `FFF2`.
 *
 * Writes go without response, like in DanaKit, which is known to work with the pump on iOS. The
 * session cuts every packet into parts of at most 20 bytes and sends the next part after the previous one.
 */
object DanaRsBleProfile {

    /** CoreBluetooth gives the 16 bit form of the UUIDs. */
    const val SERVICE_UUID = "FFF0"
    const val READ_UUID = "FFF1"
    const val WRITE_UUID = "FFF2"

    val profile = BleProfile(
        notifyCharacteristicUuid = READ_UUID,
        writeCharacteristicUuid = WRITE_UUID,
        serviceUuid = SERVICE_UUID,
        writeMode = WriteMode.NoResponse
    )
}
