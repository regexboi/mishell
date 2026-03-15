from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import uuid4

try:
    import psycopg
    from psycopg.conninfo import conninfo_to_dict
except ImportError:  # pragma: no cover - exercised only before dependency install
    psycopg = None
    conninfo_to_dict = None


CRON_JOB_NAME = "mishell-secretary-reminders-every-30-seconds"

APP_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS public.secretary_reminders (
    id uuid PRIMARY KEY,
    reminder_text text NOT NULL,
    reminder_datetime timestamptz NOT NULL,
    reminder_occurence interval NULL,
    next_run_at timestamptz NOT NULL,
    active boolean NOT NULL DEFAULT TRUE,
    sent_count integer NOT NULL DEFAULT 0,
    last_processed_run_at timestamptz NULL,
    last_enqueued_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT secretary_reminders_nonempty_text CHECK (length(btrim(reminder_text)) > 0),
    CONSTRAINT secretary_reminders_sent_count_nonnegative CHECK (sent_count >= 0)
);

CREATE INDEX IF NOT EXISTS secretary_reminders_due_idx
    ON public.secretary_reminders (active, next_run_at);

CREATE TABLE IF NOT EXISTS public.secretary_reminder_sends (
    id bigserial PRIMARY KEY,
    reminder_id uuid NOT NULL REFERENCES public.secretary_reminders(id) ON DELETE CASCADE,
    reminder_text text NOT NULL,
    reminder_datetime timestamptz NOT NULL,
    queued_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS secretary_reminder_sends_unique_run_idx
    ON public.secretary_reminder_sends (reminder_id, reminder_datetime);

CREATE OR REPLACE FUNCTION public.secretary_touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $secretary_touch$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$secretary_touch$;

DO $secretary_trigger$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'secretary_reminders_touch_updated_at'
    ) THEN
        CREATE TRIGGER secretary_reminders_touch_updated_at
        BEFORE UPDATE ON public.secretary_reminders
        FOR EACH ROW
        EXECUTE FUNCTION public.secretary_touch_updated_at();
    END IF;
END;
$secretary_trigger$;

CREATE OR REPLACE FUNCTION public.secretary_process_due_reminders(batch_size integer DEFAULT 100)
RETURNS integer
LANGUAGE plpgsql
AS $secretary_process$
DECLARE
    processed_count integer := 0;
BEGIN
    WITH due AS (
        SELECT id, reminder_text, next_run_at
        FROM public.secretary_reminders
        WHERE active = TRUE
          AND next_run_at <= now()
          AND (last_processed_run_at IS NULL OR last_processed_run_at < next_run_at)
        ORDER BY next_run_at
        FOR UPDATE SKIP LOCKED
        LIMIT GREATEST(batch_size, 1)
    ),
    queued AS (
        INSERT INTO public.secretary_reminder_sends (reminder_id, reminder_text, reminder_datetime)
        SELECT id, reminder_text, next_run_at
        FROM due
        ON CONFLICT (reminder_id, reminder_datetime) DO NOTHING
        RETURNING reminder_id, reminder_datetime
    ),
    updated AS (
        UPDATE public.secretary_reminders AS reminder
        SET last_processed_run_at = queued.reminder_datetime,
            last_enqueued_at = now(),
            sent_count = reminder.sent_count + 1,
            next_run_at = CASE
                WHEN reminder.reminder_occurence IS NULL THEN reminder.next_run_at
                ELSE reminder.next_run_at + reminder.reminder_occurence
            END,
            active = CASE
                WHEN reminder.reminder_occurence IS NULL THEN FALSE
                ELSE TRUE
            END
        FROM queued
        WHERE reminder.id = queued.reminder_id
        RETURNING 1
    )
    SELECT count(*) INTO processed_count
    FROM updated;

    RETURN processed_count;
