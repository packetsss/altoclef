#!/usr/bin/env python3
"""Lightweight UDP listener for AltoClef CamBridge events.

Run this script on the same machine as AltoClef. By default it listens on
127.0.0.1:36667 and surfaces the on-screen task status stream. Pass ``--raw``
to fall back to the original JSON dump output.
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
from datetime import datetime
from typing import Iterable, Optional, Tuple

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 36667


def iter_lines(payload: bytes) -> Iterable[str]:
    """Yield decoded UTF-8 lines from a UDP payload, ignoring empty lines."""
    text = payload.decode("utf-8", errors="replace")
    for line in text.splitlines():
        line = line.strip()
        if line:
            yield line


STATUS_SYMBOLS = {
    "running": "▶",
    "paused": "⏸",
    "stalled": "⚠",
    "rerouting": "↺",
    "success": "✅",
    "failed": "❌",
}


class DisplayState:
    def __init__(self) -> None:
        self._last_status_signature: Optional[Tuple[str | None, str | None, str | None, str | None]] = None

    def handle_status(self, payload: dict, ts: Optional[int]) -> Optional[str]:
        task_key = payload.get("task_key")
        hint = payload.get("hint")
        state = str(payload.get("state", "?")).lower()
        progress = payload.get("progress") or {}
        progress_display = None
        if isinstance(progress, dict):
            progress_display = progress.get("display")

        signature = (task_key, state, hint, progress_display)
        if signature == self._last_status_signature:
            return None
        self._last_status_signature = signature

        return format_status_line(task_key, hint, state, progress_display, payload.get("phase"), ts)


def format_status_line(task_key: Optional[str], hint: Optional[str], state: str,
                       progress_display: Optional[str], phase: Optional[str], ts: Optional[int]) -> str:
    stamp = format_timestamp(ts)
    label = hint or task_key or "Unknown task"
    symbol = STATUS_SYMBOLS.get(state, "•")
    phase_label = phase or "UNKNOWN"
    state_label = state.upper() if state else "?"

    parts = [f"{stamp} {symbol} {label}"]
    if progress_display:
        parts.append(f"progress {progress_display}")
    parts.append(f"[{state_label} @ {phase_label}]")
    return " – ".join(parts)


def format_task_event(event_type: str, payload: dict, ts: Optional[int]) -> str:
    stamp = format_timestamp(ts)
    task_key = payload.get("task_key") or payload.get("task_class") or "UnknownTask"
    task_label = payload.get("task_debug") or task_key

    if event_type == "TASK_START":
        return f"{stamp} 🚀 Started {task_label}"

    success = payload.get("success")
    reason = payload.get("reason")
    if success:
        return f"{stamp} ✅ Finished {task_label}"
    if success is False:
        detail = f" ({reason})" if reason else ""
        return f"{stamp} ❌ {task_label} failed{detail}"
    return f"{stamp} ⏹ {task_label} completed"


def default_event_summary(data: dict) -> str:
    event_id = data.get("id", "?")
    event_type = data.get("type", "?")
    ts = data.get("ts")
    payload = data.get("payload", {})
    stamp = format_timestamp(ts)
    return f"{stamp} [{event_id}] {event_type}: {json.dumps(payload, ensure_ascii=False)}"


def format_timestamp(ts: Optional[int]) -> str:
    try:
        if ts is None:
            raise ValueError
        return datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")
    except (TypeError, ValueError, OSError):
        return "--:--:--"


def print_event(line: str, raw: bool, state: DisplayState) -> None:
    if raw:
        print(line, flush=True)
        return

    try:
        data = json.loads(line)
    except json.JSONDecodeError as exc:
        print(f"[WARN] Failed to decode JSON: {exc}: {line}", file=sys.stderr, flush=True)
        return

    event_type = data.get("type")
    payload = data.get("payload", {})
    ts = data.get("ts")

    if event_type == "STATUS_NOW":
        message = state.handle_status(payload, ts)
        if message:
            print(message, flush=True)
        return

    if event_type in {"TASK_START", "TASK_END"}:
        print(format_task_event(event_type, payload, ts), flush=True)
        return

    print(default_event_summary(data), flush=True)



def run_listener(host: str, port: int, raw: bool) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((host, port))
        print(f"Listening for CamBridge events on udp://{host}:{port}", flush=True)
        state = DisplayState()
        while True:
            payload, _ = sock.recvfrom(65535)
            for line in iter_lines(payload):
                print_event(line, raw=raw, state=state)



def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=DEFAULT_HOST, help="Interface to bind (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="UDP port to bind (default: 36667)")
    parser.add_argument("--raw", action="store_true", help="Print raw JSON lines instead of formatted output")
    return parser.parse_args(argv)



def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        run_listener(args.host, args.port, raw=args.raw)
    except OSError as exc:
        print(f"[ERROR] Unable to bind udp://{args.host}:{args.port}: {exc}", file=sys.stderr, flush=True)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted; shutting down.", flush=True)
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
