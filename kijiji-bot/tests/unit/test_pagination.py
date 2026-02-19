from __future__ import annotations

from urllib.parse import parse_qs, urlparse

from kijiji_bot_mcp.filters import QueryWindow, build_search_url



def test_query_window_maps_offset_and_limit() -> None:
    url = build_search_url(
        location_id=1700273,
        sort="most_recent",
        window=QueryWindow(offset=40, limit=20),
    )
    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    assert qs["offset"] == ["40"]
    assert qs["limit"] == ["20"]
