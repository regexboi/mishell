# Kijiji Bot MCP

FastMCP HTTP server exposing Cars-first Kijiji scraping tools with TOON-only responses.

## Run

```bash
uv sync --extra dev
uv run kijiji-bot-mcp
```

Default endpoint: `http://127.0.0.1:8080/mcp`.

## Dev

```bash
uv run pytest
```

## Env vars

- `KIJIJI_MCP_HOST` default `127.0.0.1`
- `KIJIJI_MCP_PORT` default `8080`
- `KIJIJI_MCP_PATH` default `/mcp`
- `KIJIJI_HTTP_TIMEOUT_SECONDS` default `20`
- `KIJIJI_RETRY_MAX` default `3`
- `KIJIJI_MIN_DELAY_MS` default `400`
- `KIJIJI_MAX_DELAY_MS` default `900`
- `KIJIJI_CB_FAILURE_THRESHOLD` default `3`
- `KIJIJI_CB_COOLDOWN_SECONDS` default `30`
- `PLAYWRIGHT_CLI_COMMAND` default `playwright-cli`
- `PLAYWRIGHT_CLI_ENABLED` default `true`
- `NOMINATIM_BASE_URL` default `https://nominatim.openstreetmap.org`
- `NOMINATIM_USER_AGENT` required in production
