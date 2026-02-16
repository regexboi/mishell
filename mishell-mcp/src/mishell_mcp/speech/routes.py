from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any, Callable, Protocol

from fastmcp import FastMCP
from starlette.datastructures import UploadFile
from starlette.requests import Request
from starlette.responses import JSONResponse

from mishell_mcp.config import PolicyConfig, SpeechConfig

from .openai_whisper import OpenAIWhisperTranscriber
from .types import TranscriptionError, TranscriptionRequest, TranscriptionResult


class AudioTranscriber(Protocol):
    def transcribe(self, request: TranscriptionRequest, cfg: SpeechConfig) -> TranscriptionResult: ...


class RequestValidationError(ValueError):
    pass


@dataclass(frozen=True)
class _ParsedRequest:
    request: TranscriptionRequest
    byte_count: int


def register_speech_routes(
    mcp: FastMCP,
    *,
    get_policy_config: Callable[[], PolicyConfig],
    transcriber: AudioTranscriber | None = None,
) -> None:
    impl = transcriber or OpenAIWhisperTranscriber()

    @mcp.custom_route("/api/speech/transcribe", methods=["POST"])
    async def speech_transcribe(request: Request) -> JSONResponse:
        speech_cfg = get_policy_config().speech
        if not speech_cfg.enabled:
            return JSONResponse({"ok": False, "error": "Speech endpoint is disabled."}, status_code=404)

        try:
            parsed = await _parse_request(request, speech_cfg)
            result = await asyncio.to_thread(impl.transcribe, parsed.request, speech_cfg)
        except RequestValidationError as exc:
            return JSONResponse({"ok": False, "error": str(exc)}, status_code=400)
        except TranscriptionError as exc:
            return JSONResponse({"ok": False, "error": str(exc)}, status_code=exc.status_code)
        except Exception:  # noqa: BLE001
            return JSONResponse({"ok": False, "error": "Unexpected transcription failure."}, status_code=500)

        return JSONResponse(
            {
                "ok": True,
                "text": result.text,
                "model": result.model,
                "audio": {
                    "filename": parsed.request.filename,
                    "content_type": parsed.request.content_type,
                    "bytes": parsed.byte_count,
                },
            }
        )


async def _parse_request(request: Request, cfg: SpeechConfig) -> _ParsedRequest:
    content_type = request.headers.get("content-type", "")
    query = request.query_params
    language = _clean_text(query.get("language"))
    prompt = _clean_text(query.get("prompt"))
    model = _clean_text(query.get("model"))
    temperature = _parse_temperature(query.get("temperature"))

    if "multipart/form-data" in content_type:
        form = await request.form()
        upload = _extract_upload(form)

        language = _clean_text(form.get("language")) or language
        prompt = _clean_text(form.get("prompt")) or prompt
        model = _clean_text(form.get("model")) or model

        form_temperature = _parse_temperature(form.get("temperature"))
        if form_temperature is not None:
            temperature = form_temperature

        audio_bytes = await upload.read()
        filename = upload.filename or "audio.bin"
        payload_content_type = upload.content_type or "application/octet-stream"
    else:
        audio_bytes = await request.body()
        filename = request.headers.get("x-filename") or "audio.bin"
        payload_content_type = content_type or "application/octet-stream"

    if not audio_bytes:
        raise RequestValidationError("Audio payload is empty.")
    if len(audio_bytes) > cfg.max_audio_bytes:
        raise RequestValidationError(f"Audio payload exceeds max_audio_bytes={cfg.max_audio_bytes}.")

    parsed = TranscriptionRequest(
        audio_bytes=audio_bytes,
        filename=filename,
        content_type=payload_content_type,
        language=language,
        prompt=prompt,
        temperature=temperature,
        model=model,
    )
    return _ParsedRequest(request=parsed, byte_count=len(audio_bytes))


def _extract_upload(form: Any) -> UploadFile:
    upload = form.get("audio") or form.get("file")
    if not isinstance(upload, UploadFile):
        raise RequestValidationError("Expected multipart file field 'audio' (or 'file').")
    return upload


def _clean_text(value: Any) -> str | None:
    if isinstance(value, str):
        cleaned = value.strip()
        return cleaned if cleaned else None
    return None


def _parse_temperature(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise RequestValidationError("temperature must be a number.") from exc
