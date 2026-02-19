from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class StreamItem(BaseModel):
    summary_id: str
    summary_text: str
    summary_saved_for_later: bool
    article_id: str
    article_saved_for_later: bool
    source_name: str
    title: str
    link: str
    published_at: datetime | None


class StreamResponse(BaseModel):
    items: list[StreamItem]
    next_offset: int | None


class SaveForLaterResponse(BaseModel):
    summary_id: str
    article_id: str
    summary_saved_for_later: bool
    article_saved_for_later: bool
