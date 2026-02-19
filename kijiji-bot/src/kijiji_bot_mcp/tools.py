from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import httpx

from .config import Settings
from .filters import QueryWindow, apply_post_filters, build_search_url
from .location import LocationService, apply_radius_filter
from .models import (
    GetFilterOptionsInput,
    GetListingDetailsInput,
    GetListingImagesInput,
    ListingAttribute,
    ListingSummary,
    SearchListingsInput,
)
from .parsers import (
    parse_listing_details_next_data,
    parse_listing_id,
    parse_search_next_data,
)
from .observability import RequestTrace, ServiceMetrics
from .scrape import (
    HttpNextDataScraper,
    HttpRetryConfig,
    KijijiScrapeOrchestrator,
    PlaywrightCliScraper,
)
from .toon_codec import encode_tool_payload


@dataclass
class _CacheEntry:
    value: Any
    expires_at: float


class KijijiBotService:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._http_client = http_client
        self._location_service = LocationService(
            client=http_client,
            nominatim_base_url=settings.nominatim_base_url,
            nominatim_user_agent=settings.nominatim_user_agent,
        )
        http_scraper = HttpNextDataScraper(
            client=http_client,
            config=HttpRetryConfig(
                timeout_seconds=settings.http_timeout_seconds,
                retry_max=settings.retry_max,
                min_delay_ms=settings.min_delay_ms,
                max_delay_ms=settings.max_delay_ms,
            ),
        )
        playwright_scraper = (
            PlaywrightCliScraper(settings.playwright_cli_command)
            if settings.playwright_cli_enabled
            else None
        )
        self._orchestrator = KijijiScrapeOrchestrator(
            http_scraper=http_scraper,
            playwright_scraper=playwright_scraper,
            failure_threshold=settings.circuit_breaker_failure_threshold,
            circuit_cooldown_seconds=settings.circuit_breaker_cooldown_seconds,
        )
        self._cache: dict[str, _CacheEntry] = {}
        self._metrics = ServiceMetrics()

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

    async def search_listings(self, params: SearchListingsInput) -> str:
        trace = self._metrics.start_request("search_listings")
        warnings: list[str] = []
        backend_used = "http"
        try:
            resolved_location, location_warnings = await self._location_service.resolve_location(
                params.location_text
            )
            warnings.extend(location_warnings)

            page_size = params.page_size
            requested_start = (params.page - 1) * page_size
            requested_end = requested_start + page_size

            collected: list[ListingSummary] = []
            collected_attrs: list[ListingAttribute] = []
            seen_listing_ids: set[str] = set()

            offset = 0
            limit = 40
            max_windows = 10
            raw_total_count: int | None = None
            known_filter_names: set[str] = set()

            exhausted = False

            for _ in range(max_windows):
                url = build_search_url(
                    location_id=resolved_location.location_id,
                    sort=params.sort,
                    window=QueryWindow(offset=offset, limit=limit),
                )
                next_data, backend, backend_warnings = await self._orchestrator.fetch_next_data(
                    url
                )
                warnings.extend(backend_warnings)
                if backend == "playwright":
                    backend_used = "playwright"

                parsed = parse_search_next_data(next_data, url_hint=url)
                if raw_total_count is None:
                    raw_total_count = parsed.pagination.get("totalCount")
                known_filter_names.update(
                    f["filter_name"].lower()
                    for f in parsed.filters
                    if isinstance(f.get("filter_name"), str)
                )

                filtered_listings, filtered_attrs, filter_warnings = apply_post_filters(
                    parsed.listing_items,
                    parsed.listing_attributes,
                    query=params.query,
                    filters=params.filters,
                    known_filter_names=known_filter_names if known_filter_names else None,
                )
                warnings.extend(filter_warnings)

                filtered_listings, radius_warnings = apply_radius_filter(
                    filtered_listings,
                    origin_lat=resolved_location.center_lat,
                    origin_lon=resolved_location.center_lon,
                    radius_km=params.radius_km,
                )
                warnings.extend(radius_warnings)

                for listing in filtered_listings:
                    if listing.listing_id in seen_listing_ids:
                        continue
                    seen_listing_ids.add(listing.listing_id)
                    collected.append(listing)

                kept_ids = {listing.listing_id for listing in filtered_listings}
                for attr in filtered_attrs:
                    if attr.listing_id in kept_ids:
                        collected_attrs.append(attr)

                if len(collected) >= requested_end:
                    break

                raw_count = len(parsed.listing_items)
                offset += limit
                if raw_count < limit:
                    exhausted = True
                    break
                if raw_total_count is not None and offset >= int(raw_total_count):
                    exhausted = True
                    break
            else:
                warnings.append(
                    "Search reached internal scan limit before exhausting source pages."
                )

            paged_listings = collected[requested_start:requested_end]
            page_ids = {item.listing_id for item in paged_listings}
            paged_attrs = [a for a in collected_attrs if a.listing_id in page_ids]

            if (
                params.sort == "distance"
                and resolved_location.center_lat
                and resolved_location.center_lon
            ):
                for listing in paged_listings:
                    if listing.coordinates and listing.distance_km is None:
                        distance = LocationService.haversine_km(
                            resolved_location.center_lat,
                            resolved_location.center_lon,
                            listing.coordinates[0],
                            listing.coordinates[1],
                        )
                        listing.distance_km = round(distance, 2)
                paged_listings.sort(key=lambda item: item.distance_km or 1e9)

            total_filtered = len(collected) if exhausted else None
            if total_filtered is None:
                warnings.append("Filtered total_count is not exhaustive (scan window capped).")

            request_meta = self._finish_success(trace, backend_used, warnings)
            data = {
                "query_meta": {
                    "query": params.query,
                    "category_slug": params.category_slug,
                    "location_text": params.location_text,
                    "resolved_location_id": resolved_location.location_id,
                    "resolved_location_name": resolved_location.matched_name,
                    "location_match_score": round(resolved_location.match_score, 3),
                    "radius_km": params.radius_km,
                    "sort": params.sort,
                    "filters": params.filters,
                },
                "listings": [item.model_dump(exclude={"coordinates"}) for item in paged_listings],
                "attributes": [attr.model_dump() for attr in paged_attrs],
                "paging": {
                    "page": params.page,
                    "page_size": params.page_size,
                    "returned_count": len(paged_listings),
                    "filtered_total_count": total_filtered,
                    "raw_total_count": raw_total_count,
                    "exhaustive": exhausted,
                },
                "request_meta": request_meta,
            }
            return encode_tool_payload(
                payload_type="kijiji.search.v1",
                backend_used=backend_used,
                warnings=warnings,
                data=data,
            )
        except Exception:  # noqa: BLE001
            self._finish_error(trace, backend_hint=backend_used)
            raise

    async def get_filter_options(self, params: GetFilterOptionsInput) -> str:
        trace = self._metrics.start_request("get_filter_options")
        warnings: list[str] = []
        backend_used = "http"
        try:
            resolved_location, location_warnings = await self._location_service.resolve_location(
                params.location_text
            )
            warnings.extend(location_warnings)

            cache_key = f"filters:{resolved_location.location_id}"
            cached = self._cache_get(cache_key)

            if cached is None:
                url = build_search_url(
                    location_id=resolved_location.location_id,
                    sort="most_recent",
                    window=QueryWindow(offset=0, limit=40),
                )
                next_data, backend_used, backend_warnings = await self._orchestrator.fetch_next_data(
                    url
                )
                warnings.extend(backend_warnings)
                parsed = parse_search_next_data(next_data, url_hint=url)
                cached = {
                    "backend_used": backend_used,
                    "filter_groups": parsed.filter_groups,
                    "filters": parsed.filters,
                    "filter_values": parsed.filter_values,
                }
                self._cache_set(cache_key, cached, ttl_seconds=10 * 60)
            else:
                backend_used = cached["backend_used"]

            request_meta = self._finish_success(trace, backend_used, warnings)
            data = {
                "query_meta": {
                    "query": params.query,
                    "category_slug": params.category_slug,
                    "location_text": params.location_text,
                    "resolved_location_id": resolved_location.location_id,
                    "resolved_location_name": resolved_location.matched_name,
                    "applied_filters": params.applied_filters,
                },
                "filter_groups": cached["filter_groups"],
                "filters": cached["filters"],
                "filter_values": cached["filter_values"],
                "request_meta": request_meta,
            }
            return encode_tool_payload(
                payload_type="kijiji.filter_options.v1",
                backend_used=backend_used,
                warnings=warnings,
                data=data,
            )
        except Exception:  # noqa: BLE001
            self._finish_error(trace, backend_hint=backend_used)
            raise

    async def get_listing_images(self, params: GetListingImagesInput) -> str:
        trace = self._metrics.start_request("get_listing_images")
        warnings: list[str] = []
        backend_used = "http"
        try:
            listing_id = parse_listing_id(params.listing_id_or_url)
            listing_url = self._listing_url_for_input(params.listing_id_or_url, listing_id)

            next_data, backend_used, backend_warnings = await self._orchestrator.fetch_next_data(
                listing_url
            )
            warnings.extend(backend_warnings)

            details, _, image_urls = parse_listing_details_next_data(next_data, listing_id=listing_id)

            images = [
                {
                    "index": index,
                    "url": self._rewrite_image_variant(url, params.image_variant),
                }
                for index, url in enumerate(image_urls)
            ]

            request_meta = self._finish_success(trace, backend_used, warnings)
            data = {
                "listing_meta": {
                    "listing_id": details.listing_id,
                    "title": details.title,
                    "url": details.url,
                    "image_count": details.image_count,
                },
                "images": images,
                "request_meta": request_meta,
            }
            return encode_tool_payload(
                payload_type="kijiji.images.v1",
                backend_used=backend_used,
                warnings=warnings,
                data=data,
            )
        except Exception:  # noqa: BLE001
            self._finish_error(trace, backend_hint=backend_used)
            raise

    async def get_listing_details(self, params: GetListingDetailsInput) -> str:
        trace = self._metrics.start_request("get_listing_details")
        warnings: list[str] = []
        backend_used = "http"
        try:
            listing_id = parse_listing_id(params.listing_id_or_url)
            listing_url = self._listing_url_for_input(params.listing_id_or_url, listing_id)

            next_data, backend_used, backend_warnings = await self._orchestrator.fetch_next_data(
                listing_url
            )
            warnings.extend(backend_warnings)

            details, attributes, _ = parse_listing_details_next_data(next_data, listing_id=listing_id)

            request_meta = self._finish_success(trace, backend_used, warnings)
            data = {
                "listing": details.model_dump(exclude={"coordinates"}),
                "attributes": [attr.model_dump() for attr in attributes],
                "image_count": details.image_count,
                "request_meta": request_meta,
            }
            return encode_tool_payload(
                payload_type="kijiji.details.v1",
                backend_used=backend_used,
                warnings=warnings,
                data=data,
            )
        except Exception:  # noqa: BLE001
            self._finish_error(trace, backend_hint=backend_used)
            raise

    async def self_check(self) -> str:
        trace = self._metrics.start_request("self_check")
        warnings: list[str] = []
        backend_used = "http"
        try:
            checks = await self._collect_self_checks()
            warnings.extend(
                f"{check['name']}: {check['detail']}" for check in checks if not check["ok"]
            )
            request_meta = self._finish_success(trace, backend_used, warnings)
            data = {
                "checks": checks,
                "orchestrator": {"circuits": self._orchestrator.circuit_state()},
                "metrics": self._metrics.snapshot(),
                "request_meta": request_meta,
            }
            return encode_tool_payload(
                payload_type="kijiji.self_check.v1",
                backend_used=backend_used,
                warnings=warnings,
                data=data,
            )
        except Exception:  # noqa: BLE001
            self._finish_error(trace, backend_hint=backend_used)
            raise

    def _finish_success(
        self,
        trace: RequestTrace,
        backend_used: str,
        warnings: list[str],
    ) -> dict[str, Any]:
        latency_ms = round((time.perf_counter() - trace.started_at) * 1000, 2)
        self._metrics.record_success(
            trace=trace,
            latency_ms=latency_ms,
            backend_used=backend_used,
            warning_count=len(warnings),
        )
        return {
            "request_id": trace.request_id,
            "tool_name": trace.tool_name,
            "latency_ms": latency_ms,
        }

    def _finish_error(self, trace: RequestTrace, backend_hint: str | None = None) -> None:
        latency_ms = round((time.perf_counter() - trace.started_at) * 1000, 2)
        self._metrics.record_error(
            trace=trace,
            latency_ms=latency_ms,
            backend_hint=backend_hint,
        )

    async def _collect_self_checks(self) -> list[dict[str, Any]]:
        checks = [
            self._validate_nominatim_config(),
            await self._probe_network(
                name="kijiji_home_reachability",
                url="https://www.kijiji.ca",
                timeout_seconds=8.0,
            ),
            await self._probe_network(
                name="nominatim_reachability",
                url=f"{self._settings.nominatim_base_url}/search",
                timeout_seconds=8.0,
                params={"format": "jsonv2", "limit": 1, "q": "toronto"},
            ),
        ]
        preflight = self._orchestrator.playwright_preflight()
        checks.append(
            {
                "name": "playwright_cli_preflight",
                "ok": preflight.available,
                "detail": preflight.reason or "playwright-cli run-code is available",
            }
        )
        return checks

    async def _probe_network(
        self,
        name: str,
        url: str,
        timeout_seconds: float,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        started = time.perf_counter()
        try:
            response = await self._http_client.get(url, params=params, timeout=timeout_seconds)
        except Exception as exc:  # noqa: BLE001
            return {
                "name": name,
                "ok": False,
                "detail": f"network probe failed: {exc}",
                "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            }

        latency_ms = round((time.perf_counter() - started) * 1000, 2)
        status_code = response.status_code
        return {
            "name": name,
            "ok": status_code < 500,
            "detail": f"status={status_code}",
            "latency_ms": latency_ms,
        }

    def _validate_nominatim_config(self) -> dict[str, Any]:
        parsed = urlparse(self._settings.nominatim_base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return {
                "name": "nominatim_config",
                "ok": False,
                "detail": "NOMINATIM_BASE_URL must be a valid http(s) URL.",
            }
        if not self._settings.nominatim_user_agent.strip():
            return {
                "name": "nominatim_config",
                "ok": False,
                "detail": "NOMINATIM_USER_AGENT is empty.",
            }
        return {
            "name": "nominatim_config",
            "ok": True,
            "detail": "nominatim base URL and user-agent look valid.",
        }

    @staticmethod
    def _listing_url_for_input(raw: str, listing_id: str) -> str:
        raw = raw.strip()
        if raw.startswith("http://") or raw.startswith("https://"):
            return raw
        return f"https://www.kijiji.ca/v-cars-trucks/x/{listing_id}"

    @staticmethod
    def _rewrite_image_variant(url: str, variant: str) -> str:
        if variant == "as-is":
            return url
        if "?rule=" in url:
            return url.split("?rule=", 1)[0] + f"?rule={variant}"
        joiner = "&" if "?" in url else "?"
        return f"{url}{joiner}rule={variant}"
