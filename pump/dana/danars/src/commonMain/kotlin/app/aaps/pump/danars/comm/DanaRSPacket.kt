package app.aaps.pump.danars.comm

import app.aaps.pump.danars.encryption.BleEncryption
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

open class DanaRSPacket {

    var isReceived = false
        private set
    var failed = false
    var type = BleEncryption.DANAR_PACKET__TYPE_RESPONSE // most of the messages, should be changed for others
        protected set
    var opCode = 0
        protected set

    fun success(): Boolean = !failed

    fun setReceived() {
        isReceived = true
    }

    val command: Int
        get() = (type and 0xFF shl 8) + (opCode and 0xFF)

    open fun getRequestParams(): ByteArray = ByteArray(0)

    fun getCommand(data: ByteArray): Int {
        val type = byteArrayToInt(getBytes(data, TYPE_START, 1))
        val opCode = byteArrayToInt(getBytes(data, OPCODE_START, 1))
        return (type and 0xFF shl 8) + (opCode and 0xFF)
    }

    open fun handleMessage(data: ByteArray) {}
    open fun handleMessageNotReceived() {
        failed = true
    }

    open val friendlyName: String = "UNKNOWN_PACKET"

    protected fun getBytes(data: ByteArray, srcStart: Int, srcLength: Int): ByteArray {
        return data.copyOfRange(srcStart, srcStart + srcLength)
    }

    /** Start of a local date from the pump. Where the clock changes at midnight, the day starts at the first time that exists. */
    fun dateFromBuff(buff: ByteArray, offset: Int): Long =
        LocalDate(
            2000 + byteArrayToInt(getBytes(buff, offset, 1)),
            byteArrayToInt(getBytes(buff, offset + 1, 1)),
            byteArrayToInt(getBytes(buff, offset + 2, 1))
        ).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    protected fun byteArrayToInt(b: ByteArray): Int =
        when (b.size) {
            1    -> b[0].toInt() and 0xFF
            2    -> (b[1].toInt() and 0xFF shl 8) + (b[0].toInt() and 0xFF)
            3    -> (b[2].toInt() and 0xFF shl 16) + (b[1].toInt() and 0xFF shl 8) + (b[0].toInt() and 0xFF)
            4    -> (b[3].toInt() and 0xFF shl 24) + (b[2].toInt() and 0xFF shl 16) + (b[1].toInt() and 0xFF shl 8) + (b[0].toInt() and 0xFF)
            else -> -1
        }

    /**
     * Local date and time (Y M D h m s) from the pump, or 0 when the bytes are not a valid date.
     * The pump sends such records, for example all zero (#5169) or corrupted BLE data (hour 190).
     * The callers skip a record with time 0.
     */
    fun dateTimeSecFromBuff(buff: ByteArray, offset: Int): Long {
        val year = 2000 + intFromBuff(buff, offset, 1)
        val month = intFromBuff(buff, offset + 1, 1)
        val day = intFromBuff(buff, offset + 2, 1)
        val hour = intFromBuff(buff, offset + 3, 1)
        val minute = intFromBuff(buff, offset + 4, 1)
        val second = intFromBuff(buff, offset + 5, 1)
        if (!isValidDateTime(year, month, day, hour, minute, second)) return 0L
        return localTimeToMillis(year, month, day, hour, minute, second)
    }

    protected fun intFromBuff(b: ByteArray, srcStart: Int, srcLength: Int): Int =
        when (srcLength) {
            1    -> b[DATA_START + srcStart + 0].toInt() and 0xFF
            2    -> (b[DATA_START + srcStart + 1].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 0].toInt() and 0xFF)
            3    -> (b[DATA_START + srcStart + 2].toInt() and 0xFF shl 16) + (b[DATA_START + srcStart + 1].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 0].toInt() and 0xFF)
            4    -> (b[DATA_START + srcStart + 3].toInt() and 0xFF shl 24) + (b[DATA_START + srcStart + 2].toInt() and 0xFF shl 16) + (b[DATA_START + srcStart + 1].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 0].toInt() and 0xFF)
            else -> -1
        }

    protected fun intFromBuffMsbLsb(b: ByteArray, srcStart: Int, srcLength: Int): Int =
        when (srcLength) {
            1    -> b[DATA_START + srcStart].toInt() and 0xFF
            2    -> (b[DATA_START + srcStart].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 1].toInt() and 0xFF)
            3    -> (b[DATA_START + srcStart].toInt() and 0xFF shl 16) + (b[DATA_START + srcStart + 1].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 2].toInt() and 0xFF)
            4    -> (b[DATA_START + srcStart].toInt() and 0xFF shl 24) + (b[DATA_START + srcStart + 1].toInt() and 0xFF shl 16) + (b[DATA_START + srcStart + 2].toInt() and 0xFF shl 8) + (b[DATA_START + srcStart + 3].toInt() and 0xFF)
            else -> -1
        }

    fun stringFromBuff(buff: ByteArray, offset: Int, length: Int): String {
        return buff.copyOfRange(offset, offset + length).decodeToString()
    }

    companion object {

        private const val TYPE_START = 0
        private const val OPCODE_START = 1
        const val DATA_START = 2

        /** Hour and minute as the pump sends them. 0xFF means there is no value. */
        fun isValidTime(hour: Int, minute: Int, second: Int = 0): Boolean =
            hour in 0..23 && minute in 0..59 && second in 0..59

        fun isValidDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Boolean =
            month in 1..12 && day in 1..daysInMonth(year, month) && isValidTime(hour, minute, second)

        private fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2                -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11      -> 30
            else             -> 31
        }

        /**
         * A local time from a pump that keeps local time, in the time zone of the phone.
         *
         * When the clock goes forward, the hour in the daylight saving gap does not exist on the phone.
         * The pump clock has not moved yet and is behind by the gap, so the time is moved forward by the gap
         * (02:30 becomes 03:30 when the gap is one hour). The same rule for the pump clock and the history keeps
         * them in step until AAPS sets the pump clock. When the clock goes back, the first of the two times is used.
         */
        fun localTimeToMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
            LocalDateTime(year, month, day, hour, minute, second)
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()

        fun asciiStringFromBuff(buff: ByteArray, offset: Int, length: Int): String =
            buff.copyOfRange(offset, offset + length).decodeToString()

        fun toHexString(buff: ByteArray?): String {
            if (buff == null) return "null"
            val sb = StringBuilder()
            for ((count, element) in buff.withIndex()) {
                sb.append(bytesToHex(byteArrayOf(element))).append(' ')
                if ((count + 1) % 4 == 0) sb.append(" ")
            }
            return sb.toString()
        }

        private val hexArray = "0123456789ABCDEF".toCharArray()
        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v: Int = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return hexChars.concatToString()
        }

        fun hexToBytes(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((hexDigit(s[i]) shl 4)
                    + hexDigit(s[i + 1])).toByte()
                i += 2
            }
            return data
        }

        private fun hexDigit(c: Char): Int = c.digitToIntOrNull(16) ?: -1
    }
}
