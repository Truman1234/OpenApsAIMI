package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * Puts the packets of the pump back together.
 *
 * The pump sends one packet in several BLE notifications of at most 20 bytes. This class collects the
 * bytes and cuts out whole packets. The rules are the same as in the Android class `BLEComm`:
 * - A packet starts with `A5 A5` or `AA AA` and ends with `5A 5A` or `EE EE`.
 * - The third byte is the length of the payload. The whole packet has 7 bytes more.
 * - Bytes in front of a packet start are thrown away.
 * - If the end bytes are wrong, the whole buffer is thrown away.
 * - If the buffer would overflow, the old bytes are thrown away and only the newest bytes are kept.
 *
 * This class is not thread safe. The caller must call it from one place at a time.
 */
class DanaRsReadBuffer(
    private val aapsLogger: AAPSLogger,
    private val capacity: Int = 1024
) {

    private val buffer = ByteArray(capacity)
    private var length = 0

    /** Number of bytes waiting in the buffer */
    val size: Int get() = length

    fun clear() {
        length = 0
    }

    fun add(data: ByteArray) {
        if (data.isEmpty()) return
        // A stream that never gives a valid packet start would grow without end. Drop the backlog and keep
        // the newest notification (at most 20 bytes), which may hold a fresh packet start.
        if (length + data.size > capacity) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Read buffer overflow ($length + ${data.size} > $capacity); dropping unparsable backlog")
            length = 0
        }
        data.copyInto(buffer, destinationOffset = length)
        length += data.size
    }

    /**
     * The next whole packet, or null if there is none yet.
     * Call it again after every packet until it returns null.
     */
    fun nextPacket(): ByteArray? {
        if (length < 6) return null
        for (start in 0 until length - 2) {
            val isStart = buffer[start] == START_BYTE && buffer[start + 1] == START_BYTE ||
                buffer[start] == BLE5_START_BYTE && buffer[start + 1] == BLE5_START_BYTE
            if (!isStart) continue // keep looking: a packet start may come after leading trash

            if (start > 0) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Shifting the input buffer by $start bytes")
                buffer.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = length)
                length -= start
                if (length < 0) length = 0
            }
            // A5 A5 LEN TYPE CODE PARAMS CHECKSUM1 CHECKSUM2 5A 5A     (or AA AA ... EE EE)
            // The length is unsigned: a Dana-i2 history packet can be 202 bytes long.
            val payload = buffer[2].toInt() and 0xFF
            val total = payload + 7
            if (total > length) return null // not complete yet
            val endOk = buffer[payload + 5] == END_BYTE && buffer[payload + 6] == END_BYTE ||
                buffer[payload + 5] == BLE5_END_BYTE && buffer[payload + 6] == BLE5_END_BYTE
            if (!endOk) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Error in input data. Resetting buffer.")
                length = 0
                return null
            }
            val packet = buffer.copyOfRange(0, total)
            buffer.copyInto(buffer, destinationOffset = 0, startIndex = total, endIndex = length)
            length -= total
            return packet
        }
        return null
    }

    companion object {

        const val START_BYTE: Byte = 0xA5.toByte()
        const val END_BYTE: Byte = 0x5A
        const val BLE5_START_BYTE: Byte = 0xAA.toByte()
        const val BLE5_END_BYTE: Byte = 0xEE.toByte()

        /** The pump takes at most 20 bytes per BLE write */
        const val MAX_WRITE_SIZE = 20

        /** Cuts a packet into parts of at most [maxSize] bytes, in order. An empty packet gives one empty part. */
        fun splitForWrite(bytes: ByteArray, maxSize: Int = MAX_WRITE_SIZE): List<ByteArray> {
            if (bytes.size <= maxSize) return listOf(bytes)
            val parts = ArrayList<ByteArray>((bytes.size + maxSize - 1) / maxSize)
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + maxSize, bytes.size)
                parts.add(bytes.copyOfRange(offset, end))
                offset = end
            }
            return parts
        }
    }
}
