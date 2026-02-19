from __future__ import annotations

from mishell_mcp.config import PolicyConfig, sample_policy_dict
from mishell_mcp.policy import PolicyEngine


def build_config() -> PolicyConfig:
    return PolicyConfig.model_validate(sample_policy_dict())


def test_policy_allows_simple_command() -> None:
    cfg = build_config()
    engine = PolicyEngine(cfg)
    result = engine.evaluate("ls -la", cwd="/tmp")
    assert result.ok
    assert result.code == "OK"


def test_policy_blocks_unknown_command() -> None:
    cfg = build_config()
    engine = PolicyEngine(cfg)
    result = engine.evaluate("curl https://example.com", cwd="/tmp")
    assert not result.ok
    assert result.code == "CMD_NOT_ALLOWED"


def test_policy_blocks_forbidden_path_env() -> None:
    cfg = build_config()
    engine = PolicyEngine(cfg)
    result = engine.evaluate("cat .env", cwd="/tmp/project")
    assert not result.ok
    assert result.code == "PATH_BLOCKED"


def test_policy_blocks_rm_rf_rule() -> None:
    cfg = build_config()
    cfg.allowed_commands.append("rm")
    engine = PolicyEngine(cfg)
    result = engine.evaluate("rm -rf ./build", cwd="/tmp/project")
    assert not result.ok
    assert result.code == "CMD_RULE_BLOCKED"


def test_policy_handles_complex_shell_line() -> None:
    cfg = build_config()
    engine = PolicyEngine(cfg)
    result = engine.evaluate("git status && ls | grep py > out.txt", cwd="/tmp")
    assert result.ok
    assert result.code == "OK"


def test_policy_blocks_cd_into_forbidden_path() -> None:
    cfg = build_config()
    engine = PolicyEngine(cfg)
    result = engine.evaluate("cd ~/.ssh && ls", cwd="/tmp/project")
    assert not result.ok
    assert result.code == "PATH_BLOCKED"


def test_policy_allows_globbed_allowed_command() -> None:
    cfg = build_config()
    cfg.allowed_commands.append("playwright*")
    engine = PolicyEngine(cfg)
    result = engine.evaluate("playwright-cli --version", cwd="/tmp")
    assert result.ok
    assert result.code == "OK"


def test_policy_glob_does_not_allow_non_matching_command() -> None:
    cfg = build_config()
    cfg.allowed_commands = ["playwright*"]
    engine = PolicyEngine(cfg)
    result = engine.evaluate("python3 -V", cwd="/tmp")
    assert not result.ok
    assert result.code == "CMD_NOT_ALLOWED"
