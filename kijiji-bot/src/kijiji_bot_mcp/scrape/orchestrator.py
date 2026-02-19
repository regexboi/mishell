from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any

from .http_next_data import HttpNextDataScraper
from .playwright_cli import PlaywrightCliPreflight, PlaywrightCliScraper


@dataclass
class _CircuitBreaker:
    failure_threshold: int
    cooldown_seconds: float
    consecutive_failures: int = 0
    opened_until_monotonic: float = 0.0

    def is_open(self) -> bool:
        return time.monotonic() < self.opened_until_monotonic

    def remaining_seconds(self) -> float:
        return max(0.0, self.opened_until_monotonic - time.monotonic())

    def record_success(self) -> None:
        self.consecutive_failures = 0
        self.opened_until_monotonic = 0.0

    def record_failure(self) -> None:
        self.consecutive_failures += 1
        if self.consecutive_failures >= self.failure_threshold:
            self.opened_until_monotonic = time.monotonic() + self.cooldown_seconds

    def snapshot(self) -> dict[str, Any]:
        return {
            "is_open": self.is_open(),
            "remaining_seconds": round(self.remaining_seconds(), 2),
            "consecutive_failures": self.consecutive_failures,
            "failure_threshold": self.failure_threshold,
            "cooldown_seconds": self.cooldown_seconds,
        }


class KijijiScrapeOrchestrator:
    def __init__(
        self,
        http_scraper: HttpNextDataScraper,
        playwright_scraper: PlaywrightCliScraper | None,
        failure_threshold: int = 3,
        circuit_cooldown_seconds: float = 30.0,
    ) -> None:
        self._http_scraper = http_scraper
        self._playwright_scraper = playwright_scraper
        self._playwright_preflight: PlaywrightCliPreflight | None = None
        threshold = max(1, int(failure_threshold))
        cooldown = max(1.0, float(circuit_cooldown_seconds))
        self._http_circuit = _CircuitBreaker(
            failure_threshold=threshold,
            cooldown_seconds=cooldown,
        )
        self._playwright_circuit = _CircuitBreaker(
            failure_threshold=threshold,
            cooldown_seconds=cooldown,
        )

    def playwright_preflight(self) -> PlaywrightCliPreflight:
        if self._playwright_scraper is None:
            return PlaywrightCliPreflight(False, "playwright fallback disabled")
        if self._playwright_preflight is None:
            self._playwright_preflight = self._playwright_scraper.preflight()
        return self._playwright_preflight

    def circuit_state(self) -> dict[str, dict[str, Any]]:
        return {
            "http": self._http_circuit.snapshot(),
            "playwright": self._playwright_circuit.snapshot(),
        }

    async def fetch_next_data(self, url: str) -> tuple[dict[str, Any], str, list[str]]:
        warnings: list[str] = []

        if self._http_circuit.is_open():
            warnings.append(
                "HTTP backend circuit open; skipping primary backend "
                f"for {self._http_circuit.remaining_seconds():.1f}s."
            )
        else:
            try:
                data = await self._http_scraper.fetch_next_data(url)
                self._http_circuit.record_success()
                return data, "http", warnings
            except Exception as http_error:  # noqa: BLE001
                self._http_circuit.record_failure()
                warnings.append(f"HTTP scraper failed: {http_error}")

        preflight = self.playwright_preflight()
        if not preflight.available:
            warnings.append(f"Playwright fallback unavailable: {preflight.reason}")
            raise RuntimeError("All scraping backends failed")

        if self._playwright_circuit.is_open():
            warnings.append(
                "Playwright backend circuit open; skipping fallback "
                f"for {self._playwright_circuit.remaining_seconds():.1f}s."
            )
            raise RuntimeError("All scraping backends failed")

        assert self._playwright_scraper is not None
        try:
            data = await asyncio.to_thread(self._playwright_scraper.fetch_next_data, url)
            self._playwright_circuit.record_success()
            return data, "playwright", warnings
        except Exception as pw_error:  # noqa: BLE001
            self._playwright_circuit.record_failure()
            warnings.append(f"Playwright fallback failed: {pw_error}")
            raise RuntimeError("All scraping backends failed") from pw_error
