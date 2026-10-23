from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


SCHEMA = """
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS audio_segments (
    segment_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    started_at_utc_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    codec TEXT NOT NULL,
    capture_mode TEXT,
    vad_enabled INTEGER,
    bitrate_kbps INTEGER,
    source_ref TEXT,
    original_filename TEXT NOT NULL,
    mime_type TEXT,
    file_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('processing', 'completed', 'failed')),
    error TEXT,
    created_at_utc TEXT NOT NULL,
    updated_at_utc TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audio_segments_session_time
    ON audio_segments(session_id, started_at_utc_ms);

CREATE TABLE IF NOT EXISTS capture_events (
    event_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    observed_at_utc_ms INTEGER NOT NULL,
    event TEXT NOT NULL,
    payload_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_capture_events_session_time
    ON capture_events(session_id, observed_at_utc_ms);

CREATE TABLE IF NOT EXISTS transcripts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    segment_id TEXT NOT NULL UNIQUE REFERENCES audio_segments(segment_id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    language TEXT,
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    duration_ms INTEGER,
    confidence REAL,
    raw_json TEXT,
    created_at_utc TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS transcript_chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transcript_id INTEGER NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL,
    text TEXT NOT NULL,
    confidence REAL,
    words_json TEXT,
    UNIQUE(transcript_id, chunk_index)
);
"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Database:
    def __init__(self, database_url: str, data_dir: Path):
        if not database_url.startswith("sqlite:///"):
            raise ValueError("Only sqlite:/// database URLs are supported by this prototype")
        raw_path = database_url.removeprefix("sqlite:///")
        self.path = Path(raw_path).expanduser()
        if not self.path.is_absolute():
            self.path = (Path.cwd() / self.path).resolve()
        self.data_dir = data_dir

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    def init(self) -> None:
        with self.connect() as connection:
            connection.executescript(SCHEMA)
            columns = {row[1] for row in connection.execute("PRAGMA table_info(audio_segments)")}
            if "metadata_json" not in columns:
                connection.execute("ALTER TABLE audio_segments ADD COLUMN metadata_json TEXT")

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self.connect()
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def get_segment(self, segment_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT * FROM audio_segments WHERE segment_id = ?", (segment_id,)
            ).fetchone()
            if row is None:
                return None
            result = dict(row)
            result["metadata"] = json.loads(result.pop("metadata_json") or "{}")
            transcript = connection.execute(
                "SELECT * FROM transcripts WHERE segment_id = ?", (segment_id,)
            ).fetchone()
            if transcript:
                result["transcript"] = dict(transcript)
                result["transcript"].pop("raw_json", None)
                chunks = connection.execute(
                    "SELECT chunk_index, start_ms, end_ms, text, confidence, words_json "
                    "FROM transcript_chunks WHERE transcript_id = ? ORDER BY chunk_index",
                    (transcript["id"],),
                ).fetchall()
                result["transcript"]["chunks"] = []
                for chunk in chunks:
                    item = dict(chunk)
                    item["words"] = json.loads(item.pop("words_json")) if item["words_json"] else None
                    result["transcript"]["chunks"].append(item)
                result["transcript"].pop("id", None)
            if result.get("status") == "completed":
                result.pop("error", None)
            return result

    def list_segments(
        self, session_id: str | None, limit: int, offset: int,
        status: str | None = None, q: str | None = None,
        from_utc_ms: int | None = None, to_utc_ms: int | None = None,
    ) -> dict[str, Any]:
        clauses: list[str] = []
        args: list[Any] = []
        for clause, value in [
            ("s.session_id = ?", session_id),
            ("s.status = ?", status),
            ("s.started_at_utc_ms < ?", to_utc_ms),
        ]:
            if value is not None:
                clauses.append(clause)
                args.append(value)
        if from_utc_ms is not None:
            clauses.append("(s.started_at_utc_ms + s.duration_ms > ? OR "
                           "(s.duration_ms = 0 AND s.started_at_utc_ms >= ?))")
            args.extend([from_utc_ms, from_utc_ms])
        if q:
            clauses.append("(instr(lower(s.segment_id), lower(?)) > 0 OR "
                           "instr(lower(s.session_id), lower(?)) > 0 OR "
                           "instr(lower(COALESCE(t.text, '')), lower(?)) > 0)")
            args.extend([q, q, q])
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        source = " FROM audio_segments s LEFT JOIN transcripts t ON t.segment_id = s.segment_id"
        with self.connect() as connection:
            # Keep counts and the page in one read snapshot during concurrent uploads.
            connection.execute("BEGIN")
            summary = dict(connection.execute(
                "SELECT COUNT(*) AS total, COALESCE(SUM(s.duration_ms), 0) AS duration_ms, "
                "COUNT(CASE WHEN s.status = 'completed' THEN 1 END) AS completed, "
                "COUNT(CASE WHEN s.status = 'failed' THEN 1 END) AS failed, "
                "COUNT(CASE WHEN s.status = 'processing' THEN 1 END) AS processing"
                + source + where, args,
            ).fetchone())
            rows = connection.execute(
                "SELECT s.*, substr(t.text, 1, 240) AS transcript_preview, "
                "t.model AS asr_model, t.provider AS asr_provider, t.language AS asr_language"
                + source + where + " ORDER BY s.started_at_utc_ms DESC, s.segment_id LIMIT ? OFFSET ?",
                args + [limit, offset],
            ).fetchall()
        return {"items": [dict(row) for row in rows], "total": summary["total"],
                "summary": summary, "limit": limit, "offset": offset}
