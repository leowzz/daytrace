import json
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
