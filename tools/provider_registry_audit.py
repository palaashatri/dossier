#!/usr/bin/env python3
"""Deterministic maintenance audit for Dossier's declarative provider catalog.

This tool never performs network requests. It catches repository-level mistakes
before runtime/live health checks: duplicate IDs, insecure profile templates,
category drift, malformed priorities, and inventory changes that require TRUTH.md
review. It intentionally treats live-provider health as a separate concern.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import sys
from dataclasses import dataclass

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/io/dossier/app/data/platform/ProviderCatalogV2.kt"
TRUTH = ROOT / "TRUTH.md"

PROFILE_RE = re.compile(
    r'p\("(?P<id>[a-z0-9-]+)",\s*"(?P<name>[^"]+)",\s*ProviderCategory\.(?P<category>[A-Za-z]+),\s*"(?P<template>[^"]+)",\s*(?P<priority>\d+)',
    re.MULTILINE,
)
SERVICE_RE = re.compile(
    r'service\("(?P<id>[a-z0-9-]+)",\s*"(?P<name>[^"]+)",\s*ProviderCategory\.(?P<category>[A-Za-z]+),.*?,\s*(?P<priority>\d+),\s*SourceReliability\.[A-Za-z]+\)',
    re.MULTILINE | re.DOTALL,
)
TRUTH_COUNT_RE = re.compile(r"Declarative provider definitions on this branch:\*\*\s*(\d+)")


@dataclass(frozen=True)
class Entry:
    provider_id: str
    name: str
    category: str
    priority: int
    template: str | None


def parse_catalog(text: str) -> list[Entry]:
    entries = [
        Entry(
            provider_id=m.group("id"),
            name=m.group("name"),
            category=m.group("category"),
            priority=int(m.group("priority")),
            template=m.group("template"),
        )
        for m in PROFILE_RE.finditer(text)
    ]
    entries.extend(
        Entry(
            provider_id=m.group("id"),
            name=m.group("name"),
            category=m.group("category"),
            priority=int(m.group("priority")),
            template=None,
        )
        for m in SERVICE_RE.finditer(text)
    )
    return entries


def audit(entries: list[Entry], truth_text: str) -> tuple[list[str], dict[str, object]]:
    errors: list[str] = []
    ids = [entry.provider_id for entry in entries]
    duplicates = sorted(pid for pid, count in collections.Counter(ids).items() if count > 1)
    if duplicates:
        errors.append(f"duplicate provider IDs: {', '.join(duplicates)}")

    for entry in entries:
        if not 0 <= entry.priority <= 100:
            errors.append(f"{entry.provider_id}: priority {entry.priority} outside 0..100")
        if entry.template is not None:
            if not entry.template.startswith("https://"):
                errors.append(f"{entry.provider_id}: profile template is not HTTPS")
            if entry.template.count("{username}") != 1:
                errors.append(f"{entry.provider_id}: username profile template must contain exactly one {{username}}")

    truth_match = TRUTH_COUNT_RE.search(truth_text)
    truth_count = int(truth_match.group(1)) if truth_match else None
    if truth_count is None:
        errors.append("TRUTH.md is missing the declarative provider inventory field")
    elif truth_count != len(entries):
        errors.append(
            f"TRUTH.md says {truth_count} provider definitions but catalog contains {len(entries)}"
        )

    categories = collections.Counter(entry.category for entry in entries)
    stats: dict[str, object] = {
        "providerCount": len(entries),
        "profileTemplateCount": sum(entry.template is not None for entry in entries),
        "serviceCount": sum(entry.template is None for entry in entries),
        "categories": dict(sorted(categories.items())),
        "duplicateIds": duplicates,
    }
    return errors, stats


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="emit machine-readable statistics")
    args = parser.parse_args()

    catalog_text = CATALOG.read_text(encoding="utf-8")
    truth_text = TRUTH.read_text(encoding="utf-8")
    entries = parse_catalog(catalog_text)
    if not entries:
        print("provider audit: no catalog entries parsed", file=sys.stderr)
        return 2

    errors, stats = audit(entries, truth_text)
    if args.json:
        print(json.dumps({"ok": not errors, "errors": errors, **stats}, indent=2, sort_keys=True))
    else:
        print(f"Provider definitions: {stats['providerCount']}")
        print(f"Profile templates: {stats['profileTemplateCount']}")
        print(f"Service definitions: {stats['serviceCount']}")
        for category, count in stats["categories"].items():
            print(f"  {category}: {count}")
        if errors:
            print("\nAudit failures:", file=sys.stderr)
            for error in errors:
                print(f"- {error}", file=sys.stderr)

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
