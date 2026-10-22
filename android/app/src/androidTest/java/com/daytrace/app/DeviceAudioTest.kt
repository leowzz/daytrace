package com.daytrace.app

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after recording on the device: verifies real encrypted microphone data and Android WAV decoding. */
@RunWith(AndroidJUnit4::class)
class DeviceAudioTest {
    @Test fun savedMicrophoneAudioDecryptsAndDecodes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as DayTraceApp).store
        store.refreshSegments()
        val segments = store.segments.value
        assertTrue("Record at least one segment before running this device test", segments.isNotEmpty())
        val segment = segments.first { it.info.header.codec == "pcm" }
        val wav = File(context.cacheDir, "verified-audio.wav")
        wav.outputStream().use { EncryptedAudio.exportWav(segment.file, store.key, it) }
        assertEquals(segment.info.pcmBytes + 44, wav.length())
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(wav.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(segment.info.durationMs * 1000, format.getLong(MediaFormat.KEY_DURATION))
            val bytes = wav.readBytes()
            assertTrue("Microphone must contain real, non-zero samples", bytes.drop(44).any { it != 0.toByte() })
            // Retain an explicitly exported test copy for independent desktop WAV validation.
            wav.copyTo(File(context.filesDir, "verified-audio.wav"), overwrite = true)
        } finally { extractor.release(); wav.delete() }
    }
}
