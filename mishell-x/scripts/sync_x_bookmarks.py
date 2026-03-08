from __future__ import annotations

import argparse
import os
import time
from dataclasses import dataclass
from typing import Any

import httpx
import psycopg
from authlib.integrations.httpx_client import OAuth1Auth
from psycopg.types.json import Jsonb

BOOKMARK_FIELDS = ",".join(
    [
        "id",
        "author_id",
        "created_at",
        "text",
        "lang",
        "source",
        "conversation_id",
        "in_reply_to_user_id",
        "possibly_sensitive",
        "public_metrics",
        "entities",
        "context_annotations",
        "attachments",
        "geo",
        "referenced_tweets",
        "edit_history_tweet_ids",
    ]
)

USER_FIELDS = ",".join(
    [
        "id",
        "name",
        "username",
        "verified",
        "verified_type",
        "public_metrics",
    ]
)


@dataclass
class SyncResult:
    pages_fetched: int
    bookmarks_processed: int
    stopped_on_existing: bool
    checkpoint_tweet_id: str | None
    truncated_by_max_pages: bool


@dataclass
class XAuth:
    headers: dict[str, str]
    auth: Any | None
    mode: str


def require_env(*names: str) -> str:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    joined = ", ".join(names)
    raise ValueError(f"Missing required environment variable. Expected one of: {joined}")


