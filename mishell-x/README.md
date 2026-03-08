# X Bookmarks -> Neon Sync

This repo contains a resumable sync script that ingests your X bookmarks into Neon.

## What it stores

- Full tweet text
- Tweet metadata (timestamps, language, metrics, reply/conversation fields, entities, attachments, etc.)
- Author metadata (id, username, name, verification, public metrics)
- Raw tweet/author payloads for future-proofing

## Resume behavior

The sync is incremental by default:

- It saves a checkpoint (`bookmark_sync_state.checkpoint_tweet_id`).
- On later runs, it fetches bookmark pages from newest to oldest and stops when it reaches:
  - the checkpoint tweet, or
  - any tweet ID already present in `x_bookmarks`.

So after initial backfill, later runs only process newly bookmarked tweets.

## Prerequisites

- Python 3.11+
- `uv`
- X API OAuth2 user token with bookmark read scopes
- Neon Postgres connection string

## Setup

```bash
cp .env.example .env
```

Fill these env vars:

- `DATABASE_URL` or `NEON_CONNECTION_STRING`
- `X_BEARER_TOKEN`, `X_SECRET_KEY`, or `X_ACCESS_TOKEN`
- Optional: `X_AUTH_MODE` (`auto`, `bearer`, `oauth1`)
- Optional: `X_USER_ID` (if omitted, script calls `/2/users/me`)
- Optional: `X_API_BASE_URL` (defaults to `https://api.x.com/2`)

For OAuth1 user-context auth, set all of:
- `X_API_KEY`
- `X_API_SECRET`
- `X_ACCESS_TOKEN`
- `X_ACCESS_TOKEN_SECRET`

## Run

```bash
uv run python scripts/sync_x_bookmarks.py
```

Optional flags:

```bash
# test fetch without writing to DB
uv run python scripts/sync_x_bookmarks.py --dry-run

# force full backfill regardless of checkpoint
uv run python scripts/sync_x_bookmarks.py --full-sync

# limit pages for testing
uv run python scripts/sync_x_bookmarks.py --max-pages 2
```
