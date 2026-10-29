#!/usr/bin/env python3
"""RTM check (US-001, testing strategy section 5).

Fails if any test is tagged with a test-case ID that is not in docs/07-quality/rtm.csv, so the
traceability matrix cannot drift from the code. Prints which RTM cases are covered by code.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RTM = ROOT / "docs" / "07-quality" / "rtm.csv"
TAG = re.compile(r"""(?:@Tag\(\s*"|pytest\.mark\.tc\(\s*["']|test\(\s*["'])(TC-[A-Z0-9-]+)""")
SOURCES = [
    (ROOT / "api" / "src" / "test", "*.java"),
    (ROOT / "workers" / "tests", "*.py"),
    (ROOT / "web", "*.test.ts"),
]


def main() -> int:
    with RTM.open(newline="", encoding="utf-8") as f:
        known = {row["test_case"] for row in csv.DictReader(f)}

    found: dict[str, list[str]] = {}
    for base, pattern in SOURCES:
        if not base.exists():
            continue
        for path in base.rglob(pattern):
            if "node_modules" in path.parts:
                continue
            for tc in TAG.findall(path.read_text(encoding="utf-8")):
                found.setdefault(tc, []).append(str(path.relative_to(ROOT)))

    unknown = sorted(set(found) - known)
    for tc in sorted(found):
        print(f"{tc}: {', '.join(sorted(set(found[tc])))}")
    print(f"{len(found)} of {len(known)} RTM test cases have tagged tests.")
    if unknown:
        print(f"ERROR: tags not in {RTM.relative_to(ROOT)}: {', '.join(unknown)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
