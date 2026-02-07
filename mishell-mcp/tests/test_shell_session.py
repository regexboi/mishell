from __future__ import annotations

import os

from mishell_mcp.config import PolicyConfig, sample_policy_dict
from mishell_mcp.shell_session import SessionManager


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
