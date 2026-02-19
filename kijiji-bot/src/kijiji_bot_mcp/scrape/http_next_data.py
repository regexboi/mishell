from __future__ import annotations

import asyncio
import json
import random
import re
import time
from dataclasses import dataclass
from typing import Any

import httpx


_NEXT_DATA_RE = re.compile(
    r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',
    re.DOTALL,
)


@dataclass
class HttpRetryConfig:
    timeout_seconds: float = 20.0
    retry_max: int = 3
    min_delay_ms: int = 400
    max_delay_ms: int = 900


class HttpNextDataScraper:
    def __init__(self, client: httpx.AsyncClient, config: HttpRetryConfig) -> None:
        self._client = client
        self._config = config
        self._last_request_ts = 0.0
        self._request_lock = asyncio.Lock()

    async def fetch_next_data(self, url: str) -> dict[str, Any]:
        exc: Exception | None = None

        for attempt in range(1, self._config.retry_max + 1):
            try:
                html = await self._fetch_html_with_rate_limit(url)
                return self.extract_next_data_from_html(html)
            except Exception as error:  # noqa: BLE001
                exc = error
                if attempt >= self._config.retry_max:
                    break
                await asyncio.sleep(min(2 ** (attempt - 1), 4))

        assert exc is not None
        raise RuntimeError(f"Failed to fetch __NEXT_DATA__ after retries: {exc}") from exc

    async def _fetch_html_with_rate_limit(self, url: str) -> str:
        async with self._request_lock:
            now = time.monotonic()
            min_delay = random.uniform(
                self._config.min_delay_ms / 1000.0,
                self._config.max_delay_ms / 1000.0,
            )
            elapsed = now - self._last_request_ts
            if elapsed < min_delay:
                await asyncio.sleep(min_delay - elapsed)

            response = await self._client.get(url, timeout=self._config.timeout_seconds)
            self._last_request_ts = time.monotonic()

        response.raise_for_status()
        return response.text

    @staticmethod
    def extract_next_data_from_html(html: str) -> dict[str, Any]:
        match = _NEXT_DATA_RE.search(html)
        if not match:
            raise ValueError("Could not find __NEXT_DATA__ script tag in page HTML.")
        return json.loads(match.group(1))
