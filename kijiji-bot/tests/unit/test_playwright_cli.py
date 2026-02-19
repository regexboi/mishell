from __future__ import annotations

import json

from kijiji_bot_mcp.scrape.playwright_cli import PlaywrightCliScraper


def test_extract_json_text_handles_quoted_json_payload() -> None:
    scraper = PlaywrightCliScraper("playwright-cli")
    next_data_text = '{"props":{"pageProps":{"x":1}}}'
    output = json.dumps(next_data_text)

    extracted = scraper._extract_json_text(output)  # noqa: SLF001
    assert json.loads(extracted)["props"]["pageProps"]["x"] == 1


def test_extract_json_text_handles_noisy_output() -> None:
    scraper = PlaywrightCliScraper("playwright-cli")
    next_data_text = '{"props":{"pageProps":{"listingId":"123"}}}'
    output = "\n".join(
        [
            "[playwright-cli] running script",
            "trace=enabled",
            json.dumps(next_data_text),
            "[playwright-cli] done",
        ]
    )

    extracted = scraper._extract_json_text(output)  # noqa: SLF001
    assert json.loads(extracted)["props"]["pageProps"]["listingId"] == "123"
