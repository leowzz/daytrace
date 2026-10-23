package com.daytrace.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UploadProtocol {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size == -1) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun metadata(info: EncryptedAudio.Info, sourceName: String, audioHash: String): JSONObject {
        val h = info.header
        return JSONObject().put("schema_version", 1).put("segment_id", h.segmentId).put("session_id", h.sessionId)
            .put("started_at_utc_ms", h.startedAt).put("end_at_utc_ms", Timeline.absolute(info, info.durationMs))
            .put("duration_ms", info.durationMs).put("codec", h.codec)
            .put("container", if (h.codec == "opus") "ogg" else "wav")
            .put("capture_mode", if (h.vad) "vad" else "continuous").put("vad_enabled", h.vad)
            .put("bitrate_kbps", h.bitrate / 1000).put("input_sample_rate_hz", EncryptedAudio.SAMPLE_RATE)
            .put("input_sample_count", info.pcmBytes / 2).put("channels", 1)
            .put("source_ref", "android://daytrace/${h.sessionId}/$sourceName").put("sha256", audioHash)
            .put("time_source", "device_wall_clock_plus_sample_offset").put("time_accuracy_status", "uncalibrated")
            .put("timeline_mapping", "absolute_ms = started_at_utc_ms + decoded_audio_offset_ms")
            .put("coverage_outside_segment", "unknown")
    }

    fun upload(address: String, audio: File, metadata: JSONObject) {
        val mime = if (metadata.getString("codec") == "opus") "audio/ogg" else "audio/wav"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadata.toString())
            .addFormDataPart("audio", audio.name, audio.asRequestBody(mime.toMediaType())).build()
        val json = send(Request.Builder().url("${ServerAddress.normalize(address)}/v1/audio/segments").post(body).build())
        check(json.optString("segment_id") == metadata.getString("segment_id") &&
            json.optString("session_id") == metadata.getString("session_id") &&
            json.optLong("started_at_utc_ms", -1) == metadata.getLong("started_at_utc_ms") &&
            json.optLong("duration_ms", -1) == metadata.getLong("duration_ms") &&
            json.optString("sha256") == metadata.getString("sha256") && json.optString("status") == "completed") {
            "服务响应未确认片段、时间或校验值；未标记上传成功，请重试。"
        }
        val stored = json.optJSONObject("metadata")
        check(stored != null && metadata.keys().asSequence().all { stored.opt(it)?.toString() == metadata.get(it).toString() }) {
            "服务未确认完整采集元数据，请更新服务端后重试。"
        }
    }

    fun uploadEvents(address: String, sourceId: String, events: JSONArray) {
        val body = JSONObject().put("source_id", sourceId).put("events", events).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val json = send(Request.Builder().url("${ServerAddress.normalize(address)}/v1/capture-events").post(body).build())
        val acknowledged = json.optJSONArray("event_ids") ?: error("服务未确认采集事件。")
        check(acknowledged.length() == events.length() && (0 until events.length()).all {
            acknowledged.optString(it) == events.getJSONObject(it).getString("event_id")
        }) { "采集事件回执不匹配，请重试。" }
    }

    private fun send(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        // Avoid retaining long transcripts or unexpected HTML pages in memory/logs.
        val body = response.peekBody(2 * 1024 * 1024).string()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (!response.isSuccessful) {
            val detail = (json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: json?.optString("detail")?.takeIf { it.isNotBlank() })?.take(400).orEmpty()
            error("HTTP ${response.code}${if (detail.isBlank()) "" else "：$detail"}。可修复后重试；服务端可能已保存音频。")
        }
        json ?: error("服务响应不是有效 JSON；未确认上传结果。")
    }
}
