# Handoff: LTD (Left To Do)

## What "LTD" Means
In this project, **LTD** means **Left To Do**: work that is still open after the current implementation.

## Current Delivered Scope
- FastMCP server running over HTTP transport at `/mcp`.
- Tools implemented:
  - `search_listings`
  - `get_filter_options`
  - `get_listing_images`
  - `get_listing_details`
  - `self_check`
- Hybrid scraping flow:
  - HTTP `__NEXT_DATA__` first
  - Playwright CLI fallback
- Cars-first support (category `174`) with typed inputs (Pydantic).
- TOON-only tool output envelope:
  - `type`
  - `generated_at`
  - `backend_used`
  - `warnings`
  - `data`
- Location resolution + Nominatim geocoding + radius filtering.
- Tests in place and passing (`19 passed`).

## Newly Delivered (2026-02-15)
- P0 observability baseline:
  - per-request structured IDs (`request_meta.request_id`)
  - per-tool latency (`request_meta.latency_ms`)
  - in-memory metrics snapshot (calls, per-tool stats, backend success/error counters)
  - warning/error rates exposed by `self_check`
- Circuit breaker added to orchestrator for HTTP/Playwright backends:
  - configurable failure threshold + cooldown
  - skips temporarily unhealthy backend and emits warnings
- Playwright CLI parser hardened for noisy output:
  - supports mixed logs/fenced blocks and robust JSON extraction
- Startup/runtime self-check tool added:
  - network reachability probes (Kijiji + Nominatim)
  - Playwright CLI preflight validation
  - Nominatim config validation
- Tooling migrated to uv-first workflow:
  - `uv run pytest`
  - lockfile generated (`uv.lock`)

## LTD: Priority Backlog

## P0 (Operational Reliability)
- Extend observability beyond in-memory metrics:
  - optional export sink (Prometheus/OpenTelemetry/log drain)
  - persisted counters across restarts
  - percentiles/histograms for latency
- Add richer circuit-breaker telemetry and configurable per-backend thresholds.
- Expand self-check to include synthetic scrape probe and explicit degraded/healthy status code semantics.

## P1 (Data Quality and Search Semantics)
- Improve post-filter behavior for range filters (price, mileage) and mixed filter types.
- Expand filter coercion/mapping for cars attributes with strict canonical normalization.
- Improve pagination behavior under post-filtering so "filtered total" can be exhaustive or explicitly bounded with deterministic scan policy.
- Add deduplication guarantees across pagination windows for all listing sources.

## P1 (TOON Compatibility)
- Replace local TOON codec with a fully compliant upstream TOON encoder/decoder once production-ready.
- Add compliance tests against official TOON spec fixtures (v3.0).
- Add serializer fuzz tests for nested/tabular edge cases.

## P2 (Product Scope)
- Add category abstraction for non-cars categories while keeping cars behavior unchanged.
- Add optional transport support for STDIO (while keeping HTTP as default).
- Add optional configurable geocoder providers beyond Nominatim.

## Known Technical Notes
- The PyPI `toon-format` package currently behaves like a stub in this environment, so the project uses a local TOON codec in `src/kijiji_bot_mcp/toon_codec.py`.
- Kijiji query parameters are not consistently honored server-side, so keyword and many filters are enforced in post-processing.
- Radius filtering depends on listing coordinates; listings without coordinates are excluded when radius is requested.

## File Map (Key Implementation Files)
- `src/kijiji_bot_mcp/server.py`
- `src/kijiji_bot_mcp/tools.py`
- `src/kijiji_bot_mcp/models.py`
- `src/kijiji_bot_mcp/filters.py`
- `src/kijiji_bot_mcp/location.py`
- `src/kijiji_bot_mcp/toon_codec.py`
- `src/kijiji_bot_mcp/parsers/kijiji_next_data.py`
- `src/kijiji_bot_mcp/scrape/http_next_data.py`
- `src/kijiji_bot_mcp/scrape/playwright_cli.py`
- `src/kijiji_bot_mcp/scrape/orchestrator.py`

## Test Status
- Unit + integration tests are present under `tests/`.
- Last verified result: `15 passed`.

## Suggested Next Execution Order
1. Deliver P0 observability + health/self-check.
2. Tighten post-filter semantics and pagination determinism.
3. Add TOON spec compliance suite and migrate off local codec when upstream is stable.
4. Expand category support and transport options.
