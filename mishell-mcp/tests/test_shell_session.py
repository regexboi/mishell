from __future__ import annotations

import os
import pytest

from mishell_mcp.config import PolicyConfig, sample_policy_dict
from mishell_mcp.shell_session import SessionManager, _strip_cwd_marker


def test_session_persists_cwd() -> None:
    cfg = PolicyConfig.model_validate(sample_policy_dict())
    manager = SessionManager(cfg)
    try:
        session = manager.get("s1")

        base = session.run("pwd", timeout_s=5, max_output_chars=4096)
        assert base.ec == 0

        target = "/tmp" if os.path.isdir("/tmp") else os.getcwd()
        moved = session.run(f"cd {target}", timeout_s=5, max_output_chars=4096)
        assert moved.ec == 0

        after = session.run("pwd", timeout_s=5, max_output_chars=4096)
        assert after.ec == 0
        assert target in after.out.strip()
    finally:
        manager.close_all()


def test_e2b_backend_requires_key() -> None:
    cfg = PolicyConfig.model_validate(sample_policy_dict())
    with pytest.raises(RuntimeError, match="E2B"):
        SessionManager(cfg, backend="e2b", e2b_api_key=None)


def test_strip_cwd_marker() -> None:
    token = "abc123"
    stderr = "warning\n__MISHELL_CWD_abc123:/tmp/demo\n"
    cwd, cleaned = _strip_cwd_marker(stderr, token=token, fallback_cwd="/home/user")
    assert cwd == "/tmp/demo"
    assert cleaned == "warning\n"
