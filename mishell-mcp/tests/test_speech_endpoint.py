from __future__ import annotations

import os
from pathlib import Path

from starlette.testclient import TestClient

from mishell_mcp.config import sample_policy_toml
from mishell_mcp.server import MishellApp
from mishell_mcp.speech.openai_whisper import OpenAIWhisperTranscriber
from mishell_mcp.speech.types import TranscriptionResult


def _build_app(tmp_path: Path, *, speech_enabled: bool = True) -> MishellApp:
    os.environ["MISHELL_API_KEY"] = "test-api-key"
    cfg_path = tmp_path / "mishell.toml"
    toml = sample_policy_toml()
    if not speech_enabled:
        toml = toml.replace("[speech]\nenabled = true", "[speech]\nenabled = false")
    cfg_path.write_text(toml, encoding="utf-8")
    return MishellApp(config_path=cfg_path, dangerous=True)


AUTH_HEADERS = {"x-api-key": "test-api-key"}


def test_speech_transcribe_accepts_multipart_audio(tmp_path: Path, monkeypatch) -> None:
    app = _build_app(tmp_path)

    def fake_transcribe(self, request, cfg):
        assert request.filename == "clip.m4a"
        assert request.content_type == "audio/mp4"
        assert request.language == "en"
        assert request.prompt == "quick note"
        assert request.temperature == 0.2
        assert request.audio_bytes == b"audio-bytes"
        assert request.model == "whisper-1"
        assert cfg.model == "whisper-1"
        return TranscriptionResult(text="hello world", model="whisper-1")

    monkeypatch.setattr(OpenAIWhisperTranscriber, "transcribe", fake_transcribe)

    with TestClient(app.http_app()) as client:
        response = client.post(
            "/api/speech/transcribe",
            files={"audio": ("clip.m4a", b"audio-bytes", "audio/mp4")},
            data={
                "language": "en",
                "prompt": "quick note",
                "temperature": "0.2",
                "model": "whisper-1",
            },
            headers=AUTH_HEADERS,
        )

    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert payload["text"] == "hello world"
    assert payload["model"] == "whisper-1"
    assert payload["audio"]["filename"] == "clip.m4a"


def test_speech_transcribe_accepts_raw_audio_body(tmp_path: Path, monkeypatch) -> None:
    app = _build_app(tmp_path)

    def fake_transcribe(self, request, cfg):
        assert request.filename == "android_recording.ogg"
        assert request.content_type == "audio/ogg"
        assert request.language == "en"
        assert request.prompt == "from android"
        assert request.temperature == 0.0
        assert request.model == "whisper-1"
        assert request.audio_bytes == b"\x00\x01raw-audio"
        assert cfg.model == "whisper-1"
        return TranscriptionResult(text="android transcript", model="whisper-1")

    monkeypatch.setattr(OpenAIWhisperTranscriber, "transcribe", fake_transcribe)

    with TestClient(app.http_app()) as client:
        response = client.post(
            "/api/speech/transcribe?language=en&prompt=from%20android&temperature=0&model=whisper-1",
            content=b"\x00\x01raw-audio",
            headers={
                "content-type": "audio/ogg",
                "x-filename": "android_recording.ogg",
                "x-api-key": "test-api-key",
            },
        )

    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert payload["text"] == "android transcript"
    assert payload["audio"]["filename"] == "android_recording.ogg"
    assert payload["audio"]["content_type"] == "audio/ogg"


def test_speech_transcribe_rejects_empty_audio(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        response = client.post("/api/speech/transcribe", content=b"", headers=AUTH_HEADERS)

    assert response.status_code == 400
    payload = response.json()
    assert payload["ok"] is False
    assert "empty" in payload["error"].lower()


def test_speech_transcribe_returns_404_when_disabled(tmp_path: Path) -> None:
    app = _build_app(tmp_path, speech_enabled=False)

    with TestClient(app.http_app()) as client:
        response = client.post(
            "/api/speech/transcribe",
            files={"audio": ("clip.wav", b"abc", "audio/wav")},
            headers=AUTH_HEADERS,
        )

    assert response.status_code == 404
    payload = response.json()
    assert payload["ok"] is False
    assert "disabled" in payload["error"].lower()
