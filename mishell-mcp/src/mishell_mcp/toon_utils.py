from __future__ import annotations

from typing import Any

try:
    from toon_format import encode as toon_encode
except Exception as exc:  # noqa: BLE001
    raise RuntimeError(
        "TOON dependency is required but unavailable. Install with: "
        "pip install 'toon_format @ git+https://github.com/toon-format/toon-python.git'"
    ) from exc


def encode_toon(payload: dict[str, Any]) -> str:
    return toon_encode(payload)