def get_env(*names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return None


def build_x_auth() -> XAuth:
    auth_mode = os.getenv("X_AUTH_MODE", "auto").strip().lower()
    access_token = os.getenv("X_ACCESS_TOKEN")
    access_token_secret = os.getenv("X_ACCESS_TOKEN_SECRET")
    api_key = get_env("X_API_KEY", "X_CONSUMER_KEY")
    api_secret = get_env("X_API_SECRET", "X_API_KEY_SECRET", "X_CONSUMER_SECRET")

    if auth_mode not in {"auto", "bearer", "oauth1"}:
        raise ValueError("X_AUTH_MODE must be one of: auto, bearer, oauth1")

    if auth_mode == "oauth1" or (auth_mode == "auto" and access_token and access_token_secret):
        if not (access_token and access_token_secret and api_key and api_secret):
            raise ValueError(
                "OAuth1 requires X_ACCESS_TOKEN + X_ACCESS_TOKEN_SECRET + X_API_KEY + X_API_SECRET "
                "(or X_CONSUMER_KEY + X_CONSUMER_SECRET). If your X_ACCESS_TOKEN is actually an OAuth2 bearer "
                "token, set X_AUTH_MODE=bearer."
            )
        return XAuth(
            headers={},
            auth=OAuth1Auth(api_key, api_secret, access_token, access_token_secret),
            mode="oauth1_user_context",
        )

    bearer = get_env("X_BEARER_TOKEN", "X_SECRET_KEY", "X_ACCESS_TOKEN")
    if bearer:
        return XAuth(headers={"Authorization": f"Bearer {bearer}"}, auth=None, mode="oauth2_bearer")

    raise ValueError(
        "Missing X auth credentials. Provide either:\n"
        "1) OAuth2 user bearer token: X_BEARER_TOKEN\n"
        "2) OAuth1 user context: X_ACCESS_TOKEN + X_ACCESS_TOKEN_SECRET + X_API_KEY + X_API_SECRET"
    )


def x_get_json(
    *,
    client: httpx.Client,
    url: str,
    headers: dict[str, str] | None = None,
    auth: Any | None = None,
    params: dict[str, Any],
    retries: int = 5,
) -> dict[str, Any]:
    attempt = 0
    while True:
        response = client.get(url, headers=headers, auth=auth, params=params)
        if response.status_code == 429 and attempt < retries:
            reset_at = response.headers.get("x-rate-limit-reset")
            wait_s = 60
            if reset_at:
                try:
                    wait_s = max(int(reset_at) - int(time.time()), 1)
                except ValueError:
                    wait_s = 60
            wait_s = min(wait_s, 300)
            print(f"Rate limited. Waiting {wait_s}s before retrying...")
            time.sleep(wait_s)
            attempt += 1
            continue
        if 500 <= response.status_code < 600 and attempt < retries:
            wait_s = min(2**attempt, 30)
            print(f"X API temporary error ({response.status_code}). Retrying in {wait_s}s...")
            time.sleep(wait_s)
            attempt += 1
            continue

        if response.status_code >= 400:
            if response.status_code == 403:
                try:
                    payload = response.json()
                except Exception:
                    payload = None
                if isinstance(payload, dict):
                    detail = payload.get("detail", "")
                    if "Unsupported Authentication" in str(payload.get("title", "")) or "unsupported-authentication" in str(
                        payload.get("type", "")
                    ):
                        raise RuntimeError(
                            "X API rejected authentication for this endpoint. "
                            "Bookmarks require user-context auth (OAuth1 user context or OAuth2 user context). "
                            f"Server detail: {detail}"
                        )
            response.raise_for_status()
        return response.json()


def resolve_user_id(client: httpx.Client, base_url: str, x_auth: XAuth) -> str:
    env_user_id = os.getenv("X_USER_ID")
    if env_user_id:
        return env_user_id

    me_payload = x_get_json(
        client=client,
        url=f"{base_url}/users/me",
        headers=x_auth.headers,
        auth=x_auth.auth,
        params={"user.fields": "id"},
    )
    data = me_payload.get("data", {})
    user_id = data.get("id")
    if not user_id:
        raise RuntimeError("Could not resolve X user id from /users/me response.")
    return user_id


def ensure_tables(conn: psycopg.Connection[Any]) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS public.x_bookmarks (
              tweet_id text PRIMARY KEY,
              tweet_text text NOT NULL,
              tweet_created_at timestamptz,
              author_id text,
              author_username text,
              author_name text,
              author_verified boolean,
              author_verified_type text,
              author_public_metrics jsonb,
              lang text,
              source text,
              conversation_id text,
              in_reply_to_user_id text,
              possibly_sensitive boolean,
              public_metrics jsonb,
              entities jsonb,
              context_annotations jsonb,
              attachments jsonb,
              geo jsonb,
              referenced_tweets jsonb,
              edit_history_tweet_ids jsonb,
              raw_tweet jsonb NOT NULL,
              raw_author jsonb,
              first_seen_at timestamptz NOT NULL DEFAULT now(),
              last_synced_at timestamptz NOT NULL DEFAULT now()
            );
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_x_bookmarks_created_at
              ON public.x_bookmarks (tweet_created_at DESC);
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_x_bookmarks_author_username
              ON public.x_bookmarks (author_username);
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS public.bookmark_sync_state (
              source text PRIMARY KEY,
              checkpoint_tweet_id text,
              last_synced_at timestamptz,
              last_run_processed integer NOT NULL DEFAULT 0,
              updated_at timestamptz NOT NULL DEFAULT now()
            );
            """
        )
    conn.commit()


def get_sync_checkpoint(conn: psycopg.Connection[Any], source: str) -> str | None:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT checkpoint_tweet_id
            FROM public.bookmark_sync_state
            WHERE source = %s
            """,
            (source,),
        )
        row = cur.fetchone()
    return row[0] if row else None


def set_sync_checkpoint(
    conn: psycopg.Connection[Any], source: str, checkpoint_tweet_id: str | None, processed: int
) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO public.bookmark_sync_state (
              source, checkpoint_tweet_id, last_synced_at, last_run_processed, updated_at
            )
            VALUES (%s, %s, now(), %s, now())
            ON CONFLICT (source) DO UPDATE SET
              checkpoint_tweet_id = EXCLUDED.checkpoint_tweet_id,
              last_synced_at = EXCLUDED.last_synced_at,
              last_run_processed = EXCLUDED.last_run_processed,
              updated_at = now();
            """,
            (source, checkpoint_tweet_id, processed),
        )
    conn.commit()


def find_existing_tweet_ids(conn: psycopg.Connection[Any], tweet_ids: list[str]) -> set[str]:
    if not tweet_ids:
        return set()
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT tweet_id
            FROM public.x_bookmarks
            WHERE tweet_id = ANY(%s)
            """,
            (tweet_ids,),
        )
        rows = cur.fetchall()
    return {row[0] for row in rows}


