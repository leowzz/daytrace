package com.daytrace.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class AnalysisBundleDeviceTest {
    @Test fun bundlePreservesGapsLegacyWavAndAuthenticatedOpusTimestamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = (context.applicationContext as DayTraceApp).store.key
        val pcm = File.createTempFile("bundle-pcm", ".dta", context.cacheDir)
        val opus = File.createTempFile("bundle-opus", ".dta", context.cacheDir)
        try {
            EncryptedAudio.Writer(pcm, key, EncryptedAudio.Header(1_700_000_000_000, "session", "pcm")).use {
                it.append(ByteArray(32000), 32000)
            }
            EncryptedAudio.Writer(opus, key, EncryptedAudio.Header(1_700_000_010_000, "session", "opus", "opus", true, 16000)).use { writer ->
                OggOpus(OpusEncoder(), 982) { page, bytes -> writer.appendPayload(page, bytes) }.use { ogg ->
                    repeat(50) { ogg.append(ByteArray(640)) }
                }
            }
            val output = ByteArrayOutputStream()
            AnalysisBundle.write(output, listOf(opus, pcm), key, "header\n".toByteArray(), ByteArray(0), 1_700_000_100_000)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
            }
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            val segments = manifest.getJSONArray("segments")
            assertEquals(2, segments.length())
            assertEquals("pcm", segments.getJSONObject(0).getString("segment_id"))
            assertEquals(1_700_000_010_000, segments.getJSONObject(1).getLong("start_at_utc_ms"))
            assertEquals(1_700_000_011_000, segments.getJSONObject(1).getLong("end_at_utc_ms"))
            val gap = manifest.getJSONArray("coverage_gaps").getJSONObject(0)
            assertEquals(1_700_000_001_000, gap.getLong("start_at_utc_ms"))
            assertEquals(1_700_000_010_000, gap.getLong("end_at_utc_ms"))
            assertEquals("unknown", gap.getString("cause"))
            repeat(segments.length()) { index ->
                val record = segments.getJSONObject(index)
                val bytes = entries.getValue(record.getString("audio_path"))
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(sha, record.getString("sha256"))
                assertTrue(record.isNull("transcript"))
            }
            assertEquals("RIFF", String(entries.getValue("audio/000000.wav"), 0, 4))
            assertTrue(entries.getValue("audio/000001.opus").toString(Charsets.ISO_8859_1).contains("DAYTRACE_STARTED_AT_UTC_MS=1700000010000"))
            File(context.filesDir, "codec-verification").mkdirs()
            File(context.filesDir, "codec-verification/analysis-test.zip").writeBytes(output.toByteArray())
        } finally { pcm.delete(); opus.delete() }
    }
}
