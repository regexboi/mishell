from __future__ import annotations

import fnmatch
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import bashlex

from .config import ForbiddenCommandRule, PolicyConfig

_ASSIGNMENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")

_PATH_ARG_COMMANDS = {
    "cd",
    "ls",
    "cat",
    "grep",
    "rg",
    "find",
    "sed",
    "awk",
    "head",
    "tail",
    "wc",
    "cp",
    "mv",
    "rm",
    "touch",
    "chmod",
    "chown",
    "mkdir",
    "rmdir",
    "ln",
    "stat",
    "realpath",
    "readlink",
    "python",
    "python3",
    "node",
    "ffmpeg",
    "git",
}


@dataclass
class CommandInvocation:
    binary: str
    args: list[str]
    raw_words: list[str]


@dataclass
class PolicyDecision:
    ok: bool
    code: str
    why: str
    commands: list[CommandInvocation] = field(default_factory=list)
    paths_checked: list[str] = field(default_factory=list)


class PolicyEngine:
    def __init__(self, config: PolicyConfig):
        self.config = config
        self.allowed_commands = {cmd for cmd in config.allowed_commands if not _has_glob_chars(cmd)}
        self.allowed_command_globs = [cmd for cmd in config.allowed_commands if _has_glob_chars(cmd)]

    def evaluate(self, command: str, cwd: str) -> PolicyDecision:
        if len(command) > self.config.defaults.max_command_chars:
            return PolicyDecision(
                ok=False,
                code="COMMAND_TOO_LONG",
                why=(
                    f"Command length {len(command)} exceeds max_command_chars "
                    f"{self.config.defaults.max_command_chars}"
                ),
            )

        try:
            invocations, path_candidates = extract_invocations_and_paths(command)
        except Exception as exc:  # noqa: BLE001
            return PolicyDecision(ok=False, code="PARSE_ERROR", why=f"Shell parse failed: {exc}")

        for invocation in invocations:
            binary = invocation.binary
            if not self._is_command_allowed(binary):
                return PolicyDecision(
                    ok=False,
                    code="CMD_NOT_ALLOWED",
                    why=f"Command '{binary}' is not in allowed_commands",
                    commands=invocations,
                    paths_checked=path_candidates,
                )

            blocked, reason = _matches_forbidden_rule(invocation, self.config.forbidden_command_rules)
            if blocked:
                return PolicyDecision(
                    ok=False,
                    code="CMD_RULE_BLOCKED",
                    why=reason,
                    commands=invocations,
                    paths_checked=path_candidates,
                )

        for candidate in path_candidates:
            if _matches_forbidden_path(candidate, cwd, self.config.forbidden_paths):
                return PolicyDecision(
                    ok=False,
                    code="PATH_BLOCKED",
                    why=f"Path '{candidate}' matches forbidden_paths",
                    commands=invocations,
                    paths_checked=path_candidates,
                )

        return PolicyDecision(
            ok=True,
            code="OK",
            why="Allowed by policy",
            commands=invocations,
            paths_checked=path_candidates,
        )

    def _is_command_allowed(self, binary: str) -> bool:
        if binary in self.allowed_commands:
            return True
        return any(fnmatch.fnmatch(binary, pattern) for pattern in self.allowed_command_globs)


def extract_invocations_and_paths(command: str) -> tuple[list[CommandInvocation], list[str]]:
    trees = bashlex.parse(command)
    invocations: list[CommandInvocation] = []
    paths: list[str] = []

    for node in trees:
        for current in _walk(node):
            kind = getattr(current, "kind", "")

            if kind == "command":
                words = [p.word for p in getattr(current, "parts", []) if getattr(p, "kind", "") == "word"]
                redirects = [p for p in getattr(current, "parts", []) if getattr(p, "kind", "") == "redirect"]

                binary, args = _extract_binary_and_args(words)
                if binary:
                    invocations.append(CommandInvocation(binary=binary, args=args, raw_words=words))
                    paths.extend(_extract_path_like_args(binary, args))

                for redir in redirects:
                    redir_path = _extract_redirect_path(redir)
                    if redir_path:
                        paths.append(redir_path)

            if kind == "redirect":
                redir_path = _extract_redirect_path(current)
                if redir_path:
                    paths.append(redir_path)

    return invocations, _dedupe(paths)


def _walk(node: Any):
    yield node
    for attr in ("parts", "list", "command", "commands", "redirects", "left", "right"):
        value = getattr(node, attr, None)
        if value is None:
            continue
        if isinstance(value, list):
            for item in value:
                if hasattr(item, "kind"):
                    yield from _walk(item)
        elif hasattr(value, "kind"):
            yield from _walk(value)



