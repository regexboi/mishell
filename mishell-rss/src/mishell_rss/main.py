from __future__ import annotations

import logging
import threading
from datetime import UTC, datetime
from typing import Annotated

import uvicorn
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import Depends, FastAPI, HTTPException, Query
from sqlalchemy import asc, case, desc, select
from sqlalchemy.orm import Session

from mishell_rss.config import get_settings
from mishell_rss.db import SessionLocal, get_db, init_db
from mishell_rss.ingest import IngestionService
from mishell_rss.models import Article, ArticleSummary
from mishell_rss.schemas import SaveForLaterResponse, StreamItem, StreamResponse
from mishell_rss.summarizer import ArticleSummarizer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)
logging.getLogger("httpx").setLevel(logging.WARNING)

settings = get_settings()
app = FastAPI(title="mishell-rss", version="0.1.0")

_ingest_lock = threading.Lock()
_scheduler: BackgroundScheduler | None = None


def build_ingest_service() -> IngestionService:
    summarizer = ArticleSummarizer(api_key=settings.openai_api_key, model=settings.openai_model)
    return IngestionService(
        feed_urls=settings.feed_urls,
        timeout_seconds=settings.request_timeout_seconds,
        max_entries_per_feed=settings.max_entries_per_feed,
        user_agent=settings.user_agent,
        summarizer=summarizer,
    )


def run_ingestion_once() -> dict[str, int]:
    if not _ingest_lock.acquire(blocking=False):
        return {
            "feeds_processed": 0,
            "entries_seen": 0,
            "new_articles": 0,
            "new_summaries": 0,
        }

    try:
        ingest_service = build_ingest_service()
        with SessionLocal() as db:
            stats = ingest_service.ingest_all_feeds(db)
            return {
                "feeds_processed": stats.feeds_processed,
                "entries_seen": stats.entries_seen,
                "new_articles": stats.new_articles,
                "new_summaries": stats.new_summaries,
            }
    finally:
        _ingest_lock.release()


@app.on_event("startup")
def on_startup() -> None:
    global _scheduler

    init_db()
    _scheduler = BackgroundScheduler(timezone="UTC")
    _scheduler.add_job(
        run_ingestion_once,
        trigger="interval",
        minutes=settings.poll_interval_minutes,
        max_instances=1,
        coalesce=True,
    )
    _scheduler.start()
    run_ingestion_once()
    logger.info("scheduler started (every %s minutes)", settings.poll_interval_minutes)


@app.on_event("shutdown")
def on_shutdown() -> None:
    if _scheduler:
        _scheduler.shutdown(wait=False)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ingest/run")
def manual_ingest() -> dict[str, int]:
    return run_ingestion_once()


@app.get("/stream", response_model=StreamResponse)
def get_stream(
    db: Annotated[Session, Depends(get_db)],
    limit: int = Query(default=settings.stream_page_size, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
) -> StreamResponse:
    ordering_time = case(
        (Article.published_at.is_not(None), Article.published_at),
        else_=Article.created_at,
    )

    stmt = (
        select(ArticleSummary, Article)
        .join(Article, Article.id == ArticleSummary.article_id)
        .order_by(
            desc(ordering_time),
            asc(ArticleSummary.sequence),
            desc(ArticleSummary.created_at),
        )
        .offset(offset)
        .limit(limit)
    )

    rows = db.execute(stmt).all()
    items = [
        StreamItem(
            summary_id=summary.id,
            summary_text=summary.summary_text,
            summary_saved_for_later=summary.saved_for_later,
            article_id=article.id,
            article_saved_for_later=article.saved_for_later,
            source_name=article.source_name,
            title=article.title,
            link=article.link,
            published_at=article.published_at,
        )
        for summary, article in rows
    ]

    next_offset = offset + len(items) if len(items) == limit else None
    return StreamResponse(items=items, next_offset=next_offset)


@app.post("/summaries/{summary_id}/save-for-later", response_model=SaveForLaterResponse)
def save_summary_for_later(
    summary_id: str,
    db: Annotated[Session, Depends(get_db)],
) -> SaveForLaterResponse:
    summary = db.get(ArticleSummary, summary_id)
    if summary is None:
        raise HTTPException(status_code=404, detail="Summary not found")

    article = db.get(Article, summary.article_id)
    if article is None:
        raise HTTPException(status_code=404, detail="Article not found")

    now = datetime.now(UTC)
    summary.saved_for_later = True
    summary.saved_for_later_at = now
    article.saved_for_later = True
    article.saved_for_later_at = now
    db.commit()

    return SaveForLaterResponse(
        summary_id=summary.id,
        article_id=article.id,
        summary_saved_for_later=summary.saved_for_later,
        article_saved_for_later=article.saved_for_later,
    )


def run() -> None:
    uvicorn.run("mishell_rss.main:app", host="0.0.0.0", port=8000, reload=True)
