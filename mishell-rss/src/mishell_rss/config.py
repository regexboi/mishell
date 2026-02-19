from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_FEED_URLS = [
    "https://openai.com/news/rss.xml",
    "https://www.anthropic.com/rss.xml",
    "https://simonwillison.net/atom/everything/",
    "https://rss.beehiiv.com/feeds/2R3C6Bt5wj.xml",
    "https://www.interconnects.ai/feed",
    "https://www.marktechpost.com/feed",
    "https://huggingface.co/blog/feed.xml",
    "https://venturebeat.com/category/ai/feed/",
    "https://www.artificialintelligence-news.com/feed/rss/",
    "https://www.oneusefulthing.org/feed",
    "https://magazine.sebastianraschka.com/feed",
    "https://www.latent.space/feed",
]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str
    openai_api_key: str | None = None
    openai_model: str = "gpt-5-mini"
    poll_interval_minutes: int = 15
    stream_page_size: int = 50
    max_entries_per_feed: int = 100
    request_timeout_seconds: int = 20
    user_agent: str = "mishell-rss/0.1 (+https://github.com/mishca/mishell-rss)"
    feed_urls_csv: str = ",".join(DEFAULT_FEED_URLS)

    @property
    def feed_urls(self) -> list[str]:
        raw = self.feed_urls_csv.replace("\n", ",")
        return [url.strip() for url in raw.split(",") if url.strip()]


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
