from __future__ import annotations

from kijiji_bot_mcp.location import apply_radius_filter
from kijiji_bot_mcp.models import ListingSummary



def test_apply_radius_filter_keeps_close_items_and_sets_distance() -> None:
    listings = [
        ListingSummary(
            listing_id="1",
            title="A",
            description="",
            price_cad=1,
            location_name="",
            url="u1",
            coordinates=(43.6532, -79.3832),
        ),
        ListingSummary(
            listing_id="2",
            title="B",
            description="",
            price_cad=1,
            location_name="",
            url="u2",
            coordinates=(45.5017, -73.5673),
        ),
    ]

    kept, warnings = apply_radius_filter(
        listings,
        origin_lat=43.6532,
        origin_lon=-79.3832,
        radius_km=25,
    )

    assert [item.listing_id for item in kept] == ["1"]
    assert kept[0].distance_km == 0.0
    assert warnings == []
