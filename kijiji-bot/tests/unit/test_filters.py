from __future__ import annotations

from kijiji_bot_mcp.filters import apply_post_filters
from kijiji_bot_mcp.models import ListingAttribute, ListingSummary



def test_apply_post_filters_with_keyword_and_known_filter() -> None:
    listings = [
        ListingSummary(
            listing_id="1",
            title="Manual Mazda",
            description="Great condition",
            price_cad=12000,
            location_name="Toronto",
            url="https://example.com/1",
        ),
        ListingSummary(
            listing_id="2",
            title="Automatic Civic",
            description="Clean",
            price_cad=14000,
            location_name="Toronto",
            url="https://example.com/2",
        ),
    ]
    attrs = [
        ListingAttribute(
            listing_id="1",
            canonical_name="cartransmission",
            canonical_values=["1"],
            values=["Manual"],
        ),
        ListingAttribute(
            listing_id="2",
            canonical_name="cartransmission",
            canonical_values=["2"],
            values=["Automatic"],
        ),
    ]

    kept, kept_attrs, warnings = apply_post_filters(
        listings,
        attrs,
        query="manual",
        filters={"cartransmission": "1", "unknown": "x"},
        known_filter_names={"cartransmission"},
    )

    assert [item.listing_id for item in kept] == ["1"]
    assert [item.listing_id for item in kept_attrs] == ["1"]
    assert warnings and "unknown" in warnings[0].lower()
