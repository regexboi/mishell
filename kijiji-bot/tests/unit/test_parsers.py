from __future__ import annotations

import json
from pathlib import Path

from kijiji_bot_mcp.parsers import (
    parse_listing_details_next_data,
    parse_listing_id,
    parse_search_next_data,
)


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def test_parse_search_next_data_extracts_listings_and_filters() -> None:
    parsed = parse_search_next_data(_load("search_next_data.json"))

    assert len(parsed.listing_items) == 2
    assert parsed.listing_items[0].listing_id == "1"
    assert parsed.listing_items[0].price_cad == 12500.0
    assert any(f["filter_name"] == "cartransmission" for f in parsed.filters)
    assert any(v["value"] == "1" for v in parsed.filter_values)


def test_parse_listing_details_extracts_core_fields() -> None:
    details, attrs, images = parse_listing_details_next_data(_load("detail_next_data.json"), "1")

    assert details.listing_id == "1"
    assert details.price_cad == 12500.0
    assert details.location_name == "City of Toronto"
    assert details.image_count == 2
    assert len(attrs) == 2
    assert len(images) == 2


def test_parse_listing_id_from_url_and_raw_id() -> None:
    assert parse_listing_id("1") == "1"
    assert parse_listing_id("https://www.kijiji.ca/v-cars-trucks/x/1732948061") == "1732948061"
