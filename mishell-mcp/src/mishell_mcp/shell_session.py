from __future__ import annotations

import os
import re
import select
import signal
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any

from .config import PolicyConfig


@dataclass
class ExecOutcome:
    ok: bool
    ec: int
    out: str
    err: str
    timed_out: bool
    truncated: bool
    duration_ms: int
    cwd: str


class ShellSession:
    def __init__(self, shell_path: str):
        self.shell_path = shell_path
        self._lock = threading.RLock()
        self._proc = self._spawn()

    def _spawn(self) -> subprocess.Popen[str]:
        proc = subprocess.Popen(
            [self.shell_path, "--noprofile", "--norc", "-s"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=0,
            preexec_fn=os.setsid,
        )
        if proc.stdin is None or proc.stdout is None or proc.stderr is None:
            raise RuntimeError("Failed to create shell stdio pipes")

        # Reserve stable fds for server-side signaling.
        proc.stdin.write("exec 3>&1 4>&2\n")
        proc.stdin.flush()
        return proc

    def _ensure_live(self) -> None:
        if self._proc.poll() is not None:
            self._proc = self._spawn()

    def reset(self) -> None:
        with self._lock:
            self._terminate_process(self._proc)
            self._proc = self._spawn()

    def close(self) -> None:
        with self._lock:
            self._terminate_process(self._proc)

    def run(self, command: str, timeout_s: int, max_output_chars: int) -> ExecOutcome:
        started = time.monotonic()
        with self._lock:
            self._ensure_live()
            result = self._run_locked(command, timeout_s, max_output_chars, resolve_cwd=True)

        duration_ms = int((time.monotonic() - started) * 1000)
        result.duration_ms = duration_ms
        return result

    def get_cwd(self, timeout_s: int = 5) -> str:
        with self._lock:
            self._ensure_live()
            outcome = self._run_locked("pwd", timeout_s=timeout_s, max_output_chars=4096, resolve_cwd=False)
        if not outcome.ok:
            return os.getcwd()
        lines = [line.strip() for line in outcome.out.splitlines() if line.strip()]
        if not lines:
            return os.getcwd()
        return lines[-1]

    def _run_locked(
        self,
        command: str,
        timeout_s: int,
        max_output_chars: int,
        *,
        resolve_cwd: bool,
    ) -> ExecOutcome:
        proc = self._proc
        assert proc.stdin is not None
        assert proc.stdout is not None
        assert proc.stderr is not None

        token = uuid.uuid4().hex
        marker_re = re.compile(rf"__MISHELL_S_{token}:(-?\d+)\r?\n?")
        script = (
            "exec 1>&3 2>&4\n"
            f"{command}\n"
            "__mishell_ec=$?\n"
            f"printf '__MISHELL_S_{token}:%s\\n' \"$__mishell_ec\" >&3\n"
        )

        try:
            proc.stdin.write(script)
            proc.stdin.flush()
        except BrokenPipeError:
            self._proc = self._spawn()
            proc = self._proc
            assert proc.stdin is not None
            proc.stdin.write(script)
            proc.stdin.flush()

        stdout_fd = proc.stdout.fileno()
        stderr_fd = proc.stderr.fileno()
        os.set_blocking(stdout_fd, False)
        os.set_blocking(stderr_fd, False)

        out_buf = ""
        err_buf = ""
        ec: int | None = None

        deadline = time.monotonic() + timeout_s
        saw_out_marker = False
        truncated = False

        while True:
            now = time.monotonic()
            if now >= deadline:
                self._interrupt_running_shell()
                self.reset()
                return ExecOutcome(
                    ok=False,
                    ec=124,
                    out=out_buf,
                    err=err_buf + "\nTimed out",
                    timed_out=True,
                    truncated=truncated,
                    duration_ms=0,
                    cwd=os.getcwd(),
                )

            read_ready, _, _ = select.select([stdout_fd, stderr_fd], [], [], 0.10)
            for fd in read_ready:
                try:
                    chunk = os.read(fd, 4096).decode("utf-8", errors="replace")
                except BlockingIOError:
                    continue

                if not chunk:
                    continue

                if fd == stdout_fd:
                    out_buf += chunk
                    match = marker_re.search(out_buf)
                    if match:
                        saw_out_marker = True
                        ec = int(match.group(1))
                        out_buf = marker_re.sub("", out_buf)
                else:
                    err_buf += chunk

                if len(out_buf) + len(err_buf) > max_output_chars:
                    out_buf, err_buf = _truncate_pair(out_buf, err_buf, max_output_chars)
                    truncated = True

            if saw_out_marker:
                break

        if resolve_cwd and command.strip() != "pwd":
            cwd = self.get_cwd(timeout_s=3)
        elif command.strip() == "pwd":
            cwd = out_buf.strip() or os.getcwd()
        else:
            cwd = os.getcwd()
        if ec is None:
            ec = 1
        return ExecOutcome(
            ok=(ec == 0),
            ec=ec,
            out=out_buf,
            err=err_buf,
            timed_out=False,
            truncated=truncated,
            duration_ms=0,
            cwd=cwd,
        )

    def _interrupt_running_shell(self) -> None:
        proc = self._proc
        if proc.poll() is not None:
            return
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGINT)
            time.sleep(0.1)
        except ProcessLookupError:
            return
        except PermissionError:
            return

    @staticmethod
    def _terminate_process(proc: subprocess.Popen[str]) -> None:
        if proc.poll() is not None:
            return

        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        except ProcessLookupError:
            return
        except PermissionError:
            proc.terminate()

        try:
            proc.wait(timeout=2)
            return
        except subprocess.TimeoutExpired:
            pass

        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except ProcessLookupError:
            return
        except PermissionError:
            proc.kill()


def _truncate_pair(out: str, err: str, max_chars: int) -> tuple[str, str]:
    if max_chars <= 0:
        return "", ""

    joined = out + err
    if len(joined) <= max_chars:
        return out, err

    keep = max_chars
    if len(out) >= keep:
        return out[:keep], ""

    err_keep = keep - len(out)
    return out, err[:err_keep]


class SessionManager:
    def __init__(self, config: PolicyConfig):
        self._config = config
        self._sessions: dict[str, ShellSession] = {}
        self._lock = threading.RLock()

    def reconfigure(self, config: PolicyConfig) -> None:
        with self._lock:
            self._config = config
            if not self._sessions:
                return

            for sid, session in list(self._sessions.items()):
                if session.shell_path != config.shell_path:
                    session.close()
                    self._sessions[sid] = ShellSession(shell_path=config.shell_path)

    def get(self, session_id: str) -> ShellSession:
        sid = session_id or "default"
        with self._lock:
            if sid in self._sessions:
                return self._sessions[sid]

            if len(self._sessions) >= self._config.defaults.max_sessions:
                raise RuntimeError("max_sessions limit reached")

            session = ShellSession(shell_path=self._config.shell_path)
            self._sessions[sid] = session
            return session

    def reset(self, session_id: str) -> None:
        sid = session_id or "default"
        with self._lock:
            if sid not in self._sessions:
                self._sessions[sid] = ShellSession(shell_path=self._config.shell_path)
                return
            self._sessions[sid].reset()

    def close_all(self) -> None:
        with self._lock:
            for session in self._sessions.values():
                session.close()
            self._sessions.clear()
