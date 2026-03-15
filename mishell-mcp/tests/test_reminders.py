from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta

import pytest

from mishell_mcp.reminders import ReminderStore, parse_reminder_datetime
from mishell_mcp.server import MishellApp


class StubReminderStore:
    def __init__(self) -> None:
        self.calls: list[dict[str, str | None]] = []

    def create_reminder(
        self,
        *,
        reminder_text: str,
        reminder_datetime: str,
        reminder_occurence: str | None = None,
    ):
        self.calls.append(
            {
                "reminder_text": reminder_text,
                "reminder_datetime": reminder_datetime,
                "reminder_occurence": reminder_occurence,
            }
        )

        class Record:
            id = "reminder-123"
            next_run_at = datetime(2026, 3, 8, 12, 0, tzinfo=UTC)
            active = True

            @staticmethod
            def to_payload() -> dict[str, object]:
                return {
                    "id": "reminder-123",
                    "reminder_text": reminder_text,
                    "reminder_datetime": reminder_datetime,
                    "reminder_occurence": reminder_occurence,
                    "next_run_at": "2026-03-08T12:00:00+00:00",
                    "active": True,
                    "sent_count": 0,
                }

        return Record()


def test_parse_reminder_datetime_requires_timezone() -> None:
    with pytest.raises(ValueError, match="timezone offset"):
        parse_reminder_datetime("2026-03-08T12:00:00")


def test_parse_reminder_datetime_accepts_z_suffix() -> None:
    parsed = parse_reminder_datetime("2026-03-08T12:00:00Z")
    assert parsed.tzinfo is not None
    assert parsed.isoformat() == "2026-03-08T12:00:00+00:00"


@pytest.mark.asyncio
async def test_secretary_reminder_create_tool_uses_store(tmp_path) -> None:
    config_path = tmp_path / "mishell.toml"
    config_path.write_text('shell_path = "/bin/bash"\n', encoding="utf-8")

    app = MishellApp(config_path=config_path, dangerous=True, require_http_auth_key=False)
    store = StubReminderStore()
    app.state.reminders = store

    result = await app.mcp._tool_manager.call_tool(
        "secretary_reminder_create",
        {
            "reminder_text": "Pay rent",
            "reminder_datetime": "2026-03-08T12:00:00Z",
            "reminder_occurence": "1 day",
        },
    )

    assert store.calls == [
        {
            "reminder_text": "Pay rent",
            "reminder_datetime": "2026-03-08T12:00:00Z",
            "reminder_occurence": "1 day",
        }
    ]
    assert result.structured_content["data"]["reminder"]["id"] == "reminder-123"
    assert result.structured_content["data"]["cron_job_name"] == "mishell-secretary-reminders-every-30-seconds"


@pytest.mark.skipif(
    not os.getenv("RUN_NEON_REMINDER_TESTS"),
    reason="Set RUN_NEON_REMINDER_TESTS=1 to run Neon-backed reminder integration tests.",
)
def test_reminder_store_round_trip_against_neon() -> None:
    store = ReminderStore(os.environ["NEON_STRING"])
    now = datetime.now(UTC)
    reminder_datetime = (now - timedelta(minutes=2)).replace(microsecond=0)

    record = store.create_reminder(
        reminder_text="integration reminder",
        reminder_datetime=reminder_datetime.isoformat(),
    )

    try:
        processed = store.process_due_reminders(batch_size=50)
        assert processed >= 1

        reminder = store.get_reminder(record.id)
        sends = store.get_send_rows(record.id)

        assert reminder["active"] is False
        assert reminder["sent_count"] >= 1
        assert len(sends) == 1
        assert sends[0]["reminder_text"] == "integration reminder"
        assert sends[0]["reminder_datetime"] == reminder_datetime.isoformat()
    finally:
        store.delete_reminder(record.id)
