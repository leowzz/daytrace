from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class ASRUnavailableError(RuntimeError):
    """Raised when the configured ASR engine cannot run."""


@dataclass
class ASRChunk:
    start_ms: int
    end_ms: int
    text: str
    confidence: float | None = None
    words: list[dict[str, Any]] | None = None


@dataclass
class ASRResult:
    text: str
    language: str | None
    duration_ms: int | None
    confidence: float | None
    model: str
    provider: str
    chunks: list[ASRChunk] = field(default_factory=list)
    raw: dict[str, Any] | None = None


class MockASR:
    model = "mock"
    provider = "mock"

    def transcribe(self, audio_path: Path, language: str | None = None) -> ASRResult:
        return ASRResult(
            text="[mock transcript]",
            language=language or "und",
            duration_ms=None,
            confidence=1.0,
            model=self.model,
            provider=self.provider,
            chunks=[ASRChunk(0, 0, "[mock transcript]", 1.0)],
        )


class FasterWhisperASR:
    provider = "faster-whisper"

    def __init__(self, model_name: str, device: str, compute_type: str, cpu_threads: int, num_workers: int):
        try:
            from faster_whisper import WhisperModel
        except ImportError as exc:
            raise ASRUnavailableError(
                "faster-whisper is not installed; run `pip install -e \".[asr]\"` "
                "or set DAYTRACE_ASR_BACKEND=mock for API development"
            ) from exc
        resolved_device = "cpu" if device == "auto" else device
        self.model_name = model_name
        self.model = WhisperModel(
            model_name,
            device=resolved_device,
            compute_type=compute_type,
            cpu_threads=cpu_threads,
            num_workers=num_workers,
        )

    def transcribe(self, audio_path: Path, language: str | None = None) -> ASRResult:
        segments, info = self.model.transcribe(
            str(audio_path), language=language, word_timestamps=True, vad_filter=False
        )
        chunks: list[ASRChunk] = []
        text_parts: list[str] = []
        confidence_values: list[float] = []
        for segment in segments:
            segment_text = segment.text.strip()
            text_parts.append(segment_text)
            probability = float(segment.avg_logprob) if segment.avg_logprob is not None else None
            if probability is not None:
                confidence_values.append(probability)
            words = None
            if segment.words:
                words = [
                    {
                        "start_ms": round(word.start * 1000),
                        "end_ms": round(word.end * 1000),
                        "text": word.word.strip(),
                        "confidence": word.probability,
                    }
                    for word in segment.words
                ]
            chunks.append(
                ASRChunk(
                    start_ms=round(segment.start * 1000),
                    end_ms=round(segment.end * 1000),
                    text=segment_text,
                    confidence=probability,
                    words=words,
                )
            )
        duration_ms = round(info.duration * 1000) if info.duration is not None else None
        return ASRResult(
            text=" ".join(part for part in text_parts if part),
            language=info.language,
            duration_ms=duration_ms,
            confidence=sum(confidence_values) / len(confidence_values) if confidence_values else None,
            model=self.model_name,
            provider=self.provider,
            chunks=chunks,
            raw={"language_probability": info.language_probability},
        )


def build_asr(
    backend: str,
    model: str,
    device: str,
    compute_type: str,
    cpu_threads: int = 8,
    num_workers: int = 1,
):
    if backend == "mock":
        return MockASR()
    if backend in {"faster-whisper", "faster_whisper"}:
        return FasterWhisperASR(model, device, compute_type, cpu_threads, num_workers)
    raise ASRUnavailableError(f"Unsupported ASR backend: {backend}")
