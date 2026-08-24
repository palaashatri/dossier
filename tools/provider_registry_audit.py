#!/usr/bin/env python3
"""Deterministic maintenance audit for Dossier's declarative provider catalog.

This tool never performs network requests. It catches repository-level mistakes
before runtime/live health checks: duplicate IDs, insecure profile templates,
category/capability/reliability drift, malformed priorities, unsafe URL
placeholders/credentials, parser drift, and inventory changes that require
TRUTH.md review. Live-provider health remains a separate concern.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import sys
from dataclasses import dataclass
from urllib.parse import urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/io/dossier/app/data/platform/ProviderCatalogV2.kt"
TRUTH = ROOT / "TRUTH.md"

# Keep the source audit independent of the Kotlin compiler, but make the
# parser deliberately broader than the production ID policy. Invalid IDs
# should become actionable audit errors rather than disappearing from the
# inventory and looking like a healthy, smaller catalog.
CALL_ID_RE = re.compile(r'\b(?P<kind>p|service)\s*\(\s*"(?P<id>[^"]*)"')
PROFILE_RE = re.compile(
    r'p\s*\(\s*"(?P<id>[^"]*)",\s*"(?P<name>[^"]*)",\s*ProviderCategory\.(?P<category>[A-Za-z]+),\s*"(?P<template>[^"]*)",\s*(?P<priority>-?\d+)',
    re.MULTILINE,
)
SERVICE_RE = re.compile(
    r'service\s*\(\s*"(?P<id>[^"]*)",\s*"(?P<name>[^"]*)",\s*ProviderCategory\.(?P<category>[A-Za-z]+),\s*setOf\((?P<capabilities>[^)]*)\),\s*(?P<priority>-?\d+),\s*SourceReliability\.(?P<reliability>[A-Za-z]+)\s*\)',
    re.MULTILINE,
)
# Accept the canonical Markdown label whether it is bolded or not and whether
# older docs included "on this branch". Stop at the current line so a missing
# inventory field cannot accidentally match a later unrelated number.
TRUTH_COUNT_RE = re.compile(
    r"Declarative provider definitions(?: on this branch)?[^\n0-9]*(\d+)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Entry:
    provider_id: str
    name: str
    category: str
    priority: int
    template: str | None
    kind: str = "profile"
    capabilities: tuple[str, ...] = ("Username",)
    reliability: str | None = "DirectPublicProfile"


def parse_catalog(text: str) -> tuple[list[Entry], list[str]]:
    entries = [
        Entry(
            provider_id=m.group("id"),
            name=m.group("name"),
            category=m.group("category"),
            priority=int(m.group("priority")),
            template=m.group("template"),
            kind="profile",
            capabilities=("Username",),
            reliability="DirectPublicProfile",
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
            kind="service",
            capabilities=tuple(
                capability
                for capability in re.findall(
                    r"\bQueryCapability\.([A-Za-z]+)", m.group("capabilities")
                )
            ),
            reliability=m.group("reliability"),
        )
        for m in SERVICE_RE.finditer(text)
    )
    declared_ids = [m.group("id") for m in CALL_ID_RE.finditer(text)]
    return entries, declared_ids


KNOWN_CATEGORIES = {
    "Developer",
    "Social",
    "Forum",
    "Gaming",
    "Creative",
    "Publishing",
    "Professional",
    "Media",
    "Commerce",
    "Education",
    "CodeHosting",
    "PackageRegistry",
    "PersonalWebsite",
    "PublicDirectory",
    "Archive",
    "BreachMetadata",
    "SearchEngine",
}
KNOWN_CAPABILITIES = {
    "Username",
    "Name",
    "Email",
    "Phone",
    "Domain",
    "Url",
    "Image",
    "Archive",
    "Breach",
}
KNOWN_RELIABILITIES = {
    "AuthoritativeApi",
    "DirectPublicProfile",
    "DirectPersonalWebsite",
    "ArchiveSnapshot",
    "SearchCandidate",
    "ThirdPartyAggregation",
}
ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
PLACEHOLDER_PATTERN = re.compile(r"\{([^{}]+)\}")


def validate_entry(entry: Entry) -> list[str]:
    """Return deterministic schema/policy errors for one parsed definition."""

    errors: list[str] = []
    provider_id = entry.provider_id or "<blank>"
    if not ID_PATTERN.fullmatch(entry.provider_id):
        errors.append(
            f"{provider_id}: provider id must use lowercase letters, numbers and hyphens"
        )
    if not entry.name.strip():
        errors.append(f"{provider_id}: display name must not be blank")
    if entry.category not in KNOWN_CATEGORIES:
        errors.append(f"{entry.provider_id}: unknown provider category {entry.category}")
    if not 0 <= entry.priority <= 100:
        errors.append(f"{entry.provider_id}: priority {entry.priority} outside 0..100")
    if entry.kind not in {"profile", "service"}:
        errors.append(f"{entry.provider_id}: unknown entry kind {entry.kind}")

    unknown_capabilities = sorted(set(entry.capabilities) - KNOWN_CAPABILITIES)
    if unknown_capabilities:
        errors.append(
            f"{entry.provider_id}: unknown query capabilities: {', '.join(unknown_capabilities)}"
        )
    if entry.kind == "service" and not entry.capabilities:
        errors.append(f"{entry.provider_id}: service must declare at least one query capability")
    if entry.reliability not in KNOWN_RELIABILITIES:
        errors.append(
            f"{entry.provider_id}: unknown source reliability {entry.reliability or '<missing>'}"
        )

    if entry.template is not None:
        if entry.kind == "service":
            errors.append(f"{entry.provider_id}: service must not declare a profile template")
        template = entry.template
        if not template.lower().startswith("https://"):
            errors.append(f"{entry.provider_id}: profile template is not HTTPS")
        if template.count("{username}") != 1:
            errors.append(
                f"{entry.provider_id}: username profile template must contain exactly one {{username}}"
            )
        placeholders = PLACEHOLDER_PATTERN.findall(template)
        unknown_placeholders = sorted(set(placeholders) - {"username"})
        if unknown_placeholders:
            errors.append(
                f"{entry.provider_id}: unsupported template placeholders: "
                + ", ".join("{" + value + "}" for value in unknown_placeholders)
            )
        if any(character.isspace() or ord(character) < 0x20 for character in template):
            errors.append(f"{entry.provider_id}: profile template contains whitespace/control characters")
        try:
            parsed = urlsplit(template.replace("{username}", "probe"))
            hostname = parsed.hostname
            username = parsed.username
            password = parsed.password
        except ValueError as exc:
            errors.append(f"{entry.provider_id}: profile template URL is malformed ({exc})")
        else:
            if parsed.scheme.lower() != "https" or not hostname:
                errors.append(f"{entry.provider_id}: profile template must have an HTTPS host")
            if username or password:
                errors.append(f"{entry.provider_id}: profile template must not contain URL credentials")
    elif entry.kind == "profile":
        errors.append(f"{entry.provider_id}: profile must declare a URL template")

    return errors


def audit(
    entries: list[Entry],
    declared_ids: list[str],
    truth_text: str,
) -> tuple[list[str], dict[str, object]]:
    errors: list[str] = []
    parsed_ids = [entry.provider_id for entry in entries]

    normalized_declared_ids = [pid.strip().lower() for pid in declared_ids]
    declared_duplicates = sorted(
        pid
        for pid, count in collections.Counter(normalized_declared_ids).items()
        if count > 1
    )
    if declared_duplicates:
        errors.append(f"duplicate provider IDs: {', '.join(declared_duplicates)}")

    missing_from_structured_parse = sorted(set(declared_ids) - set(parsed_ids))
    unexpected_structured_ids = sorted(set(parsed_ids) - set(declared_ids))
    if missing_from_structured_parse or unexpected_structured_ids or len(parsed_ids) != len(declared_ids):
        detail = []
        if missing_from_structured_parse:
            detail.append("unparsed=" + ",".join(missing_from_structured_parse))
        if unexpected_structured_ids:
            detail.append("unexpected=" + ",".join(unexpected_structured_ids))
        detail.append(f"declared={len(declared_ids)} parsed={len(parsed_ids)}")
        errors.append("provider audit parser drift: " + "; ".join(detail))

    for entry in entries:
        errors.extend(validate_entry(entry))

    truth_match = TRUTH_COUNT_RE.search(truth_text)
    truth_count = int(truth_match.group(1)) if truth_match else None
    if truth_count is None:
        errors.append("TRUTH.md is missing the declarative provider inventory field")
    elif truth_count != len(declared_ids):
        errors.append(
            f"TRUTH.md says {truth_count} provider definitions but catalog declares {len(declared_ids)}"
        )

    categories = collections.Counter(entry.category for entry in entries)
    capabilities = collections.Counter(
        capability for entry in entries for capability in entry.capabilities
    )
    reliabilities = collections.Counter(
        reliability for entry in entries if (reliability := entry.reliability) is not None
    )
    stats: dict[str, object] = {
        "providerCount": len(declared_ids),
        "profileTemplateCount": sum(entry.template is not None for entry in entries),
        "serviceCount": sum(entry.template is None for entry in entries),
        "categories": dict(sorted(categories.items())),
        "capabilities": dict(sorted(capabilities.items())),
        "reliabilities": dict(sorted(reliabilities.items())),
        "duplicateIds": declared_duplicates,
        "structuredParseCount": len(parsed_ids),
        "invalidEntryCount": sum(bool(validate_entry(entry)) for entry in entries),
    }
    return errors, stats


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="emit machine-readable statistics")
    args = parser.parse_args()

    catalog_text = CATALOG.read_text(encoding="utf-8")
    truth_text = TRUTH.read_text(encoding="utf-8")
    entries, declared_ids = parse_catalog(catalog_text)
    if not declared_ids:
        print("provider audit: no catalog entries parsed", file=sys.stderr)
        return 2

    errors, stats = audit(entries, declared_ids, truth_text)
    if args.json:
        print(json.dumps({"ok": not errors, "errors": errors, **stats}, indent=2, sort_keys=True))
    else:
        print(f"Provider definitions: {stats['providerCount']}")
        print(f"Profile templates: {stats['profileTemplateCount']}")
        print(f"Service definitions: {stats['serviceCount']}")
        print(f"Structured parse count: {stats['structuredParseCount']}")
        for category, count in stats["categories"].items():
            print(f"  {category}: {count}")
        if errors:
            print("\nAudit failures:", file=sys.stderr)
            for error in errors:
                print(f"- {error}", file=sys.stderr)

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
