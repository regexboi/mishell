from __future__ import annotations

import logging
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from mishell_rss.feeds import parse_feed
from mishell_rss.models import Article, ArticleSummary
from mishell_rss.summarizer import ArticleSummarizer

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class IngestStats:
    feeds_processed: int = 0
    entries_seen: int = 0
    new_articles: int = 0
    new_summaries: int = 0


class IngestionService:
    def __init__(
        self,
        feed_urls: list[str],
        timeout_seconds: int,
        max_entries_per_feed: int,
        user_agent: str,
        summarizer: ArticleSummarizer,
    ) -> None:
        self.feed_urls = feed_urls
        self.timeout_seconds = timeout_seconds
        self.max_entries_per_feed = max_entries_per_feed
        self.user_agent = user_agent
        self.summarizer = summarizer

    def ingest_all_feeds(self, db: Session) -> IngestStats:
        stats = IngestStats()

        for feed_url in self.feed_urls:
            try:
                entries = parse_feed(feed_url, self.timeout_seconds, self.user_agent)
            except Exception as exc:
                logger.warning("feed fetch failed for %s: %s", feed_url, exc)
                continue

            entries = entries[: self.max_entries_per_feed]
            stats.feeds_processed += 1
            stats.entries_seen += len(entries)

            for entry in entries:
                existing = db.scalar(
                    select(Article.id).where(Article.fingerprint == entry.fingerprint)
                )
                if existing:
                    continue

                summaries = self.summarizer.summarize_article(entry)
                article = Article(
                    source_name=entry.source_name,
                    feed_url=entry.feed_url,
                    external_id=entry.external_id,
                    fingerprint=entry.fingerprint,
                    title=entry.title,
                    link=entry.link,
                    published_at=entry.published_at,
                    author=entry.author,
                    excerpt=entry.excerpt,
                    content=entry.content,
                )
                try:
                    db.add(article)
                    db.flush()

                    for idx, summary_text in enumerate(summaries):
                        db.add(
                            ArticleSummary(
                                article_id=article.id,
                                sequence=idx,
                                summary_text=summary_text,
                            )
                        )
                    db.commit()
                    stats.new_articles += 1
                    stats.new_summaries += len(summaries)
                except IntegrityError:
                    db.rollback()
                    continue
        return stats
