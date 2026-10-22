package com.daytrace.app

data class VadConfig(val preRollMs: Int = 300, val silenceMs: Int = 600,
    val minimumSpeechMs: Int = 400, val maximumSegmentMs: Int = 30_000) {
    init {
        require(validationError(preRollMs, silenceMs, minimumSpeechMs, maximumSegmentMs) == null)
    }

    fun eventData(): Map<String, Int> = mapOf("pre_roll_ms" to preRollMs,
        "silence_end_ms" to silenceMs, "minimum_speech_ms" to minimumSpeechMs,
        "maximum_segment_ms" to maximumSegmentMs, "frame_duration_ms" to 20)

    companion object {
        fun validationError(preRollMs: Int?, silenceMs: Int?, minimumSpeechMs: Int?, maximumSegmentMs: Int?): String? = when {
            preRollMs == null || silenceMs == null || minimumSpeechMs == null || maximumSegmentMs == null -> "请填写四个有效的整数（ms）。"
            preRollMs !in 0..5_000 -> "前置缓存范围为 0–5000 ms。"
            silenceMs !in 20..5_000 -> "静音结束间隔范围为 20–5000 ms。"
            minimumSpeechMs !in 20..2_000 -> "最短累计人声范围为 20–2000 ms。"
            maximumSegmentMs !in 20..300_000 -> "最大片段时长范围为 20–300000 ms。"
            listOf(preRollMs, silenceMs, minimumSpeechMs, maximumSegmentMs).any { it % 20 != 0 } -> "所有时长必须是 20 ms 的整数倍。"
            maximumSegmentMs <= preRollMs + minimumSpeechMs + silenceMs -> "最大片段时长需大于前置缓存、最短累计人声与静音结束间隔之和。"
            else -> null
        }
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
    private fun emit(frame: Frame) {
        // An intermittent candidate may already exceed the file limit when it qualifies.
        // Split at source frame boundaries, including while flushing that buffered candidate.
        if (segmentFrames >= config.maximumSegmentMs / 20) endSegment()
        if (!opened) { sink.start(frame.index); opened = true }
        sink.append(frame.pcm); segmentFrames++; retainedFrames++
    }
    private fun endSegment() { if (opened) sink.end(); opened = false; segmentFrames = 0 }
    fun finish() { endSegment(); candidate.clear(); preRoll.clear(); continuation = false; speechFrames = 0; silentFrames = 0 }
}
