package com.daytrace.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TimelineTest {
    @Test fun transcriptOffsetMapsToAbsoluteTimeWithinItsOwnSegment() {
        val info = EncryptedAudio.Info(EncryptedAudio.Header(1_000_000, "s", "a", "opus", true, 16000), 32000, 0)
        assertEquals(1_000_450L, Timeline.absolute(info, 450))
        assertEquals(1_001_000L, Timeline.absolute(info, 1000))
        assertThrows(IllegalArgumentException::class.java) { Timeline.absolute(info, 1001) }
        assertThrows(IllegalArgumentException::class.java) { Timeline.absolute(info, -1) }
        val next = info.copy(header = info.header.copy(startedAt = 1_100_000))
        assertEquals(1_100_450L, Timeline.absolute(next, 450))
    }
    @Test fun gapsRespectUnsortedOverlappingAndAdjacentSegments() {
        val intervals = listOf(Timeline.Interval(5000, 7000), Timeline.Interval(1000, 3000),
            Timeline.Interval(2000, 4000), Timeline.Interval(7000, 8000))
        assertEquals(listOf(Timeline.Interval(4000, 5000)), Timeline.gaps(intervals))
        assertTrue(Timeline.gaps(emptyList()).isEmpty())
    }
    @Test fun exportedOpusTagsCarryAbsoluteTimeAcrossMultipleLacingBytes() {
        val pages = mutableListOf<ByteArray>()
        val encoder = object : PacketEncoder {
            override val lookahead = 104
            override fun encode(pcm: ByteArray) = ByteArray(40)
            override fun close() {}
        }
        OggOpus(encoder, 91) { page, _ -> pages += page }.use { it.append(ByteArray(640)) }
        val info = EncryptedAudio.Info(EncryptedAudio.Header(1_000_000, "session", "segment", "opus", true, 16000), 640, 0)
        val comments = Timeline.opusComments(info)
        val page = OggOpus.withComments(pages[1], comments)
        val lacing = page[26].toInt() and 255
        assertTrue(lacing > 1)
        val data = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        data.position(27 + lacing + 8)
        val vendorLength = data.int
        data.position(data.position() + vendorLength)
        val count = data.int
        val decoded = (0 until count).map { ByteArray(data.int).also { data.get(it) }.toString(Charsets.UTF_8) }
        assertTrue(decoded.contains("DAYTRACE_STARTED_AT_UTC_MS=1000000"))
        assertTrue(decoded.contains("DAYTRACE_ENDED_AT_UTC_MS=1000020"))
        assertEquals(comments.size, count)
        assertEquals(pages[1].copyOfRange(6, 22).toList(), page.copyOfRange(6, 22).toList())
    }
}
