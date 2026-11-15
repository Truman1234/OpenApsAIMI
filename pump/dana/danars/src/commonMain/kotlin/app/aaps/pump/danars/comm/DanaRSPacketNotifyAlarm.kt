package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.pump.dana.DanaStrings
import app.aaps.pump.danars.encryption.BleEncryption
import app.aaps.pump.danars.services.DanaRSAlarmReporter
import dev.zacsweers.metro.Inject

@Inject
class DanaRSPacketNotifyAlarm(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val alarmReporter: DanaRSAlarmReporter
) : DanaRSPacket() {

    init {
        type = BleEncryption.DANAR_PACKET__TYPE_NOTIFY
        opCode = BleEncryption.DANAR_PACKET__OPCODE_NOTIFY__ALARM
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun handleMessage(data: ByteArray) {
        val alarmCode = byteArrayToInt(getBytes(data, DATA_START, 1))
        var errorString = ""
        when (alarmCode) {
            0x01       ->  // Battery 0% Alarm
                errorString = rh.gs(DanaStrings.batterydischarged)

            0x02       ->  // Pump Error
                errorString = rh.gs(DanaStrings.pumperror)

            0x03       ->  // Occlusion
                errorString = rh.gs(DanaStrings.occlusion)

            0x04       ->  // LOW BATTERY
                errorString = rh.gs(DanaStrings.lowbattery)

            0x05       ->  // Shutdown
                errorString = rh.gs(DanaStrings.pumpshutdown)

            0x06       ->  // Basal Compare
                errorString = rh.gs(DanaStrings.basalcompare)

            0x07, 0xFF ->  // Blood sugar measurement alert
                errorString = rh.gs(DanaStrings.bloodsugarmeasurementalert)

            0x08, 0xFE ->  // Remaining insulin level
                errorString = rh.gs(DanaStrings.remaininsulinalert)

            0x09       ->  // Empty Reservoir
                errorString = rh.gs(DanaStrings.emptyreservoir)

            0x0A       ->  // Check shaft
                errorString = rh.gs(DanaStrings.checkshaft)

            0x0B       ->  // Basal MAX
                errorString = rh.gs(DanaStrings.basalmax)

            0x0C       ->  // Daily MAX
                errorString = rh.gs(DanaStrings.dailymax)

            0xFD       ->  // Blood sugar check miss alarm
                errorString = rh.gs(DanaStrings.missedbolus)
        }
        // No error no need to upload anything
        if (errorString == "") {
            failed = true
            aapsLogger.debug(LTag.PUMPCOMM, "Error detected: $errorString")
            return
        }
        alarmReporter.report(errorString)
    }

    override val friendlyName: String = "NOTIFY__ALARM"
}
