from __future__ import annotations

from kijiji_bot_mcp.toon_codec import decode_toon, encode_tool_payload



def test_encode_tool_payload_roundtrip_strict() -> None:
    toon = encode_tool_payload(
        payload_type="kijiji.search.v1",
        backend_used="http",
        warnings=["example warning"],
        data={
            "listings": [{"listing_id": "1", "title": "A", "description": "<p>Hello</p>"}],
            "paging": {"page": 1},
        },
    )

    decoded = decode_toon(toon)
    assert decoded["type"] == "kijiji.search.v1"
    assert decoded["backend_used"] == "http"
    assert decoded["data"]["listings"][0]["description"] == "Hello"
