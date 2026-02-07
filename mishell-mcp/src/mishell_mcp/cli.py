from __future__ import annotations

import argparse
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
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    app = MishellApp(config_path=Path(args.config))
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
