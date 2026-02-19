from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class Article(Base):
    __tablename__ = "articles"
    __table_args__ = (
        UniqueConstraint("feed_url", "external_id", name="uq_articles_feed_external"),
        UniqueConstraint("fingerprint", name="uq_articles_fingerprint"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    source_name: Mapped[str] = mapped_column(String(255), nullable=False)
    feed_url: Mapped[str] = mapped_column(String(1024), nullable=False)
    external_id: Mapped[str] = mapped_column(String(1024), nullable=False)
    fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    title: Mapped[str] = mapped_column(String(1024), nullable=False)
    link: Mapped[str] = mapped_column(String(2048), nullable=False)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    author: Mapped[str | None] = mapped_column(String(255), nullable=True)
    excerpt: Mapped[str | None] = mapped_column(Text, nullable=True)
    content: Mapped[str | None] = mapped_column(Text, nullable=True)
    saved_for_later: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    saved_for_later_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
        nullable=False,
    )

    summaries: Mapped[list[ArticleSummary]] = relationship(
        back_populates="article", cascade="all,delete-orphan"
    )


class ArticleSummary(Base):
    __tablename__ = "article_summaries"
    __table_args__ = (
        UniqueConstraint("article_id", "sequence", name="uq_summaries_article_sequence"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    article_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("articles.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    summary_text: Mapped[str] = mapped_column(Text, nullable=False)
    saved_for_later: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    saved_for_later_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC), nullable=False
    )

    article: Mapped[Article] = relationship(back_populates="summaries")
