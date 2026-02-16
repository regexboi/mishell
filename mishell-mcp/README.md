# mishell-mcp

FastMCP server exposing guarded shell access with:
- raw bash command input (Claude/Codex style)
- allowlisted commands + forbidden command rules
- forbidden path globs (including `*.env` style)
- localhost admin UI for config view/edit/reload
- E2B sandbox execution by default (`--dangerous` required for local host shell)

## Install

```bash
make setup
```

## Run

Default safe mode (E2B sandbox) + HTTP UI:

```bash
make run-http
```

Default safe mode (E2B sandbox) + STDIO MCP:

```bash
make run-stdio
```

Local host shell mode (dangerous, explicit opt-in):

```bash
make run-http-dangerous
```

## Test

```bash
make test
```

## E2B Setup

Mishell reads `E2B_API_KEY` first, then `E2B_KEY` (including from local `.env`).

```bash
echo 'E2B_KEY=your-key' >> .env
```

E2B sandbox settings live under `[e2b]` in `mishell.toml`:

```toml
[e2b]
template = "mishell-template" # template name or id, empty uses E2B default
timeout_s = 600
secure = true
allow_internet_access = true
start_cwd = "/home/user"
```

You can build a dedicated template using `e2b/template.toml` and `e2b/mishell.Dockerfile`:

```bash
make e2b-template-build
```

## MCP Tools

- `shell_policy_get`
  - Returns effective allowed commands, forbidden rules, forbidden paths, defaults.
  - Includes execution mode (`e2b-sandbox` or `local-dangerous`) and E2B settings.
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
