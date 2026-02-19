from __future__ import annotations

import json
import re
import shlex
import shutil
import subprocess
import uuid
from dataclasses import dataclass
from typing import Any


@dataclass
class PlaywrightCliPreflight:
    available: bool
    reason: str | None = None


class PlaywrightCliScraper:
    def __init__(self, command: str = "playwright-cli") -> None:
        self._command = command
        self._base_argv = shlex.split(command)

    def preflight(self) -> PlaywrightCliPreflight:
        if not self._base_argv:
            return PlaywrightCliPreflight(False, "playwright command is empty")

        executable = self._base_argv[0]
        if shutil.which(executable) is None:
            return PlaywrightCliPreflight(False, f"'{executable}' is not on PATH")

        try:
            result = self._run(["--help"], timeout=15)
        except Exception as exc:  # noqa: BLE001
            return PlaywrightCliPreflight(False, f"failed help probe: {exc}")

        text = (result.stdout or "") + "\n" + (result.stderr or "")
        lowered = text.lower()
        if "run-code" not in lowered:
            return PlaywrightCliPreflight(False, "run-code command not detected")
        if "-s" not in lowered and "session" not in lowered:
            return PlaywrightCliPreflight(False, "named session support not detected")

        return PlaywrightCliPreflight(True)

    def fetch_next_data(self, url: str) -> dict[str, Any]:
        session = f"kijiji-{uuid.uuid4().hex[:12]}"
        script = (
            "async page => {"
            " const el = document.querySelector('#__NEXT_DATA__');"
            " return el ? el.textContent : null;"
            "}"
        )
        try:
            self._run([f"-s={session}", "open", url], timeout=45)
            run_code = self._run([f"-s={session}", "run-code", script], timeout=45)
            payload = self._extract_json_text(run_code.stdout or "")
            return json.loads(payload)
        finally:
            try:
                self._run([f"-s={session}", "close"], timeout=20)
            except Exception:
                # Best-effort cleanup for stale browser sessions.
                try:
                    self._run(["close-all"], timeout=20)
                except Exception:
                    pass

    def _extract_json_text(self, output: str) -> str:
        text = output.strip()
        if not text:
            raise RuntimeError("playwright-cli run-code produced empty output")

        for candidate in self._candidate_payloads(text):
            parsed = self._try_parse_json(candidate)
            if parsed is not None:
                return parsed

        raise RuntimeError("Could not extract __NEXT_DATA__ JSON from playwright-cli output")

    def _candidate_payloads(self, text: str) -> list[str]:
        candidates: list[str] = [text]
        candidates.extend(line.strip() for line in text.splitlines() if line.strip())

        fenced = re.findall(r"```(?:json)?\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)
        candidates.extend(block.strip() for block in fenced if block.strip())

        candidates.extend(self._find_braced_candidates(text))

        unique: list[str] = []
        seen: set[str] = set()
        for candidate in sorted(candidates, key=len, reverse=True):
            if candidate in seen:
                continue
            seen.add(candidate)
            unique.append(candidate)
        return unique

    def _try_parse_json(self, candidate: str) -> str | None:
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            return None

        if isinstance(parsed, dict):
            return candidate if candidate.startswith("{") else json.dumps(parsed)

        if isinstance(parsed, str):
            try:
                nested = json.loads(parsed)
            except json.JSONDecodeError:
                return None
            if isinstance(nested, dict):
                return parsed

        return None

    def _find_braced_candidates(self, text: str) -> list[str]:
        results: list[str] = []
        depth = 0
        start: int | None = None
        in_string = False
        escaped = False

        for index, ch in enumerate(text):
            if escaped:
                escaped = False
                continue
            if ch == "\\" and in_string:
                escaped = True
                continue
            if ch == '"':
                in_string = not in_string
                continue
            if in_string:
                continue
            if ch == "{":
                if depth == 0:
                    start = index
                depth += 1
                continue
            if ch == "}":
                if depth == 0:
                    continue
                depth -= 1
                if depth == 0 and start is not None:
                    results.append(text[start : index + 1])
                    start = None

        return results

    def _run(self, args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
        cmd = [*self._base_argv, *args]
        return subprocess.run(
            cmd,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
