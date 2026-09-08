package com.sarab.vision.glasses.camera

import com.sarab.vision.glasses.camera.vendor.FrameAssembler
import com.sarab.vision.glasses.camera.vendor.MjpegStreamAssembler
import com.sarab.vision.glasses.camera.vendor.UvcDescriptors
import com.sarab.vision.glasses.camera.vendor.UvcFrameDesc
import org.junit.Assert.*
import org.junit.Test

class UvcPipelineRegressionTest {
    @Test fun compositeControlDescriptorsCannotBecomeCameraFrames() {
        val raw = iface(5, 1, 1) + format(6, 0x06) + frame(2, 0x07, 16384, 24322) +
            iface(9, 14, 1) + format(6, 0x06) + frame(2, 0x07, 16384, 24322) +
            iface(10, 14, 2) + format(1, 0x10) + frame(1, 0x11, 2048, 1512) +
            frame(2, 0x11, 1920, 1080) + format(2, 0x06) +
            frame(1, 0x07, 1920, 1080) + frame(2, 0x07, 1920, 1080) +
            frame(3, 0x07, 1080, 1920) + frame(4, 0x07, 1080, 1920) +
            frame(5, 0x07, 720, 1280) +
            iface(11, 14, 2) + format(3, 0x06) + frame(1, 0x07, 640, 480)

        assertEquals(
            listOf(
                UvcFrameDesc(1, 1, 2048, 1512, 0x10),
                UvcFrameDesc(1, 2, 1920, 1080, 0x10),
                UvcFrameDesc(2, 1, 1920, 1080, 0x06),
                UvcFrameDesc(2, 2, 1920, 1080, 0x06),
                UvcFrameDesc(2, 3, 1080, 1920, 0x06),
                UvcFrameDesc(2, 4, 1080, 1920, 0x06),
                UvcFrameDesc(2, 5, 720, 1280, 0x06),
            ),
            UvcDescriptors.frames(raw, 10),
        )
    }

    @Test fun aFrameNeedsAFormatFromTheCurrentInterface() {
        val raw = iface(10, 14, 2) + format(2, 0x06) +
            iface(9, 14, 1) + iface(10, 14, 2) + frame(1, 0x07, 1920, 1080)
        assertTrue(UvcDescriptors.frames(raw, 10).isEmpty())
    }

    @Test fun mismatchedFrameTypesAndEmptyDimensionsAreRejected() {
        val raw = iface(10, 14, 2) + format(2, 0x06) +
            frame(1, 0x11, 1920, 1080) + frame(2, 0x07, 0, 1080) +
            frame(3, 0x07, 1920, 0) + frame(0, 0x07, 1920, 1080) +
            frame(4, 0x07, 1920, 1080)
        assertEquals(listOf(UvcFrameDesc(2, 4, 1920, 1080, 0x06)), UvcDescriptors.frames(raw, 10))
    }

    @Test fun truncatedDescriptorsStopSafelyAfterValidFrames() {
        val prefix = iface(10, 14, 2) + format(2, 0x06) + frame(1, 0x07, 1920, 1080)
        for (tail in listOf(byteArrayOf(1, 0x24), byteArrayOf(26, 0x24, 7), byteArrayOf(0x24))) {
            assertEquals(listOf(UvcFrameDesc(2, 1, 1920, 1080, 0x06)), UvcDescriptors.frames(prefix + tail, 10))
        }
    }

    @Test fun lostJpegEndResynchronizesAtTheNextImageInsteadOfJoiningImages() {
        val assembler = MjpegStreamAssembler()
        val broken = bytes(0xff, 0xd8, 10, 20)
        val next = bytes(0xff, 0xd8, 30, 40, 0xff, 0xd9)
        assertTrue(assembler.feed(broken).isEmpty())
        assertArrayEquals(next, assembler.feed(next).single())
        assertEquals(broken.size.toLong(), assembler.discardedBytes)
        assertEquals(1L, assembler.framesExtracted)
    }

