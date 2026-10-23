package com.daytrace.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class UploadTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sample(): Pair<File, JSONObject> {
        val encrypted = temp.newFile("sample.dta")
        val audio = temp.newFile("sample.wav")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        EncryptedAudio.Writer(encrypted, key, EncryptedAudio.Header(1792700000123, "session-a", "segment-a")).use {
            it.append(ByteArray(32000), 32000)
        }
        audio.outputStream().use { EncryptedAudio.export(encrypted, key, it) }
        return audio to UploadProtocol.metadata(EncryptedAudio.scan(encrypted), encrypted.name, UploadProtocol.fileHash(audio))
    }

    @Test fun sendsDecryptedAudioAndCompleteTimeEvidenceWithoutAuthentication() {
        val (audio, meta) = sample()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(JSONObject(meta.toString())
                .put("status", "completed").put("metadata", meta).toString()))
            UploadProtocol.upload(server.url("/proxy").toString(), audio, meta)
            val request = server.takeRequest()
            assertEquals("/proxy/v1/audio/segments", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"metadata\""))
            assertTrue(body.contains("name=\"audio\""))
            assertTrue(body.contains("RIFF"))
            assertTrue(body.contains("\"started_at_utc_ms\":1792700000123"))
            assertEquals(1000L, meta.getLong("duration_ms"))
            assertEquals(1792700001123L, meta.getLong("end_at_utc_ms"))
            assertEquals(16000, meta.getInt("input_sample_count"))
            assertEquals("unknown", meta.getString("coverage_outside_segment"))
        }
    }

    @Test fun doesNotTreatWrongReceiptAsSuccessOrFollowRedirects() {
        val (audio, meta) = sample()
        MockWebServer().use { server ->
            val valid = JSONObject(meta.toString()).put("status", "completed").put("metadata", meta)
            for (response in listOf(
                MockResponse().setResponseCode(503).setBody("{\"error\":\"ASR unavailable\"}"),
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")),
                MockResponse().setBody(JSONObject(valid.toString()).put("segment_id", "wrong").toString()),
                MockResponse().setBody(JSONObject(valid.toString()).put("sha256", "wrong").toString()),
                MockResponse().setBody(JSONObject(valid.toString()).put("metadata", JSONObject()).toString()),
                MockResponse().setBody("<html>login</html>")
            )) {
                server.enqueue(response)
                assertThrows(Exception::class.java) { UploadProtocol.upload(server.url("/").toString(), audio, meta) }
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun eventsRequireMatchingAcknowledgement() {
        val id = "a".repeat(64)
        val events = JSONArray().put(JSONObject().put("event_id", id).put("payload", JSONObject().put("event", "PAUSED")))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("event_ids", JSONArray().put(id)).toString()))
            UploadProtocol.uploadEvents(server.url("/").toString(), "source", events)
            val request = server.takeRequest()
            assertEquals("/v1/capture-events", request.path)
            assertEquals("source", JSONObject(request.body.readUtf8()).getString("source_id"))
            server.enqueue(MockResponse().setBody("{\"event_ids\":[]}"))
            assertThrows(Exception::class.java) { UploadProtocol.uploadEvents(server.url("/").toString(), "source", events) }
        }
    }

    @Test fun ledgerSurvivesRestartSeparatesServersAndRecoversInterruptedUploads() {
        val file = File(temp.root, "ledger.json")
        val first = UploadLedger(file)
        first.record("http://host:8000/", "done", "completed", "ok")
        first.record("http://host:8000", "pending", "uploading", "uploading")
        first.advance("http://host:8000", 123)
        val reopened = UploadLedger(file)
        assertEquals(1, reopened.interruptedCount)
        assertEquals("completed", reopened.receipts.value[UploadLedger.key("http://host:8000", "done")]?.status)
        assertEquals("interrupted", reopened.receipts.value[UploadLedger.key("http://host:8000", "pending")]?.status)
        assertNull(reopened.receipts.value[UploadLedger.key("http://other:8000", "done")])
        assertEquals(123L, reopened.offset("http://host:8000/"))
        assertEquals(0L, reopened.offset("http://other:8000"))
    }

    @Test fun corruptLedgerIsPreservedWithoutPreventingStartup() {
        val file = temp.newFile("ledger.json").apply { writeText("broken") }
        val ledger = UploadLedger(file)
        assertNotNull(ledger.recoveryMessage)
        assertEquals("broken", temp.root.listFiles()!!.single { it.name.startsWith("ledger.json.damaged-") }.readText())
        ledger.record("http://host", "new", "completed", "ok")
        assertEquals("completed", UploadLedger(file).receipts.value[UploadLedger.key("http://host", "new")]?.status)
    }

    @Test fun recentLogsRemainBoundedPersistedAndDoNotModifyCaptureEvidence() {
        val capture = temp.newFile("capture.jsonl")
        val content = (1..250).joinToString("\n", postfix = "\n") {
            JSONObject().put("event", "PAUSED").put("observed_at_utc_ms", it).put("detail", "中文-$it").toString()
        }
        capture.writeText(content)
        val ops = File(temp.root, "ops.jsonl")
        val logs = AppLogs(ops, capture)
        logs.add("上传失败", "segment-a: HTTP 503", true)
        val reopened = AppLogs(ops, capture).recent()
        assertEquals(200, reopened.size)
        assertEquals("ERROR", reopened.first().level)
        assertTrue(reopened[1].message.contains("中文-250"))
        assertEquals(content, capture.readText())
        capture.appendText("{\"event\":")
        assertEquals(200, AppLogs(ops, capture).recent().size)
    }

    @Test fun journalResumesAtAcknowledgedBytesAndDefersIncompleteTail() {
        val capture = temp.newFile("journal.jsonl")
        fun line(index: Int) = JSONObject().put("event", "PAUSED").put("observed_at_utc_ms", index)
            .put("detail", "中文-$index").toString()
        capture.writeText((1..101).joinToString("\n", postfix = "\n") { line(it) } + line(102))
        val batches = CaptureJournal.batches(capture, "source", 0).toList()
        assertEquals(listOf(100, 1), batches.map { it.events.length() })
        assertEquals("中文-101", batches.last().events.getJSONObject(0).getJSONObject("payload").getString("detail"))
        val retry = CaptureJournal.batches(capture, "source", batches.first().endOffset).toList()
        assertEquals(batches.last().events.toString(), retry.single().events.toString())
        assertTrue(CaptureJournal.batches(capture, "source", batches.last().endOffset).toList().isEmpty())
        capture.appendText("\n")
        val tail = CaptureJournal.batches(capture, "source", batches.last().endOffset).toList().single()
        assertEquals("中文-102", tail.events.getJSONObject(0).getJSONObject("payload").getString("detail"))
        assertEquals(capture.length(), tail.endOffset)
    }
}
