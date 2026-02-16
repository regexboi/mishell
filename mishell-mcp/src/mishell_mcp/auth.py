from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass
from typing import Callable

from starlette.requests import Request
from starlette.responses import JSONResponse

AUTH_COOKIE_NAME = "mishell_auth"


@dataclass(frozen=True)
class AuthResult:
    ok: bool
    source: str | None = None


class ApiKeyAuthManager:
    def __init__(self, api_key: str, *, session_ttl_s: int):
        self._api_key = api_key
        self._session_ttl_s = session_ttl_s
        self._lock = threading.RLock()
        self._sessions: dict[str, float] = {}

    def authenticate_request(self, request: Request) -> AuthResult:
        # Allow direct API-key auth for non-browser clients.
        header_key = request.headers.get("x-api-key")
        if header_key and self.verify_api_key(header_key):
            return AuthResult(ok=True, source="x-api-key")

        authz = request.headers.get("authorization", "")
        if authz.startswith("Bearer "):
            bearer = authz[7:].strip()
            if bearer and self.verify_api_key(bearer):
                return AuthResult(ok=True, source="authorization")

        token = request.cookies.get(AUTH_COOKIE_NAME)
        if token and self._is_valid_session_token(token):
            return AuthResult(ok=True, source="session-cookie")

        return AuthResult(ok=False)

    def verify_api_key(self, api_key: str | None) -> bool:
        if not api_key:
            return False
        return secrets.compare_digest(self._api_key, api_key)

    def create_session_token(self) -> str:
        token = secrets.token_urlsafe(32)
        expires_at = time.time() + self._session_ttl_s
        with self._lock:
            self._sessions[token] = expires_at
            self._prune_expired(now=time.time())
        return token

    def clear_session_token(self, token: str | None) -> None:
        if not token:
            return
        with self._lock:
            self._sessions.pop(token, None)

    @property
    def session_ttl_s(self) -> int:
        return self._session_ttl_s

    def _is_valid_session_token(self, token: str) -> bool:
        now = time.time()
        with self._lock:
            self._prune_expired(now=now)
            expiry = self._sessions.get(token)
            if expiry is None:
                return False
            if expiry < now:
                self._sessions.pop(token, None)
                return False
            return True

    def _prune_expired(self, *, now: float) -> None:
        expired = [token for token, expiry in self._sessions.items() if expiry < now]
        for token in expired:
            self._sessions.pop(token, None)


class ApiKeyHTTPMiddleware:
    def __init__(
        self,
        app,
        *,
        get_auth_manager: Callable[[], ApiKeyAuthManager | None],
        public_paths: set[str] | None = None,
    ):
        self.app = app
        self._get_auth_manager = get_auth_manager
        self._public_paths = public_paths or {"/", "/api/auth/status", "/api/auth/login"}

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        auth_manager = self._get_auth_manager()
        if auth_manager is None:
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        if path in self._public_paths:
            await self.app(scope, receive, send)
            return

        request = Request(scope, receive=receive)
        auth = auth_manager.authenticate_request(request)
        if auth.ok:
            await self.app(scope, receive, send)
            return

        response = JSONResponse(
            {
                "ok": False,
                "error": "Unauthorized. Provide API key via x-api-key header, bearer token, or UI login.",
            },
            status_code=401,
        )
        await response(scope, receive, send)
