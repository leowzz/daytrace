package com.daytrace.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class UploadReceipt(val status: String, val message: String)

class UploadLedger(private val file: File) {
    var recoveryMessage: String? = null
        private set
    private var writable = true
    private val data = try {
        (if (file.exists()) JSONObject(file.readText()) else JSONObject()).also { json ->
            json.keys().asSequence().filter { it.startsWith("audio:") }.forEach {
                json.getJSONObject(it).getString("status")
                json.getJSONObject(it).getString("message")
            }
        }
    } catch (_: Exception) {
        val backup = File(file.parentFile, "${file.name}.damaged-${System.currentTimeMillis()}")
        writable = file.renameTo(backup)
        recoveryMessage = if (writable) "上传状态文件损坏，原件已保留；片段需重新确认上传。"
            else "上传状态文件无法读取且无法保留备份，上传已禁用；录音仍可使用。"
        JSONObject()
    }
    private val receiptsState = MutableStateFlow(readReceipts())
    val receipts = receiptsState.asStateFlow()
    val interruptedCount: Int

    init {
        val interrupted = receiptsState.value.filterValues { it.status == "uploading" }
        interruptedCount = interrupted.size
        interrupted.forEach { (key, _) ->
            data.put(key, JSONObject().put("status", "interrupted").put("message", "上次上传中断，远端状态待确认，请重试。"))
        }
        if (interrupted.isNotEmpty()) { persist(); receiptsState.value = readReceipts() }
    }

    private fun readReceipts(): Map<String, UploadReceipt> = data.keys().asSequence().filter { it.startsWith("audio:") }
        .associateWith { key -> data.getJSONObject(key).let { UploadReceipt(it.getString("status"), it.getString("message")) } }

    @Synchronized fun record(address: String, id: String, status: String, message: String) {
        data.put(key(address, id), JSONObject().put("status", status).put("message", message.take(1000)))
        persist()
        receiptsState.value = readReceipts()
    }

    @Synchronized fun offset(address: String): Long = data.optLong("journal:${ServerAddress.normalize(address)}", 0)
    @Synchronized fun advance(address: String, offset: Long) {
        data.put("journal:${ServerAddress.normalize(address)}", offset)
        persist()
    }

    private fun persist() {
        check(writable) { recoveryMessage ?: "上传状态无法保存" }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(data.toString())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun key(address: String, id: String) = "audio:${ServerAddress.normalize(address)}|$id"
    }
}
