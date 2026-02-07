from __future__ import annotations

from pathlib import Path

from mishell_mcp.config import ConfigManager, sample_policy_toml


def test_missing_config_uses_sample_defaults(tmp_path: Path) -> None:
    cfg_path = tmp_path / "mishell.toml"
    manager = ConfigManager(cfg_path)
    manager.load_startup()

    cfg = manager.get_config()
    status = manager.get_status()

    assert status.source == "sample-defaults"
    assert cfg.server.http_port == 8067
    assert "ls" in cfg.allowed_commands


def test_reload_from_disk_validates_and_applies(tmp_path: Path) -> None:
    cfg_path = tmp_path / "mishell.toml"
    cfg_path.write_text(sample_policy_toml(), encoding="utf-8")

    manager = ConfigManager(cfg_path)
    manager.load_startup()

    text = cfg_path.read_text(encoding="utf-8")
    text = text.replace('"ls", ', '"ls", "whoami", ', 1)
    cfg_path.write_text(text, encoding="utf-8")

    cfg = manager.reload_from_disk()
    assert "whoami" in cfg.allowed_commands
    assert manager.get_status().source == "file"
