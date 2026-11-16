package app.aaps.pump.danars.ios

import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.queue.CommandQueue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The iOS side of [DanaRsHost]. Same texts and notices as the Android `BLEComm`/`DanaRSPlugin`,
 * through the same [TextResolver]/[NotificationManager] that Android uses, so the wording cannot
 * drift between platforms.
 *
 * `restartApp(reason)` stays simpler than Android's: there is no "restart the whole app" concept
 * to offer here, and Dana RS v3's encryption failing twice is rare enough that logging it and
 * leaving reconnects to the normal retry path is an acceptable gap for now.
 */
@SingleIn(AppScope::class)
@Inject
class IosDanaRsHost(
    private val rh: TextResolver,
    private val notificationManager: NotificationManager,
    private val preferences: Preferences,
    private val danaRsAlarmReporter: DanaRSAlarmReporter,
    private val danaPump: DanaPump,
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    @ApplicationScope private val appScope: CoroutineScope
) : DanaRsHost {

    /**
     * Holds the name a pairing wizard set in memory, before the pump is actually paired and the
     * name is written to preferences (see [DanaRsHost.deviceName]'s doc). `null` once nothing is
     * pending, which is when this falls back to the stored name, same as before this was settable.
     */
    private var pendingDeviceName: String? = null

    override var deviceName: String
        get() = pendingDeviceName ?: preferences.get(DanaStringNonKey.RsName)
        set(value) { pendingDeviceName = value }

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
        pendingDeviceName = null
        danaPump.serialNumber = preferences.get(DanaStringNonKey.RsName)
        danaPump.reset()
        // Same as Android's DanaRSPlugin.changePump(): ask the active pump (IosDanaRsPump, since
        // 4c) to read its status, through the queue rather than directly - this host has no
        // reference to the driver, only the other way around.
        appScope.launch { commandQueue.readStatus(rh.gs(CoreUiStrings.device_changed)) }
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
        // No real app restart yet: there is no such thing as "restart the app" to offer on iOS the
        // way Android can. Rare in practice (two failed Dana RS v3 encryption attempts in a row);
        // logging it and leaving the next reconnect attempt to the normal retry path is an
        // acceptable gap for now.
        aapsLogger.error(LTag.PUMPCOMM, "DanaRS iOS: restart requested but not implemented: $reason")
    }
}
