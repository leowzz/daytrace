from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse

from .asr import ASRResult, ASRUnavailableError, build_asr
from .config import Settings, load_settings
from .db import Database, utc_now


app = FastAPI(title="DayTrace Server", version="0.1.0")
settings: Settings = load_settings()
database = Database(settings.database_url, settings.data_dir)
asr_engine = None


@app.on_event("startup")
def startup() -> None:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.audio_dir.mkdir(parents=True, exist_ok=True)
    database.init()


def _get_asr():
    global asr_engine
    if asr_engine is None:
        asr_engine = build_asr(
            settings.asr_backend,
            settings.asr_model,
            settings.asr_device,
            settings.asr_compute_type,
            settings.asr_cpu_threads,
            settings.asr_num_workers,
        )
    return asr_engine


def _parse_metadata(metadata: str | None) -> dict[str, Any]:
    if not metadata:
        return {}
    try:
        value = json.loads(metadata)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail=f"metadata must be valid JSON: {exc.msg}") from exc
    if not isinstance(value, dict):
        raise HTTPException(status_code=422, detail="metadata must be a JSON object")
    return value


def _required_str(metadata: dict[str, Any], name: str, default: str | None = None) -> str:
    value = metadata.get(name, default)
    if value is None or not str(value).strip():
        raise HTTPException(status_code=422, detail=f"metadata.{name} is required")
    return str(value).strip()


def _int_field(metadata: dict[str, Any], name: str, default: int | None = None) -> int | None:
    value = metadata.get(name, default)
    if value is None:
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=f"metadata.{name} must be an integer") from exc
    if parsed < 0:
        raise HTTPException(status_code=422, detail=f"metadata.{name} must be >= 0")
    return parsed


def _bool_field(value: Any) -> bool | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in {"true", "1", "yes"}:
        return True
    if isinstance(value, str) and value.strip().lower() in {"false", "0", "no"}:
        return False
    raise HTTPException(status_code=422, detail="metadata.vad_enabled must be a boolean")


async def _save_upload(upload: UploadFile, destination: Path, max_bytes: int) -> tuple[str, int]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    digest = hashlib.sha256()
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".upload-", delete=False) as target:
            temporary_name = Path(target.name)
            while chunk := await upload.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(status_code=413, detail="audio upload exceeds configured size limit")
                digest.update(chunk)
                target.write(chunk)
        os.replace(temporary_name, destination)
    except Exception:
        if temporary_name:
            temporary_name.unlink(missing_ok=True)
        raise
    finally:
        await upload.close()
    return digest.hexdigest(), total


def _write_processing_result(segment_id: str, result: ASRResult) -> None:
    now = utc_now()
    with database.transaction() as connection:
        connection.execute("DELETE FROM transcripts WHERE segment_id = ?", (segment_id,))
        cursor = connection.execute(
            "INSERT INTO transcripts(segment_id, text, language, model, provider, duration_ms, confidence, raw_json, created_at_utc) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                segment_id,
                result.text,
                result.language,
                result.model,
                result.provider,
                result.duration_ms,
                result.confidence,
                json.dumps(result.raw, ensure_ascii=False) if result.raw is not None else None,
                now,
            ),
        )
        transcript_id = cursor.lastrowid
        connection.executemany(
            "INSERT INTO transcript_chunks(transcript_id, chunk_index, start_ms, end_ms, text, confidence, words_json) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            [
                (
                    transcript_id,
                    index,
                    chunk.start_ms,
                    chunk.end_ms,
                    chunk.text,
                    chunk.confidence,
                    json.dumps(chunk.words, ensure_ascii=False) if chunk.words is not None else None,
                )
                for index, chunk in enumerate(result.chunks)
            ],
        )
        connection.execute(
            "UPDATE audio_segments SET status = 'completed', error = NULL, updated_at_utc = ? WHERE segment_id = ?",
            (now, segment_id),
        )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/audio/segments")
