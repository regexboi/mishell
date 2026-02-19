from __future__ import annotations

import calendar
import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime
from html import unescape

import feedparser
import httpx
from bs4 import BeautifulSoup

FEED_FALLBACKS: dict[str, list[str]] = {
    "https://www.anthropic.com/rss.xml": ["https://www.anthropic.com/news/rss.xml"],
}


@dataclass(slots=True)
class ParsedFeedEntry:
    source_name: str
    feed_url: str
    external_id: str
    fingerprint: str
    title: str
    link: str
    published_at: datetime | None
    author: str | None
    excerpt: str | None
    content: str | None


def _extract_text(value: str | None) -> str | None:
    if not value:
        return None
    soup = BeautifulSoup(unescape(value), "html.parser")
    text = " ".join(soup.get_text(" ", strip=True).split())
    return text or None


def _to_datetime(entry: dict) -> datetime | None:
    for key in ("published_parsed", "updated_parsed", "created_parsed"):
        parsed = entry.get(key)
        if parsed:
            ts = calendar.timegm(parsed)
            return datetime.fromtimestamp(ts, tz=UTC)
    return None


def _fingerprint(feed_url: str, external_id: str, link: str, title: str) -> str:
    raw = f"{feed_url}|{external_id}|{link}|{title}".encode()
    return hashlib.sha256(raw).hexdigest()


def parse_feed(feed_url: str, timeout_seconds: int, user_agent: str) -> list[ParsedFeedEntry]:
    headers = {"User-Agent": user_agent}
    candidate_urls = [feed_url, *FEED_FALLBACKS.get(feed_url, [])]
    response: httpx.Response | None = None
    last_response: httpx.Response | None = None
    for candidate_url in candidate_urls:
        last_response = httpx.get(
            candidate_url,
            timeout=timeout_seconds,
            headers=headers,
            follow_redirects=True,
        )
        if last_response.is_success:
            response = last_response
            break
    if response is None:
        if last_response is None:
            raise RuntimeError("No feed URL candidates available")
        last_response.raise_for_status()

    parsed = feedparser.parse(response.text)
    source_name = parsed.feed.get("title") or feed_url

    entries: list[ParsedFeedEntry] = []
    for item in parsed.entries:
        title = (item.get("title") or "Untitled").strip()
        link = (item.get("link") or "").strip()
        if not link:
            continue

        external_id = (item.get("id") or item.get("guid") or link).strip()
        summary = _extract_text(item.get("summary"))
        content = None
        contents = item.get("content") or []
        if contents:
            content = _extract_text(contents[0].get("value"))

        entry = ParsedFeedEntry(
            source_name=source_name,
            feed_url=feed_url,
            external_id=external_id,
            fingerprint=_fingerprint(feed_url, external_id, link, title),
            title=title,
            link=link,
            published_at=_to_datetime(item),
            author=(item.get("author") or None),
            excerpt=summary,
            content=content,
        )
        entries.append(entry)

    return entries
