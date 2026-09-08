package com.sarab.vision.glasses.camera

import com.sarab.vision.glasses.camera.vendor.FrameAssembler
import com.sarab.vision.glasses.camera.vendor.GlassesCommands
import com.sarab.vision.glasses.camera.vendor.GlassesFrame
import com.sarab.vision.glasses.camera.vendor.HevcNal
import com.sarab.vision.glasses.camera.vendor.MjpegStreamAssembler
import com.sarab.vision.glasses.camera.vendor.UsbConfigCodec
import org.junit.Assert.*
import org.junit.Test

class EyeCameraProtocolTest {
    @Test fun activationUsesCapturedUsbProfileAndFourPreamblePackets() {
        val messages = GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD)
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd3), messages.map { GlassesFrame.parse(it, it.size).cmd })
        assertArrayEquals(byteArrayOf(0x45, 0x10, 0x01, 0x00), GlassesFrame.parse(messages.last(), messages.last().size).payload)
    }

    @Test fun damagedControlResponseIsRejectedBeforeItsPayloadIsUsed() {
        val response = GlassesFrame.build(0xd3, byteArrayOf(0))
        response[response.lastIndex] = 1
        assertThrows(IllegalArgumentException::class.java) { GlassesFrame.parse(response, response.size) }
    }

    @Test fun controlLengthCannotWrapItsOneByteLengthField() {
        assertThrows(IllegalArgumentException::class.java) { GlassesFrame.build(0xd3, ByteArray(239)) }
        val response = GlassesFrame.build(0xd3)
        assertThrows(IllegalArgumentException::class.java) { GlassesFrame.parse(response, response.size + 1) }
    }

    @Test fun mjpegSpanningUsbChunksNeedsItsActualJpegEndMarker() {
        val assembler = MjpegStreamAssembler()
        assertTrue(assembler.feed(byteArrayOf(0, 0xff.toByte())).isEmpty())
        assertTrue(assembler.feed(byteArrayOf(0xd8.toByte(), 10, 20, 0xff.toByte())).isEmpty())
        val frames = assembler.feed(byteArrayOf(0xd9.toByte()))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 10, 20, 0xff.toByte(), 0xd9.toByte()), frames.single())
    }

    @Test fun unterminatedJpegCannotGrowWithoutBoundAndNextFrameRecovers() {
        val assembler = MjpegStreamAssembler(maxBufferBytes = 8)
        assertTrue(assembler.feed(byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(9)).isEmpty())
        assertTrue(assembler.discardedBytes >= 11)
        val complete = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        assertArrayEquals(complete, assembler.feed(complete).single())
    }

    @Test fun uvcHeadersAreRemovedWithoutDroppingSplitFramePayload() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(byteArrayOf(2, 0, 10, 20), 4).isEmpty())
        val frames = assembler.offerPayload(byteArrayOf(2, 2, 30, 40), 4)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), frames.single())
    }

    @Test fun hevcWaitsForAllParameterSetsBeforeDeliveringSlices() {
        val nal = HevcNal()
        fun unit(type: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1)
        assertTrue(nal.processAccessUnit(unit(19)).isEmpty())
        assertTrue(nal.processAccessUnit(unit(32) + unit(33)).isEmpty())
        assertFalse(nal.parameterSetsComplete)
        val slice = unit(19)
        assertArrayEquals(slice, nal.processAccessUnit(unit(34) + slice).single())
        assertArrayEquals(unit(32) + unit(33) + unit(34), nal.currentCsd0())
    }
}
