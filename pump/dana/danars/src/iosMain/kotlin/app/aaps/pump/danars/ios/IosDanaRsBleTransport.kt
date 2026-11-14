package app.aaps.pump.danars.ios

import app.aaps.core.interfaces.pump.ble.BleTransport
import app.aaps.core.interfaces.pump.ble.session.IosBleTransport
import app.aaps.pump.danars.session.DanaRsBleProfile

/**
 * The Bluetooth transport of the Dana RS / Dana-i driver on iOS (CoreBluetooth).
 *
 * The shared [IosBleTransport] already does the general work: connect, find the service, switch on the
 * notifications, write, and tell the listener about every step. Dana only adds its own layout, see
 * [DanaRsBleProfile].
 *
 * Build the session with `requiresBond = false` on top of this transport: iOS has no system bond that the
 * app can ask for.
 *
 * Not created by anything yet. The iOS shell does not list the Dana driver, so this changes nothing for a pump.
 */
fun createIosDanaRsBleTransport(): BleTransport = IosBleTransport(profile = DanaRsBleProfile.profile)