async def upload_segment(
    audio: UploadFile = File(...),
    metadata: str | None = Form(default=None),
) -> JSONResponse:
    payload = _parse_metadata(metadata)
    segment_id = _required_str(payload, "segment_id")
    if Path(segment_id).name != segment_id or segment_id in {".", ".."}:
        raise HTTPException(status_code=422, detail="metadata.segment_id must be a simple identifier")
    session_id = _required_str(payload, "session_id")
    started_at = _int_field(payload, "started_at_utc_ms", _int_field(payload, "start_at_utc_ms"))
    duration_ms = _int_field(payload, "duration_ms", 0) or 0
    if started_at is None:
        raise HTTPException(status_code=422, detail="metadata.started_at_utc_ms is required")
    codec = _required_str(payload, "codec", Path(audio.filename or "audio").suffix.lstrip(".") or "unknown")
    capture_mode = payload.get("capture_mode")
    vad_enabled = _bool_field(payload.get("vad_enabled"))
    bitrate_kbps = _int_field(payload, "bitrate_kbps")
    source_ref = payload.get("source_ref")
    extension = Path(audio.filename or "audio").suffix or ".bin"
    destination = settings.audio_dir / f"{segment_id}{extension}"
    digest, _ = await _save_upload(audio, destination, settings.max_upload_bytes)
    now = utc_now()
    with database.transaction() as connection:
        connection.execute(
            "INSERT INTO audio_segments(segment_id, session_id, started_at_utc_ms, duration_ms, codec, capture_mode, "
            "vad_enabled, bitrate_kbps, source_ref, original_filename, mime_type, file_path, sha256, status, error, created_at_utc, updated_at_utc) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'processing', NULL, ?, ?) "
            "ON CONFLICT(segment_id) DO UPDATE SET session_id=excluded.session_id, started_at_utc_ms=excluded.started_at_utc_ms, "
            "duration_ms=excluded.duration_ms, codec=excluded.codec, capture_mode=excluded.capture_mode, vad_enabled=excluded.vad_enabled, "
            "bitrate_kbps=excluded.bitrate_kbps, source_ref=excluded.source_ref, original_filename=excluded.original_filename, "
            "mime_type=excluded.mime_type, file_path=excluded.file_path, sha256=excluded.sha256, status='processing', error=NULL, updated_at_utc=excluded.updated_at_utc",
            (
                segment_id,
                session_id,
                started_at,
                duration_ms,
                codec,
                capture_mode,
                int(vad_enabled) if vad_enabled is not None else None,
                bitrate_kbps,
                source_ref,
                audio.filename or "audio",
                audio.content_type,
                str(destination),
                digest,
                now,
                now,
            ),
        )
        connection.execute(
            "UPDATE audio_segments SET metadata_json=? WHERE segment_id=?",
            (json.dumps(payload, ensure_ascii=False), segment_id),
        )
    try:
        result = _get_asr().transcribe(destination, settings.asr_language)
        _write_processing_result(segment_id, result)
    except ASRUnavailableError as exc:
        error = str(exc)
        with database.transaction() as connection:
            connection.execute(
                "UPDATE audio_segments SET status='failed', error=?, updated_at_utc=? WHERE segment_id=?",
                (error, utc_now(), segment_id),
            )
        return JSONResponse(status_code=503, content={"segment_id": segment_id, "status": "failed", "error": error})
    except Exception as exc:
        error = f"ASR failed: {exc}"
        with database.transaction() as connection:
            connection.execute(
                "UPDATE audio_segments SET status='failed', error=?, updated_at_utc=? WHERE segment_id=?",
                (error, utc_now(), segment_id),
            )
        return JSONResponse(status_code=500, content={"segment_id": segment_id, "status": "failed", "error": error})
    return JSONResponse(status_code=201, content=database.get_segment(segment_id))


@app.post("/v1/capture-events")
def upload_capture_events(batch: dict[str, Any] = Body(...)) -> dict[str, Any]:
    source_id = _required_str(batch, "source_id")
    events = batch.get("events")
    if not isinstance(events, list) or not 1 <= len(events) <= 100:
        raise HTTPException(status_code=422, detail="events must contain 1 to 100 events")
    rows = []
    for item in events:
        if not isinstance(item, dict) or not isinstance(item.get("payload"), dict):
            raise HTTPException(status_code=422, detail="each event needs event_id and payload")
        event_id = _required_str(item, "event_id")
        if len(event_id) != 64 or any(c not in "0123456789abcdef" for c in event_id):
            raise HTTPException(status_code=422, detail="event_id must be a SHA-256 identifier")
        payload = item["payload"]
        observed = _int_field(payload, "observed_at_utc_ms")
        if observed is None:
            raise HTTPException(status_code=422, detail="observed_at_utc_ms is required")
        encoded = json.dumps(payload, ensure_ascii=False)
        if len(encoded.encode("utf-8")) > 65536:
            raise HTTPException(status_code=413, detail="event payload too large")
        rows.append((event_id, source_id, str(payload.get("session_id", "")), observed,
                     _required_str(payload, "event"), encoded))
    with database.transaction() as connection:
        for row in rows:
            previous = connection.execute(
                "SELECT source_id, payload_json FROM capture_events WHERE event_id=?", (row[0],)
            ).fetchone()
            if previous and (previous["source_id"] != row[1] or json.loads(previous["payload_json"]) != json.loads(row[5])):
                raise HTTPException(status_code=409, detail="event_id conflicts with existing evidence")
            connection.execute("INSERT OR IGNORE INTO capture_events VALUES (?, ?, ?, ?, ?, ?)", row)
    return {"event_ids": [row[0] for row in rows]}


@app.get("/v1/capture-events")
def list_capture_events(
    session_id: str | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    with database.connect() as connection:
        where = " WHERE session_id=?" if session_id is not None else ""
        args = ([session_id] if session_id is not None else []) + [limit, offset]
        rows = connection.execute(
            "SELECT * FROM capture_events" + where + " ORDER BY observed_at_utc_ms DESC, event_id LIMIT ? OFFSET ?", args
        ).fetchall()
    return {"items": [{"event_id": row["event_id"], "source_id": row["source_id"],
                       "payload": json.loads(row["payload_json"])} for row in rows], "limit": limit, "offset": offset}


@app.get("/v1/segments")
def list_segments(
    session_id: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    return {"items": database.list_segments(session_id, limit, offset), "limit": limit, "offset": offset}


@app.get("/v1/segments/{segment_id}")
def get_segment(segment_id: str) -> dict[str, Any]:
    result = database.get_segment(segment_id)
    if result is None:
        raise HTTPException(status_code=404, detail="segment not found")
    return result
