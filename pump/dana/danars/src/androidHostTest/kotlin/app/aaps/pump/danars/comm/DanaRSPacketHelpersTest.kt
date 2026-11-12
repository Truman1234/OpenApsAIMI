package app.aaps.pump.danars.comm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Tests of the byte and date helpers of [DanaRSPacket], which are shared with iOS. */
class DanaRSPacketHelpersTest {

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x0A, 0x7F, 0xFF.toByte(), 0x80.toByte())
        assertThat(DanaRSPacket.bytesToHex(bytes)).isEqualTo("000A7FFF80")
        assertThat(DanaRSPacket.hexToBytes("000A7FFF80")).isEqualTo(bytes)
        assertThat(DanaRSPacket.hexToBytes("000a7fff80")).isEqualTo(bytes)
    }

    @Test
    fun toHexStringGroupsFourBytes() {
        assertThat(DanaRSPacket.toHexString(byteArrayOf(1, 2, 3, 4, 5))).isEqualTo("01 02 03 04  05 ")
        assertThat(DanaRSPacket.toHexString(null)).isEqualTo("null")
    }

    @Test
    fun asciiString() {
        val data = byteArrayOf(0, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte())
        assertThat(DanaRSPacket.asciiStringFromBuff(data, 1, 3)).isEqualTo("ABC")
    }

    @Test
    fun validDateTimeKnowsLeapYears() {
        assertThat(DanaRSPacket.isValidDateTime(2024, 2, 29, 0, 0, 0)).isTrue()
        assertThat(DanaRSPacket.isValidDateTime(2025, 2, 29, 0, 0, 0)).isFalse()
        assertThat(DanaRSPacket.isValidDateTime(2100, 2, 29, 0, 0, 0)).isFalse()
        assertThat(DanaRSPacket.isValidDateTime(2000, 2, 29, 0, 0, 0)).isTrue()
        assertThat(DanaRSPacket.isValidDateTime(2026, 4, 31, 0, 0, 0)).isFalse()
        assertThat(DanaRSPacket.isValidDateTime(2026, 12, 31, 23, 59, 59)).isTrue()
        assertThat(DanaRSPacket.isValidDateTime(2026, 13, 1, 0, 0, 0)).isFalse()
        assertThat(DanaRSPacket.isValidDateTime(2026, 1, 1, 24, 0, 0)).isFalse()
    }
}
