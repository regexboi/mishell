from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

from ..models import (
    ListingAttribute,
    ListingDetails,
    ListingSummary,
    SearchPageParsed,
)


LISTING_ID_RE = re.compile(r"/(\d{7,})(?:\D|$)")


class ParseError(RuntimeError):
    pass



def _resolve_ref(apollo_state: dict[str, Any], node: Any) -> Any:
    if isinstance(node, dict) and "__ref" in node:
        return apollo_state.get(node["__ref"], {})
    return node



def _extract_apollo_state(next_data: dict[str, Any]) -> dict[str, Any]:
    try:
        return next_data["props"]["pageProps"]["__APOLLO_STATE__"]
    except KeyError as exc:
        raise ParseError("__APOLLO_STATE__ missing in __NEXT_DATA__ payload.") from exc



def _find_search_page_node(
    apollo_state: dict[str, Any],
    url_hint: str | None = None,
) -> dict[str, Any]:
    root = apollo_state.get("ROOT_QUERY", {})
    entries = [
        (k, v)
        for k, v in root.items()
        if isinstance(k, str) and k.startswith("searchResultsPageByUrl:")
    ]
    if not entries:
        raise ParseError("No searchResultsPageByUrl node found in Apollo state.")

    if url_hint:
        parsed = urlparse(url_hint)
        hint = parsed.path
        for key, value in entries:
            if key.startswith(f"searchResultsPageByUrl:{hint}"):
                return _resolve_ref(apollo_state, value)

    return _resolve_ref(apollo_state, entries[0][1])



def _parse_price_to_cad(price_node: dict[str, Any] | None) -> float | None:
    if not isinstance(price_node, dict):
        return None
    amount = price_node.get("amount")
    if amount is None:
        return None
    try:
        value = float(amount)
    except (TypeError, ValueError):
        return None
    # Kijiji autos prices are cents in Apollo state.
    if value >= 1000:
        value = value / 100.0
    return round(value, 2)



def _parse_listing_summary(
    apollo_state: dict[str, Any], listing_ref: Any
) -> tuple[ListingSummary | None, list[ListingAttribute]]:
    listing = _resolve_ref(apollo_state, listing_ref)
    if not isinstance(listing, dict):
        return None, []

    listing_id = str(listing.get("id") or "")
    if not listing_id:
        return None, []

    location = listing.get("location") or {}
    coordinates = (location.get("coordinates") or {}) if isinstance(location, dict) else {}
    lat = coordinates.get("latitude")
    lon = coordinates.get("longitude")

    coords = None
    if lat is not None and lon is not None:
        coords = (float(lat), float(lon))

    image_urls = listing.get("imageUrls") or []
    image_count = listing.get("imageCount")
    if image_count is None:
        image_count = len(image_urls) if isinstance(image_urls, list) else 0

    summary = ListingSummary(
        listing_id=listing_id,
        title=str(listing.get("title") or ""),
        description=str(listing.get("description") or ""),
        price_cad=_parse_price_to_cad(listing.get("price")),
        location_name=(location.get("name") if isinstance(location, dict) else None),
        url=str(listing.get("url") or listing.get("productUrl") or ""),
        posted_at=str(listing.get("sortingDate") or listing.get("activationDate") or ""),
        image_count=int(image_count or 0),
        coordinates=coords,
    )

    attrs: list[ListingAttribute] = []
    attrs_node = (listing.get("attributes") or {}).get("all")
    if isinstance(attrs_node, list):
        for attr in attrs_node:
            if not isinstance(attr, dict):
                continue
            attrs.append(
                ListingAttribute(
                    listing_id=listing_id,
                    canonical_name=str(attr.get("canonicalName") or ""),
                    name=(str(attr.get("name")) if attr.get("name") is not None else None),
                    canonical_values=[str(v) for v in (attr.get("canonicalValues") or [])],
                    values=[str(v) for v in (attr.get("values") or [])],
                )
            )

    return summary, attrs



def _extract_listing_refs(results_node: dict[str, Any]) -> list[Any]:
    refs: list[Any] = []
    if not isinstance(results_node, dict):
        return refs

    top_listings = results_node.get("topListings") or []
    if isinstance(top_listings, list):
        refs.extend(top_listings)

    for key, value in results_node.items():
        if key.startswith("mainListings") and isinstance(value, list):
            refs.extend(value)

    return refs



