package com.daytrace.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey

/** Portable evidence bundle. Missing intervals are observations of export coverage, not claims about silence. */
object AnalysisBundle {
    fun write(output: OutputStream, files: List<File>, key: SecretKey,
        diagnostics: ByteArray, journal: ByteArray, generatedAt: Long = System.currentTimeMillis()) {
        val ordered = files.map { it to EncryptedAudio.scan(it) }.sortedBy { it.second.header.startedAt }
        val segments = JSONArray()
        val intervals = mutableListOf<Timeline.Interval>()
        val identifiers = mutableSetOf<String>()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(0) // Audio is already compressed; avoid extra CPU and keep streaming memory bounded.
            ordered.forEachIndexed { index, (file, info) ->
                check(identifiers.add(info.header.segmentId)) { "重复片段标识" }
                val extension = if (info.header.codec == "opus") "opus" else "wav"
                val path = "audio/${index.toString().padStart(6, '0')}.$extension"
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(path))
                val digestStream = DigestOutputStream(zip, digest)
                EncryptedAudio.export(file, key, digestStream)
                digestStream.flush()
                zip.closeEntry()
                val end = Timeline.absolute(info, info.durationMs)
                intervals += Timeline.Interval(info.header.startedAt, end)
                segments.put(JSONObject()
                    .put("segment_id", info.header.segmentId).put("session_id", info.header.sessionId)
                    .put("audio_path", path).put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                    .put("start_at_utc_ms", info.header.startedAt).put("end_at_utc_ms", end)
                    .put("start_at_utc", Instant.ofEpochMilli(info.header.startedAt).toString())
                    .put("duration_ms", info.durationMs).put("input_sample_count", info.pcmBytes / 2)
                    .put("input_sample_rate_hz", 16_000).put("channels", 1).put("codec", info.header.codec)
                    .put("vad_enabled", info.header.vad).put("bitrate_bps", info.header.bitrate)
                    .put("time_source", "device_wall_clock_plus_sample_offset")
                    .put("time_accuracy_ms", JSONObject.NULL).put("time_accuracy_status", "uncalibrated")
                    .put("timeline_mapping", "absolute_ms = start_at_utc_ms + decoded_audio_offset_ms")
                    .put("transcript_status", "not_transcribed")
                    .put("transcript", JSONObject.NULL))
            }
            val gaps = JSONArray()
            Timeline.gaps(intervals).forEach { gap ->
                gaps.put(JSONObject().put("start_at_utc_ms", gap.startMs).put("end_at_utc_ms", gap.endMs)
                    .put("status", "no_audio_in_export").put("cause", "unknown")
                    .put("note", "May be VAD filtering, pause, interruption, deletion, or no capture. Do not infer silence or absence of events."))
            }
            val manifest = JSONObject().put("schema", "daytrace.analysis_bundle").put("schema_version", 1)
                .put("generated_at_utc", Instant.ofEpochMilli(generatedAt).toString())
                .put("app_version", BuildConfig.VERSION_NAME).put("scope", "all_finalized_local_segments")
                .put("active_or_unrecoverable_files_included", false)
                .put("segments", segments).put("coverage_gaps", gaps)
                .put("coverage_before_first_or_after_last", "unknown")
                .put("asr_offset_basis", "decoded_audio_after_preskip")
                .put("asr_offsets_are_not_yet_available", true)
                .put("clock_note", "Device clock is not externally calibrated; UTC millisecond precision does not imply millisecond accuracy.")
                .put("context_policy", "Summaries must cite source segments and offsets, separate evidence from inference, and acknowledge coverage gaps.")
            fun entry(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            entry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            entry("diagnostics.csv", diagnostics)
            entry("capture-events.jsonl", journal)
            entry("README.txt", """
                DayTrace analysis bundle v1
                Audio files are decrypted standard Opus/WAV; keep this bundle private.
                manifest.json is the primary timeline index. Times are UTC.
                ASR must return sentence/word offsets into the decoded source audio.
                absolute_ms = segment.start_at_utc_ms + offset_ms
                Do not concatenate disjoint audio and treat its offsets as real elapsed time.
                Gaps do not prove silence. Logs may help explain gaps but are not complete evidence.
                OpusTags also carry segment/session/time fields; legacy WAV relies on this manifest.
                No transcript or AI summary has been generated by this app.
            """.trimIndent().toByteArray(Charsets.UTF_8))
        }
    }
}
