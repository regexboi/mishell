from __future__ import annotations

import argparse
import json

from mishell_rss.db import SessionLocal, init_db
from mishell_rss.ingest import IngestionService
from mishell_rss.main import build_ingest_service


def run_once(service: IngestionService) -> dict[str, int]:
    with SessionLocal() as db:
        stats = service.ingest_all_feeds(db)
        return {
            "feeds_processed": stats.feeds_processed,
            "entries_seen": stats.entries_seen,
            "new_articles": stats.new_articles,
            "new_summaries": stats.new_summaries,
        }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a single ingestion pass")
    parser.add_argument("--json", action="store_true", help="Print ingest stats as JSON")
    args = parser.parse_args()

    init_db()
    service = build_ingest_service()
    stats = run_once(service)

    if args.json:
        print(json.dumps(stats))
    else:
        print(
            "feeds_processed={feeds_processed} entries_seen={entries_seen} "
            "new_articles={new_articles} new_summaries={new_summaries}".format(**stats)
        )


if __name__ == "__main__":
    main()
