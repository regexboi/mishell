from __future__ import annotations

import shlex
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse

from .config import ConfigManager, PolicyConfig
from .policy import PolicyEngine
from .shell_session import SessionManager
from .toon_utils import encode_toon
from .ui import UI_HTML


@dataclass
class AppState:
    config_path: Path
    execution_mode: str
    config: ConfigManager
    policy: PolicyEngine
    sessions: SessionManager

    def reload(self) -> PolicyConfig:
        cfg = self.config.reload_from_disk()
        self.policy = PolicyEngine(cfg)
        self.sessions.reconfigure(cfg)
        return cfg


class MishellApp:
    def __init__(self, config_path: str | Path, *, dangerous: bool = False, e2b_api_key: str | None = None):
        self.state = self._build_state(config_path, dangerous=dangerous, e2b_api_key=e2b_api_key)
        self.mcp = FastMCP("Mishell MCP")
        self._register_tools()
        self._register_routes()

    @staticmethod
    def _build_state(config_path: str | Path, *, dangerous: bool, e2b_api_key: str | None) -> AppState:
        manager = ConfigManager(config_path)
        manager.load_startup()
        cfg = manager.get_config()
        execution_mode = "local-dangerous" if dangerous else "e2b-sandbox"
        return AppState(
            config_path=Path(config_path),
            execution_mode=execution_mode,
            config=manager,
            policy=PolicyEngine(cfg),
            sessions=SessionManager(
                cfg,
                backend="local" if dangerous else "e2b",
                e2b_api_key=e2b_api_key,
            ),
        )

    def _register_tools(self) -> None:
        @self.mcp.tool
        def shell_policy_get() -> dict[str, Any]:
            """Return current shell policy. Call this first to avoid rejected commands."""
            cfg = self.state.config.get_config()
            st = self.state.config.get_status()
            payload = {
                "ok": True,
                "policy_hash": self.state.config.policy_hash(),
                "source": st.source,
                "warning": st.warning,
                "execution_mode": self.state.execution_mode,
                "allowed_commands": cfg.allowed_commands,
                "forbidden_command_rules": [rule.model_dump(exclude_none=True) for rule in cfg.forbidden_command_rules],
                "forbidden_paths": cfg.forbidden_paths,
                "defaults": cfg.defaults.model_dump(),
                "e2b": cfg.e2b.model_dump(),
            }
            return {
                "txt": _policy_text(payload),
                "toon": encode_toon(payload),
                "data": payload,
            }

        @self.mcp.tool
        def shell_exec(
            command: str,
            workdir: str | None = None,
            session_id: str | None = None,
            timeout_s: int | None = None,
            max_output_chars: int | None = None,
        ) -> dict[str, Any]:
            """Run a bash command string in a persistent session. Use shell_policy_get first. If blocked, response includes allowed commands and path rules."""
            sid = session_id or "default"
            cfg = self.state.config.get_config()

            session = self.state.sessions.get(sid)
            current_cwd = session.get_cwd(timeout_s=3)

            effective = command
            if workdir:
                effective = f"cd -- {shlex.quote(workdir)} && {command}"

            decision = self.state.policy.evaluate(effective, cwd=current_cwd)
            if not decision.ok:
                blocked_payload = {
                    "ok": False,
                    "code": decision.code,
                    "why": decision.why,
                    "sid": sid,
                    "allowed_commands": cfg.allowed_commands,
                    "forbidden_paths": cfg.forbidden_paths,
                }
                return {
                    "txt": _blocked_text(blocked_payload),
                    "toon": encode_toon(blocked_payload),
                    "data": blocked_payload,
                }

            timeout = timeout_s if timeout_s is not None else cfg.defaults.timeout_s
            output_cap = max_output_chars if max_output_chars is not None else cfg.defaults.max_output_chars

            started = time.monotonic()
            outcome = session.run(effective, timeout_s=timeout, max_output_chars=output_cap)
            elapsed_ms = int((time.monotonic() - started) * 1000)

            payload = {
                "ok": outcome.ok,
                "ec": outcome.ec,
                "sid": sid,
                "cwd": outcome.cwd,
                "out": outcome.out,
                "err": outcome.err,
                "tr": outcome.truncated,
                "to": outcome.timed_out,
                "t_ms": elapsed_ms,
            }

            return {
                "txt": _exec_text(payload),
                "data": payload,
            }

        @self.mcp.tool
        def shell_session_reset(session_id: str | None = None) -> dict[str, Any]:
            """Reset a shell session to a clean state."""
            sid = session_id or "default"
            self.state.sessions.reset(sid)
            payload = {"ok": True, "sid": sid, "msg": "session reset"}
            return {
                "txt": f"ok sid={sid} session reset",
                "data": payload,
            }

    def _register_routes(self) -> None:
        @self.mcp.custom_route("/", methods=["GET"])
        async def index(_: Request) -> HTMLResponse:
            return HTMLResponse(UI_HTML)

        @self.mcp.custom_route("/api/config", methods=["GET"])
        async def get_config(_: Request) -> JSONResponse:
            st = self.state.config.get_status()
            return JSONResponse(
                {
                    "ok": True,
                    "toml": self.state.config.get_raw_toml(),
                    "policy_hash": self.state.config.policy_hash(),
                    "source": st.source,
                    "warning": st.warning,
                }
            )

        @self.mcp.custom_route("/api/config", methods=["PUT"])
        async def put_config(request: Request) -> JSONResponse:
            text = (await request.body()).decode("utf-8", errors="replace")
            try:
                self.state.config.save_text(text)
                return JSONResponse({"ok": True, "msg": "saved"})
            except Exception as exc:  # noqa: BLE001
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=400)

        @self.mcp.custom_route("/api/reload", methods=["POST"])
        async def reload_config(_: Request) -> JSONResponse:
            try:
                cfg = self.state.reload()
                st = self.state.config.get_status()
                summary = f"allowed={len(cfg.allowed_commands)} forbidden_paths={len(cfg.forbidden_paths)}"
                return JSONResponse(
                    {
                        "ok": True,
                        "policy_hash": self.state.config.policy_hash(),
                        "source": st.source,
                        "warning": st.warning,
                        "summary": summary,
                        "toml": self.state.config.get_raw_toml(),
                    }
                )
            except Exception as exc:  # noqa: BLE001
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=400)


def _policy_text(payload: dict[str, Any]) -> str:
    allowed = ",".join(payload["allowed_commands"])
    forb_paths = ",".join(payload["forbidden_paths"])
    warning = payload.get("warning")
    base = [
        f"ok policy_hash={payload['policy_hash']} source={payload['source']} mode={payload['execution_mode']}",
        f"allowed_commands:{allowed}",
        f"forbidden_paths:{forb_paths}",
    ]
    if warning:
        base.append(f"warning:{warning}")
    return "\n".join(base)



def _blocked_text(payload: dict[str, Any]) -> str:
    allowed = ",".join(payload["allowed_commands"])
    forb = ",".join(payload["forbidden_paths"])
    return (
        f"blocked code={payload['code']} why={payload['why']}\n"
        f"allowed_commands:{allowed}\n"
        f"forbidden_paths:{forb}"
    )



def _exec_text(payload: dict[str, Any]) -> str:
    head = f"ok={str(payload['ok']).lower()} ec={payload['ec']} sid={payload['sid']} cwd={payload['cwd']} t_ms={payload['t_ms']} tr={str(payload['tr']).lower()}"
    parts = [head]
    if payload["out"]:
        parts.append(f"stdout:\n{payload['out']}")
    if payload["err"]:
        parts.append(f"stderr:\n{payload['err']}")
    return "\n".join(parts)
