/*
 * Protocol framing/offsets adapted from Skarian/one-xr, MIT (c) 2026 Neil Skaria.
 * Full attribution and license: third_party/one-xr/LICENSE.
 */
package com.sarab.vision.glasses.motion

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class OneImuReport(
    val deviceTimeNanos: Long,
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
)

/** TCP is a byte stream: headers and bodies can be split at ANY byte boundary. */
internal class OneImuFramer {
    private var pending = ByteArray(0)

    fun append(chunk: ByteArray, count: Int = chunk.size): List<OneImuReport> {
        require(count in 0..chunk.size)
        if (count == 0) return emptyList()
        // A real report is 134 bytes; bound allocation even for malicious/unrelated peers.
        if (count > MAX_CHUNK_BYTES) {
            pending = ByteArray(0)
            return emptyList()
        }
        pending += chunk.copyOf(count)
        val reports = ArrayList<OneImuReport>()
        var offset = 0
        while (pending.size - offset >= HEADER_BYTES) {
            val first = pending[offset].toInt() and 0xff
            if ((first != 0x28 && first != 0x27) || pending[offset + 1] != 0x36.toByte()) {
                offset++
                continue
            }
            val bodyLength = ((pending[offset + 2].toInt() and 0xff) shl 24) or
                ((pending[offset + 3].toInt() and 0xff) shl 16) or
                ((pending[offset + 4].toInt() and 0xff) shl 8) or
                (pending[offset + 5].toInt() and 0xff)
            if (bodyLength != BODY_BYTES) {
                offset++
                continue
            }
            if (pending.size - offset < REPORT_BYTES) break
            decode(pending, offset + HEADER_BYTES)?.let(reports::add)
            offset += REPORT_BYTES
        }
        pending = pending.copyOfRange(offset, pending.size)
        return reports
    }

    private fun decode(bytes: ByteArray, offset: Int): OneImuReport? {
        val body = ByteBuffer.wrap(bytes, offset, BODY_BYTES).slice().order(ByteOrder.LITTLE_ENDIAN)
        // MAG (0x04) reports legitimately contain NaN in the unused IMU fields.
        if (body.getInt(0x18) != 0x0b) return null
        val timestamp = body.getLong(0x08)
        if (timestamp <= 0L) return null
        val values = DoubleArray(6) { body.getFloat(0x1c + it * 4).toDouble() }
        if (values.any { !it.isFinite() }) return null
        return OneImuReport(timestamp, values[0], values[1], values[2], values[3], values[4], values[5])
    }

    companion object {
        private const val HEADER_BYTES = 6
        private const val BODY_BYTES = 128
        private const val REPORT_BYTES = HEADER_BYTES + BODY_BYTES
        private const val MAX_CHUNK_BYTES = 16_384
    }
}
