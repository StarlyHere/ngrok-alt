#!/usr/bin/env python3
"""Read or wait for a matching Sprinklr LocalConnect inspector record."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--inspector",
        default="http://127.0.0.1:4040/api/requests",
        help="LocalConnect inspector API URL",
    )
    parser.add_argument("--baseline", action="store_true", help="Print the latest request ID")
    parser.add_argument("--after", type=int, default=-1, help="Only match records after this ID")
    parser.add_argument("--path", help="Required substring of the inspected request path")
    parser.add_argument("--method", help="Required HTTP method")
    parser.add_argument("--timeout", type=float, default=45.0, help="Seconds to wait")
    parser.add_argument(
        "--require-success",
        action="store_true",
        help="Require a completed HTTP status in the 200-399 range",
    )
    args = parser.parse_args()
    if not args.baseline and not args.path:
        parser.error("--path is required unless --baseline is used")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    return args


def read_records(url: str) -> list[dict]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=3) as response:
        if response.status != 200:
            raise RuntimeError(f"inspector returned HTTP {response.status}")
        payload = json.load(response)
    if not isinstance(payload, list):
        raise RuntimeError("inspector response is not a JSON array")
    return payload


def matches(record: dict, args: argparse.Namespace) -> bool:
    if not isinstance(record.get("id"), int) or record["id"] <= args.after:
        return False
    if args.path not in str(record.get("path", "")):
        return False
    if args.method and str(record.get("method", "")).upper() != args.method.upper():
        return False
    status = record.get("status")
    duration = record.get("durationMs")
    if not isinstance(status, int) or not isinstance(duration, int) or duration < 0:
        return False
    return not args.require_success or 200 <= status < 400


def main() -> int:
    args = parse_args()
    deadline = time.monotonic() + args.timeout
    last_error: Exception | None = None

    while True:
        try:
            records = read_records(args.inspector)
            last_error = None
            if args.baseline:
                print(max((record.get("id", 0) for record in records), default=0))
                return 0
            for record in records:
                if matches(record, args):
                    print(json.dumps(record, sort_keys=True))
                    return 0
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
            last_error = error

        if time.monotonic() >= deadline:
            detail = f": {last_error}" if last_error else ""
            print(f"timed out waiting for a matching inspector request{detail}", file=sys.stderr)
            return 1
        time.sleep(1)


if __name__ == "__main__":
    raise SystemExit(main())
