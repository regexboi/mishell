from __future__ import annotations

import math
import re
from collections import defaultdict
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urlencode

from .models import FilterValue, ListingAttribute, ListingSummary

BASE_URL = "https://www.kijiji.ca"
CARS_CATEGORY_ID = 174

_SORT_MAP: dict[str, tuple[str, str]] = {
    "most_recent": ("DATE", "DESC"),
    "least_recent": ("DATE", "ASC"),
    "lowest_price": ("PRICE", "ASC"),
    "highest_price": ("PRICE", "DESC"),
    "lowest_km": ("MILEAGE", "ASC"),
    "highest_km": ("MILEAGE", "DESC"),
    "distance": ("DISTANCE", "ASC"),
}


@dataclass(frozen=True)
class QueryWindow:
    offset: int
    limit: int


def build_search_url(location_id: int, sort: str, window: QueryWindow) -> str:
    by, order = _SORT_MAP.get(sort, _SORT_MAP["most_recent"])
    path = f"/b-cars-trucks/c{CARS_CATEGORY_ID}l{location_id}"
    query = {
        "sort": by,
        "order": order,
        "type": "OFFER",
        "offset": str(window.offset),
        "limit": str(window.limit),
    }
    return f"{BASE_URL}{path}?{urlencode(query)}"



def normalize_text(value: str) -> str:
    value = re.sub(r"\s+", " ", value).strip()
    return value



def _split_query_tokens(query: str) -> list[str]:
    return [token for token in re.split(r"\s+", query.lower().strip()) if token]



def _to_list(value: FilterValue) -> list[str]:
    if isinstance(value, list):
        return [str(v).strip().lower() for v in value]
    return [str(value).strip().lower()]



def _try_float(value: str) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None



def _build_attr_index(
    attributes: Iterable[ListingAttribute],
) -> dict[str, dict[str, set[str]]]:
    by_listing: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for attr in attributes:
        key = (attr.canonical_name or "").strip().lower()
        if not key:
            continue
        for value in attr.canonical_values:
            by_listing[attr.listing_id][key].add(str(value).strip().lower())
        for value in attr.values:
            by_listing[attr.listing_id][key].add(str(value).strip().lower())
    return by_listing



def _matches_filter(
    listing: ListingSummary,
    listing_attrs: dict[str, set[str]],
    name: str,
    raw_value: FilterValue,
) -> bool:
    values = _to_list(raw_value)

    if name in {"price", "price_min", "price_max"}:
        if listing.price_cad is None:
            return False
        if name == "price":
            if len(values) == 1:
                return math.isclose(listing.price_cad, float(values[0]))
            if len(values) >= 2:
                lo = _try_float(values[0])
                hi = _try_float(values[1])
                if lo is not None and listing.price_cad < lo:
                    return False
                if hi is not None and listing.price_cad > hi:
                    return False
                return True
        if name == "price_min":
            lo = _try_float(values[0])
            return lo is not None and listing.price_cad >= lo
        if name == "price_max":
            hi = _try_float(values[0])
            return hi is not None and listing.price_cad <= hi

    attr_values = listing_attrs.get(name, set())
    if not attr_values:
        return False

    if len(values) == 1 and values[0] in {"true", "false"}:
        # Toggle filters on Kijiji are represented as true/false strings.
        return values[0] in attr_values

    return any(value in attr_values for value in values)



def apply_post_filters(
    listings: list[ListingSummary],
    attributes: list[ListingAttribute],
    query: str | None,
    filters: dict[str, FilterValue],
    known_filter_names: set[str] | None = None,
) -> tuple[list[ListingSummary], list[ListingAttribute], list[str]]:
    warnings: list[str] = []
    attr_index = _build_attr_index(attributes)

    normalized_filters: dict[str, FilterValue] = {}
    for raw_name, raw_value in filters.items():
        name = raw_name.strip().lower()
        if not name:
            continue
        if known_filter_names and name not in known_filter_names:
            warnings.append(f"Ignored unknown filter '{raw_name}'.")
            continue
        normalized_filters[name] = raw_value

    tokens = _split_query_tokens(query or "")

    kept_listings: list[ListingSummary] = []
    kept_ids: set[str] = set()

    for listing in listings:
        text = f"{listing.title} {listing.description}".lower()
        if tokens and not all(token in text for token in tokens):
            continue

        listing_attrs = attr_index.get(listing.listing_id, {})
        if any(
            not _matches_filter(listing, listing_attrs, name, value)
            for name, value in normalized_filters.items()
        ):
            continue

        kept_listings.append(listing)
        kept_ids.add(listing.listing_id)

    kept_attributes = [a for a in attributes if a.listing_id in kept_ids]
    return kept_listings, kept_attributes, warnings
