package com.daytrace.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** The visible Activity starts this service; START_NOT_STICKY deliberately requires a manual restart after death. */
class RecordingService : Service() {
    companion object {
        const val START = "com.daytrace.START"
        const val PAUSE = "com.daytrace.PAUSE"
        const val STOP = "com.daytrace.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
    }
    private val store get() = (application as DayTraceApp).store
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var recorder: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private val captureDone = AtomicBoolean(false)
    private var busy = false
    @Volatile private var terminalPhase = Phase.IDLE
    @Volatile private var failure: String? = null
    private val queue = ArrayBlockingQueue<ByteArray>(250) // Five seconds; bounded, never silently drops audio.
    private var callback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var lastSampleAt = 0L
    @Volatile private var captureStartedAt = 0L
    @Volatile private var captureStartedElapsedMs = 0L
    private var audioSettings = AudioSettings()
    private var captureRunId = ""

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "持续录音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "录音时持续显示；可以随时暂停或停止"
                setSound(null, null)
            })
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!busy) startRecording()
            PAUSE -> requestStop(Phase.PAUSED)
            STOP -> requestStop(Phase.IDLE)
            else -> if (!busy) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun command(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("DayTrace · 正在录音")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "暂停", command(PAUSE, 1)).addAction(0, "停止", command(STOP, 2)).build()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            store.update({ it.copy(phase = Phase.INTERRUPTED, message = "需要麦克风权限，请回到应用开启。") }, true)
            stopSelf(); return
        }
        try {
            startForeground(NOTIFICATION, notification("正在启动 · 本地加密保存"),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            store.begin()
            audioSettings = store.settings.value
            captureRunId = UUID.randomUUID().toString()
            // Held only while capturing/draining. A watchdog and all terminal paths release it.
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DayTrace:Capture").apply {
                setReferenceCounted(false)
                acquire(120_000L)
            }
            queue.clear(); captureDone.set(false); failure = null; terminalPhase = Phase.IDLE
            running.set(true); busy = true; lastSampleAt = SystemClock.elapsedRealtime()
            Thread({ writeLoop() }, "daytrace-storage").start()
            Thread({ captureLoop() }, "daytrace-capture").start()
            main.postDelayed(watchdog, 10_000)
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            running.set(false)
            if (!busy) finishService()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (SystemClock.elapsedRealtime() - lastSampleAt > 15_000) {
                failure = "麦克风超过 15 秒没有返回数据，请重新开始。"
                requestStop(Phase.INTERRUPTED)
            } else {
                wakeLock?.acquire(120_000L)
                main.postDelayed(this, 10_000)
            }
        }
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var audio: AudioRecord? = null
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) error("麦克风权限已撤销")
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16kHz 录音" }
            audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, 32_000)).build()
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder = audio
            val recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val silenced = configs.firstOrNull { it.clientAudioSessionId == recorder?.audioSessionId }?.isClientSilenced ?: false
                    if (silenced != store.state.value.silenced) {
                        store.update({ it.copy(silenced = silenced, interruptions = it.interruptions + if (silenced) 1 else 0,
                            message = if (silenced) "麦克风被系统静音（可能通话、其他应用或隐私开关），此期间可能只有静音。" else "持续采音中 · 音频仅保存在本机") }, true)
                        store.event(if (silenced) "MIC_SILENCED" else "MIC_RESTORED")
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(store.state.value.message))
                    }
                }
            }
            callback = recordingCallback
            audio.registerAudioRecordingCallback(main::post, recordingCallback)
            if (!running.get()) return
            audio.startRecording()
            captureStartedElapsedMs = SystemClock.elapsedRealtime()
            captureStartedAt = System.currentTimeMillis()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
            store.update({ if (it.phase == Phase.STARTING) it.copy(phase = Phase.RECORDING, message = "持续采音中 · 音频仅保存在本机") else it }, true)
            val systemOpus = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findEncoderForFormat(MediaFormat.createAudioFormat("audio/opus", 16_000, 1)) }.getOrNull()
            store.event("START", "encoder=libopus-1.6.1;system_opus=$systemOpus;vad=${audioSettings.vadEnabled};bitrate=${audioSettings.bitrate}",
                mapOf("capture_run_id" to captureRunId, "capture_origin_utc_ms" to captureStartedAt,
                    "capture_origin_elapsed_realtime_ms" to captureStartedElapsedMs,
                    "time_source" to "device_wall_clock_plus_sample_offset", "vad_enabled" to audioSettings.vadEnabled,
                    "vad_mode" to audioSettings.vadMode, "bitrate_bps" to audioSettings.bitrate,
                    "pre_roll_ms" to audioSettings.vadConfig.preRollMs,
                    "silence_end_ms" to audioSettings.vadConfig.silenceMs,
                    "minimum_speech_ms" to audioSettings.vadConfig.minimumSpeechMs,
                    "maximum_segment_ms" to audioSettings.vadConfig.maximumSegmentMs,
                    "frame_duration_ms" to 20))
            while (running.get()) {
                val frame = ByteArray(640) // 20 ms, 16 kHz, mono PCM16
                var count = 0
                while (count < frame.size && running.get()) {
                    val read = audio.read(frame, count, frame.size - count, AudioRecord.READ_BLOCKING)
                    if (!running.get()) break
                    check(read > 0 && read % 2 == 0) { "麦克风读取失败：$read" }
                    count += read
                    lastSampleAt = SystemClock.elapsedRealtime()
                }
                if (!running.get()) break
                check(queue.offer(frame, 200, TimeUnit.MILLISECONDS)) {
                    "存储速度不足，已停止以避免静默丢失音频"
                }
            }
        } catch (e: Exception) {
            if (running.get()) failure = e.message ?: "录音异常"
            running.set(false)
        } finally {
            callback?.let { cb -> runCatching { audio?.unregisterAudioRecordingCallback(cb) } }
            callback = null
            runCatching { audio?.stop() }
            audio?.release(); recorder = null
            captureDone.set(true)
        }
    }

    private fun writeLoop() {
        var writer: EncryptedAudio.Writer? = null
        var file: File? = null
        var ogg: OggOpus? = null
        var vad: SpeechDetector? = null
        val base = store.state.value
        var frames = 0L
        var voiceFrames = 0L
        var encodingNs = 0L
        var segmenter: VoiceSegmenter? = null
        val sink = object : SegmentSink {
            override fun start(firstFrame: Long) {
                check(store.directory.usableSpace > 128L * 1024 * 1024) { "剩余空间不足 128 MB，已安全停止" }
                check(store.storageBytes() < 4L * 1024 * 1024 * 1024) { "本地录音达到 4 GB 上限，请先导出或删除" }
                val id = UUID.randomUUID().toString()
                file = File(store.directory, "$id.part")
                val at = captureStartedAt + firstFrame * 20
                writer = EncryptedAudio.Writer(file!!, store.key,
                    EncryptedAudio.Header(at, base.sessionId, id, "opus", audioSettings.vadEnabled, audioSettings.bitrate))
                store.event("SEGMENT_START", data = mapOf("segment_id" to id, "capture_run_id" to captureRunId,
                    "start_at_utc_ms" to at, "first_frame_index" to firstFrame, "input_sample_rate_hz" to 16_000))
                val encoder = OpusEncoder(audioSettings.bitrate)
                try { ogg = OggOpus(encoder, id.hashCode()) { page, pcmBytes -> writer!!.appendPayload(page, pcmBytes) } }
                catch (e: Exception) { encoder.close(); throw e }
            }
            override fun append(pcm: ByteArray) {
                val started = System.nanoTime()
                ogg!!.append(pcm)
                encodingNs += System.nanoTime() - started
            }
            override fun end() {
                ogg?.close(); ogg = null
                writer?.let {
                    store.finish(it, file!!)
                    store.event("SEGMENT_END", data = mapOf("segment_id" to it.header.segmentId,
                        "capture_run_id" to captureRunId, "start_at_utc_ms" to it.header.startedAt,
                        "duration_ms" to it.pcmBytes * 1000 / EncryptedAudio.BYTES_PER_SECOND))
                }
                writer = null; file = null
            }
        }
        try {
            if (audioSettings.vadEnabled) vad = WebRtcDetector(audioSettings.vadMode)
            segmenter = VoiceSegmenter(audioSettings.vadEnabled, sink, audioSettings.vadConfig)
            while (!captureDone.get() || queue.isNotEmpty()) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val speech = vad?.isSpeech(frame) ?: false
                if (speech) voiceFrames++
                segmenter.accept(frame, speech)
                frames++
                if (frames % 10 == 0L) {
                    var peak = 0
                    for (i in frame.indices step 2) {
                        val sample = ((frame[i].toInt() and 255) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, abs(sample))
                    }
                    store.update({ it.copy(capturedMs = base.capturedMs + frames * 20,
                        retainedMs = base.retainedMs + segmenter.retainedFrames * 20, voiceMs = base.voiceMs + voiceFrames * 20,
                        vadSpeech = speech, level = peak / 32768f) }, frames % 250 == 0L)
                }
                if (frames % 500 == 0L) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                    notification(if (store.state.value.silenced) "注意：麦克风被系统静音" else "已采集 ${formatDuration(store.state.value.capturedMs)} · Opus ${audioSettings.bitrate / 1000}k"))
                if (frames % 3000 == 0L) { recordDiagnostics(); store.event("ENCODING", "elapsed_ms=${encodingNs / 1_000_000};retained_ms=${segmenter.retainedFrames * 20}") }
            }
            segmenter.finish()
        } catch (e: Exception) {
            failure = e.message ?: "本地保存失败"
            running.set(false)
            runCatching { recorder?.stop() }
        } finally {
            runCatching { sink.end() }.onFailure {
                failure = it.message ?: "保存失败"
                // Leave .part for authenticated-block recovery on the next launch.
                runCatching { writer?.close() }
            }
            vad?.close()
            store.update({ it.copy(capturedMs = base.capturedMs + frames * 20,
                retainedMs = base.retainedMs + (segmenter?.retainedFrames ?: 0) * 20,
                voiceMs = base.voiceMs + voiceFrames * 20, vadSpeech = false) }, true)
            store.event("ENCODING", "elapsed_ms=${encodingNs / 1_000_000};frames=$frames")
            main.post { finishService() }
        }
    }

    private fun recordDiagnostics() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = getSystemService(BatteryManager::class.java)
        store.event("HEARTBEAT", "battery=${battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)};scale=${battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)};plugged=${battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)};voltage_mV=${battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)};current_uA=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)};mode=opus;vad=${audioSettings.vadEnabled};bitrate=${audioSettings.bitrate};retained_ms=${store.state.value.retainedMs};voice_ms=${store.state.value.voiceMs};silenced=${store.state.value.silenced}")
    }

    private fun requestStop(phase: Phase) {
        if (!busy) { stopSelf(); return }
        terminalPhase = phase
        store.update({ it.copy(phase = Phase.STOPPING, message = "正在保存最后一段…") }, true)
        running.set(false)
        // stop() unblocks READ_BLOCKING; release() belongs to the capture thread only.
        runCatching { recorder?.stop() }
    }

    private fun finishService() {
        main.removeCallbacks(watchdog)
        val message = failure
        store.update({ it.copy(phase = if (message != null) Phase.INTERRUPTED else terminalPhase,
            level = 0f, silenced = false,
            interruptions = it.interruptions + if (message != null) 1 else 0,
            message = message ?: if (terminalPhase == Phase.PAUSED) "已暂停，已保存全部采集音频。" else "录音已停止并保存。") }, true)
        store.event(if (message != null) "ERROR" else terminalPhase.name, message ?: "")
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        busy = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (message != null) {
            getSystemService(NotificationManager::class.java).notify(2,
                NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("DayTrace · 录音已中断").setContentText(message).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        main.removeCallbacks(watchdog)
        runCatching { recorder?.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
}

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
