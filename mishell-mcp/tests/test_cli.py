from __future__ import annotations

from pathlib import Path

from mishell_mcp.cli import _resolve_e2b_api_key, build_parser
from mishell_mcp.config import sample_policy_toml


def test_parser_defaults_to_safe_mode() -> None:
    parser = build_parser()
    args = parser.parse_args(["serve-http"])
    assert args.dangerous is False


def test_parser_accepts_dangerous_flag() -> None:
    parser = build_parser()
    args = parser.parse_args(["serve-stdio", "--dangerous"])
    assert args.dangerous is True


def test_resolve_e2b_api_key_from_dotenv(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv("E2B_API_KEY", raising=False)
    monkeypatch.delenv("E2B_KEY", raising=False)

    config_path = tmp_path / "mishell.toml"
    config_path.write_text(sample_policy_toml(), encoding="utf-8")
    (tmp_path / ".env").write_text("E2B_KEY=test-key\n", encoding="utf-8")

    monkeypatch.chdir(tmp_path)
    assert _resolve_e2b_api_key(config_path) == "test-key"
