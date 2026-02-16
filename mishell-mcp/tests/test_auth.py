from __future__ import annotations

import os
from pathlib import Path

from starlette.testclient import TestClient

from mishell_mcp.config import sample_policy_toml
from mishell_mcp.server import MishellApp


def _build_app(tmp_path: Path) -> MishellApp:
    os.environ["MISHELL_API_KEY"] = "test-api-key"
    cfg_path = tmp_path / "mishell.toml"
    cfg_path.write_text(sample_policy_toml(), encoding="utf-8")
    return MishellApp(config_path=cfg_path, dangerous=True)


def test_homepage_is_public_but_api_is_protected(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        home = client.get("/")
        assert home.status_code == 200

        cfg = client.get("/api/config")
        assert cfg.status_code == 401
        assert cfg.json()["ok"] is False


def test_login_sets_cookie_and_unlocks_api(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        bad_login = client.post("/api/auth/login", json={"api_key": "wrong"})
        assert bad_login.status_code == 401

        login = client.post("/api/auth/login", json={"api_key": "test-api-key"})
        assert login.status_code == 200
        assert login.json()["ok"] is True

        cfg = client.get("/api/config")
        assert cfg.status_code == 200
        assert cfg.json()["ok"] is True


def test_header_api_key_unlocks_api_and_mcp_endpoint(tmp_path: Path) -> None:
    app = _build_app(tmp_path)

    with TestClient(app.http_app()) as client:
        cfg = client.get("/api/config", headers={"x-api-key": "test-api-key"})
        assert cfg.status_code == 200
        assert cfg.json()["ok"] is True

        mcp = client.get("/mcp", headers={"x-api-key": "test-api-key"})
        assert mcp.status_code != 401

        blocked_mcp = client.get("/mcp")
        assert blocked_mcp.status_code == 401


def test_app_can_start_without_auth_key_for_stdio_mode(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv("MISHELL_API_KEY", raising=False)
    cfg_path = tmp_path / "mishell.toml"
    cfg_path.write_text(sample_policy_toml(), encoding="utf-8")

    app = MishellApp(
        config_path=cfg_path,
        dangerous=True,
        require_http_auth_key=False,
    )
    assert app.state.auth is None
