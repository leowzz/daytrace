import json
import hashlib
from pathlib import Path

from fastapi.testclient import TestClient


def configure_main(monkeypatch, tmp_path: Path, backend: str = "mock"):
    monkeypatch.setenv("DAYTRACE_ASR_BACKEND", backend)
    monkeypatch.setenv("DAYTRACE_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("DAYTRACE_DATABASE_URL", f"sqlite:///{tmp_path / 'data' / 'test.sqlite3'}")
    from app import main

    main.settings = main.load_settings()
    main.database = main.Database(main.settings.database_url, main.settings.data_dir)
    main.asr_engine = None
    return main


def test_upload_with_mock_asr(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        metadata = {
            "session_id": "session-1",
            "segment_id": "segment-1",
            "started_at_utc_ms": 1761177600000,
            "duration_ms": 3200,
            "codec": "opus",
            "capture_mode": "vad",
            "vad_enabled": True,
            "bitrate_kbps": 16,
        }
        response = client.post(
            "/v1/audio/segments",
            files={"audio": ("segment.opus", b"fake audio", "audio/ogg")},
            data={"metadata": json.dumps(metadata)},
        )
        assert response.status_code == 201
        body = response.json()
        assert body["status"] == "completed"
        assert body["transcript"]["text"] == "[mock transcript]"
        assert body["transcript"]["chunks"][0]["start_ms"] == 0
        assert Path(body["file_path"]).exists()

        listed = client.get("/v1/segments", params={"session_id": "session-1"})
        assert listed.status_code == 200
        assert listed.json()["items"][0]["segment_id"] == "segment-1"


def test_metadata_is_required(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        response = client.post(
            "/v1/audio/segments",
            files={"audio": ("segment.opus", b"fake audio", "audio/ogg")},
            data={"metadata": json.dumps({"segment_id": "segment-1", "session_id": "session-1"})},
        )
    assert response.status_code == 422


def test_mobile_metadata_roundtrip_retry_and_conflict(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    audio = b"synthetic audio fixture"
    metadata = {"segment_id": "mobile-1", "session_id": "session-mobile", "started_at_utc_ms": 1792700000123,
                "duration_ms": 1000, "codec": "pcm", "container": "wav", "capture_mode": "continuous",
                "vad_enabled": False, "input_sample_rate_hz": 16000, "input_sample_count": 16000,
                "channels": 1, "source_ref": "android://daytrace/session-mobile/mobile-1.dta",
                "sha256": hashlib.sha256(audio).hexdigest(), "coverage_outside_segment": "unknown"}
    with TestClient(main.app) as client:
        def upload(meta=metadata, content=audio):
            return client.post("/v1/audio/segments", files={"audio": ("audio.wav", content, "audio/wav")},
                               data={"metadata": json.dumps(meta)})
        first = upload()
        assert first.status_code == 201
        assert first.json()["metadata"] == metadata
        assert first.json()["sha256"] == metadata["sha256"]
        # A lost client response can be retried without rerunning ASR or duplicating a row.
        monkeypatch.setattr(main, "_get_asr", lambda: (_ for _ in ()).throw(AssertionError("ASR repeated")))
        assert upload().status_code == 200
        assert len(client.get("/v1/segments").json()["items"]) == 1
        assert upload(metadata | {"started_at_utc_ms": 1}).status_code == 409
        assert upload(metadata | {"capture_mode": "vad"}).status_code == 409
        assert upload(content=b"altered audio").status_code == 422
        assert Path(first.json()["file_path"]).read_bytes() == audio
        assert not list(main.settings.audio_dir.glob(".ingest-*"))
        main.database.init()  # An existing database survives repeated schema initialization.
        assert client.get("/v1/segments/mobile-1").json()["metadata"] == metadata


def test_asr_failure_keeps_evidence_and_can_retry(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    from app.asr import ASRUnavailableError, MockASR
    def unavailable():
        raise ASRUnavailableError("test ASR unavailable")
    monkeypatch.setattr(main, "_get_asr", unavailable)
    with TestClient(main.app) as client:
        args = {"files": {"audio": ("audio.opus", b"test audio", "audio/ogg")},
                "data": {"metadata": json.dumps({"segment_id": "retry", "session_id": "session",
                                                  "started_at_utc_ms": 1000, "duration_ms": 20, "codec": "opus"})}}
        assert client.post("/v1/audio/segments", **args).status_code == 503
        failed = client.get("/v1/segments/retry").json()
        assert failed["status"] == "failed"
        assert Path(failed["file_path"]).read_bytes() == b"test audio"
        monkeypatch.setattr(main, "_get_asr", lambda: MockASR())
        assert client.post("/v1/audio/segments", **args).json()["status"] == "completed"


def test_capture_events_are_idempotent_and_preserve_unknown_coverage(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    events = [{"event_id": hashlib.sha256(name.encode()).hexdigest(),
               "payload": {"event": name, "session_id": "session-1", "observed_at_utc_ms": 1000 + index,
                           "data": {"segment_id": "segment-1"}, "detail": "中文事件"}}
              for index, name in enumerate(["PAUSED", "MIC_SILENCED", "ERROR", "DELETED_SEGMENT", "RECOVERED_SEGMENT"])]
    with TestClient(main.app) as client:
        batch = {"source_id": "installation-1", "events": events}
        for _ in range(2):
            response = client.post("/v1/capture-events", json=batch)
            assert response.status_code == 200
            assert response.json()["event_ids"] == [item["event_id"] for item in events]
        stored = client.get("/v1/capture-events", params={"session_id": "session-1"}).json()["items"]
        assert len(stored) == len(events)
        assert [item["payload"] for item in stored] == [item["payload"] for item in reversed(events)]
        conflict = {"event_id": events[0]["event_id"], "payload": events[0]["payload"] | {"detail": "changed"}}
        assert client.post("/v1/capture-events", json=batch | {"events": [conflict]}).status_code == 409
        assert client.post("/v1/capture-events", json=batch | {"events": []}).status_code == 422
        assert client.post("/v1/capture-events", json=batch | {"events": events * 21}).status_code == 422
        assert client.get("/v1/capture-events", params={"session_id": "absent"}).json()["items"] == []


def test_viewer_search_pagination_and_time_overlap(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    from app.asr import ASRChunk, ASRResult
    with TestClient(main.app) as client:
        for identifier, session, start, duration in [
            ("a", "one", 1000, 2000), ("b", "one", 5000, 1000),
            ("c", "two", 5000, 1000), ("point", "one", 6000, 0),
        ]:
            response = client.post("/v1/audio/segments", files={"audio": ("audio.wav", b"audio", "audio/wav")},
                                   data={"metadata": json.dumps({"segment_id": identifier, "session_id": session,
                                         "started_at_utc_ms": start, "duration_ms": duration, "codec": "pcm"})})
            assert response.status_code == 201
        main._write_processing_result("a", ASRResult("你好 100%_ ' <script>", "zh", 2000, -0.25, "test", "fixture",
                                                     [ASRChunk(200, 800, "你好", -0.25,
                                                               [{"start_ms": 200, "end_ms": 800, "text": "你好"}])]))
        with main.database.transaction() as connection:
            connection.execute("UPDATE audio_segments SET status='failed', error='fixture failure' WHERE segment_id='b'")
            connection.execute("UPDATE audio_segments SET status='processing' WHERE segment_id='c'")
        page = client.get("/v1/segments", params={"limit": 1}).json()
        assert page["total"] == 4
        assert page["summary"] == {"total": 4, "duration_ms": 4000, "completed": 2, "failed": 1, "processing": 1}
        assert page["items"][0]["segment_id"] == "point"
        # Tie order is stable and pages do not repeat rows.
        assert client.get("/v1/segments", params={"limit": 1, "offset": 1}).json()["items"][0]["segment_id"] == "b"
        assert client.get("/v1/segments", params={"limit": 1, "offset": 2}).json()["items"][0]["segment_id"] == "c"
        found = client.get("/v1/segments", params={"q": "100%_ '", "session_id": "one", "status": "completed"}).json()
        assert found["total"] == 1
        assert found["items"][0]["transcript_preview"] == "你好 100%_ ' <script>"
        assert found["items"][0]["asr_model"] == "test"
        assert client.get("/v1/segments", params={"q": "' OR 1=1 --"}).json()["total"] == 0
        # A segment that starts before the requested window still overlaps it.
        overlap = client.get("/v1/segments", params={"from_utc_ms": 2000, "to_utc_ms": 5000}).json()
        assert [item["segment_id"] for item in overlap["items"]] == ["a"]
        assert client.get("/v1/segments", params={"from_utc_ms": 3000, "to_utc_ms": 5000}).json()["total"] == 0
        assert client.get("/v1/segments", params={"from_utc_ms": 6000}).json()["total"] == 1
        assert client.get("/v1/segments", params={"session_id": "one", "status": "failed"}).json()["total"] == 1
        assert client.get("/v1/segments", params={"offset": 999}).json()["items"] == []
        assert client.get("/v1/segments", params={"status": "unknown"}).status_code == 422
        assert client.get("/v1/segments", params={"from_utc_ms": 5000, "to_utc_ms": 5000}).status_code == 422
        assert client.get("/v1/segments/missing").status_code == 404
        original = client.get("/v1/segments/a").json()
        assert original["started_at_utc_ms"] == 1000
        assert original["transcript"]["chunks"][0]["words"][0]["start_ms"] == 200


def test_original_audio_ranges_and_path_boundaries(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    audio = b"RIFF0123456789"
    with TestClient(main.app) as client:
        response = client.post("/v1/audio/segments", files={"audio": ("audio.wav", audio, "text/html")},
                               data={"metadata": json.dumps({"segment_id": "audio-1", "session_id": "one",
                                     "started_at_utc_ms": 1000, "duration_ms": 1000, "codec": "pcm"})})
        assert response.status_code == 201
        url = "/v1/segments/audio-1/audio"
        # The endpoint only reads stored evidence, including failed ASR recordings.
        monkeypatch.setattr(main, "_get_asr", lambda: (_ for _ in ()).throw(AssertionError("ASR repeated")))
        with main.database.transaction() as connection:
            connection.execute("UPDATE audio_segments SET status='failed' WHERE segment_id='audio-1'")
        full = client.get(url)
        assert full.content == audio
        assert full.headers["content-type"] == "audio/wav"
        assert full.headers["x-content-type-options"] == "nosniff"
        assert full.headers["content-disposition"].startswith("inline;")
        partial = client.get(url, headers={"Range": "bytes=4-7"})
        assert partial.status_code == 206
        assert partial.content == audio[4:8]
        assert partial.headers["content-range"] == "bytes 4-7/14"
        assert client.get(url, headers={"Range": "bytes=100-200"}).status_code == 416
        assert client.get(url + "?download=true").headers["content-disposition"].startswith("attachment;")
        assert client.get("/v1/segments/missing/audio").status_code == 404
        Path(response.json()["file_path"]).unlink()
        assert client.get(url).status_code == 404
        outside = tmp_path / "private.txt"
        outside.write_text("private")
        with main.database.transaction() as connection:
            connection.execute("UPDATE audio_segments SET file_path=? WHERE segment_id='audio-1'", (str(outside),))
        assert client.get(url).status_code == 404


def test_viewer_assets_and_event_time_filters(monkeypatch, tmp_path: Path):
    main = configure_main(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        for path in ["/", "/viewer", "/static/viewer.css", "/static/viewer.js"]:
            response = client.get(path)
            assert response.status_code == 200
        assert "数据与转写" in client.get("/").text
        assert client.get("/v1/segments").json()["summary"]["duration_ms"] == 0
        events = [{"event_id": hashlib.sha256(str(index).encode()).hexdigest(),
                   "payload": {"event": "PAUSED", "session_id": "session", "observed_at_utc_ms": index * 1000}}
                  for index in range(1, 4)]
        assert client.post("/v1/capture-events", json={"source_id": "source", "events": events}).status_code == 200
        page = client.get("/v1/capture-events", params={"session_id": "session", "from_utc_ms": 1000,
                                                     "to_utc_ms": 3000, "limit": 1, "offset": 1}).json()
        assert page["total"] == 2
        assert page["items"][0] == {"event_id": events[0]["event_id"], "source_id": "source", "payload": events[0]["payload"]}
        assert client.get("/v1/capture-events", params={"from_utc_ms": 5000, "to_utc_ms": 1000}).status_code == 422