def upsert_bookmarks(
    conn: psycopg.Connection[Any], tweets: list[dict[str, Any]], users_by_id: dict[str, dict[str, Any]]
) -> int:
    if not tweets:
        return 0

    upsert_sql = """
        INSERT INTO public.x_bookmarks (
          tweet_id,
          tweet_text,
          tweet_created_at,
          author_id,
          author_username,
          author_name,
          author_verified,
          author_verified_type,
          author_public_metrics,
          lang,
          source,
          conversation_id,
          in_reply_to_user_id,
          possibly_sensitive,
          public_metrics,
          entities,
          context_annotations,
          attachments,
          geo,
          referenced_tweets,
          edit_history_tweet_ids,
          raw_tweet,
          raw_author,
          last_synced_at
        )
        VALUES (
          %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now()
        )
        ON CONFLICT (tweet_id) DO UPDATE SET
          tweet_text = EXCLUDED.tweet_text,
          tweet_created_at = EXCLUDED.tweet_created_at,
          author_id = EXCLUDED.author_id,
          author_username = EXCLUDED.author_username,
          author_name = EXCLUDED.author_name,
          author_verified = EXCLUDED.author_verified,
          author_verified_type = EXCLUDED.author_verified_type,
          author_public_metrics = EXCLUDED.author_public_metrics,
          lang = EXCLUDED.lang,
          source = EXCLUDED.source,
          conversation_id = EXCLUDED.conversation_id,
          in_reply_to_user_id = EXCLUDED.in_reply_to_user_id,
          possibly_sensitive = EXCLUDED.possibly_sensitive,
          public_metrics = EXCLUDED.public_metrics,
          entities = EXCLUDED.entities,
          context_annotations = EXCLUDED.context_annotations,
          attachments = EXCLUDED.attachments,
          geo = EXCLUDED.geo,
          referenced_tweets = EXCLUDED.referenced_tweets,
          edit_history_tweet_ids = EXCLUDED.edit_history_tweet_ids,
          raw_tweet = EXCLUDED.raw_tweet,
          raw_author = EXCLUDED.raw_author,
          last_synced_at = now();
    """

    rows: list[tuple[Any, ...]] = []
    for tweet in tweets:
        author_id = tweet.get("author_id")
        author = users_by_id.get(author_id, {})
        rows.append(
            (
                tweet.get("id"),
                tweet.get("text", ""),
                tweet.get("created_at"),
                author_id,
                author.get("username"),
                author.get("name"),
                author.get("verified"),
                author.get("verified_type"),
                Jsonb(author.get("public_metrics")) if author.get("public_metrics") is not None else None,
                tweet.get("lang"),
                tweet.get("source"),
                tweet.get("conversation_id"),
                tweet.get("in_reply_to_user_id"),
                tweet.get("possibly_sensitive"),
                Jsonb(tweet.get("public_metrics")) if tweet.get("public_metrics") is not None else None,
                Jsonb(tweet.get("entities")) if tweet.get("entities") is not None else None,
                Jsonb(tweet.get("context_annotations")) if tweet.get("context_annotations") is not None else None,
                Jsonb(tweet.get("attachments")) if tweet.get("attachments") is not None else None,
                Jsonb(tweet.get("geo")) if tweet.get("geo") is not None else None,
                Jsonb(tweet.get("referenced_tweets")) if tweet.get("referenced_tweets") is not None else None,
                Jsonb(tweet.get("edit_history_tweet_ids"))
                if tweet.get("edit_history_tweet_ids") is not None
                else None,
                Jsonb(tweet),
                Jsonb(author) if author else None,
            )
        )

    with conn.cursor() as cur:
        cur.executemany(upsert_sql, rows)
    conn.commit()
    return len(rows)


