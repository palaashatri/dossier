#!/usr/bin/env python3
"""One-time deterministic cleanup for the September 2026 Dossier reset.

This script is intentionally narrow: it removes developer/repository-owner
identifiers from production HTTP identity strings, replaces browser/device
impersonation user agents with a generic Dossier product user agent, and restores
one machine-readable provider-inventory fact required by the existing audit.
It is deleted after the migration commit is verified.
"""

from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app/src/main/java"
TRUTH = ROOT / "TRUTH.md"
GENERIC_UA = '"Dossier/0.1 public-exposure-audit"'
BROWSER_UA = re.compile(r'"Mozilla/5\.0 \([^"\n]*"')
OWNER_SUFFIX = re.compile(r"\s*\(\+https://github\.com/palaashatri/dossier\)")
TRUTH_ANCHOR = "- **Current mission-readiness score:** **UNSCORED / NOT ESTABLISHED**"
TRUTH_PROVIDER_FIELD = "- **Declarative provider definitions:** **78 authored**"


def rewrite(path: pathlib.Path) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = BROWSER_UA.sub(GENERIC_UA, original)
    updated = OWNER_SUFFIX.sub("", updated)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8", newline="\n")
    return True


def restore_truth_inventory_field() -> bool:
    original = TRUTH.read_text(encoding="utf-8")
    if TRUTH_PROVIDER_FIELD in original:
        return False
    if TRUTH_ANCHOR not in original:
        raise SystemExit("TRUTH.md reset anchor not found; refusing ambiguous rewrite")
    updated = original.replace(
        TRUTH_ANCHOR,
        f"{TRUTH_ANCHOR}\n{TRUTH_PROVIDER_FIELD}",
        1,
    )
    TRUTH.write_text(updated, encoding="utf-8", newline="\n")
    return True


def main() -> None:
    changed: list[str] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        if rewrite(path):
            changed.append(str(path.relative_to(ROOT)))

    truth_changed = restore_truth_inventory_field()

    remaining: list[str] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for needle in ("palaashatri", "SM-S931B", "Mozilla/5.0 ("):
            if needle in text:
                remaining.append(f"{path.relative_to(ROOT)}: {needle}")

    if remaining:
        raise SystemExit("Hygiene cleanup incomplete:\n" + "\n".join(remaining))
    if not changed and not truth_changed:
        raise SystemExit("Expected migration changes were not found; refusing no-op migration")

    print(f"Rewrote {len(changed)} production source files:")
    for path in changed:
        print(f"- {path}")
    if truth_changed:
        print("- TRUTH.md: restored declarative provider inventory field")


if __name__ == "__main__":
    main()
