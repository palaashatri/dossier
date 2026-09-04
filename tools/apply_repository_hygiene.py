#!/usr/bin/env python3
"""One-time deterministic cleanup for the September 2026 Dossier reset.

This script is intentionally narrow: it removes developer/repository-owner
identifiers from production HTTP identity strings and replaces browser/device
impersonation user agents with a generic Dossier product user agent.
It is deleted after the migration commit is verified.
"""

from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app/src/main/java"
GENERIC_UA = '"Dossier/0.1 public-exposure-audit"'
BROWSER_UA = re.compile(r'"Mozilla/5\.0 \([^"\n]*"')
OWNER_SUFFIX = re.compile(r"\s*\(\+https://github\.com/palaashatri/dossier\)")


def rewrite(path: pathlib.Path) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = BROWSER_UA.sub(GENERIC_UA, original)
    updated = OWNER_SUFFIX.sub("", updated)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8", newline="\n")
    return True


def main() -> None:
    changed: list[str] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        if rewrite(path):
            changed.append(str(path.relative_to(ROOT)))

    remaining: list[str] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for needle in ("palaashatri", "SM-S931B", "Mozilla/5.0 ("):
            if needle in text:
                remaining.append(f"{path.relative_to(ROOT)}: {needle}")

    if remaining:
        raise SystemExit("Hygiene cleanup incomplete:\n" + "\n".join(remaining))
    if not changed:
        raise SystemExit("Expected hygiene violations were not found; refusing no-op migration")

    print(f"Rewrote {len(changed)} production source files:")
    for path in changed:
        print(f"- {path}")


if __name__ == "__main__":
    main()
