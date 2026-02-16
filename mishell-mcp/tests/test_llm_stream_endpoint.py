from __future__ import annotations

import os
from pathlib import Path

from starlette.testclient import TestClient

from mishell_mcp.config import sample_policy_toml
from mishell_mcp.server import MishellApp

AUTH_HEADERS = {"x-api-key": "test-api-key"}


class _FakeResult:
    def __init__(
        self,
        chunks: list[str],
        *,
        all_messages: list[dict[str, int]] | None = None,
        fail_with: Exception | None = None,
    ):
        self._chunks = chunks
        self._all_messages = all_messages or []
        self._fail_with = fail_with

    async def stream_text(self, *, delta: bool):
        assert delta is True
        for chunk in self._chunks:
            yield chunk
        if self._fail_with is not None:
            raise self._fail_with

    def all_messages(self):
        return list(self._all_messages)


class _FakeRunStream:
    def __init__(self, result: _FakeResult):
        self._result = result

    async def __aenter__(self) -> _FakeResult:
        return self._result

    async def __aexit__(self, exc_type, exc, tb) -> bool:  # noqa: ANN001
        return False


class _FakeAgent:
    def __init__(
        self,
        result: _FakeResult,
        prompts: list[str],
        metadatas: list[dict[str, str]],
        histories: list[list[dict[str, int]] | None],
    ):
        self._result = result
        self._prompts = prompts
        self._metadatas = metadatas
        self._histories = histories

    def run_stream(
        self,
        prompt: str,
        *,
        metadata: dict[str, str],
        message_history=None,
    ) -> _FakeRunStream:
        self._prompts.append(prompt)
        self._metadatas.append(metadata)
        if message_history is None:
            self._histories.append(None)
        else:
            self._histories.append(list(message_history))
        return _FakeRunStream(self._result)


def _build_app(tmp_path: Path) -> MishellApp:
    os.environ["MISHELL_API_KEY"] = "test-api-key"
    cfg_path = tmp_path / "mishell.toml"
    cfg_path.write_text(sample_policy_toml(), encoding="utf-8")
    return MishellApp(config_path=cfg_path, dangerous=True)


def test_llm_stream_emits_delta_and_done_sse_events(tmp_path: Path, monkeypatch) -> None:
    app = _build_app(tmp_path)

    prompts: list[str] = []
    metadatas: list[dict[str, str]] = []
    histories: list[list[dict[str, int]] | None] = []
    seen_model: dict[str, str] = {}

    def fake_build_agent(model: str):
        seen_model["model"] = model
        return _FakeAgent(
            _FakeResult(["hel", "lo"], all_messages=[{"turn": 1}]),
            prompts,
            metadatas,
            histories,
        )

    monkeypatch.setattr("mishell_mcp.llm.routes._build_agent", fake_build_agent)

    with TestClient(app.http_app()) as client:
        response = client.post(
            "/v1/llm/stream",
            json={"text": "hello", "session_id": "android-client-a"},
            headers=AUTH_HEADERS,
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["cache-control"] == "no-cache"
    assert response.headers["connection"] == "keep-alive"
    assert prompts == ["hello"]
    assert metadatas == [{"session_id": "android-client-a"}]
    assert histories == [None]
    assert seen_model["model"] == "openai:gpt-5-mini"
    assert response.text == (
        'event: delta\ndata: {"text":"hel"}\n\n'
        'event: delta\ndata: {"text":"lo"}\n\n'
        "event: done\ndata: {}\n\n"
    )


def test_llm_stream_emits_error_event_for_invalid_payload(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        response = client.post("/v1/llm/stream", json={"text": "hello"}, headers=AUTH_HEADERS)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.text.startswith("event: error\ndata: {\"message\":\"Invalid request body:")
    assert "event: done" not in response.text


def test_llm_stream_emits_error_event_on_stream_exception(tmp_path: Path, monkeypatch) -> None:
    app = _build_app(tmp_path)

    prompts: list[str] = []
    metadatas: list[dict[str, str]] = []
    histories: list[list[dict[str, int]] | None] = []

    def fake_build_agent(_model: str):
        return _FakeAgent(
            _FakeResult(["partial"], fail_with=RuntimeError("boom")),
            prompts,
            metadatas,
            histories,
        )

    monkeypatch.setattr("mishell_mcp.llm.routes._build_agent", fake_build_agent)

    with TestClient(app.http_app()) as client:
        response = client.post(
            "/v1/llm/stream",
            json={"text": "hello", "session_id": "android-client-b"},
            headers=AUTH_HEADERS,
        )

    assert response.status_code == 200
    assert prompts == ["hello"]
    assert metadatas == [{"session_id": "android-client-b"}]
    assert histories == [None]
    assert response.text == (
        'event: delta\ndata: {"text":"partial"}\n\n'
        'event: error\ndata: {"message":"boom"}\n\n'
    )


def test_llm_stream_requires_auth_header(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        response = client.post("/v1/llm/stream", json={"text": "hello", "session_id": "android-client-a"})

    assert response.status_code == 401
    assert response.json()["ok"] is False


def test_llm_stream_uses_in_memory_history_for_same_session(tmp_path: Path, monkeypatch) -> None:
    app = _build_app(tmp_path)

    prompts: list[str] = []
    metadatas: list[dict[str, str]] = []
    histories: list[list[dict[str, int]] | None] = []

    responses = [
        _FakeResult(["first"], all_messages=[{"turn": 1}]),
        _FakeResult(["second"], all_messages=[{"turn": 1}, {"turn": 2}]),
    ]

    def fake_build_agent(_model: str):
        return _FakeAgent(responses.pop(0), prompts, metadatas, histories)

    monkeypatch.setattr("mishell_mcp.llm.routes._build_agent", fake_build_agent)

    with TestClient(app.http_app()) as client:
        first = client.post(
            "/v1/llm/stream",
            json={"text": "hello", "session_id": "android-client-c"},
            headers=AUTH_HEADERS,
        )
        second = client.post(
            "/v1/llm/stream",
            json={"text": "again", "session_id": "android-client-c"},
            headers=AUTH_HEADERS,
        )

    assert first.status_code == 200
    assert second.status_code == 200
    assert prompts == ["hello", "again"]
    assert metadatas == [{"session_id": "android-client-c"}, {"session_id": "android-client-c"}]
    assert histories == [None, [{"turn": 1}]]
