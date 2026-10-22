package com.daytrace.app

import java.time.Instant

/** Offsets refer to decoded audio after Opus pre-skip, never to container granule time. */
object Timeline {
    data class Interval(val startMs: Long, val endMs: Long) {
        init { require(endMs >= startMs) }
    }
    fun absolute(info: EncryptedAudio.Info, offsetMs: Long): Long {
        require(offsetMs in 0..info.durationMs) { "转写偏移超出音频范围" }
        return Math.addExact(info.header.startedAt, offsetMs)
    }
    fun gaps(intervals: List<Interval>): List<Interval> {
        val sorted = intervals.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        var end = sorted.first().endMs
        val result = mutableListOf<Interval>()
        sorted.drop(1).forEach {
            if (it.startMs > end) result += Interval(end, it.startMs)
            end = maxOf(end, it.endMs)
        }
        return result
    }
    fun opusComments(info: EncryptedAudio.Info) = linkedMapOf(
        "DATE" to Instant.ofEpochMilli(info.header.startedAt).toString(),
        "DAYTRACE_SCHEMA_VERSION" to "1",
        "DAYTRACE_SEGMENT_ID" to info.header.segmentId,
        "DAYTRACE_SESSION_ID" to info.header.sessionId,
        "DAYTRACE_STARTED_AT_UTC_MS" to info.header.startedAt.toString(),
        "DAYTRACE_ENDED_AT_UTC_MS" to absolute(info, info.durationMs).toString(),
        "DAYTRACE_DURATION_MS" to info.durationMs.toString(),
        "DAYTRACE_INPUT_SAMPLE_RATE_HZ" to "16000",
        "DAYTRACE_VAD_ENABLED" to info.header.vad.toString(),
        "DAYTRACE_BITRATE_BPS" to info.header.bitrate.toString(),
        "DAYTRACE_TIME_SOURCE" to "device_wall_clock_plus_sample_offset",
        "DAYTRACE_TIME_ACCURACY" to "uncalibrated",
        "DAYTRACE_OFFSET_BASIS" to "decoded_audio_after_preskip"
    )
}
