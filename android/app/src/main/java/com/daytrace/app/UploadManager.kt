package com.daytrace.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class UploadProgress(val busy: Boolean = false, val message: String = "", val target: String = "")

/** A manual batch survives navigation/rotation, but does not claim guaranteed background execution. */
class UploadManager(private val context: Context, private val store: RecordingStore, private val logs: AppLogs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = UploadLedger(File(context.filesDir, "upload-ledger.json"))
    val receipts = ledger.receipts
    private val progressState = MutableStateFlow(UploadProgress())
    val progress = progressState.asStateFlow()
    private val sourceId: String

    init {
        val prefs = context.getSharedPreferences("upload", Context.MODE_PRIVATE)
        sourceId = prefs.getString("sourceId", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("sourceId", it).commit()) { "无法保存上传来源标识" }
        }
        // Remove plaintext left only by a process death during export/upload.
        File(context.cacheDir, "uploads").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        if (ledger.interruptedCount > 0) logs.add("上传", "发现 ${ledger.interruptedCount} 个中断上传，保留本地音频，可手动重试。", true)
        ledger.recoveryMessage?.let { logs.add("上传状态恢复", it, true) }
    }

    @Synchronized fun start(address: String, selected: List<Segment>) {
        if (progressState.value.busy) return
        val target = ServerAddress.normalize(address)
        val pending = selected.filter { receipts.value[UploadLedger.key(target, it.info.header.segmentId)]?.status != "completed" }
            .sortedBy { it.info.header.startedAt }
        progressState.value = UploadProgress(true, "准备同步采集事件和 ${pending.size} 段音频…", target)
        scope.launch {
            var success = 0
            var failed = 0
            var journalFailure: String? = null
            try {
                logs.add("上传", "开始同步：${pending.size} 段待上传音频；服务 $target")
                try {
                    val count = syncJournal(target)
                    logs.add("采集事件同步", "已确认 $count 条新增事件；服务 $target")
                } catch (e: Exception) {
                    journalFailure = e.message ?: "网络异常"
                    logs.add("采集事件同步", "失败：$journalFailure；音频仍可上传，下次重试事件同步。", true)
                }
                for ((index, segment) in pending.withIndex()) {
                    val id = segment.info.header.segmentId
                    progressState.value = UploadProgress(true, "上传 ${index + 1}/${pending.size}；成功 $success，失败 $failed", target)
                    ledger.record(target, id, "uploading", "正在上传并等待服务端处理…")
                    logs.add("上传", "开始片段 $id；session_id=${segment.info.header.sessionId}")
                    val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
                    val extension = if (segment.info.header.codec == "opus") "opus" else "wav"
                    var temp: File? = null
                    try {
                        val audio = File.createTempFile("audio-", ".$extension", directory)
                        temp = audio
                        // Complete authenticated export before sending any audio to the server.
                        audio.outputStream().use { EncryptedAudio.export(segment.file, store.key, it) }
                        val metadata = UploadProtocol.metadata(segment.info, segment.file.name, UploadProtocol.fileHash(audio))
                        UploadProtocol.upload(target, audio, metadata)
                        ledger.record(target, id, "completed", "已上传 · 服务端处理完成")
                        success++
                        logs.add("上传成功", "片段 $id；${segment.info.durationMs} ms；服务端已确认音频和时间信息。")
                    } catch (e: Exception) {
                        failed++
                        val message = e.message ?: "网络或本地文件异常"
                        ledger.record(target, id, "failed", message)
                        logs.add("上传失败", "片段 $id：$message；保留本地原件，可重试。", true)
                    } finally { temp?.delete() }
                }
                val message = "同步结束：音频成功 $success，失败 $failed；" +
                    if (journalFailure == null) "采集事件已同步。" else "采集事件同步失败，请重试（$journalFailure）。"
                progressState.value = UploadProgress(false, message, target)
                logs.add("同步结果", message, failed > 0 || journalFailure != null)
            } catch (e: Exception) {
                val message = "同步中断：${e.message ?: "本地读写异常"}；请重试。"
                progressState.value = UploadProgress(false, message, target)
                logs.add("上传", message, true)
            }
        }
    }

    private fun syncJournal(target: String): Int {
        var count = 0
        for (batch in CaptureJournal.batches(store.captureEvents, sourceId, ledger.offset(target))) {
            UploadProtocol.uploadEvents(target, sourceId, batch.events)
            ledger.advance(target, batch.endOffset)
            count += batch.events.length()
        }
        return count
    }
}
