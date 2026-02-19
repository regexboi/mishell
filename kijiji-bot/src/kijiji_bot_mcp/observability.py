from __future__ import annotations

import time
import uuid
from collections import defaultdict
from dataclasses import dataclass
from threading import Lock
from typing import Any


@dataclass(frozen=True)
class RequestTrace:
    request_id: str
    tool_name: str
    started_at: float


class ServiceMetrics:
    def __init__(self) -> None:
        self._lock = Lock()
        self._tool_calls: dict[str, int] = defaultdict(int)
        self._tool_errors: dict[str, int] = defaultdict(int)
        self._tool_latency_ms_total: dict[str, float] = defaultdict(float)
        self._backend_success: dict[str, int] = defaultdict(int)
        self._backend_errors: dict[str, int] = defaultdict(int)
        self._total_calls = 0
        self._total_errors = 0
        self._total_warnings = 0
        self._calls_with_warnings = 0
        self._last_request_id: str | None = None

    def start_request(self, tool_name: str) -> RequestTrace:
        trace = RequestTrace(
            request_id=f"req-{tool_name}-{uuid.uuid4().hex[:10]}",
            tool_name=tool_name,
            started_at=time.perf_counter(),
        )
        with self._lock:
            self._total_calls += 1
            self._tool_calls[tool_name] += 1
            self._last_request_id = trace.request_id
        return trace

    def record_success(
        self,
        trace: RequestTrace,
        latency_ms: float,
        backend_used: str | None,
        warning_count: int,
    ) -> None:
        with self._lock:
            self._tool_latency_ms_total[trace.tool_name] += latency_ms
            self._total_warnings += warning_count
            if warning_count > 0:
                self._calls_with_warnings += 1
            if backend_used:
                self._backend_success[backend_used] += 1

    def record_error(
        self,
        trace: RequestTrace,
        latency_ms: float,
        backend_hint: str | None = None,
    ) -> None:
        with self._lock:
            self._tool_latency_ms_total[trace.tool_name] += latency_ms
            self._tool_errors[trace.tool_name] += 1
            self._total_errors += 1
            if backend_hint:
                self._backend_errors[backend_hint] += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            total_calls = self._total_calls
            total_errors = self._total_errors
            total_warnings = self._total_warnings
            calls_with_warnings = self._calls_with_warnings
            tool_calls = dict(self._tool_calls)
            tool_errors = dict(self._tool_errors)
            tool_latency_totals = dict(self._tool_latency_ms_total)
            backend_success = dict(self._backend_success)
            backend_errors = dict(self._backend_errors)
            last_request_id = self._last_request_id

        per_tool: dict[str, Any] = {}
        for tool_name, calls in tool_calls.items():
            total_latency = tool_latency_totals.get(tool_name, 0.0)
            errors = tool_errors.get(tool_name, 0)
            per_tool[tool_name] = {
                "calls": calls,
                "errors": errors,
                "error_rate": round(errors / calls, 4) if calls else 0.0,
                "avg_latency_ms": round(total_latency / calls, 2) if calls else None,
            }

        return {
            "total_calls": total_calls,
            "total_errors": total_errors,
            "error_rate": round(total_errors / total_calls, 4) if total_calls else 0.0,
            "total_warnings": total_warnings,
            "warning_rate": round(calls_with_warnings / total_calls, 4)
            if total_calls
            else 0.0,
            "backend_success_counts": backend_success,
            "backend_error_counts": backend_errors,
            "per_tool": per_tool,
            "last_request_id": last_request_id,
        }
