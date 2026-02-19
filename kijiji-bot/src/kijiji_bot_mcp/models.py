from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


FilterPrimitive = str | int | float | bool
FilterValue = FilterPrimitive | list[FilterPrimitive]


class ToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SearchListingsInput(ToolInput):
    query: str | None = None
    location_text: str = Field(min_length=1)
    radius_km: float | None = Field(default=None, gt=0)
    category_slug: Literal["cars-trucks"] = "cars-trucks"
    filters: dict[str, FilterValue] = Field(default_factory=dict)
    sort: Literal[
        "most_recent",
        "least_recent",
        "lowest_price",
        "highest_price",
        "lowest_km",
        "highest_km",
        "distance",
    ] = "most_recent"
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=20, ge=1, le=40)


class GetFilterOptionsInput(ToolInput):
    location_text: str = Field(min_length=1)
    category_slug: Literal["cars-trucks"] = "cars-trucks"
    query: str | None = None
    applied_filters: dict[str, FilterValue] = Field(default_factory=dict)


class GetListingImagesInput(ToolInput):
    listing_id_or_url: str = Field(min_length=1)
    image_variant: Literal["kijijica-640-webp", "kijijica-200-jpg", "as-is"] = (
        "kijijica-640-webp"
    )


class GetListingDetailsInput(ToolInput):
    listing_id_or_url: str = Field(min_length=1)


class ListingAttribute(BaseModel):
    listing_id: str
    canonical_name: str
    name: str | None = None
    canonical_values: list[str] = Field(default_factory=list)
    values: list[str] = Field(default_factory=list)


class ListingSummary(BaseModel):
    listing_id: str
    title: str
    description: str
    price_cad: float | None = None
    location_name: str | None = None
    url: str
    posted_at: str | None = None
    image_count: int = 0
    distance_km: float | None = None
    coordinates: tuple[float, float] | None = None


class ListingDetails(BaseModel):
    listing_id: str
    title: str
    description: str
    price_cad: float | None = None
    currency: str | None = None
    location_name: str | None = None
    location_address: str | None = None
    location_id: int | None = None
    coordinates: tuple[float, float] | None = None
    url: str
    category_id: int | None = None
    seller_id: str | None = None
    seller_rating: float | None = None
    image_count: int = 0


class SearchPageParsed(BaseModel):
    listing_items: list[ListingSummary] = Field(default_factory=list)
    listing_attributes: list[ListingAttribute] = Field(default_factory=list)
    filter_groups: list[dict[str, Any]] = Field(default_factory=list)
    filters: list[dict[str, Any]] = Field(default_factory=list)
    filter_values: list[dict[str, Any]] = Field(default_factory=list)
    pagination: dict[str, Any] = Field(default_factory=dict)
    query_meta: dict[str, Any] = Field(default_factory=dict)


class ResolvedLocation(BaseModel):
    location_id: int = 0
    matched_name: str = "Canada"
    match_score: float = 0.0
    center_lat: float | None = None
    center_lon: float | None = None


class ToolEnvelope(BaseModel):
    type: str
    generated_at: datetime
    backend_used: Literal["http", "playwright"]
    warnings: list[str] = Field(default_factory=list)
    data: dict[str, Any]

    @field_validator("generated_at", mode="before")
    @classmethod
    def _ensure_datetime(cls, value: datetime | str) -> datetime:
        if isinstance(value, datetime):
            return value
        return datetime.fromisoformat(value)
