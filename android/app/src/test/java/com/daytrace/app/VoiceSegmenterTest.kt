package com.daytrace.app

import org.junit.Assert.*
import org.junit.Test

class VoiceSegmenterTest {
    private class Sink : SegmentSink {
        val starts = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        var count = 0
        override fun start(firstFrame: Long) { starts += firstFrame; count = 0 }
        override fun append(pcm: ByteArray) { count++ }
        override fun end() { sizes += count }
    }
    private val frame = ByteArray(640)
    @Test fun silenceProducesNoFiles() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink)
        repeat(10_000) { segmenter.accept(frame, false) }; segmenter.finish()
        assertTrue(sink.starts.isEmpty())
    }
    @Test fun shortSpeechIsRejectedWithoutDiscardingFuturePreRoll() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink)
        repeat(19) { segmenter.accept(frame, true) }
        repeat(30) { segmenter.accept(frame, false) }
        assertTrue(sink.starts.isEmpty())
        repeat(20) { segmenter.accept(frame, true) }; segmenter.finish()
        assertEquals(listOf(34L), sink.starts)
        assertEquals(listOf(35), sink.sizes)
    }
    @Test fun preRollTailAndRealTimelineArePreserved() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink)
        repeat(100) { segmenter.accept(frame, false) }
        repeat(20) { segmenter.accept(frame, true) }
        repeat(100) { segmenter.accept(frame, false) }
        repeat(20) { segmenter.accept(frame, true) }
        repeat(30) { segmenter.accept(frame, false) }; segmenter.finish()
        assertEquals(listOf(85L, 205L), sink.starts)
        assertEquals(listOf(65, 65), sink.sizes)
        assertEquals(130L, segmenter.retainedFrames)
    }
    @Test fun continuousModeKeepsEveryFrameAcrossBoundaries() {
        val sink = Sink(); val segmenter = VoiceSegmenter(false, sink)
        repeat(3017) { segmenter.accept(frame, false) }; segmenter.finish()
        assertEquals(listOf(0L, 1500L, 3000L), sink.starts)
        assertEquals(listOf(1500, 1500, 17), sink.sizes)
    }
    @Test fun longUtteranceRetainsShortContinuationAfterSplit() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink)
        repeat(1507) { segmenter.accept(frame, true) }; segmenter.finish()
        assertEquals(listOf(1500, 7), sink.sizes)
    }
    @Test fun pauseDiscardsOnlyUnconfirmedCandidate() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink)
        repeat(20) { segmenter.accept(frame, true) }; segmenter.finish()
        assertEquals(listOf(20), sink.sizes)
    }
    @Test fun configuredThresholdKeepsBriefReplyWithConfiguredContextAndRealTime() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink, VadConfig(40, 60, 40, 200))
        repeat(10) { segmenter.accept(frame, false) }
        segmenter.accept(frame, true) // 20 ms is below the configured 40 ms minimum.
        repeat(3) { segmenter.accept(frame, false) }
        assertTrue(sink.starts.isEmpty())
        repeat(2) { segmenter.accept(frame, true) }
        repeat(3) { segmenter.accept(frame, false) }; segmenter.finish()
        assertEquals(listOf(12L), sink.starts)
        assertEquals(listOf(7), sink.sizes) // 40 ms pre-roll + 40 ms speech + 60 ms tail.
    }
    @Test fun configuredMaximumAlsoAppliesToContinuousMode() {
        val sink = Sink(); val segmenter = VoiceSegmenter(false, sink, VadConfig(40, 60, 40, 200))
        repeat(23) { segmenter.accept(frame, false) }; segmenter.finish()
        assertEquals(listOf(0L, 10L, 20L), sink.starts)
        assertEquals(listOf(10, 10, 3), sink.sizes)
    }
    @Test fun intermittentCandidateCannotExceedConfiguredFileLimitOrLoseFrames() {
        val sink = Sink(); val segmenter = VoiceSegmenter(true, sink, VadConfig(0, 100, 60, 180))
        repeat(2) {
            segmenter.accept(frame, true)
            repeat(4) { segmenter.accept(frame, false) }
        }
        segmenter.accept(frame, true)
        repeat(5) { segmenter.accept(frame, false) }; segmenter.finish()
        assertEquals(listOf(0L, 9L), sink.starts)
        assertEquals(listOf(9, 7), sink.sizes)
        assertEquals(16L, segmenter.retainedFrames)
    }
    @Test fun unsafeAndSubframeTimingsAreRejectedBeforeRecording() {
        assertNotNull(VadConfig.validationError(null, 600, 400, 30_000))
        assertNotNull(VadConfig.validationError(301, 600, 400, 30_000))
        assertNotNull(VadConfig.validationError(-20, 600, 400, 30_000))
        assertNotNull(VadConfig.validationError(300, 0, 400, 30_000))
        assertNotNull(VadConfig.validationError(300, 600, 400, 1300))
        assertNotNull(VadConfig.validationError(Int.MAX_VALUE, 600, 400, 30_000))
        assertNull(VadConfig.validationError(0, 20, 20, 60))
        assertTrue(runCatching { VadConfig(maximumSegmentMs = 1300) }.isFailure)
    }
}
