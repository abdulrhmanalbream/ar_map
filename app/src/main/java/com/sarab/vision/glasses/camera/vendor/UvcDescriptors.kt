package com.sarab.vision.glasses.camera.vendor

internal data class UvcFrameDesc(
    val formatIndex: Int,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val formatSubtype: Int,
)

/** Pure descriptor parsing: 0x24 is shared by audio, control and video interfaces. */
internal object UvcDescriptors {
    fun frames(raw: ByteArray, streamingInterfaceId: Int): List<UvcFrameDesc> {
        val frames = mutableListOf<UvcFrameDesc>()
        var inVideoStreaming = false
        var formatIndex = 0
        var formatSubtype = 0
        var offset = 0
        fun byte(index: Int) = raw[index].toInt() and 0xff
        while (offset + 1 < raw.size) {
            val length = byte(offset)
            if (length < 2 || length > raw.size - offset) break
            when (byte(offset + 1)) {
                0x02, 0x04 -> {
                    // Never carry a format from another interface/configuration into this one.
                    formatIndex = 0
                    formatSubtype = 0
                    inVideoStreaming = byte(offset + 1) == 0x04 && length >= 9 &&
                        byte(offset + 2) == streamingInterfaceId &&
                        byte(offset + 5) == 0x0e && byte(offset + 6) == 0x02
                }
                0x24 -> if (inVideoStreaming && length >= 3) {
                    val subtype = byte(offset + 2)
                    if (subtype in intArrayOf(0x04, 0x06, 0x10, 0x12)) {
                        formatIndex = if (length >= 4) byte(offset + 3) else 0
                        formatSubtype = subtype
                    } else if (length >= 9 && formatIndex > 0 &&
                        ((formatSubtype == 0x04 && subtype == 0x05) ||
                            (formatSubtype == 0x06 && subtype == 0x07) ||
                            (formatSubtype == 0x10 && subtype == 0x11))
                    ) {
                        val frameIndex = byte(offset + 3)
                        val width = byte(offset + 5) or (byte(offset + 6) shl 8)
                        val height = byte(offset + 7) or (byte(offset + 8) shl 8)
                        if (frameIndex > 0 && width > 0 && height > 0) {
                            frames += UvcFrameDesc(formatIndex, frameIndex, width, height, formatSubtype)
                        }
                    }
                }
            }
            offset += length
        }
        return frames
    }
}
