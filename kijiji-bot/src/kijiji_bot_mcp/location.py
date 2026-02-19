from __future__ import annotations

import json
import re
import time
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher
from math import asin, cos, radians, sin, sqrt
from typing import Any

import httpx

from .models import ListingSummary, ResolvedLocation


_LOCATIONS_URL = "https://www.kijiji.ca/j-locations.json"
_GEOCODE_TTL_SECONDS = 24 * 60 * 60
_LOCATION_TREE_TTL_SECONDS = 6 * 60 * 60


@dataclass
class _CacheEntry:
    value: Any
    expires_at: float


class LocationService:
    def __init__(
        self,
        client: httpx.AsyncClient,
        nominatim_base_url: str,
        nominatim_user_agent: str,
    ) -> None:
        self._client = client
        self._nominatim_base_url = nominatim_base_url.rstrip("/")
        self._nominatim_user_agent = nominatim_user_agent.strip() or "kijiji-bot-mcp/0.1.0"
        self._cache: dict[str, _CacheEntry] = {}

    def _cache_get(self, key: str) -> Any | None:
        entry = self._cache.get(key)
        if not entry:
            return None
        if time.time() >= entry.expires_at:
            self._cache.pop(key, None)
            return None
        return entry.value

    def _cache_set(self, key: str, value: Any, ttl_seconds: int) -> None:
        self._cache[key] = _CacheEntry(value=value, expires_at=time.time() + ttl_seconds)

    async def resolve_location(self, location_text: str) -> tuple[ResolvedLocation, list[str]]:
        warnings: list[str] = []
        location_text = location_text.strip()
        if not location_text:
            return ResolvedLocation(), ["Empty location_text; using Canada (location_id=0)."]

        tree = await self._get_location_tree()

        if location_text.isdigit():
            location_id = int(location_text)
            match = self._find_by_id(tree, location_id)
            name = match.get("name") if match else f"Location {location_id}"
            lat, lon = await self._geocode(location_text)
            return (
                ResolvedLocation(
                    location_id=location_id,
                    matched_name=name,
                    match_score=1.0,
                    center_lat=lat,
                    center_lon=lon,
                ),
                warnings,
            )

        candidates = self._flatten_locations(tree)
        best = self._pick_best_match(location_text, candidates)

        if not best or best["score"] < 0.45:
            warnings.append(
                f"No strong Kijiji location match for '{location_text}'. Using Canada (location_id=0)."
            )
            location_id = 0
            matched_name = "Canada"
            match_score = 0.0
        else:
            location_id = int(best["id"])
            matched_name = best["name"]
            match_score = float(best["score"])

        lat, lon = await self._geocode(location_text)
        if lat is None or lon is None:
            warnings.append(
                f"Could not geocode '{location_text}' with Nominatim. Radius filtering may be unavailable."
            )

        return (
            ResolvedLocation(
                location_id=location_id,
                matched_name=matched_name,
                match_score=match_score,
                center_lat=lat,
                center_lon=lon,
            ),
            warnings,
        )

    async def _get_location_tree(self) -> dict[str, Any]:
        cached = self._cache_get("location_tree")
        if cached is not None:
            return cached

        response = await self._client.get(_LOCATIONS_URL)
        response.raise_for_status()
        text = response.text

        match = re.search(r"locationsTree\s*=\s*(\{.*\})\s*;?", text, re.DOTALL)
        if not match:
            raise ValueError("Failed to parse Kijiji locations tree.")

        tree = json.loads(match.group(1))
        self._cache_set("location_tree", tree, _LOCATION_TREE_TTL_SECONDS)
        return tree

    def _find_by_id(self, tree: dict[str, Any], location_id: int) -> dict[str, Any] | None:
        for node in self._flatten_locations(tree):
            if int(node["id"]) == location_id:
                return node
        return None

    def _flatten_locations(self, root: dict[str, Any]) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []

        def walk(node: dict[str, Any]) -> None:
            location_id = node.get("id")
            if location_id is not None:
                name = node.get("nameEn") or node.get("nameFr") or str(location_id)
                rows.append(
                    {
                        "id": int(location_id),
                        "name": str(name),
                        "name_en": str(node.get("nameEn") or ""),
                        "name_fr": str(node.get("nameFr") or ""),
                        "home_url": str(node.get("homePageSEOUrl") or ""),
                    }
                )
            for child in node.get("children", []):
                if isinstance(child, dict):
                    walk(child)

        walk(root)
        return rows

    @staticmethod
    def _normalize(value: str) -> str:
        value = unicodedata.normalize("NFKD", value)
        value = "".join(ch for ch in value if not unicodedata.combining(ch))
        value = re.sub(r"[^a-zA-Z0-9]+", " ", value).strip().lower()
        return re.sub(r"\s+", " ", value)

    def _pick_best_match(
        self, location_text: str, candidates: list[dict[str, Any]]
    ) -> dict[str, Any] | None:
        needle = self._normalize(location_text)
        if not needle:
            return None

        best: dict[str, Any] | None = None
        best_score = 0.0

        for candidate in candidates:
            names = [candidate["name"], candidate["name_en"], candidate["name_fr"]]
            score = 0.0
            for name in names:
                normalized = self._normalize(name)
                if not normalized:
                    continue
                ratio = SequenceMatcher(a=needle, b=normalized).ratio()
                contains_bonus = 0.15 if needle in normalized or normalized in needle else 0.0
                score = max(score, ratio + contains_bonus)

            if score > best_score:
                best_score = score
                best = {**candidate, "score": min(score, 1.0)}

        return best

    async def _geocode(self, location_text: str) -> tuple[float | None, float | None]:
        key = f"geocode:{location_text.lower().strip()}"
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        response = await self._client.get(
            f"{self._nominatim_base_url}/search",
            params={"format": "jsonv2", "limit": 1, "q": location_text},
            headers={"User-Agent": self._nominatim_user_agent},
        )
        if response.status_code >= 400:
            self._cache_set(key, (None, None), _GEOCODE_TTL_SECONDS)
            return None, None

        items = response.json()
        if not isinstance(items, list) or not items:
            self._cache_set(key, (None, None), _GEOCODE_TTL_SECONDS)
            return None, None

        lat = float(items[0].get("lat"))
        lon = float(items[0].get("lon"))
        self._cache_set(key, (lat, lon), _GEOCODE_TTL_SECONDS)
        return lat, lon

    @staticmethod
    def haversine_km(
        lat1: float, lon1: float, lat2: float, lon2: float
    ) -> float:
        radius_km = 6371.0
        d_lat = radians(lat2 - lat1)
        d_lon = radians(lon2 - lon1)
        a = (
            sin(d_lat / 2) ** 2
            + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
        )
        c = 2 * asin(sqrt(a))
        return radius_km * c



def apply_radius_filter(
    listings: list[ListingSummary],
    origin_lat: float | None,
    origin_lon: float | None,
    radius_km: float | None,
) -> tuple[list[ListingSummary], list[str]]:
    if radius_km is None:
        return listings, []

    warnings: list[str] = []
    if origin_lat is None or origin_lon is None:
        warnings.append("radius_km requested but origin coordinates are unavailable.")
        return [], warnings

    kept: list[ListingSummary] = []
    missing_coordinates = 0

    for listing in listings:
        if not listing.coordinates:
            missing_coordinates += 1
            continue
        lat, lon = listing.coordinates
        distance = LocationService.haversine_km(origin_lat, origin_lon, lat, lon)
        if distance <= radius_km:
            listing.distance_km = round(distance, 2)
            kept.append(listing)

    if missing_coordinates:
        warnings.append(
            f"Excluded {missing_coordinates} listings without coordinates from radius filter."
        )

    return kept, warnings
