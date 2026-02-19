from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
from kijiji_bot_mcp.toon_codec import decode_toon

from kijiji_bot_mcp.config import Settings
from kijiji_bot_mcp.scrape.playwright_cli import PlaywrightCliPreflight
from kijiji_bot_mcp.models import (
    GetFilterOptionsInput,
    GetListingDetailsInput,
    GetListingImagesInput,
    ResolvedLocation,
    SearchListingsInput,
)
from kijiji_bot_mcp.tools import KijijiBotService


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


class _FakeLocationService:
    async def resolve_location(self, _location_text: str):
        return (
            ResolvedLocation(
                location_id=1700273,
                matched_name="City of Toronto",
                match_score=0.99,
                center_lat=43.6532,
                center_lon=-79.3832,
            ),
            [],
        )


class _FakeOrchestrator:
    def __init__(self, payload: dict):
        self.payload = payload

    async def fetch_next_data(self, _url: str):
        return self.payload, "http", []

    def playwright_preflight(self):
        return PlaywrightCliPreflight(True)

    def circuit_state(self):
        return {
            "http": {"is_open": False, "remaining_seconds": 0.0, "consecutive_failures": 0},
            "playwright": {"is_open": False, "remaining_seconds": 0.0, "consecutive_failures": 0},
        }


@pytest.mark.asyncio
async def test_search_listings_filters_and_radius() -> None:
    async with httpx.AsyncClient() as client:
        service = KijijiBotService(Settings.from_env(), client)
        service._location_service = _FakeLocationService()  # type: ignore[attr-defined]
        service._orchestrator = _FakeOrchestrator(_load("search_next_data.json"))  # type: ignore[attr-defined]

        toon = await service.search_listings(
            SearchListingsInput(
                location_text="Toronto",
                filters={"cartransmission": "1"},
                radius_km=50,
                page=1,
                page_size=20,
            )
        )

    decoded = decode_toon(toon)
    listings = decoded["data"]["listings"]
    assert len(listings) == 1
    assert listings[0]["listing_id"] == "1"
    assert listings[0]["distance_km"] <= 50
    assert decoded["data"]["request_meta"]["request_id"].startswith("req-search_listings-")


@pytest.mark.asyncio
async def test_get_filter_options_contains_transmission() -> None:
    async with httpx.AsyncClient() as client:
        service = KijijiBotService(Settings.from_env(), client)
        service._location_service = _FakeLocationService()  # type: ignore[attr-defined]
        service._orchestrator = _FakeOrchestrator(_load("search_next_data.json"))  # type: ignore[attr-defined]

        toon = await service.get_filter_options(GetFilterOptionsInput(location_text="Toronto"))

    decoded = decode_toon(toon)
    filter_names = {row["filter_name"] for row in decoded["data"]["filters"]}
    assert "cartransmission" in filter_names


@pytest.mark.asyncio
async def test_get_listing_images_only_images_table() -> None:
    async with httpx.AsyncClient() as client:
        service = KijijiBotService(Settings.from_env(), client)
        service._orchestrator = _FakeOrchestrator(_load("detail_next_data.json"))  # type: ignore[attr-defined]

        toon = await service.get_listing_images(GetListingImagesInput(listing_id_or_url="1"))

    decoded = decode_toon(toon)
    assert "images" in decoded["data"]
    assert "listing_meta" in decoded["data"]
    assert len(decoded["data"]["images"]) == 2


@pytest.mark.asyncio
async def test_get_listing_details_omits_full_image_urls() -> None:
    async with httpx.AsyncClient() as client:
        service = KijijiBotService(Settings.from_env(), client)
        service._orchestrator = _FakeOrchestrator(_load("detail_next_data.json"))  # type: ignore[attr-defined]

        toon = await service.get_listing_details(GetListingDetailsInput(listing_id_or_url="1"))

    decoded = decode_toon(toon)
    assert "listing" in decoded["data"]
    assert "attributes" in decoded["data"]
    assert "image_count" in decoded["data"]
    assert "images" not in decoded["data"]


@pytest.mark.asyncio
async def test_self_check_contains_checks_and_metrics() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "www.kijiji.ca":
            return httpx.Response(200, text="ok")
        if request.url.host == "nominatim.openstreetmap.org":
            return httpx.Response(200, json=[{"lat": "43.6532", "lon": "-79.3832"}])
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        service = KijijiBotService(Settings.from_env(), client)
        service._orchestrator = _FakeOrchestrator(_load("search_next_data.json"))  # type: ignore[attr-defined]
        toon = await service.self_check()

    decoded = decode_toon(toon)
    assert decoded["type"] == "kijiji.self_check.v1"
    assert len(decoded["data"]["checks"]) == 4
    assert decoded["data"]["metrics"]["per_tool"]["self_check"]["calls"] == 1
    assert decoded["data"]["request_meta"]["tool_name"] == "self_check"