def _extract_binary_and_args(words: list[str]) -> tuple[str | None, list[str]]:
    if not words:
        return None, []

    idx = 0
    while idx < len(words) and _ASSIGNMENT_RE.match(words[idx]):
        idx += 1

    if idx >= len(words):
        return None, []

    binary = os.path.basename(_strip_quotes(words[idx]))
    args = [_strip_quotes(w) for w in words[idx + 1 :]]
    return binary, args



def _extract_redirect_path(redir: Any) -> str | None:
    # bashlex redirect nodes usually contain .output as a word node.
    output = getattr(redir, "output", None)
    if hasattr(output, "word"):
        return _strip_quotes(output.word)
    if isinstance(output, str):
        return _strip_quotes(output)

    input_node = getattr(redir, "input", None)
    if hasattr(input_node, "word"):
        return _strip_quotes(input_node.word)
    if isinstance(input_node, str):
        return _strip_quotes(input_node)

    word = getattr(redir, "word", None)
    if isinstance(word, str):
        return _strip_quotes(word)

    return None



def _extract_path_like_args(binary: str, args: list[str]) -> list[str]:
    if binary not in _PATH_ARG_COMMANDS:
        return []

    out: list[str] = []
    non_option_seen = 0

    for arg in args:
        if not arg:
            continue

        if arg.startswith("-"):
            continue

        # grep/rg patterns are usually the first non-option positional arg.
        if binary in {"grep", "rg"} and non_option_seen == 0:
            non_option_seen += 1
            continue

        if _looks_like_path(arg):
            out.append(arg)

        non_option_seen += 1

    return out



def _looks_like_path(value: str) -> bool:
    if value in {".", ".."}:
        return True
    if value.startswith(("./", "../", "~/", "/")):
        return True
    if any(ch in value for ch in ("/", "*", "?", "[", "]")):
        return True
    if value.startswith("."):
        return True
    if "." in Path(value).name:
        return True
    return False



def _matches_forbidden_rule(invocation: CommandInvocation, rules: list[ForbiddenCommandRule]) -> tuple[bool, str]:
    cmdline = " ".join([invocation.binary, *invocation.args])

    for rule in rules:
        if invocation.binary != rule.binary:
            continue

        if rule.arg_globs:
            # Treat arg_globs as "any-of" patterns for practical deny rules
            # like -rf / -fr variants.
            glob_ok = any(any(fnmatch.fnmatch(arg, patt) for arg in invocation.args) for patt in rule.arg_globs)
            if not glob_ok:
                continue

        if rule.arg_regex:
            try:
                if not re.search(rule.arg_regex, cmdline):
                    continue
            except re.error as exc:
                return True, f"Invalid arg_regex in rule for {rule.binary}: {exc}"

        if not rule.arg_globs and not rule.arg_regex:
            return True, f"Rule blocks command '{rule.binary}'"

        label = rule.description or "Matched forbidden command rule"
        return True, f"{label} ({rule.binary})"

    return False, ""



def _normalize_for_matching(candidate: str, cwd: str) -> tuple[str, str, str]:
    raw = _strip_quotes(candidate)
    expanded = os.path.expanduser(raw)
    if os.path.isabs(expanded):
        absolute = os.path.normpath(expanded)
    else:
        absolute = os.path.normpath(os.path.join(cwd, expanded))

    try:
        rel = os.path.relpath(absolute, cwd)
    except ValueError:
        rel = absolute

    base = os.path.basename(absolute)
    return absolute.replace("\\", "/"), rel.replace("\\", "/"), base



def _matches_forbidden_path(candidate: str, cwd: str, forbidden_patterns: list[str]) -> bool:
    abs_path, rel_path, base = _normalize_for_matching(candidate, cwd)

    for patt in forbidden_patterns:
        pattern = patt.strip()
        if not pattern:
            continue

        patterns = [pattern]
        if pattern.endswith("/**"):
            # Directory glob should also match the directory node itself.
            patterns.append(pattern[:-3])

        for candidate_pattern in patterns:
            if fnmatch.fnmatch(abs_path, candidate_pattern):
                return True
            if fnmatch.fnmatch(rel_path, candidate_pattern):
                return True
            if fnmatch.fnmatch(base, candidate_pattern):
                return True

            if candidate_pattern.startswith("**/") and fnmatch.fnmatch(rel_path, candidate_pattern[3:]):
                return True

    return False



def _strip_quotes(text: str) -> str:
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {"'", '"'}:
        return text[1:-1]
    return text



def _has_glob_chars(text: str) -> bool:
    return any(ch in text for ch in ("*", "?", "[", "]"))


def _dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        out.append(value)
    return out
