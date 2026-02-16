from __future__ import annotations

import hashlib
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import tomllib
from pydantic import BaseModel, Field, ValidationError

DEFAULT_HTTP_HOST = "127.0.0.1"
DEFAULT_HTTP_PORT = 8067
DEFAULT_SHELL_PATH = "/bin/bash"


class ForbiddenCommandRule(BaseModel):
    binary: str
    arg_globs: list[str] = Field(default_factory=list)
    arg_regex: str | None = None
    description: str | None = None


class RuntimeDefaults(BaseModel):
    timeout_s: int = 120
    max_output_chars: int = 200_000
    max_command_chars: int = 50_000
    max_sessions: int = 32


class ServerConfig(BaseModel):
    http_host: str = DEFAULT_HTTP_HOST
    http_port: int = DEFAULT_HTTP_PORT


class UIConfig(BaseModel):
    enabled: bool = True


class E2BConfig(BaseModel):
    template: str | None = None
    timeout_s: int = 600
    secure: bool = True
    allow_internet_access: bool = True
    start_cwd: str = "/home/user"


class PolicyConfig(BaseModel):
    shell_path: str = DEFAULT_SHELL_PATH
    allowed_commands: list[str] = Field(default_factory=list)
    forbidden_command_rules: list[ForbiddenCommandRule] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    defaults: RuntimeDefaults = Field(default_factory=RuntimeDefaults)
    server: ServerConfig = Field(default_factory=ServerConfig)
    ui: UIConfig = Field(default_factory=UIConfig)
    e2b: E2BConfig = Field(default_factory=E2BConfig)


@dataclass
class ConfigStatus:
    source: str
    warning: str | None


def sample_policy_dict() -> dict[str, Any]:
    return {
        "shell_path": DEFAULT_SHELL_PATH,
        "allowed_commands": [
            "ls",
            "cat",
            "grep",
            "rg",
            "find",
            "pwd",
            "cd",
            "echo",
            "head",
            "tail",
            "wc",
            "sed",
            "awk",
            "git",
            "python",
            "python3",
            "pip",
            "pip3",
            "node",
            "npm",
            "pnpm",
            "yarn",
            "uv",
            "az",
            "ffmpeg",
        ],
        "forbidden_command_rules": [
            {
                "binary": "rm",
                "arg_globs": ["-rf", "-fr"],
                "description": "Block recursive+force deletion",
            },
            {
                "binary": "rm",
                "arg_globs": ["--recursive", "--force"],
                "description": "Block recursive+force deletion",
            },
        ],
        "forbidden_paths": [
            "*.env",
            "**/.env",
            "**/.env.*",
            "**/.ssh/**",
            "**/id_rsa",
            "**/id_ed25519",
        ],
        "defaults": {
            "timeout_s": 120,
            "max_output_chars": 200000,
            "max_command_chars": 50000,
            "max_sessions": 32,
        },
        "server": {
            "http_host": DEFAULT_HTTP_HOST,
            "http_port": DEFAULT_HTTP_PORT,
        },
        "ui": {"enabled": True},
        "e2b": {
            "template": None,
            "timeout_s": 600,
            "secure": True,
            "allow_internet_access": True,
            "start_cwd": "/home/user",
        },
    }


def sample_policy_toml() -> str:
    return """# Mishell MCP policy
# Edit then use UI Reload to apply changes.

shell_path = "/bin/bash"
allowed_commands = [
  "ls", "cat", "grep", "rg", "find", "pwd", "cd", "echo", "head", "tail", "wc", "sed", "awk",
  "git", "python", "python3", "pip", "pip3", "node", "npm", "pnpm", "yarn", "uv", "az", "ffmpeg"
]
forbidden_paths = ["*.env", "**/.env", "**/.env.*", "**/.ssh/**", "**/id_rsa", "**/id_ed25519"]

[[forbidden_command_rules]]
binary = "rm"
arg_globs = ["-rf", "-fr"]
description = "Block recursive+force deletion"

[[forbidden_command_rules]]
binary = "rm"
arg_globs = ["--recursive", "--force"]
description = "Block recursive+force deletion"

[defaults]
timeout_s = 120
max_output_chars = 200000
max_command_chars = 50000
max_sessions = 32

[server]
http_host = "127.0.0.1"
http_port = 8067

[ui]
enabled = true

[e2b]
# Template name or ID. Keep empty to use E2B default template.
template = ""
timeout_s = 600
secure = true
allow_internet_access = true
start_cwd = "/home/user"
"""


def _normalize_binary_list(cmds: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for cmd in cmds:
        name = cmd.strip()
        if not name:
            continue
        if name not in seen:
            out.append(name)
            seen.add(name)
    return out


class ConfigManager:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self._lock = threading.RLock()
        self._config = PolicyConfig.model_validate(sample_policy_dict())
        self._status = ConfigStatus(source="sample-defaults", warning=None)

    def load_startup(self) -> None:
        with self._lock:
            if not self.path.exists():
                self._config = PolicyConfig.model_validate(sample_policy_dict())
                self._status = ConfigStatus(
                    source="sample-defaults",
                    warning=f"Config not found at {self.path}; using sample defaults.",
                )
                return

            try:
                cfg = self._parse_toml(self.path.read_text(encoding="utf-8"))
            except Exception as exc:  # noqa: BLE001
                self._config = PolicyConfig.model_validate(sample_policy_dict())
                self._status = ConfigStatus(
                    source="sample-defaults",
                    warning=f"Invalid config at {self.path}; using sample defaults: {exc}",
                )
                return

            self._config = cfg
            self._status = ConfigStatus(source="file", warning=None)

    def reload_from_disk(self) -> PolicyConfig:
        with self._lock:
            text = self.path.read_text(encoding="utf-8")
            cfg = self._parse_toml(text)
            self._config = cfg
            self._status = ConfigStatus(source="file", warning=None)
            return cfg

    def save_text(self, text: str) -> None:
        with self._lock:
            # Validate before writing.
            self._parse_toml(text)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(text, encoding="utf-8")

    def get_config(self) -> PolicyConfig:
        with self._lock:
            return self._config.model_copy(deep=True)

    def get_status(self) -> ConfigStatus:
        with self._lock:
            return ConfigStatus(source=self._status.source, warning=self._status.warning)

    def get_raw_toml(self) -> str:
        with self._lock:
            if self.path.exists():
                return self.path.read_text(encoding="utf-8")
            return sample_policy_toml()

    def policy_hash(self) -> str:
        cfg = self.get_config()
        blob = cfg.model_dump_json(exclude_none=True, by_alias=True)
        return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:12]

    @staticmethod
    def _parse_toml(text: str) -> PolicyConfig:
        try:
            data = tomllib.loads(text)
        except tomllib.TOMLDecodeError as exc:
            raise ValueError(f"TOML parse error: {exc}") from exc

        try:
            cfg = PolicyConfig.model_validate(data)
        except ValidationError as exc:
            raise ValueError(f"Config validation error: {exc}") from exc

        cfg.allowed_commands = _normalize_binary_list(cfg.allowed_commands)
        if cfg.e2b.template is not None and not cfg.e2b.template.strip():
            cfg.e2b.template = None
        if cfg.server.http_host != "127.0.0.1":
            raise ValueError("Only localhost host binding is allowed; set server.http_host=127.0.0.1")

        # Avoid surprising failures when shell path does not exist.
        if not os.path.exists(cfg.shell_path):
            raise ValueError(f"shell_path does not exist: {cfg.shell_path}")

        return cfg