END;
$secretary_process$;
"""


@dataclass(frozen=True)
class ReminderRecord:
    id: str
    reminder_text: str
    reminder_datetime: datetime
    reminder_occurence: str | None
    next_run_at: datetime
    active: bool
    sent_count: int

    def to_payload(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "reminder_text": self.reminder_text,
            "reminder_datetime": self.reminder_datetime.isoformat(),
            "reminder_occurence": self.reminder_occurence,
            "next_run_at": self.next_run_at.isoformat(),
            "active": self.active,
            "sent_count": self.sent_count,
        }


class ReminderStore:
    def __init__(self, dsn: str):
        self._dsn = dsn.strip()
        if not self._dsn:
            raise ValueError("NEON_STRING is empty.")

    @classmethod
    def from_env(cls, env_var: str = "NEON_STRING") -> ReminderStore | None:
        dsn = os.getenv(env_var)
        if not dsn or not dsn.strip():
            return None
        return cls(dsn)

    def ensure_schema(self) -> None:
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(APP_SCHEMA_SQL)

    def ensure_setup(self) -> None:
        self.ensure_schema()
        self._ensure_cron_schedule()

    def create_reminder(
        self,
        *,
        reminder_text: str,
        reminder_datetime: str,
        reminder_occurence: str | None = None,
    ) -> ReminderRecord:
        text = reminder_text.strip()
        if not text:
            raise ValueError("reminder_text must not be empty.")

        parsed_datetime = parse_reminder_datetime(reminder_datetime)
        occurence = _normalize_occurence(reminder_occurence)

        self.ensure_setup()
        reminder_id = str(uuid4())

        with self._connect() as conn, conn.cursor() as cur:
            if occurence is None:
                cur.execute(
                    """
                    INSERT INTO public.secretary_reminders (
                        id,
                        reminder_text,
                        reminder_datetime,
                        reminder_occurence,
                        next_run_at
                    )
                    VALUES (%s::uuid, %s, %s, NULL, %s)
                    RETURNING
                        id::text,
                        reminder_text,
                        reminder_datetime,
                        reminder_occurence::text,
                        next_run_at,
                        active,
                        sent_count
                    """,
                    (reminder_id, text, parsed_datetime, parsed_datetime),
                )
            else:
                cur.execute(
                    """
                    INSERT INTO public.secretary_reminders (
                        id,
                        reminder_text,
                        reminder_datetime,
                        reminder_occurence,
                        next_run_at
                    )
                    VALUES (%s::uuid, %s, %s, %s::interval, %s)
                    RETURNING
                        id::text,
                        reminder_text,
                        reminder_datetime,
                        reminder_occurence::text,
                        next_run_at,
                        active,
                        sent_count
                    """,
                    (reminder_id, text, parsed_datetime, occurence, parsed_datetime),
                )

            row = cur.fetchone()
            if row is None:
                raise RuntimeError("Failed to create reminder.")
            return ReminderRecord(*row)

    def process_due_reminders(self, *, batch_size: int = 100) -> int:
        self.ensure_schema()
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT public.secretary_process_due_reminders(%s)", (batch_size,))
            row = cur.fetchone()
            return int(row[0]) if row is not None else 0

    def get_reminder(self, reminder_id: str) -> dict[str, Any]:
        self.ensure_schema()
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    id::text,
                    reminder_text,
                    reminder_datetime,
                    reminder_occurence::text,
                    next_run_at,
                    active,
                    sent_count,
                    last_processed_run_at,
                    last_enqueued_at
                FROM public.secretary_reminders
                WHERE id = %s::uuid
                """,
                (reminder_id,),
            )
            row = cur.fetchone()
            if row is None:
                raise KeyError(reminder_id)
            return {
                "id": row[0],
                "reminder_text": row[1],
                "reminder_datetime": row[2].isoformat(),
                "reminder_occurence": row[3],
                "next_run_at": row[4].isoformat(),
                "active": row[5],
                "sent_count": row[6],
                "last_processed_run_at": row[7].isoformat() if row[7] else None,
                "last_enqueued_at": row[8].isoformat() if row[8] else None,
            }

    def get_send_rows(self, reminder_id: str) -> list[dict[str, Any]]:
        self.ensure_schema()
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, reminder_id::text, reminder_text, reminder_datetime, queued_at
                FROM public.secretary_reminder_sends
                WHERE reminder_id = %s::uuid
                ORDER BY id
                """,
                (reminder_id,),
            )
            rows = cur.fetchall()
            return [
                {
                    "id": row[0],
                    "reminder_id": row[1],
                    "reminder_text": row[2],
                    "reminder_datetime": row[3].isoformat(),
                    "queued_at": row[4].isoformat(),
                }
                for row in rows
            ]

    def delete_reminder(self, reminder_id: str) -> None:
        self.ensure_schema()
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute("DELETE FROM public.secretary_reminders WHERE id = %s::uuid", (reminder_id,))

    def _ensure_cron_schedule(self) -> None:
        if psycopg is None or conninfo_to_dict is None:  # pragma: no cover
            raise RuntimeError("psycopg is not installed. Run `uv sync` to install dependencies.")

        try:
            with self._connect() as conn, conn.cursor() as cur:
                cur.execute("SELECT current_setting('cron.database_name', true)")
                row = cur.fetchone()
                cron_database_name = row[0] if row is not None else None
                target_dbname = self._target_dbname()

                if cron_database_name != target_dbname:
                    raise RuntimeError(
                        "pg_cron is not enabled for this Neon database. "
                        f"Current cron.database_name={cron_database_name!r}, expected {target_dbname!r}. "
                        "Neon currently requires pg_cron to be enabled for the target database by support. "
                        "Open a Neon support ticket with your endpoint ID and database name, ask them to enable "
                        f"pg_cron for {target_dbname!r}, then restart the compute."
                    )

                cur.execute("CREATE EXTENSION IF NOT EXISTS pg_cron")
                cur.execute(
                    "SELECT cron.unschedule(%s) WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = %s)",
                    (CRON_JOB_NAME, CRON_JOB_NAME),
                )
                cur.execute(
                    "SELECT cron.schedule(%s, %s, %s)",
                    (
                        CRON_JOB_NAME,
                        "30 seconds",
                        "SELECT public.secretary_process_due_reminders();",
                    ),
                )
        except psycopg.Error as exc:
            raise RuntimeError(
                "Failed to configure pg_cron in the target Neon database. "
                "Verify that Neon support enabled pg_cron for this database, restart the compute, "
                "and ensure the current role can create extensions and manage cron jobs."
            ) from exc

    def _target_dbname(self) -> str:
        assert conninfo_to_dict is not None
        parts = conninfo_to_dict(self._dsn)
        dbname = parts.get("dbname")
        if not dbname:
            raise RuntimeError("NEON_STRING is missing the database name.")
        return dbname

    def _connect(self, dsn: str | None = None):
        if psycopg is None:  # pragma: no cover - exercised only before dependency install
            raise RuntimeError("psycopg is not installed. Run `uv sync` to install dependencies.")
        return psycopg.connect(dsn or self._dsn, autocommit=True)


def parse_reminder_datetime(value: str) -> datetime:
    raw = value.strip()
    if not raw:
        raise ValueError("reminder_datetime must not be empty.")

    normalized = raw.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError("reminder_datetime must be an ISO 8601 datetime string.") from exc

    if parsed.tzinfo is None:
        raise ValueError("reminder_datetime must include a timezone offset.")

    return parsed


def _normalize_occurence(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None
