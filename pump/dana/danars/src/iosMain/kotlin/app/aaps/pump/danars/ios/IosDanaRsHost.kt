package app.aaps.pump.danars.ios

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.ui.CoreUiStrings
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.DanaStrings
import app.aaps.pump.dana.keys.DanaStringComposedKey
import app.aaps.pump.dana.keys.DanaStringNonKey
import app.aaps.pump.danars.services.DanaRSAlarmReporter
import app.aaps.pump.danars.session.DanaRsHost
import app.aaps.pump.danars.session.DanaRsNotice
import app.aaps.pump.danars.session.DanaRsText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The iOS side of [DanaRsHost]. Same texts and notices as the Android `BLEComm`/`DanaRSPlugin`,
 * through the same [TextResolver]/[NotificationManager] that Android uses, so the wording cannot
 * drift between platforms.
 *
 * `changePump()` and `restartApp(reason)` are simpler here than on Android: there is no iOS pump
 * driver/plugin yet (step 4c), so there is nothing to reconnect or restart. Both are filled in
 * properly once that driver exists.
 */
@SingleIn(AppScope::class)
@Inject
class IosDanaRsHost(
    private val rh: TextResolver,
    private val notificationManager: NotificationManager,
    private val preferences: Preferences,
    private val danaRsAlarmReporter: DanaRSAlarmReporter,
    private val danaPump: DanaPump,
    private val aapsLogger: AAPSLogger
) : DanaRsHost {

    override val deviceName: String
        get() = preferences.get(DanaStringNonKey.RsName)

    /**
     * CoreBluetooth asks for the Bluetooth permission itself, the first time the app scans. There
     * is no equivalent of Android's `BLUETOOTH_CONNECT` check to do in advance.
     */
    override fun hasBluetoothPermission(): Boolean = true

    override fun text(text: DanaRsText, argument: String?): String =
        when (text) {
            DanaRsText.PUMP_BUSY         -> rh.gs(CoreUiStrings.pump_busy)
            DanaRsText.CONNECTION_ERROR  -> rh.gs(CoreUiStrings.connection_error)
            DanaRsText.INVALID_PAIRING   -> rh.gs(DanaStrings.invalidpairing)
            DanaRsText.UNSUPPORTED_MODEL -> rh.gs(DanaStrings.danars_unsupported_model.withArgs(argument))
        }

    override fun show(notice: DanaRsNotice) {
        when (notice) {
            DanaRsNotice.MISSING_BLUETOOTH_PERMISSION -> notificationManager.post(NotificationId.BLUETOOTH_PERMISSION_MISSING, CoreUiStrings.need_connect_permission)
            DanaRsNotice.DEVICE_NOT_PAIRED             -> notificationManager.post(NotificationId.DEVICE_NOT_PAIRED, DanaStrings.pairfirst)
            DanaRsNotice.WRONG_SERIAL_NUMBER           -> notificationManager.post(NotificationId.WRONG_SERIAL_NUMBER, CoreUiStrings.password_cleared)
            DanaRsNotice.WRONG_PUMP_PASSWORD           -> notificationManager.post(NotificationId.WRONG_PUMP_PASSWORD, DanaStrings.wrongpumppassword)
        }
    }

    override fun dismissWrongPumpPassword() {
        notificationManager.dismiss(NotificationId.WRONG_PUMP_PASSWORD)
    }

    override fun pumpCheckErrorText(errorFlags: Int?): String = danaRsAlarmReporter.pumpCheckErrorText(errorFlags)

    override fun reportAlarm(text: String) = danaRsAlarmReporter.report(text)

    override fun changePump() {
        danaPump.serialNumber = preferences.get(DanaStringNonKey.RsName)
        danaPump.reset()
        // No iOS driver to tell "readStatus" yet (step 4c). Android's commandQueue.readStatus(...)
        // has no iOS counterpart until then.
    }

    override fun clearPairing() {
        val name = deviceName
        preferences.remove(DanaStringComposedKey.ParingKey, name)
        preferences.remove(DanaStringComposedKey.V3RandomParingKey, name)
        preferences.remove(DanaStringComposedKey.V3ParingKey, name)
        preferences.remove(DanaStringComposedKey.V3RandomSyncKey, name)
        preferences.remove(DanaStringComposedKey.Ble5PairingKey, name)
    }

    override fun restartApp(reason: String) {
        // No real app restart yet: there is no iOS driver using this host in production (step 4c).
        aapsLogger.error(LTag.PUMPCOMM, "DanaRS iOS: restart requested but not implemented yet: $reason")
    }
}
