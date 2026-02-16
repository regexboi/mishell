from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import uuid

from mishell_mcp.config import SpeechConfig

from .types import TranscriptionError, TranscriptionRequest, TranscriptionResult


class OpenAIWhisperTranscriber:
    def transcribe(self, request: TranscriptionRequest, cfg: SpeechConfig) -> TranscriptionResult:
        if cfg.provider != "openai":
            raise TranscriptionError(
                f"Unsupported speech provider '{cfg.provider}'. Expected 'openai'.",
                status_code=500,
            )

        api_key = os.getenv(cfg.api_key_env)
        if not api_key:
            raise TranscriptionError(
                f"Speech endpoint is missing API key in env var '{cfg.api_key_env}'.",
                status_code=503,
            )

        model = request.model or cfg.model
        body, boundary = _encode_multipart(
            model=model,
            audio_bytes=request.audio_bytes,
            filename=request.filename,
            content_type=request.content_type,
            language=request.language,
            prompt=request.prompt,
            temperature=request.temperature,
        )

        base = cfg.base_url.rstrip("/")
        url = f"{base}/audio/transcriptions"
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        }
        http_request = urllib.request.Request(url=url, data=body, headers=headers, method="POST")

        try:
            with urllib.request.urlopen(http_request, timeout=cfg.request_timeout_s) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            msg = _extract_provider_error(detail) or str(exc)
            raise TranscriptionError(f"OpenAI transcription failed: {msg}", status_code=502) from exc
        except urllib.error.URLError as exc:
            raise TranscriptionError(f"OpenAI transcription request failed: {exc.reason}", status_code=502) from exc

        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise TranscriptionError("OpenAI transcription returned invalid JSON.", status_code=502) from exc

        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise TranscriptionError("OpenAI transcription response did not include text.", status_code=502)

        response_model = payload.get("model")
        model_name = response_model if isinstance(response_model, str) and response_model else model
        return TranscriptionResult(text=text.strip(), model=model_name)


def _encode_multipart(
    *,
    model: str,
    audio_bytes: bytes,
    filename: str,
    content_type: str,
    language: str | None,
    prompt: str | None,
    temperature: float | None,
) -> tuple[bytes, str]:
    boundary = f"mishell-{uuid.uuid4().hex}"
    safe_name = filename.replace('"', "")

    out = bytearray()

    def add_text(name: str, value: str) -> None:
        out.extend(f"--{boundary}\r\n".encode("utf-8"))
        out.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"))
        out.extend(value.encode("utf-8"))
        out.extend(b"\r\n")

    add_text("model", model)
    add_text("response_format", "json")
    if language:
        add_text("language", language)
    if prompt:
        add_text("prompt", prompt)
    if temperature is not None:
        add_text("temperature", str(temperature))

    out.extend(f"--{boundary}\r\n".encode("utf-8"))
    out.extend(
        f'Content-Disposition: form-data; name="file"; filename="{safe_name}"\r\n'.encode("utf-8")
    )
    out.extend(f"Content-Type: {content_type}\r\n\r\n".encode("utf-8"))
    out.extend(audio_bytes)
    out.extend(b"\r\n")
    out.extend(f"--{boundary}--\r\n".encode("utf-8"))

    return bytes(out), boundary


def _extract_provider_error(detail: str) -> str | None:
    try:
        payload = json.loads(detail)
    except json.JSONDecodeError:
        return detail.strip() or None

    error_obj = payload.get("error") if isinstance(payload, dict) else None
    if isinstance(error_obj, dict):
        msg = error_obj.get("message")
        if isinstance(msg, str) and msg.strip():
            return msg.strip()

    return detail.strip() or None
