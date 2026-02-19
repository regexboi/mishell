from __future__ import annotations

import pytest
from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport
from fastmcp.utilities.tests import run_server_async
from kijiji_bot_mcp.toon_codec import decode_toon

from kijiji_bot_mcp.server import create_server
from kijiji_bot_mcp.toon_codec import encode_tool_payload
from kijiji_bot_mcp.tools import KijijiBotService


@pytest.mark.asyncio
async def test_http_transport_tool_invocation(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _search(self, _params):  # noqa: ANN001
        return encode_tool_payload(
            payload_type="kijiji.search.v1",
            backend_used="http",
            warnings=[],
            data={"listings": [{"listing_id": "1"}], "attributes": [], "paging": {"page": 1}},
        )

    monkeypatch.setattr(KijijiBotService, "search_listings", _search)

    server = create_server()
    async with run_server_async(server) as url:
        async with Client(transport=StreamableHttpTransport(url)) as client:
            result = await client.call_tool(
                "search_listings",
                {
                    "location_text": "Toronto",
                    "filters": {},
                    "page": 1,
                    "page_size": 20,
                    "sort": "most_recent",
                },
            )

    payload = result.data if hasattr(result, "data") else result
    decoded = decode_toon(payload)
    assert decoded["type"] == "kijiji.search.v1"
    assert decoded["data"]["listings"][0]["listing_id"] == "1"
