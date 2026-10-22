package com.daytrace.app

data class VadConfig(val preRollMs: Int = 300, val silenceMs: Int = 600,
    val minimumSpeechMs: Int = 400, val maximumSegmentMs: Int = 30_000) {
    init {
        require(preRollMs >= 0 && silenceMs > 0 && minimumSpeechMs > 0)
        require(maximumSegmentMs > preRollMs + minimumSpeechMs + silenceMs)
        require(listOf(preRollMs, silenceMs, minimumSpeechMs, maximumSegmentMs).all { it % 20 == 0 })
    }
}

interface SegmentSink {
    fun start(firstFrame: Long)
    fun append(pcm: ByteArray)
    fun end()
}

/** Timestamped 20 ms frames; bounded pre-roll/candidate buffer; no discarded silence concatenated into speech. */
class VoiceSegmenter(private val enabled: Boolean, private val sink: SegmentSink, private val config: VadConfig = VadConfig()) {
    private data class Frame(val index: Long, val pcm: ByteArray)
    private val preRoll = ArrayDeque<Frame>()
    private val candidate = mutableListOf<Frame>()
    private var speechFrames = 0
    private var silentFrames = 0
    private var segmentFrames = 0
    private var opened = false
    private var continuation = false
    private var nextIndex = 0L
    var retainedFrames = 0L
        private set
    fun accept(pcm: ByteArray, speech: Boolean) {
        require(pcm.size == 640)
        val frame = Frame(nextIndex++, pcm)
        if (!enabled) {
            if (!opened) { sink.start(frame.index); opened = true }
            emit(frame)
            if (segmentFrames >= config.maximumSegmentMs / 20) endSegment()
            return
        }
        if (!opened && candidate.isEmpty() && !continuation && !speech) {
            preRoll.addLast(frame)
            while (preRoll.size > config.preRollMs / 20) preRoll.removeFirst()
            return
        }
        if (speech) { speechFrames++; silentFrames = 0 } else silentFrames++
        if (!opened) {
            if (candidate.isEmpty()) { candidate.addAll(preRoll); preRoll.clear() }
            candidate += frame
            if (continuation || speechFrames >= config.minimumSpeechMs / 20) {
                sink.start(candidate.first().index); opened = true; continuation = false
                candidate.forEach(::emit); candidate.clear()
            }
        } else emit(frame)
        if (silentFrames >= config.silenceMs / 20) {
            // Rejected short candidates need no file. Keep their final silence as future pre-roll.
            if (!opened) candidate.takeLast(config.preRollMs / 20).forEach { preRoll.addLast(it) }
            endSegment(); continuation = false; candidate.clear(); speechFrames = 0; silentFrames = 0
        } else if (opened && segmentFrames >= config.maximumSegmentMs / 20) {
            endSegment(); continuation = true
        }
    }
    private fun emit(frame: Frame) { sink.append(frame.pcm); segmentFrames++; retainedFrames++ }
    private fun endSegment() { if (opened) sink.end(); opened = false; segmentFrames = 0 }
    fun finish() { endSegment(); candidate.clear(); preRoll.clear(); continuation = false; speechFrames = 0; silentFrames = 0 }
}
