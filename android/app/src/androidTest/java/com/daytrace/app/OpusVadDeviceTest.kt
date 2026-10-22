package com.daytrace.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OpusVadDeviceTest {
    @Test fun existingMicrophoneOpusCanBeExported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as DayTraceApp).store
        store.refreshSegments()
        val segment = store.segments.value.firstOrNull { it.info.header.codec == "opus" }
        org.junit.Assume.assumeNotNull(segment)
        val file = File(context.filesDir, "codec-verification/real-microphone.opus")
        file.parentFile!!.mkdirs()
        file.outputStream().use { EncryptedAudio.export(segment!!.file, store.key, it) }
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath); player.prepare()
            assertTrue(kotlin.math.abs(player.duration - segment!!.info.durationMs) <= 25)
        } finally { player.release() }
    }

    @Test fun realWebRtcFiltersSpeechAndNativeOpusProducesPlayableOgg() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as DayTraceApp).store
        val fixture = instrumentation.context.assets.open("speech-16k.pcm").use { it.readBytes() }
        val speechFrames = fixture.asList().chunked(640).filter { it.size == 640 }.map { it.toByteArray() }
        val silence = List(250) { ByteArray(640) }
        val frames = silence + speechFrames + silence
        WebRtcDetector().use { detector -> repeat(200) { assertFalse(detector.isSpeech(ByteArray(640))) } }
        var recognized = 0
        WebRtcDetector().use { detector -> speechFrames.forEach { if (detector.isSpeech(it)) recognized++ } }
        assertTrue("Actual WebRTC must recognize the upstream speech fixture", recognized >= 20)
        val resultDirectory = File(context.filesDir, "codec-verification").apply { mkdirs() }
        val counts = mutableListOf<Long>()
        val sizes = mutableListOf<Long>()
        for (enabled in listOf(false, true)) {
            val name = if (enabled) "voice" else "continuous"
            var file: File? = null
            var writer: EncryptedAudio.Writer? = null
            var ogg: OggOpus? = null
            var index = 0
            var totalSize = 0L
            val sink = object : SegmentSink {
                override fun start(firstFrame: Long) {
                    file = File(context.cacheDir, "test-$name-${index++}.dta")
                    writer = EncryptedAudio.Writer(file!!, store.key, EncryptedAudio.Header(
                        1_700_000_000_000L + firstFrame * 20, "test", "$name-$index", "opus", enabled, 16000))
                    ogg = OggOpus(OpusEncoder(), index) { page, bytes -> writer!!.appendPayload(page, bytes) }
                }
                override fun append(pcm: ByteArray) { ogg!!.append(pcm) }
                override fun end() {
                    ogg!!.close(); writer!!.close()
                    val info = EncryptedAudio.scan(file!!, store.key)
                    assertTrue(info.durationMs > 0)
                    val exported = File(resultDirectory, "$name-$index.opus")
                    exported.outputStream().use { EncryptedAudio.export(file!!, store.key, it) }
                    totalSize += exported.length()
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(exported.absolutePath)
                        assertEquals(1, extractor.trackCount)
                        assertEquals("audio/opus", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                    } finally { extractor.release() }
                    val player = MediaPlayer()
                    try {
                        player.setDataSource(exported.absolutePath); player.prepare()
                        assertTrue("Playable duration must match source", kotlin.math.abs(player.duration - info.durationMs) <= 25)
                    } finally { player.release() }
                    file!!.delete()
                }
            }
            val segmenter = VoiceSegmenter(enabled, sink)
            WebRtcDetector().use { detector -> frames.forEach { segmenter.accept(it, enabled && detector.isSpeech(it)) } }
            segmenter.finish()
            counts += segmenter.retainedFrames
            sizes += totalSize
        }
        assertEquals(frames.size.toLong(), counts[0])
        assertTrue(counts[1] in 20 until counts[0])
        assertTrue(sizes[1] < sizes[0])
        assertTrue("16kbps Opus should be much smaller than PCM", sizes[0] < frames.size * 640 / 8)
        File(resultDirectory, "results.json").writeText("""{"inputFrames":${frames.size},"speechFrames":$recognized,"retainedFrames":$counts,"oggBytes":$sizes}""")
    }
}
