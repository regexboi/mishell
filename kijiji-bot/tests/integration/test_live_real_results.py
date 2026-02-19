from __future__ import annotations

import os
from dataclasses import replace
from typing import Any

import httpx
import pytest
from kijiji_bot_mcp.config import Settings
from kijiji_bot_mcp.models import GetListingDetailsInput, GetListingImagesInput, SearchListingsInput
from kijiji_bot_mcp.tools import KijijiBotService
from kijiji_bot_mcp.toon_codec import decode_toon


pytestmark = [
    pytest.mark.live,
    pytest.mark.skipif(
        os.getenv("KIJIJI_RUN_LIVE_TESTS") != "1",
        reason="Set KIJIJI_RUN_LIVE_TESTS=1 to run tests against live Kijiji/Nominatim.",
    ),
]


def _assert_listing_is_usable(listing: dict[str, Any]) -> None:
    listing_id = str(listing.get("listing_id", "")).strip()
    assert listing_id.isdigit(), "listing_id must be numeric"
    assert len(listing_id) >= 7, "listing_id appears invalid"
    assert str(listing.get("title", "")).strip(), "title should not be empty"
    assert str(listing.get("url", "")).startswith("http"), "url should be absolute"


@pytest.mark.asyncio
async def test_live_search_and_followup_details_images_are_usable() -> None:
    settings = replace(
        Settings.from_env(),
        playwright_cli_enabled=False,
        retry_max=2,
        min_delay_ms=100,
        max_delay_ms=250,
    )
    async with httpx.AsyncClient(
        headers={
            "User-Agent": settings.nominatim_user_agent,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
        follow_redirects=True,
        timeout=30.0,
    ) as client:
        service = KijijiBotService(settings, client)

        search_toon = await service.search_listings(
            SearchListingsInput(
                location_text="Toronto",
                page=1,
                page_size=8,
                sort="most_recent",
            )
        )
        search_decoded = decode_toon(search_toon)

        assert search_decoded["type"] == "kijiji.search.v1"
        listings = search_decoded["data"]["listings"]
        assert listings, "live search returned no listings"
        for listing in listings[:3]:
            _assert_listing_is_usable(listing)

        listing_with_images = next(
            (item for item in listings if int(item.get("image_count") or 0) > 0),
            None,
        )
        assert listing_with_images is not None, "expected at least one listing with images"
        listing_id = listing_with_images["listing_id"]

        details_toon = await service.get_listing_details(
            GetListingDetailsInput(listing_id_or_url=listing_id)
        )
        details = decode_toon(details_toon)
        assert details["type"] == "kijiji.details.v1"
        assert details["data"]["listing"]["listing_id"] == listing_id
        assert str(details["data"]["listing"]["title"]).strip()
        assert str(details["data"]["listing"]["url"]).startswith("http")
        assert details["data"]["image_count"] >= 1

        images_toon = await service.get_listing_images(
            GetListingImagesInput(listing_id_or_url=listing_id)
        )
        images = decode_toon(images_toon)
        assert images["type"] == "kijiji.images.v1"
        image_rows = images["data"]["images"]
        assert image_rows, "expected at least one image row"
        assert str(image_rows[0]["url"]).startswith("http")
