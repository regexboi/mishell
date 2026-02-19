from __future__ import annotations

import os
from dataclasses import dataclass



def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    mcp_host: str = "127.0.0.1"
    mcp_port: int = 8080
    mcp_path: str = "/mcp"

    http_timeout_seconds: float = 20.0
    retry_max: int = 3
    min_delay_ms: int = 400
    max_delay_ms: int = 900

    playwright_cli_command: str = "playwright-cli"
    playwright_cli_enabled: bool = True
    circuit_breaker_failure_threshold: int = 3
    circuit_breaker_cooldown_seconds: float = 30.0

    nominatim_base_url: str = "https://nominatim.openstreetmap.org"
    nominatim_user_agent: str = "kijiji-bot-mcp/0.1.0 (+https://kijiji.ca)"

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            mcp_host=os.getenv("KIJIJI_MCP_HOST", cls.mcp_host),
            mcp_port=int(os.getenv("KIJIJI_MCP_PORT", str(cls.mcp_port))),
            mcp_path=os.getenv("KIJIJI_MCP_PATH", cls.mcp_path),
            http_timeout_seconds=float(
                os.getenv("KIJIJI_HTTP_TIMEOUT_SECONDS", str(cls.http_timeout_seconds))
            ),
            retry_max=int(os.getenv("KIJIJI_RETRY_MAX", str(cls.retry_max))),
            min_delay_ms=int(os.getenv("KIJIJI_MIN_DELAY_MS", str(cls.min_delay_ms))),
            max_delay_ms=int(os.getenv("KIJIJI_MAX_DELAY_MS", str(cls.max_delay_ms))),
            playwright_cli_command=os.getenv(
                "PLAYWRIGHT_CLI_COMMAND", cls.playwright_cli_command
            ),
            playwright_cli_enabled=_bool_env(
                "PLAYWRIGHT_CLI_ENABLED", cls.playwright_cli_enabled
            ),
            circuit_breaker_failure_threshold=int(
                os.getenv(
                    "KIJIJI_CB_FAILURE_THRESHOLD",
                    str(cls.circuit_breaker_failure_threshold),
                )
            ),
            circuit_breaker_cooldown_seconds=float(
                os.getenv(
                    "KIJIJI_CB_COOLDOWN_SECONDS",
                    str(cls.circuit_breaker_cooldown_seconds),
                )
            ),
            nominatim_base_url=os.getenv(
                "NOMINATIM_BASE_URL", cls.nominatim_base_url
            ).rstrip("/"),
            nominatim_user_agent=os.getenv(
                "NOMINATIM_USER_AGENT", cls.nominatim_user_agent
            ),
        )
