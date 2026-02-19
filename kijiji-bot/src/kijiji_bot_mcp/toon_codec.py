from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any


_whitespace_re = re.compile(r"\s+")
_safe_key_re = re.compile(r"^[A-Za-z_][A-Za-z0-9_.]*$")
_number_re = re.compile(r"^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$")
_array_header_re = re.compile(
    r'^(?P<key>(?:"(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_.]*))\[(?P<count>\d+)\](?:\{(?P<fields>.*)\})?$'
)



def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    value = re.sub(r"<[^>]+>", " ", value)
    value = _whitespace_re.sub(" ", value)
    return value.strip()



def normalize_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: normalize_payload(v) for k, v in value.items()}
    if isinstance(value, list):
        return [normalize_payload(v) for v in value]
    if isinstance(value, str):
        return normalize_text(value)
    return value



def _quote_key_if_needed(key: str) -> str:
    return key if _safe_key_re.match(key) else json.dumps(key, ensure_ascii=False)



def _is_primitive(value: Any) -> bool:
    return value is None or isinstance(value, (str, int, float, bool))



def _string_needs_quotes(value: str, delimiter: str = ",") -> bool:
    if value == "":
        return True
    if value.strip() != value:
        return True
    lowered = value.lower()
    if lowered in {"true", "false", "null"}:
        return True
    if _number_re.match(value):
        return True
    for ch in [":", "\\", '"', "\n", "\r", "\t", "[", "]", "{", "}", delimiter]:
        if ch in value:
            return True
    return False



def _encode_scalar(value: Any, delimiter: str = ",") -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value)
    if _string_needs_quotes(text, delimiter=delimiter):
        return json.dumps(text, ensure_ascii=False)
    return text



def _uniform_object_rows(items: list[Any]) -> tuple[list[str], list[dict[str, Any]]] | None:
    if not items:
        return None
    if not all(isinstance(item, dict) for item in items):
        return None
    keys = list(items[0].keys())
    if not keys:
        return None
    for item in items:
        if list(item.keys()) != keys:
            return None
        if not all(_is_primitive(item[key]) for key in keys):
            return None
    return keys, items  # type: ignore[return-value]



def _encode_value(lines: list[str], key: str, value: Any, depth: int) -> None:
    indent = " " * (depth * 2)
    encoded_key = _quote_key_if_needed(key)

    if isinstance(value, dict):
        lines.append(f"{indent}{encoded_key}:")
        for child_key, child_value in value.items():
            _encode_value(lines, str(child_key), child_value, depth + 1)
        return

    if isinstance(value, list):
        if not value:
            lines.append(f"{indent}{encoded_key}[0]:")
            return

        uniform = _uniform_object_rows(value)
        if uniform is not None:
            fields, rows = uniform
            field_text = ",".join(_quote_key_if_needed(str(field)) for field in fields)
            lines.append(f"{indent}{encoded_key}[{len(rows)}]{{{field_text}}}:")
            for row in rows:
                row_values = [_encode_scalar(row[field]) for field in fields]
                lines.append(f"{' ' * ((depth + 1) * 2)}{','.join(row_values)}")
            return

        if all(_is_primitive(item) for item in value):
            inline = ",".join(_encode_scalar(item) for item in value)
            lines.append(f"{indent}{encoded_key}[{len(value)}]: {inline}")
            return

        lines.append(f"{indent}{encoded_key}[{len(value)}]:")
        for item in value:
            item_indent = " " * ((depth + 1) * 2)
            if _is_primitive(item):
                lines.append(f"{item_indent}- {_encode_scalar(item)}")
            elif isinstance(item, dict):
                lines.append(f"{item_indent}-")
                for child_key, child_value in item.items():
                    _encode_value(lines, str(child_key), child_value, depth + 2)
            else:
                lines.append(f"{item_indent}- {_encode_scalar(str(item))}")
        return

    lines.append(f"{indent}{encoded_key}: {_encode_scalar(value)}")



def encode_toon(value: dict[str, Any]) -> str:
    lines: list[str] = []
    for key, child_value in value.items():
        _encode_value(lines, str(key), child_value, 0)
    return "\n".join(lines)



