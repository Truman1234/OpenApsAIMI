package app.aaps.pump.danars.session

/** Texts the session needs. The platform looks them up in its own way (Android resources, iOS strings). */
enum class DanaRsText {

    PUMP_BUSY,
    CONNECTION_ERROR,
    INVALID_PAIRING,

    /** The argument is the model as hex text, for example `0x0D` */
    UNSUPPORTED_MODEL
}

/** Things the user must be told about. The platform decides how: notification, banner, dialog. */
enum class DanaRsNotice {

    MISSING_BLUETOOTH_PERMISSION,
    DEVICE_NOT_PAIRED,
    WRONG_SERIAL_NUMBER,
    WRONG_PUMP_PASSWORD
}

/**
 * What [DanaRsSession] needs from the platform. Android and iOS each give their own.
 * The session calls these from its own coroutine, so they must not block for long.
 */
interface DanaRsHost {

    /** Bluetooth name of the pump, for example `UHH00002TI`. The pairing keys are stored per name. */
    val deviceName: String

    fun hasBluetoothPermission(): Boolean

    fun text(text: DanaRsText, argument: String? = null): String

    fun show(notice: DanaRsNotice)

    /** The notice about the wrong pump password is not needed any more */
    fun dismissWrongPumpPassword()

    /** Text for the reply "PUMP" to the pump check. [errorFlags] is null on pumps that send none. */
    fun pumpCheckErrorText(errorFlags: Int?): String

    /** Show the alarm of the pump to the user and store it as an announcement */
    fun reportAlarm(text: String)

    /** The pairing keys are not valid any more: forget the pump so the user can pair again */
    fun changePump()

    /** The pump refused the connection with a wrong serial number: delete the stored pairing */
    fun clearPairing()

    /** The encryption of Dana RS v3 failed twice: the app has to restart to recover */
    fun restartApp(reason: String)
}
