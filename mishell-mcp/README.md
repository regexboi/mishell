# mishell-mcp

FastMCP server exposing guarded shell access with:
- raw bash command input (Claude/Codex style)
- allowlisted commands + forbidden command rules
- forbidden path globs (including `*.env` style)
- localhost admin UI for config view/edit/reload

## Install

```bash
uv sync --extra dev
```

## Run

HTTP + UI (localhost `127.0.0.1:8067`):

```bash
uv run mishell-mcp serve-http --config ./mishell.toml
```

STDIO MCP mode:

```bash
uv run mishell-mcp serve-stdio --config ./mishell.toml
```

## Test

```bash
uv run pytest -q
```

## MCP Tools

- `shell_policy_get`
  - Returns effective allowed commands, forbidden rules, forbidden paths, defaults.
  - Tool description tells LLM to call this first.

- `shell_exec`
  - Executes raw bash command in a persistent shell session.
  - Inputs: `command`, optional `workdir`, optional `session_id`, optional time/output caps.
  - Rejected commands always include allowed commands + forbidden paths.

- `shell_session_reset`
  - Resets a session process.

## Admin UI endpoints (HTTP mode)

- `GET /` page
- `GET /api/config` read TOML + status
- `PUT /api/config` save TOML (validate first)
- `POST /api/reload` apply updated config

## Notes

- Config reload is intentionally **not** exposed as MCP tool.
- Host binding is restricted to `127.0.0.1`.
- TOON support is a required dependency and is returned on structured responses where useful.
