#!/usr/bin/env python3
"""Lightweight UDP listener for AltoClef CamBridge events.

Run this script on the same machine as AltoClef. By default it listens on
127.0.0.1:36667 and prints parsed JSON events to stdout.
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
from typing import Iterable

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 36667


def iter_lines(payload: bytes) -> Iterable[str]:
    """Yield decoded UTF-8 lines from a UDP payload, ignoring empty lines."""
    text = payload.decode("utf-8", errors="replace")
    for line in text.splitlines():
        line = line.strip()
        if line:
            yield line


def print_event(line: str, raw: bool) -> None:
    if raw:
        print(line, flush=True)
        return

    try:
        data = json.loads(line)
    except json.JSONDecodeError as exc:
        print(f"[WARN] Failed to decode JSON: {exc}: {line}", file=sys.stderr, flush=True)
        return

    event_id = data.get("id", "?")
    event_type = data.get("type", "?")
    ts = data.get("ts", "?")
    payload = data.get("payload", {})
    print(f"[{event_id} @ {ts}] {event_type}: {json.dumps(payload)}", flush=True)



def run_listener(host: str, port: int, raw: bool) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((host, port))
        print(f"Listening for CamBridge events on udp://{host}:{port}", flush=True)
        while True:
            payload, _ = sock.recvfrom(65535)
            for line in iter_lines(payload):
                print_event(line, raw=raw)



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
