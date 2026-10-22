package com.daytrace.app

import java.io.Closeable

internal object NativeAudio {
    init { System.loadLibrary("daytrace_audio") }
    external fun createEncoder(bitrate: Int): Long
    external fun lookahead(handle: Long): Int
    external fun encode(handle: Long, pcm: ByteArray): ByteArray
    external fun destroyEncoder(handle: Long)
    external fun createVad(mode: Int): Long
    external fun isSpeech(handle: Long, pcm: ByteArray): Boolean
    external fun destroyVad(handle: Long)
}

interface SpeechDetector : Closeable { fun isSpeech(pcm: ByteArray): Boolean }
class WebRtcDetector(mode: Int = 2) : SpeechDetector {
    private var handle = NativeAudio.createVad(mode)
    override fun isSpeech(pcm: ByteArray): Boolean { check(handle != 0L); return NativeAudio.isSpeech(handle, pcm) }
    override fun close() { NativeAudio.destroyVad(handle); handle = 0 }
}

interface PacketEncoder : Closeable {
    val lookahead: Int
    fun encode(pcm: ByteArray): ByteArray
}
class OpusEncoder(bitrate: Int = 16_000) : PacketEncoder {
    private var handle = NativeAudio.createEncoder(bitrate)
    override val lookahead = NativeAudio.lookahead(handle)
    override fun encode(pcm: ByteArray): ByteArray { check(handle != 0L); return NativeAudio.encode(handle, pcm) }
    override fun close() { NativeAudio.destroyEncoder(handle); handle = 0 }
}