def _parse_filter_metadata(search_node: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    filter_groups: list[dict[str, Any]] = []
    filters: list[dict[str, Any]] = []
    filter_values: list[dict[str, Any]] = []

    controls = search_node.get("controls") or {}
    filtering = controls.get("filtering") or []
    for group in filtering:
        if not isinstance(group, dict):
            continue
        group_name = str(group.get("name") or "")
        group_label = str(group.get("label") or "")
        filter_groups.append(
            {
                "group_name": group_name,
                "label": group_label,
                "should_show_top_level_accordion": bool(
                    group.get("shouldShowTopLevelAccordion")
                ),
                "quick_filter_sort_order": group.get("quickFilterSortOrder"),
            }
        )

        for filt in group.get("filters", []) or []:
            if not isinstance(filt, dict):
                continue
            row = {
                "group_name": group_name,
                "filter_name": str(filt.get("name") or ""),
                "type": str(filt.get("type") or ""),
                "label": str(filt.get("label") or ""),
                "parent_name": filt.get("parentName"),
                "true_value": filt.get("trueValue"),
                "from_min": ((filt.get("fromRange") or {}).get("min")),
                "from_max": ((filt.get("fromRange") or {}).get("max")),
                "to_min": ((filt.get("toRange") or {}).get("min")),
                "to_max": ((filt.get("toRange") or {}).get("max")),
            }
            filters.append(row)

            if isinstance(filt.get("values"), list):
                for value in filt["values"]:
                    if not isinstance(value, dict):
                        continue
                    filter_values.append(
                        {
                            "group_name": group_name,
                            "filter_name": str(filt.get("name") or ""),
                            "label": str(value.get("label") or ""),
                            "value": str(value.get("value") or ""),
                            "total_results": value.get("totalResults"),
                            "parent_value": value.get("parentValue"),
                            "seo_url": value.get("seoUrl"),
                        }
                    )
            elif filt.get("trueValue") is not None:
                filter_values.append(
                    {
                        "group_name": group_name,
                        "filter_name": str(filt.get("name") or ""),
                        "label": str(filt.get("label") or ""),
                        "value": str(filt.get("trueValue") or ""),
                        "total_results": None,
                        "parent_value": None,
                        "seo_url": None,
                    }
                )

    return filter_groups, filters, filter_values



def parse_search_next_data(
    next_data: dict[str, Any],
    url_hint: str | None = None,
) -> SearchPageParsed:
    apollo_state = _extract_apollo_state(next_data)
    search_node = _find_search_page_node(apollo_state, url_hint=url_hint)

    results = search_node.get("results") or {}
    listing_refs = _extract_listing_refs(results)

    listing_items: list[ListingSummary] = []
    listing_attributes: list[ListingAttribute] = []
    seen_ids: set[str] = set()

    for ref in listing_refs:
        listing, attrs = _parse_listing_summary(apollo_state, ref)
        if listing is None or listing.listing_id in seen_ids:
            continue
        seen_ids.add(listing.listing_id)
        listing_items.append(listing)
        listing_attributes.extend(attrs)

    filter_groups, filters, filter_values = _parse_filter_metadata(search_node)

    return SearchPageParsed(
        listing_items=listing_items,
        listing_attributes=listing_attributes,
        filter_groups=filter_groups,
        filters=filters,
        filter_values=filter_values,
        pagination=search_node.get("pagination") or {},
        query_meta=search_node.get("searchQuery") or {},
    )



def _find_listing_ref_key(root_query: dict[str, Any], listing_id: str | None = None) -> str | None:
    if listing_id:
        for key in root_query.keys():
            if isinstance(key, str) and key.startswith('listing({"id":"') and f'"{listing_id}"' in key:
                return key

    for key in root_query.keys():
        if isinstance(key, str) and key.startswith("listing({"):
            return key
    return None



def parse_listing_id(value: str) -> str:
    value = value.strip()
    if value.isdigit():
        return value

    match = LISTING_ID_RE.search(value)
    if not match:
        raise ParseError(f"Could not parse listing ID from '{value}'.")
    return match.group(1)



def parse_listing_details_next_data(
    next_data: dict[str, Any],
    listing_id: str | None = None,
) -> tuple[ListingDetails, list[ListingAttribute], list[str]]:
    apollo_state = _extract_apollo_state(next_data)
    root_query = apollo_state.get("ROOT_QUERY", {})
    ref_key = _find_listing_ref_key(root_query, listing_id=listing_id)
    if not ref_key:
        raise ParseError("Could not find listing() query node in Apollo state.")

    listing_node = _resolve_ref(apollo_state, root_query[ref_key])
    if not isinstance(listing_node, dict):
        raise ParseError("Listing node is invalid in Apollo state.")

    resolved_listing_id = str(listing_node.get("id") or listing_id or "")
    if not resolved_listing_id:
        raise ParseError("Listing id missing in listing node.")

    location = listing_node.get("location") or {}
    coords = (location.get("coordinates") or {}) if isinstance(location, dict) else {}
    lat = coords.get("latitude")
    lon = coords.get("longitude")
    coordinates = (float(lat), float(lon)) if lat is not None and lon is not None else None

    attributes: list[ListingAttribute] = []
    for attr in ((listing_node.get("attributes") or {}).get("all") or []):
        if not isinstance(attr, dict):
            continue
        attributes.append(
            ListingAttribute(
                listing_id=resolved_listing_id,
                canonical_name=str(attr.get("canonicalName") or ""),
                name=(str(attr.get("name")) if attr.get("name") is not None else None),
                canonical_values=[str(v) for v in (attr.get("canonicalValues") or [])],
                values=[str(v) for v in (attr.get("values") or [])],
            )
        )

    price_node = listing_node.get("price") or {}
    image_urls = [str(v) for v in (listing_node.get("imageUrls") or [])]

    detail = ListingDetails(
        listing_id=resolved_listing_id,
        title=str(listing_node.get("title") or ""),
        description=str(listing_node.get("description") or ""),
        price_cad=_parse_price_to_cad(price_node),
        currency=str(price_node.get("currency") or "CAD"),
        location_name=(location.get("name") if isinstance(location, dict) else None),
        location_address=(location.get("address") if isinstance(location, dict) else None),
        location_id=(int(location.get("id")) if location and location.get("id") else None),
        coordinates=coordinates,
        url=str(listing_node.get("url") or listing_node.get("productUrl") or ""),
        category_id=(
            int(listing_node.get("categoryId"))
            if listing_node.get("categoryId") is not None
            else None
        ),
        seller_id=((listing_node.get("posterInfo") or {}).get("posterId")),
        seller_rating=((listing_node.get("posterInfo") or {}).get("rating")),
        image_count=len(image_urls),
    )

    return detail, attributes, image_urls
