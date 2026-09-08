package com.sarab.vision.glasses.motion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneImuProtocolTest {
    private fun capturedBytes(): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/packets/one-xr-capture-prefix.bin")
    ).use { it.readBytes() }

    @Test fun readsCapturedImuAndSkipsMagnetometerNaNs() {
        val reports = OneImuFramer().append(capturedBytes())
        assertEquals(3, reports.size)
        val first = reports.first()
        assertEquals(0.002263687, first.gx, 0.00000001)
        assertEquals(0.005060006, first.gy, 0.00000001)
        assertEquals(-0.004793690, first.gz, 0.00000001)
        assertEquals(0.5706299, first.ax, 0.000001)
        assertEquals(-8.697021, first.ay, 0.000001)
        assertEquals(4.456177, first.az, 0.000001)
        assertTrue(reports.zipWithNext().all { (a, b) -> b.deviceTimeNanos > a.deviceTimeNanos })
    }

    @Test fun handlesEveryPossibleTcpSplit() {
        val bytes = capturedBytes()
        val expected = OneImuFramer().append(bytes)
        for (split in 0..bytes.size) {
            val parser = OneImuFramer()
            val actual = parser.append(bytes.copyOfRange(0, split)) +
                parser.append(bytes.copyOfRange(split, bytes.size))
            assertEquals("split $split", expected, actual)
        }
        val parser = OneImuFramer()
        assertEquals(expected, bytes.flatMap { parser.append(byteArrayOf(it)) })
    }

    @Test fun doesNotDecodeTruncatedBodyUntilItArrives() {
        val packet = capturedBytes().copyOfRange(134, 268)
        val parser = OneImuFramer()
        assertTrue(parser.append(packet.copyOfRange(0, 133)).isEmpty())
        assertEquals(1, parser.append(byteArrayOf(packet.last())).size)
    }

    @Test fun acceptsBothVerifiedHeadersAndResynchronizesAfterInvalidLength() {
        val packet = capturedBytes().copyOfRange(134, 268)
        packet[0] = 0x27
        val invalid = byteArrayOf(0x28, 0x36, 0x7f, 0x7f, 0x7f, 0x7f, 0, 0)
        val parser = OneImuFramer()
        assertEquals(1, parser.append(invalid + packet).size)
    }

    @Test fun rejectsNonFiniteImuWithoutLosingNextPacket() {
        val good = capturedBytes().copyOfRange(134, 268)
        val bad = good.copyOf()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(6 + 0x1c, Float.NaN)
        assertEquals(1, OneImuFramer().append(bad + good).size)
    }
}
