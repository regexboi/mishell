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

Bruno API collection (one request per HTTP endpoint, with assertions) is available in `/Users/mishca/scripts/mishell/mishell-mcp/bruno`.

Run the Docker-backed E2B template Playwright integration test (opt-in, slower):

```bash
RUN_E2B_TEMPLATE_TESTS=1 uv run pytest -q tests/test_e2b_template_playwright.py
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

`allowed_commands` supports glob patterns for binary names (for example, `playwright*`).

You can build a dedicated template using `e2b/template.toml` and `e2b/mishell.Dockerfile`:

```bash
make e2b-template-build
```

## API Key Auth (HTTP)

HTTP mode is protected by API key auth, including `/mcp` and all `/api/*` routes (except auth bootstrap routes).

Set key in `.env` (or your environment):

```bash
echo 'MISHELL_API_KEY=your-key' >> .env
```

Browser flow:
- `GET /` is public and shows the login form.
- Submit key to `POST /api/auth/login` to receive an auth session cookie.
- Use `POST /api/auth/logout` to lock the UI.

Non-browser clients can authenticate per request using either:
- `x-api-key: your-key`
- `Authorization: Bearer your-key`

## Speech-to-Text Setup (Whisper)

The HTTP server includes `POST /api/speech/transcribe` for audio transcription via OpenAI.

Set your key (or change `speech.api_key_env` in `mishell.toml`):

```bash
echo 'OPENAI_API_KEY=your-key' >> .env
```

Example request:

```bash
curl -sS -X POST \
  -F "audio=@/path/to/audio.m4a" \
  -F "language=en" \
  http://127.0.0.1:8067/api/speech/transcribe
```

Response shape:

```json
{
  "ok": true,
  "text": "transcribed text here",
  "model": "whisper-1",
  "audio": {
    "filename": "audio.m4a",
    "content_type": "audio/mp4",
    "bytes": 12345
  }
}
```

## LLM Text Streaming (SSE)

The HTTP server includes `POST /v1/llm/stream` for text delta streaming via PydanticAI using OpenAI `gpt-5-mini`.

It uses `OPENAI_API_KEY` from your environment:

```bash
echo 'OPENAI_API_KEY=your-key' >> .env
```

Request body (both fields required):

```json
{
  "text": "Write a haiku about shell scripting.",
  "session_id": "android-client-a"
}
```

Response is `text/event-stream` with SSE events:
- `delta` with payload `{"text":"<chunk>"}`
- `done` with payload `{}`
- `error` with payload `{"message":"<error text>"}`

This endpoint is intentionally text-only for a stable client parser contract. If tool/loop visibility is needed later, add a separate advanced endpoint using `run_stream_events()` (or `agent.iter()`), rather than changing this contract.

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
- `GET /api/auth/status` auth/session status
- `POST /api/auth/login` exchange API key for session cookie
- `POST /api/auth/logout` clear auth session
- `GET /api/config` read TOML + status
- `PUT /api/config` save TOML (validate first)
- `POST /api/reload` apply updated config
- `POST /api/speech/transcribe` transcribe uploaded audio (`audio` multipart field; supports raw body too)
- `POST /v1/llm/stream` stream text deltas over SSE

## Notes

- Config reload is intentionally **not** exposed as MCP tool.
- Host binding is restricted to `127.0.0.1`.
- TOON support is a required dependency and is returned on structured responses where useful.
