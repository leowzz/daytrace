package com.daytrace.app

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class LogEntry(val id: String, val at: Long, val level: String, val category: String, val message: String)

/** The UI keeps a bounded recent view; the original capture journal is never truncated. */
class AppLogs(private val file: File, private val captureFile: File) {
    private var operations = readRecentLines(file).mapNotNull { line ->
        runCatching {
            val json = JSONObject(line)
            LogEntry(json.getString("id"), json.getLong("at"), json.getString("level"),
                json.getString("category"), json.getString("message"))
        }.getOrNull()
    }

    @Synchronized fun add(category: String, message: String, error: Boolean = false) {
        operations = (operations + LogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
            if (error) "ERROR" else "INFO", category, message.take(4000))).takeLast(200)
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(operations.joinToString("\n", postfix = "\n") {
                JSONObject().put("id", it.id).put("at", it.at).put("level", it.level)
                    .put("category", it.category).put("message", it.message).toString()
            })
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            operations = (operations + LogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
                "ERROR", "日志", "运行日志无法写入磁盘；本次仅在内存显示。")).takeLast(200)
        }
    }

    @Synchronized fun recent(): List<LogEntry> {
        val capture = readRecentLines(captureFile).mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val name = json.getString("event")
                val level = when (name) {
                    "ERROR", "PROCESS_RESTART_AFTER_INTERRUPTION" -> "ERROR"
                    "MIC_SILENCED", "DELETED_SEGMENT" -> "WARN"
                    else -> "INFO"
                }
                LogEntry("capture-${UploadProtocol.sha256(line.toByteArray())}", json.getLong("observed_at_utc_ms"),
                    level, "采集 · $name", json.toString(2))
            }.getOrNull()
        }
        return (operations + capture).sortedByDescending { it.at }.distinctBy { it.id }.take(200)
    }

    companion object {
        fun readRecentLines(file: File, limit: Int = 200): List<String> {
            if (!file.exists()) return emptyList()
            return RandomAccessFile(file, "r").use { input ->
                val start = maxOf(0, input.length() - 1024 * 1024)
                input.seek(start)
                val bytes = ByteArray((input.length() - start).toInt())
                input.readFully(bytes)
                val lines = bytes.toString(Charsets.UTF_8).split('\n')
                // Drop a possibly partial first line and the not-yet-committed final line.
                lines.drop(if (start > 0) 1 else 0).dropLast(1).filter { it.isNotBlank() }.takeLast(limit)
            }
        }
    }
}
