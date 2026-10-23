package com.daytrace.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class JournalBatch(val endOffset: Long, val events: JSONArray)

object CaptureJournal {
    /** Snapshot complete UTF-8 lines only. Acknowledged byte offsets remain valid across appends. */
    fun batches(file: File, sourceId: String, savedOffset: Long): Sequence<JournalBatch> = sequence {
        if (!file.exists()) return@sequence
        val snapshotEnd = file.length()
        var offset = if (savedOffset in 0..snapshotEnd) savedOffset else 0
        while (offset < snapshotEnd) {
            // Close before yielding: a network failure must not leak an open journal handle.
            val batch = RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val events = JSONArray()
                var end = input.filePointer
                while (events.length() < 100 && input.filePointer < snapshotEnd) {
                    val start = input.filePointer
                    val raw = input.readLine() ?: break
                    val next = input.filePointer
                    input.seek(next - 1)
                    val complete = input.read() == 10
                    if (!complete || next > snapshotEnd) break
                    val line = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    if (line.isNotBlank()) {
                        val payload = JSONObject(line)
                        val id = UploadProtocol.sha256("$sourceId:$start:$line".toByteArray(Charsets.UTF_8))
                        events.put(JSONObject().put("event_id", id).put("payload", payload))
                    }
                    end = next
                }
                if (events.length() == 0) null else JournalBatch(end, events)
            }
            if (batch == null) break
            yield(batch)
            offset = batch.endOffset
        }
    }
}