def _split_escaped(line: str, delimiter: str = ",") -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    in_quotes = False
    escaped = False
    for ch in line:
        if escaped:
            current.append(ch)
            escaped = False
            continue
        if ch == "\\":
            current.append(ch)
            escaped = True
            continue
        if ch == '"':
            current.append(ch)
            in_quotes = not in_quotes
            continue
        if ch == delimiter and not in_quotes:
            parts.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    parts.append("".join(current).strip())
    return parts



def _parse_scalar(token: str) -> Any:
    token = token.strip()
    if token == "null":
        return None
    if token == "true":
        return True
    if token == "false":
        return False
    if token.startswith('"') and token.endswith('"'):
        return json.loads(token)
    if _number_re.match(token):
        if "." in token or "e" in token.lower():
            return float(token)
        return int(token)
    return token



def _parse_key(raw_key: str) -> str:
    raw_key = raw_key.strip()
    if raw_key.startswith('"') and raw_key.endswith('"'):
        return str(json.loads(raw_key))
    return raw_key



def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))



def _parse_object(lines: list[str], start: int, depth: int) -> tuple[dict[str, Any], int]:
    result: dict[str, Any] = {}
    i = start
    indent_required = depth * 2

    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        indent = _indent_of(line)
        if indent < indent_required:
            break
        if indent > indent_required:
            raise ValueError("Invalid indentation in TOON document")

        content = line[indent_required:]
        if ":" not in content:
            raise ValueError("Expected ':' in TOON line")

        key_part, rest = content.split(":", 1)
        key_part = key_part.strip()
        rest = rest.strip()

        header_match = _array_header_re.match(key_part)
        if header_match:
            key = _parse_key(header_match.group("key"))
            count = int(header_match.group("count"))
            fields_raw = header_match.group("fields")
            if rest:
                values = [_parse_scalar(part) for part in _split_escaped(rest)]
                result[key] = values
                i += 1
                continue

            if fields_raw is not None:
                fields = [_parse_key(field) for field in _split_escaped(fields_raw)]
                rows: list[dict[str, Any]] = []
                i += 1
                while i < len(lines):
                    row_line = lines[i]
                    if not row_line.strip():
                        i += 1
                        continue
                    row_indent = _indent_of(row_line)
                    if row_indent < (depth + 1) * 2:
                        break
                    if row_indent != (depth + 1) * 2:
                        raise ValueError("Invalid row indentation in TOON table")
                    cells = [_parse_scalar(cell) for cell in _split_escaped(row_line.strip())]
                    if len(cells) != len(fields):
                        raise ValueError("Tabular row width does not match header")
                    rows.append({fields[idx]: cells[idx] for idx in range(len(fields))})
                    i += 1
                if count != len(rows):
                    raise ValueError("Tabular row count does not match header")
                result[key] = rows
                continue

            # Expanded list mode
            items: list[Any] = []
            i += 1
            while i < len(lines):
                item_line = lines[i]
                if not item_line.strip():
                    i += 1
                    continue
                item_indent = _indent_of(item_line)
                if item_indent < (depth + 1) * 2:
                    break
                if item_indent != (depth + 1) * 2:
                    raise ValueError("Invalid list indentation")
                stripped = item_line.strip()
                if not stripped.startswith("- ") and stripped != "-":
                    break
                if stripped == "-":
                    item, i = _parse_object(lines, i + 1, depth + 2)
                    items.append(item)
                    continue
                items.append(_parse_scalar(stripped[2:]))
                i += 1
            if count != len(items):
                raise ValueError("List item count does not match header")
            result[key] = items
            continue

        key = _parse_key(key_part)
        if rest:
            result[key] = _parse_scalar(rest)
            i += 1
            continue

        child, i = _parse_object(lines, i + 1, depth + 1)
        result[key] = child

    return result, i



def decode_toon(text: str) -> dict[str, Any]:
    lines = text.splitlines()
    parsed, index = _parse_object(lines, 0, 0)
    # Ensure trailing lines are blank only.
    for line in lines[index:]:
        if line.strip():
            raise ValueError("Unexpected trailing TOON content")
    return parsed



def encode_tool_payload(
    payload_type: str,
    backend_used: str,
    warnings: list[str],
    data: dict[str, Any],
) -> str:
    payload = {
        "type": payload_type,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "backend_used": backend_used,
        "warnings": warnings,
        "data": normalize_payload(data),
    }
    toon = encode_toon(payload)
    # Enforce strict parseability for MCP tool output safety.
    decode_toon(toon)
    return toon