def sync_bookmarks(conn: psycopg.Connection[Any], args: argparse.Namespace) -> SyncResult:
    x_auth = build_x_auth()
    base_url = os.getenv("X_API_BASE_URL", "https://api.x.com/2").rstrip("/")
    checkpoint = get_sync_checkpoint(conn, args.source)

    pages_fetched = 0
    bookmarks_processed = 0
    stopped_on_existing = False
    newest_tweet_id: str | None = None
    truncated_by_max_pages = False

    with httpx.Client(timeout=30.0, follow_redirects=True) as client:
        user_id = resolve_user_id(client, base_url, x_auth)
        next_token: str | None = None

        while True:
            params: dict[str, Any] = {
                "max_results": args.max_results,
                "expansions": "author_id",
                "tweet.fields": BOOKMARK_FIELDS,
                "user.fields": USER_FIELDS,
            }
            if next_token:
                params["pagination_token"] = next_token

            payload = x_get_json(
                client=client,
                url=f"{base_url}/users/{user_id}/bookmarks",
                headers=x_auth.headers,
                auth=x_auth.auth,
                params=params,
            )
            pages_fetched += 1

            tweets: list[dict[str, Any]] = payload.get("data", [])
            users: list[dict[str, Any]] = payload.get("includes", {}).get("users", [])
            users_by_id = {u["id"]: u for u in users if "id" in u}

            if tweets and newest_tweet_id is None:
                newest_tweet_id = tweets[0].get("id")

            should_stop = False
            tweets_to_write = tweets

            if tweets and not args.full_sync:
                stop_idx: int | None = None

                if checkpoint:
                    for idx, tweet in enumerate(tweets):
                        if tweet.get("id") == checkpoint:
                            stop_idx = idx
                            break

                if stop_idx is None:
                    existing_ids = find_existing_tweet_ids(conn, [t.get("id") for t in tweets if t.get("id")])
                    if existing_ids:
                        for idx, tweet in enumerate(tweets):
                            if tweet.get("id") in existing_ids:
                                stop_idx = idx
                                break

                if stop_idx is not None:
                    tweets_to_write = tweets[:stop_idx]
                    should_stop = True
                    stopped_on_existing = True

            if args.dry_run:
                bookmarks_processed += len(tweets_to_write)
            else:
                bookmarks_processed += upsert_bookmarks(conn, tweets_to_write, users_by_id)

            meta = payload.get("meta", {})
            next_token = meta.get("next_token")

            if should_stop:
                break
            if args.max_pages is not None and pages_fetched >= args.max_pages:
                truncated_by_max_pages = True
                break
            if not next_token:
                break

    next_checkpoint = checkpoint if truncated_by_max_pages else (newest_tweet_id or checkpoint)
    if not args.dry_run:
        set_sync_checkpoint(conn, args.source, next_checkpoint, bookmarks_processed)

    return SyncResult(
        pages_fetched=pages_fetched,
        bookmarks_processed=bookmarks_processed,
        stopped_on_existing=stopped_on_existing,
        checkpoint_tweet_id=next_checkpoint,
        truncated_by_max_pages=truncated_by_max_pages,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Sync X bookmarks into Neon")
    parser.add_argument(
        "--source",
        default="x_bookmarks",
        help="Sync source key used in bookmark_sync_state (default: x_bookmarks).",
    )
    parser.add_argument(
        "--max-results",
        type=int,
        default=100,
        help="Bookmarks to request per page (5-100, default: 100).",
    )
    parser.add_argument(
        "--max-pages",
        type=int,
        default=None,
        help="Optional safety limit for number of pages fetched in one run.",
    )
    parser.add_argument(
        "--full-sync",
        action="store_true",
        help="Ignore checkpoint/existing detection and fetch every page.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Fetch and count bookmarks but do not write to Neon.",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()

    if args.max_results < 5 or args.max_results > 100:
        raise ValueError("--max-results must be between 5 and 100.")

    database_url = require_env("DATABASE_URL", "NEON_CONNECTION_STRING")
    with psycopg.connect(database_url) as conn:
        ensure_tables(conn)
        result = sync_bookmarks(conn, args)

    print("Sync complete.")
    print(f"Pages fetched: {result.pages_fetched}")
    print(f"Bookmarks processed: {result.bookmarks_processed}")
    print(f"Stopped on existing bookmark: {result.stopped_on_existing}")
    print(f"Truncated by max pages: {result.truncated_by_max_pages}")
    print(f"Checkpoint tweet id: {result.checkpoint_tweet_id}")


if __name__ == "__main__":
    main()
