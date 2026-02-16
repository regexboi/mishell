from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from .server import MishellApp


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Mishell MCP server")
    parser.add_argument(
        "mode",
        choices=["serve-http", "serve-stdio"],
        help="Transport mode: HTTP (with UI) or STDIO",
    )
    parser.add_argument(
        "--config",
        default="./mishell.toml",
        help="Path to policy config TOML (default: ./mishell.toml)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Override HTTP port (defaults to config; fallback 8067)",
    )
    parser.add_argument(
        "--dangerous",
        action="store_true",
        help="Run commands in the local host shell. Without this flag, commands run in E2B sandbox.",
    )
    return parser


def _load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or key in os.environ:
            continue
        value = value.strip().strip('"').strip("'")
        os.environ[key] = value


def _resolve_e2b_api_key(config_path: Path) -> str | None:
    if os.getenv("E2B_API_KEY"):
        return os.getenv("E2B_API_KEY")
    if os.getenv("E2B_KEY"):
        return os.getenv("E2B_KEY")

    candidates: list[Path] = []
    cwd_env = Path.cwd() / ".env"
    cfg_env = config_path.resolve().parent / ".env"
    for candidate in (cwd_env, cfg_env):
        if candidate not in candidates:
            candidates.append(candidate)

    for candidate in candidates:
        _load_env_file(candidate)

    return os.getenv("E2B_API_KEY") or os.getenv("E2B_KEY")


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    config_path = Path(args.config)
    e2b_api_key = None
    if not args.dangerous:
        e2b_api_key = _resolve_e2b_api_key(config_path)

    try:
        app = MishellApp(
            config_path=config_path,
            dangerous=args.dangerous,
            e2b_api_key=e2b_api_key,
        )
    except RuntimeError as exc:
        parser.error(str(exc))
        return
    cfg = app.state.config.get_config()

    if args.mode == "serve-http":
        host = cfg.server.http_host
        port = args.port if args.port is not None else cfg.server.http_port
        app.mcp.run(transport="http", host=host, port=port)
        return

    if args.mode == "serve-stdio":
        app.mcp.run(transport="stdio")
        return

    parser.print_help()
    sys.exit(2)
