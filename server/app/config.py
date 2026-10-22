from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    database_url: str
    asr_backend: str
    asr_model: str
    asr_device: str
    asr_compute_type: str
    asr_cpu_threads: int
    asr_num_workers: int
    asr_language: str | None
    max_upload_bytes: int

    @property
    def audio_dir(self) -> Path:
        return self.data_dir / "audio"


def load_settings() -> Settings:
    data_dir = Path(os.getenv("DAYTRACE_DATA_DIR", "./data")).expanduser()
    database_url = os.getenv(
        "DAYTRACE_DATABASE_URL", f"sqlite:///{(data_dir / 'daytrace.sqlite3').as_posix()}"
    )
    language = os.getenv("DAYTRACE_ASR_LANGUAGE", "").strip() or None
    return Settings(
        data_dir=data_dir,
        database_url=database_url,
        asr_backend=os.getenv("DAYTRACE_ASR_BACKEND", "faster-whisper").lower(),
        asr_model=os.getenv("DAYTRACE_ASR_MODEL", "small"),
        asr_device=os.getenv("DAYTRACE_ASR_DEVICE", "auto"),
        asr_compute_type=os.getenv("DAYTRACE_ASR_COMPUTE_TYPE", "int8"),
        asr_cpu_threads=_env_int("DAYTRACE_ASR_CPU_THREADS", 8),
        asr_num_workers=_env_int("DAYTRACE_ASR_NUM_WORKERS", 1),
        asr_language=language,
        max_upload_bytes=_env_int("DAYTRACE_MAX_UPLOAD_BYTES", 128 * 1024 * 1024),
    )