    @Test fun recoveryAlsoWorksWhenTheNewStartMarkerIsSplitAcrossUsbReads() {
        val assembler = MjpegStreamAssembler()
        assertTrue(assembler.feed(bytes(0xff, 0xd8, 10, 0xff)).isEmpty())
        val next = bytes(0xff, 0xd8, 30, 0xff, 0xd9)
        assertArrayEquals(next, assembler.feed(next.copyOfRange(1, next.size)).single())
        assertEquals(3L, assembler.discardedBytes)
    }

    @Test fun severalBrokenImagesAreSkippedBeforeTheNextWholeImage() {
        val assembler = MjpegStreamAssembler()
        val broken = bytes(0xff, 0xd8, 10)
        val next = bytes(0xff, 0xd8, 30, 0xff, 0xd9)
        assertArrayEquals(next, assembler.feed(broken + broken + next).single())
        assertEquals(6L, assembler.discardedBytes)
    }

    @Test fun sizeLimitAppliesEvenWhenAnOversizedJpegArrivesWithItsEndMarker() {
        val assembler = MjpegStreamAssembler(maxBufferBytes = 8)
        val oversized = bytes(0xff, 0xd8) + ByteArray(8) + bytes(0xff, 0xd9)
        val next = bytes(0xff, 0xd8, 30, 0xff, 0xd9)
        assertArrayEquals(next, assembler.feed(oversized + next).single())
        assertEquals(oversized.size.toLong(), assembler.discardedBytes)
    }

    @Test fun jpegEscapingAndRestartMarkersArePreserved() {
        val assembler = MjpegStreamAssembler()
        val jpeg = bytes(0xff, 0xd8, 20, 0xff, 0x00, 0xd8, 0xff, 0xd0, 30, 0xff, 0xd9)
        assertArrayEquals(jpeg, assembler.feed(jpeg).single())
        assertEquals(0L, assembler.discardedBytes)
    }

