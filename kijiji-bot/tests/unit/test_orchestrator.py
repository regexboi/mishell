from __future__ import annotations

import pytest

from kijiji_bot_mcp.scrape.orchestrator import KijijiScrapeOrchestrator
from kijiji_bot_mcp.scrape.playwright_cli import PlaywrightCliPreflight


class _HttpFail:
    async def fetch_next_data(self, _url: str):
        raise RuntimeError("http failed")


class _HttpFailCount:
    def __init__(self) -> None:
        self.calls = 0

    async def fetch_next_data(self, _url: str):
        self.calls += 1
        raise RuntimeError("http failed")


class _HttpOk:
    async def fetch_next_data(self, _url: str):
        return {"ok": True}


class _PlaywrightOk:
    def preflight(self):
        return PlaywrightCliPreflight(True)

    def fetch_next_data(self, _url: str):
        return {"fallback": True}


class _PlaywrightMissing:
    def preflight(self):
        return PlaywrightCliPreflight(False, "missing cli")


@pytest.mark.asyncio
async def test_orchestrator_uses_http_first() -> None:
    orchestrator = KijijiScrapeOrchestrator(http_scraper=_HttpOk(), playwright_scraper=None)
    payload, backend, warnings = await orchestrator.fetch_next_data("https://x")
    assert payload["ok"] is True
    assert backend == "http"
    assert warnings == []


@pytest.mark.asyncio
async def test_orchestrator_falls_back_to_playwright() -> None:
    orchestrator = KijijiScrapeOrchestrator(
        http_scraper=_HttpFail(), playwright_scraper=_PlaywrightOk()
    )
    payload, backend, warnings = await orchestrator.fetch_next_data("https://x")
    assert payload["fallback"] is True
    assert backend == "playwright"
    assert any("HTTP scraper failed" in w for w in warnings)


@pytest.mark.asyncio
async def test_orchestrator_warns_when_playwright_unavailable() -> None:
    orchestrator = KijijiScrapeOrchestrator(
        http_scraper=_HttpFail(), playwright_scraper=_PlaywrightMissing()
    )
    with pytest.raises(RuntimeError):
        await orchestrator.fetch_next_data("https://x")


@pytest.mark.asyncio
async def test_orchestrator_opens_http_circuit_after_repeated_failures() -> None:
    http = _HttpFailCount()
    orchestrator = KijijiScrapeOrchestrator(
        http_scraper=http,
        playwright_scraper=_PlaywrightOk(),
        failure_threshold=1,
        circuit_cooldown_seconds=60.0,
    )

    await orchestrator.fetch_next_data("https://x")
    payload, backend, warnings = await orchestrator.fetch_next_data("https://x")

    assert payload["fallback"] is True
    assert backend == "playwright"
    assert http.calls == 1
    assert any("circuit open" in warning.lower() for warning in warnings)
