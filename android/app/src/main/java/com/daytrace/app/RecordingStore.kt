package com.daytrace.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.UUID
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

enum class Phase { IDLE, STARTING, RECORDING, STOPPING, PAUSED, INTERRUPTED }
data class RecordingState(
    val phase: Phase = Phase.IDLE,
    val sessionId: String = "",
    val startedAt: Long = 0,
    val capturedMs: Long = 0,
    val retainedMs: Long = 0,
    val voiceMs: Long = 0,
    val vadSpeech: Boolean = false,
    val interruptions: Int = 0,
    val level: Float = 0f,
    val silenced: Boolean = false,
    val message: String = "准备好，记录此刻。"
)
data class Segment(val file: File, val info: EncryptedAudio.Info)
data class AudioSettings(val vadEnabled: Boolean = true, val bitrate: Int = 16_000, val vadMode: Int = 2,
    val vadConfig: VadConfig = VadConfig())

class DayTraceApp : Application() {
    lateinit var store: RecordingStore
        private set
    lateinit var logs: AppLogs
        private set
    lateinit var uploads: UploadManager
        private set
    lateinit var serverPreferences: ServerPreferences
        private set
    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(this)
        logs = AppLogs(File(filesDir, "app-events.jsonl"), store.captureEvents)
        serverPreferences = ServerPreferences(this)
        uploads = UploadManager(this, store, logs)
    }
}