    @Test fun multipleWholeJpegsDoNotDependOnUsbChunkBoundaries() {
        val assembler = MjpegStreamAssembler()
        val first = bytes(0xff, 0xd8, 10, 0xff, 0xd9)
        val second = bytes(0xff, 0xd8, 20, 0xff, 0xd9)
        val frames = assembler.feed(first + second)
        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test fun aTransportErrorCannotJoinBufferedPrefixToTheSurvivingSuffix() {
        val assembler = MjpegStreamAssembler()
        assertTrue(assembler.feed(bytes(0xff, 0xd8, 10)).isEmpty())
        assembler.discardPartialFrame()
        assertTrue(assembler.feed(bytes(20, 0xff, 0xd9)).isEmpty())
        val next = bytes(0xff, 0xd8, 30, 0xff, 0xd9)
        assertArrayEquals(next, assembler.feed(next).single())
        assertEquals(1L, assembler.framesExtracted)
    }

    @Test fun sizeOverflowKeepsASplitStartMarkerForTheNextValidImage() {
        val assembler = MjpegStreamAssembler(maxBufferBytes = 8)
        assertTrue(assembler.feed(bytes(0xff, 0xd8) + ByteArray(8) + bytes(0xff)).isEmpty())
        val next = bytes(0xff, 0xd8, 30, 0xff, 0xd9)
        assertArrayEquals(next, assembler.feed(next.copyOfRange(1, next.size)).single())
    }

    @Test fun usbErrorDiscardsTheWholeDamagedFrameAndResumesAtTheNextFid() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(bytes(2, 0, 10), 3).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 0x40, 20), 3).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 0, 30), 3).isEmpty())
        assertArrayEquals(bytes(40), assembler.offerPayload(bytes(2, 3, 40), 3).single())
        assertEquals(1L, assembler.discardedFrames)
    }

    @Test fun errorOnLastPayloadCannotPublishAPartialImage() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(bytes(2, 0, 10), 3).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 0x42, 20), 3).isEmpty())
        assertArrayEquals(bytes(40), assembler.offerPayload(bytes(2, 2, 40), 3).single())
    }

    @Test fun aFidChangeWithoutEofPreservesThePreviousWholePayload() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(bytes(2, 0, 10, 20), 4).isEmpty())
        val frames = assembler.offerPayload(bytes(2, 3, 30, 40), 4)
        assertEquals(2, frames.size)
        assertArrayEquals(bytes(10, 20), frames[0])
        assertArrayEquals(bytes(30, 40), frames[1])
        assertEquals(0L, assembler.discardedFrames)
    }

    @Test fun shortAndUnrecognizedHeadersRemainRawContinuationForEyeBulkChunks() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(bytes(2, 0, 10), 3).isEmpty())
        assertTrue(assembler.offerPayload(bytes(0xff), 1).isEmpty())
        assertTrue(assembler.offerPayload(bytes(12, 0, 20), 3).isEmpty())
        assertArrayEquals(
            bytes(10, 0xff, 12, 0, 20, 30),
            assembler.offerPayload(bytes(2, 2, 30), 3).single(),
        )
    }

    @Test fun rawContinuationOfAnErroredFrameIsDiscardedUntilABoundary() {
        val assembler = FrameAssembler()
        assertTrue(assembler.offerPayload(bytes(2, 0x40, 10), 3).isEmpty())
        assertTrue(assembler.offerPayload(bytes(0xff, 0xd9), 2).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 2, 20), 3).isEmpty())
        assertArrayEquals(bytes(30), assembler.offerPayload(bytes(2, 3, 30), 3).single())
        assertEquals(1L, assembler.discardedFrames)
    }

    @Test fun errorAtAFidChangeCannotReintroduceAPrefixAfterDecoderDiscontinuity() {
        val uvc = FrameAssembler()
        val mjpeg = MjpegStreamAssembler()
        fun receive(payload: ByteArray): List<ByteArray> {
            val discardedBefore = uvc.discardedFrames
            val chunks = uvc.offerPayload(payload, payload.size)
            if (discardedBefore != uvc.discardedFrames) mjpeg.discardPartialFrame()
            return chunks.flatMap(mjpeg::feed)
        }

        assertTrue(receive(bytes(2, 2, 0xff, 0xd8, 10)).isEmpty())
        assertTrue(receive(bytes(2, 0, 0xff, 0xd8, 20)).isEmpty())
        assertTrue(receive(bytes(2, 0x41, 30)).isEmpty())
        // A later independently delimited UVC chunk can be just a JPEG suffix on this Eye.
        assertTrue(receive(bytes(2, 2, 40, 0xff, 0xd9)).isEmpty())
        val next = bytes(0xff, 0xd8, 50, 0xff, 0xd9)
        assertArrayEquals(next, receive(bytes(2, 3) + next).single())
        assertEquals(1L, mjpeg.framesExtracted)
    }

    @Test fun oversizedFrameCannotPublishItsTailAfterTheBufferWasCleared() {
        val assembler = FrameAssembler(maxFrameSize = 3)
        assertTrue(assembler.offerPayload(bytes(2, 0, 10, 20), 4).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 0, 30, 40), 4).isEmpty())
        assertTrue(assembler.offerPayload(bytes(2, 2, 50), 3).isEmpty())
        assertArrayEquals(bytes(60), assembler.offerPayload(bytes(2, 3, 60), 3).single())
        assertEquals(1L, assembler.discardedFrames)
    }

    private fun iface(id: Int, clazz: Int, subclass: Int) = bytes(9, 4, id, 0, 0, clazz, subclass, 0, 0)

    private fun format(index: Int, subtype: Int) = ByteArray(27).apply {
        this[0] = size.toByte()
        this[1] = 0x24
        this[2] = subtype.toByte()
        this[3] = index.toByte()
    }

    private fun frame(index: Int, subtype: Int, width: Int, height: Int) = ByteArray(26).apply {
        this[0] = size.toByte()
        this[1] = 0x24
        this[2] = subtype.toByte()
        this[3] = index.toByte()
        this[5] = width.toByte()
        this[6] = (width shr 8).toByte()
        this[7] = height.toByte()
        this[8] = (height shr 8).toByte()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
