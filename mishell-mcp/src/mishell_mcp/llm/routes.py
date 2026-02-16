from __future__ import annotations

import asyncio
import json
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

from fastmcp import FastMCP
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.requests import Request
from starlette.responses import StreamingResponse

DEFAULT_STREAM_MODEL = "openai:gpt-5-mini"
SESSION_TTL_SECONDS = 60 * 30
MAX_SESSION_COUNT = 256


class StreamRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    text: str = Field(min_length=1)
    session_id: str = Field(min_length=1)


@dataclass
class _SessionEntry:
    messages: list[Any]
    updated_at_monotonic: float


class _SessionMemoryStore:
    def __init__(self, *, ttl_seconds: int = SESSION_TTL_SECONDS, max_sessions: int = MAX_SESSION_COUNT):
        self._ttl_seconds = ttl_seconds
        self._max_sessions = max_sessions
        self._sessions: dict[str, _SessionEntry] = {}
        self._lock = asyncio.Lock()

    async def get(self, session_id: str) -> list[Any] | None:
        now = time.monotonic()
        async with self._lock:
            self._prune_locked(now)
            entry = self._sessions.get(session_id)
            if entry is None:
                return None
            entry.updated_at_monotonic = now
            return list(entry.messages)

    async def save(self, session_id: str, messages: list[Any]) -> None:
        now = time.monotonic()
        async with self._lock:
            self._prune_locked(now)
            self._sessions[session_id] = _SessionEntry(messages=list(messages), updated_at_monotonic=now)
            self._enforce_capacity_locked()

    def _prune_locked(self, now: float) -> None:
        expired = [
            sid
            for sid, entry in self._sessions.items()
            if now - entry.updated_at_monotonic > self._ttl_seconds
        ]
        for sid in expired:
            self._sessions.pop(sid, None)

    def _enforce_capacity_locked(self) -> None:
        while len(self._sessions) > self._max_sessions:
            oldest_session = min(
                self._sessions.items(),
                key=lambda item: item[1].updated_at_monotonic,
            )[0]
            self._sessions.pop(oldest_session, None)


def register_llm_routes(mcp: FastMCP) -> None:
    session_store = _SessionMemoryStore()

    @mcp.custom_route("/v1/llm/stream", methods=["POST"])
    async def llm_stream(request: Request) -> StreamingResponse:
        try:
            payload = await request.json()
            parsed = StreamRequest.model_validate(payload)
        except (json.JSONDecodeError, ValidationError, TypeError) as exc:
            return _streaming_response(_error_only_stream(f"Invalid request body: {exc}"))

        return _streaming_response(
            _text_delta_stream(
                request=request,
                user_text=parsed.text,
                session_id=parsed.session_id,
                session_store=session_store,
            )
        )


async def _text_delta_stream(
    request: Request,
    user_text: str,
    session_id: str,
    session_store: _SessionMemoryStore,
) -> AsyncIterator[bytes]:
    history = await session_store.get(session_id)

    try:
        agent = _build_agent(DEFAULT_STREAM_MODEL)
        async with agent.run_stream(
            user_text,
            message_history=history or None,
            metadata={
                "session_id": session_id,
            },
        ) as result:
            async for chunk in result.stream_text(delta=True):
                if await _request_disconnected(request):
                    return
                yield _sse_event("delta", {"text": chunk})
            if await _request_disconnected(request):
                return
            await session_store.save(session_id, list(result.all_messages()))
        yield _sse_event("done", {})
    except Exception as exc:  # noqa: BLE001
        if await _request_disconnected(request):
            return
        yield _sse_event("error", {"message": str(exc)})


async def _error_only_stream(message: str) -> AsyncIterator[bytes]:
    yield _sse_event("error", {"message": message})


async def _request_disconnected(request: Request) -> bool:
    try:
        return await request.is_disconnected()
    except Exception:  # noqa: BLE001
        return False


def _build_agent(model: str) -> Any:
    try:
        from pydantic_ai import Agent
    except ImportError as exc:
        raise RuntimeError(
            "pydantic_ai is required for /v1/llm/stream. Install pydantic-ai-slim[openai]."
        ) from exc

    return Agent(model)


def _streaming_response(stream: AsyncIterator[bytes]) -> StreamingResponse:
    return StreamingResponse(
        stream,
        media_type="text/event-stream",
        headers={
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )


def _sse_event(event_name: str, payload: dict[str, Any]) -> bytes:
    data = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    return f"event: {event_name}\ndata: {data}\n\n".encode("utf-8")