class RecordingStore(private val context: Context) {
    val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    val diagnostics = File(context.filesDir, "diagnostics.csv")
    val captureEvents = File(context.filesDir, "capture-events.jsonl")
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)
    private val settingsState = MutableStateFlow(AudioSettings(prefs.getBoolean("vadEnabled", true),
        prefs.getInt("bitrate", 16_000), prefs.getInt("vadMode", 2), runCatching {
            VadConfig(prefs.getInt("preRollMs", 300), prefs.getInt("silenceMs", 600),
                prefs.getInt("minimumSpeechMs", 400), prefs.getInt("maximumSegmentMs", 30_000))
        }.getOrDefault(VadConfig())))
    val settings = settingsState.asStateFlow()
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    private val segmentsFlow = MutableStateFlow<List<Segment>>(emptyList())
    val segments = segmentsFlow.asStateFlow()
    val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("daytrace-audio-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("daytrace-audio-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    init {
        // Temporary decrypted playback files never survive an application restart.
        context.cacheDir.listFiles()?.filter { it.name.startsWith("play-") }?.forEach { it.delete() }
        val previous = runCatching { Phase.valueOf(prefs.getString("phase", "IDLE")!!) }.getOrDefault(Phase.IDLE)
        val unexpected = previous in setOf(Phase.STARTING, Phase.RECORDING, Phase.STOPPING)
        mutable.value = RecordingState(
            phase = if (unexpected) Phase.INTERRUPTED else previous,
            sessionId = prefs.getString("sessionId", "") ?: "",
            startedAt = prefs.getLong("startedAt", 0),
            capturedMs = prefs.getLong("capturedMs", 0),
            retainedMs = prefs.getLong("retainedMs", prefs.getLong("capturedMs", 0)),
            voiceMs = prefs.getLong("voiceMs", 0),
            interruptions = prefs.getInt("interruptions", 0) + if (unexpected) 1 else 0,
            message = if (unexpected) "上次录音被系统终止，已保留落盘音频。请手动重新开始。" else prefs.getString("message", "准备好，记录此刻。")!!
        )
        var recoveryFailed = false
        directory.listFiles()?.filter { it.extension == "part" }?.forEach { file ->
            runCatching {
                val info = EncryptedAudio.scan(file, key, allowPartial = true)
                RandomAccessFile(file, "rw").use { it.setLength(info.validLength) }
                check(file.renameTo(File(directory, file.nameWithoutExtension + ".dta")))
                event("RECOVERED_SEGMENT", data = mapOf("segment_id" to info.header.segmentId,
                    "start_at_utc_ms" to info.header.startedAt, "recovered_duration_ms" to info.durationMs))
            }.onFailure { recoveryFailed = true }
        }
        refreshSegments()
        val recoveredMs = segmentsFlow.value.filter { it.info.header.sessionId == mutable.value.sessionId }.sumOf { it.info.durationMs }
        mutable.value = mutable.value.copy(capturedMs = maxOf(mutable.value.capturedMs, recoveredMs), retainedMs = recoveredMs)
        if (recoveryFailed) mutable.value = mutable.value.copy(message = "部分中断文件恢复失败，原文件已保留。可导出诊断日志。")
        persist()
        if (unexpected) event("PROCESS_RESTART_AFTER_INTERRUPTION")
    }

    @Synchronized fun update(transform: (RecordingState) -> RecordingState, save: Boolean = false) {
        mutable.value = transform(mutable.value)
        if (save) persist()
    }
    @Synchronized private fun persist() {
        val s = mutable.value
        prefs.edit().putString("phase", s.phase.name).putString("sessionId", s.sessionId)
            .putLong("startedAt", s.startedAt).putLong("capturedMs", s.capturedMs)
            .putLong("retainedMs", s.retainedMs).putLong("voiceMs", s.voiceMs)
            .putInt("interruptions", s.interruptions).putString("message", s.message).commit()
        // Human/machine-readable snapshot for reproducible ADB checks. Never contains audio.
        val status = JSONObject().put("phase", s.phase.name).put("sessionId", s.sessionId)
            .put("startedAt", s.startedAt).put("capturedMs", s.capturedMs)
            .put("retainedMs", s.retainedMs).put("voiceMs", s.voiceMs).put("vadSpeech", s.vadSpeech)
            .put("vadEnabled", settingsState.value.vadEnabled).put("bitrate", settingsState.value.bitrate)
            .put("vadTiming", JSONObject(settingsState.value.vadConfig.eventData()))
            .put("interruptions", s.interruptions).put("silenced", s.silenced).put("message", s.message)
        val temp = File(context.filesDir, "status.tmp").apply { writeText(status.toString(2)) }
        check(temp.renameTo(File(context.filesDir, "status.json")))
    }
    fun begin() {
        update({ s ->
            if (s.phase == Phase.PAUSED) s.copy(phase = Phase.STARTING, message = "正在恢复录音…")
            else RecordingState(phase = Phase.STARTING, sessionId = UUID.randomUUID().toString(), startedAt = System.currentTimeMillis(), message = "正在启动麦克风…")
        }, true)
    }
    @Synchronized fun configure(value: AudioSettings) {
        check(state.value.phase !in setOf(Phase.STARTING, Phase.RECORDING, Phase.STOPPING))
        require(value.bitrate in listOf(12_000, 16_000, 24_000, 32_000) && value.vadMode in 0..3)
        settingsState.value = value
        prefs.edit().putBoolean("vadEnabled", value.vadEnabled).putInt("bitrate", value.bitrate)
            .putInt("vadMode", value.vadMode).putInt("preRollMs", value.vadConfig.preRollMs)
            .putInt("silenceMs", value.vadConfig.silenceMs).putInt("minimumSpeechMs", value.vadConfig.minimumSpeechMs)
            .putInt("maximumSegmentMs", value.vadConfig.maximumSegmentMs).apply()
        persist()
    }
    fun refreshSegments() {
        segmentsFlow.value = directory.listFiles()?.filter { it.extension == "dta" }?.mapNotNull { file ->
            runCatching { Segment(file, EncryptedAudio.scan(file)) }.getOrNull()
        }?.sortedByDescending { it.info.header.startedAt } ?: emptyList()
    }
    fun finish(writer: EncryptedAudio.Writer, file: File) {
        writer.close()
        if (writer.pcmBytes == 0L) file.delete()
        else check(file.renameTo(File(directory, file.nameWithoutExtension + ".dta"))) { "无法完成分段保存" }
        refreshSegments()
    }
    fun delete(segment: Segment) {
        check(segment.file.delete()) { "删除失败" }
        event("DELETED_SEGMENT", data = mapOf("segment_id" to segment.info.header.segmentId,
            "segment_session_id" to segment.info.header.sessionId, "start_at_utc_ms" to segment.info.header.startedAt,
            "end_at_utc_ms" to Timeline.absolute(segment.info, segment.info.durationMs)))
        refreshSegments()
    }
    fun storageBytes() = directory.listFiles()?.sumOf { it.length() } ?: 0L
    @Synchronized fun event(name: String, detail: String = "", data: Map<String, Any?> = emptyMap()) {
        if (!diagnostics.exists()) diagnostics.writeText("timestamp_utc_ms,event,session_id,captured_ms,interruptions,detail\n")
        val s = mutable.value
        val observedAt = System.currentTimeMillis()
        diagnostics.appendText("$observedAt,$name,${s.sessionId},${s.capturedMs},${s.interruptions},\"${detail.replace("\"", "\"\"").replace('\n', ' ')}\"\n")
        captureEvents.appendText(JSONObject().put("schema_version", 1).put("event", name)
            .put("observed_at_utc_ms", observedAt).put("observed_elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("device_timezone_at_event", ZoneId.systemDefault().id).put("session_id", s.sessionId)
            .put("captured_ms", s.capturedMs).put("retained_ms", s.retainedMs).put("silenced", s.silenced)
            .put("detail", detail).put("data", JSONObject(data)).toString() + "\n")
    }

    @Synchronized fun exportLogs(): Pair<ByteArray, ByteArray> =
        (if (diagnostics.exists()) diagnostics.readBytes() else ByteArray(0)) to
            (if (captureEvents.exists()) captureEvents.readBytes() else ByteArray(0))
}
