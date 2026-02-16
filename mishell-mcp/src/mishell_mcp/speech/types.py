from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TranscriptionRequest:
    audio_bytes: bytes
    filename: str
    content_type: str
    language: str | None = None
    prompt: str | None = None
    temperature: float | None = None
    model: str | None = None


@dataclass(frozen=True)
class TranscriptionResult:
    text: str
    model: str


class TranscriptionError(RuntimeError):
    def __init__(self, message: str, *, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code
