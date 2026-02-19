from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
from fastmcp import FastMCP

from .config import Settings
from .models import (
    GetFilterOptionsInput,
    GetListingDetailsInput,
    GetListingImagesInput,
    SearchListingsInput,
)
from .tools import KijijiBotService



def create_server(settings: Settings | None = None) -> FastMCP:
    cfg = settings or Settings.from_env()
    runtime: dict[str, KijijiBotService] = {}

    @asynccontextmanager
    async def lifespan(_: FastMCP):
        async with httpx.AsyncClient(
            headers={
                "User-Agent": cfg.nominatim_user_agent,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            },
            follow_redirects=True,
        ) as client:
            runtime["service"] = KijijiBotService(cfg, client)
            yield

    mcp = FastMCP(
        name="KijijiBotMCP",
        instructions=(
            "Cars-first Kijiji scraper that returns TOON-only payloads for listings, filters, images, and listing details."
        ),
        lifespan=lifespan,
    )

    def service() -> KijijiBotService:
        if "service" not in runtime:
            raise RuntimeError("Service runtime is not initialized.")
        return runtime["service"]

    @mcp.tool
    async def search_listings(
        query: str | None = None,
        location_text: str = "",
        radius_km: float | None = None,
        category_slug: str = "cars-trucks",
        filters: dict[str, str | int | float | bool | list[str]] | None = None,
        sort: str = "most_recent",
        page: int = 1,
        page_size: int = 20,
    ) -> str:
        """Search Kijiji listings and return TOON payload with listings + attributes."""
        params = SearchListingsInput(
            query=query,
            location_text=location_text,
            radius_km=radius_km,
            category_slug=category_slug,
            filters=filters or {},
            sort=sort,
            page=page,
            page_size=page_size,
        )
        return await service().search_listings(params)

    @mcp.tool
    async def get_filter_options(
        location_text: str,
        category_slug: str = "cars-trucks",
        query: str | None = None,
        applied_filters: dict[str, str | int | float | bool | list[str]] | None = None,
    ) -> str:
        """Return filter metadata (groups, filters, values) as TOON."""
        params = GetFilterOptionsInput(
            location_text=location_text,
            category_slug=category_slug,
            query=query,
            applied_filters=applied_filters or {},
        )
        return await service().get_filter_options(params)

    @mcp.tool
    async def get_listing_images(
        listing_id_or_url: str,
        image_variant: str = "kijijica-640-webp",
    ) -> str:
        """Return listing image URLs without full listing payload."""
        params = GetListingImagesInput(
            listing_id_or_url=listing_id_or_url,
            image_variant=image_variant,
        )
        return await service().get_listing_images(params)

    @mcp.tool
    async def get_listing_details(listing_id_or_url: str) -> str:
        """Return detailed listing info (excluding full image list) as TOON."""
        params = GetListingDetailsInput(listing_id_or_url=listing_id_or_url)
        return await service().get_listing_details(params)

    @mcp.tool
    async def self_check() -> str:
        """Run dependency and runtime health checks for the Kijiji bot service."""
        return await service().self_check()

    return mcp



def main() -> None:
    settings = Settings.from_env()
    mcp = create_server(settings)
    mcp.run(
        transport="http",
        host=settings.mcp_host,
        port=settings.mcp_port,
        path=settings.mcp_path,
    )


if __name__ == "__main__":
    main()
