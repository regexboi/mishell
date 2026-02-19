# mishell-rss

Python RSS aggregator for AI feeds, backed by Neon Postgres.

## Features

- Polls all requested RSS/Atom feeds
- Stores canonical articles and deduplicates by feed entry fingerprint
- Summarizes each new article with `gpt-5-mini` using structured JSON output
- Allows multiple summary chunks per article when the model finds distinct takeaways
- Exposes API endpoints for Android stream consumption
- Supports save-for-later actions on summary chunks (also marks parent article)
- Includes scheduled polling and manual ingestion endpoint

## Quickstart

1. Create and sync environment:

```bash
uv sync
```

2. Configure env vars:

```bash
cp .env.example .env
# edit .env
```

Required variables:

- `DATABASE_URL`: Neon connection URL
- `OPENAI_API_KEY`: API key for GPT summaries

3. Run the API:

```bash
uv run mishell-rss-api
```

4. Trigger an ingestion pass:

```bash
curl -X POST http://localhost:8000/ingest/run
```

## API

- `GET /health`
- `POST /ingest/run`
- `GET /stream?limit=50&offset=0`
- `POST /summaries/{summary_id}/save-for-later`

## Notes

- Scheduled ingestion starts on API boot and runs every `POLL_INTERVAL_MINUTES`.
- Each feed run is capped by `MAX_ENTRIES_PER_FEED` (default `100`) to avoid deep historical replays.
- If `OPENAI_API_KEY` is missing, fallback summaries use article titles.
