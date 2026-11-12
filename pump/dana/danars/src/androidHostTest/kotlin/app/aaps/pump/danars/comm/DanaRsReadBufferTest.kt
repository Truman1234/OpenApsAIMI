package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class DanaRsReadBufferTest {

    private val buffer = DanaRsReadBuffer(mock<AAPSLogger>())

    /** A5 A5 LEN <LEN payload bytes> CRC CRC 5A 5A, so LEN + 7 bytes in total */
    private fun packet(payloadSize: Int, start: Byte = 0xA5.toByte(), end: Byte = 0x5A): ByteArray {
        val p = ByteArray(payloadSize + 7)
        p[0] = start; p[1] = start; p[2] = payloadSize.toByte()
        for (i in 3 until payloadSize + 5) p[i] = (i and 0x7F).toByte()
        p[payloadSize + 5] = end; p[payloadSize + 6] = end
        return p
    }

    @Test
    fun wholePacketIsReturned() {
        val p = packet(4)
        buffer.add(p)
        assertThat(buffer.nextPacket()).isEqualTo(p)
        assertThat(buffer.nextPacket()).isNull()
        assertThat(buffer.size).isEqualTo(0)
    }

    @Test
    fun packetInPartsIsReturnedWhenComplete() {
        val p = packet(30)
        val parts = DanaRsReadBuffer.splitForWrite(p)
        parts.dropLast(1).forEach {
            buffer.add(it)
            assertThat(buffer.nextPacket()).isNull()
        }
        buffer.add(parts.last())
        assertThat(buffer.nextPacket()).isEqualTo(p)
    }

    @Test
    fun twoPacketsInOneNotification() {
        val a = packet(3)
        val b = packet(5)
        buffer.add(a + b)
        assertThat(buffer.nextPacket()).isEqualTo(a)
        assertThat(buffer.nextPacket()).isEqualTo(b)
        assertThat(buffer.nextPacket()).isNull()
    }

    @Test
    fun leadingTrashIsThrownAway() {
        val p = packet(4)
        buffer.add(byteArrayOf(0x01, 0x02, 0x03) + p)
        assertThat(buffer.nextPacket()).isEqualTo(p)
    }

    @Test
    fun ble5MarkersAreAccepted() {
        val p = packet(6, start = 0xAA.toByte(), end = 0xEE.toByte())
        buffer.add(p)
        assertThat(buffer.nextPacket()).isEqualTo(p)
    }

    @Test
    fun lengthByteIsUnsigned() {
        // A Dana-i2 history packet: 195 payload bytes, a signed byte would be negative
        val p = packet(195)
        buffer.add(p)
        assertThat(buffer.nextPacket()).isEqualTo(p)
    }

    @Test
    fun wrongEndBytesResetTheBuffer() {
        val bad = packet(4)
        bad[bad.size - 1] = 0x00
        buffer.add(bad)
        assertThat(buffer.nextPacket()).isNull()
        assertThat(buffer.size).isEqualTo(0)
        // and the next good packet is read
        val good = packet(4)
        buffer.add(good)
        assertThat(buffer.nextPacket()).isEqualTo(good)
    }

    @Test
    fun overflowKeepsOnlyTheNewestBytes() {
        val small = DanaRsReadBuffer(mock<AAPSLogger>(), capacity = 32)
        small.add(ByteArray(30) { 0x11 }) // no packet start in it
        val p = packet(4)
        small.add(p) // 30 + 11 > 32: the backlog is dropped
        assertThat(small.nextPacket()).isEqualTo(p)
    }

    @Test
    fun fewerThanSixBytesGiveNothing() {
        buffer.add(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x00))
        assertThat(buffer.nextPacket()).isNull()
        assertThat(buffer.size).isEqualTo(3)
    }

    @Test
    fun splitForWriteMakesPartsOfTwentyBytes() {
        val data = ByteArray(45) { it.toByte() }
        val parts = DanaRsReadBuffer.splitForWrite(data)
        assertThat(parts.map { it.size }).containsExactly(20, 20, 5).inOrder()
        assertThat(parts.reduce { a, b -> a + b }).isEqualTo(data)
    }

    @Test
    fun splitForWriteKeepsShortAndExactPackets() {
        assertThat(DanaRsReadBuffer.splitForWrite(ByteArray(7)).map { it.size }).containsExactly(7)
        assertThat(DanaRsReadBuffer.splitForWrite(ByteArray(20)).map { it.size }).containsExactly(20)
        assertThat(DanaRsReadBuffer.splitForWrite(ByteArray(40)).map { it.size }).containsExactly(20, 20).inOrder()
    }
}
