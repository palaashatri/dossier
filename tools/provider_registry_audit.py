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
import hashlib
import json
import pathlib
import re
import sys
from dataclasses import dataclass
from urllib.parse import urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/io/dossier/app/data/platform/ProviderCatalogV2.kt"
TRUTH = ROOT / "TRUTH.md"
WHATS_MY_NAME_DATA = ROOT / "app/src/main/assets/providers/whatsmyname/wmn-data.json"
WHATS_MY_NAME_LICENSE = ROOT / "app/src/main/assets/providers/whatsmyname/LICENSE.md"

# These values are the reviewed, pinned asset contract. The source catalog is
# audited as a separate username-rule surface; its rows must never inflate the
# ProviderCatalogV2 definition count or be reported as live provider health.
WHATS_MY_NAME_EXPECTED_SHA256 = (
    "779922223756F47D1512F81A5A2D0C69D19418FE5DF1A2A9406C7CF18CF68F34"
)
WHATS_MY_NAME_EXPECTED_LICENSE_SHA256 = (
    "3EAB49AA5CABC24918C11AAB97DFE8873E0641317B898D989C993C4283A4D84B"
)
WHATS_MY_NAME_EXPECTED_SIZE_BYTES = 258_615
WHATS_MY_NAME_EXPECTED_SOURCE_COUNT = 716
WHATS_MY_NAME_EXPECTED_EXECUTABLE_COUNT = 644

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


def _whats_my_name_exclusion_reason(record: object) -> str | None:
    """Mirror the Kotlin asset policy without turning rows into providers."""

    if not isinstance(record, dict):
        return "InvalidSchema"

    name = record.get("name")
    if name is None:
        return "MissingName"
    if not isinstance(name, str):
        return "InvalidSchema"
    name = name.strip()
    if not name:
        return "MissingName"

    valid = record.get("valid")
    if valid is not None:
        if not isinstance(valid, bool):
            return "InvalidSchema"
        if not valid:
            return "NotValid"

    post_body = record.get("post_body")
    if post_body is not None and not isinstance(post_body, str):
        return "InvalidSchema"
    if isinstance(post_body, str) and post_body.strip():
        return "ContainsPostBody"

    category = record.get("cat")
    if category is not None and not isinstance(category, str):
        return "InvalidSchema"
    if isinstance(category, str) and "nsfw" in category.lower():
        return "CategoryNSFW"

    uri_check = record.get("uri_check")
    if uri_check is not None and not isinstance(uri_check, str):
        return "InvalidSchema"
    if not isinstance(uri_check, str) or uri_check.count("{account}") != 1:
        return "MissingOrMultipleAccountTokens"
    probe_uri = uri_check.replace("{account}", "probe")
    try:
        parsed = urlsplit(probe_uri)
        hostname = parsed.hostname
        username = parsed.username
        password = parsed.password
    except ValueError:
        return "InvalidUriHost"
    if parsed.scheme.lower() != "https":
        return "NotHttps"
    if not hostname or username or password:
        return "InvalidUriHost"

    protection = record.get("protection")
    if protection is not None:
        if not isinstance(protection, list) or any(
            not isinstance(value, str) for value in protection
        ):
            return "InvalidSchema"
        if any(
            value.lower() in {"captcha", "user-auth", "anubis"}
            for value in protection
        ):
            return "ProtectionEnabled"

    exists_code = record.get("e_code")
    missing_code = record.get("m_code")
    if (
        isinstance(exists_code, bool)
        or isinstance(missing_code, bool)
        or not isinstance(exists_code, int)
        or not isinstance(missing_code, int)
        or exists_code not in range(100, 600)
        or missing_code not in range(100, 600)
    ):
        return "InvalidStatusCode"

    exists_marker = record.get("e_string")
    missing_marker = record.get("m_string")
    if (
        (exists_marker is not None and not isinstance(exists_marker, str))
        or (missing_marker is not None and not isinstance(missing_marker, str))
    ):
        return "InvalidSchema"
    exists_marker = exists_marker or ""
    missing_marker = missing_marker or ""
    if (
        exists_code == missing_code
        and not exists_marker.strip()
        and not missing_marker.strip()
    ):
        return "AmbiguousSameStatusNoMarkers"

    uri_pretty = record.get("uri_pretty")
    strip_bad_char = record.get("strip_bad_char")
    if (
        (uri_pretty is not None and not isinstance(uri_pretty, str))
        or (strip_bad_char is not None and not isinstance(strip_bad_char, str))
    ):
        return "InvalidSchema"
    return None


def _whats_my_name_provider_id(record: dict[str, object]) -> str:
    """Derive the same bounded source ID used by WhatsMyNameCatalog.kt."""

    name = str(record["name"]).strip()
    uri_check = str(record["uri_check"])
    probe_uri = uri_check.replace("{account}", "probe")
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:100] or "site"
    digest = hashlib.sha256(probe_uri.encode("utf-8")).hexdigest()[:10]
    return f"wmn-{slug}-{digest}"


def audit_whats_my_name_catalog(
    data_path: pathlib.Path = WHATS_MY_NAME_DATA,
    license_path: pathlib.Path = WHATS_MY_NAME_LICENSE,
) -> tuple[list[str], dict[str, object]]:
    """Audit the pinned source catalog as a distinct rule-surface contract.

    This is deliberately separate from :func:`audit`: executable source rows
    are not declarative ProviderCatalogV2 definitions and cannot inflate its
    inventory or health coverage.
    """

    errors: list[str] = []
    stats: dict[str, object] = {
        "dataPath": str(data_path.relative_to(ROOT)) if data_path.is_relative_to(ROOT) else str(data_path),
        "licensePath": str(license_path.relative_to(ROOT)) if license_path.is_relative_to(ROOT) else str(license_path),
        "dataByteCount": None,
        "dataSha256": None,
        "licenseSha256": None,
        "sourceRecordCount": None,
        "executableRuleCount": None,
        "excludedRecordCount": None,
        "exclusionReasons": {},
        "generatedRuleIdCount": None,
    }

    try:
        data = data_path.read_bytes()
    except OSError as exc:
        errors.append(f"WhatsMyName data asset cannot be read: {exc}")
        return errors, stats
    stats["dataByteCount"] = len(data)
    data_sha256 = hashlib.sha256(data).hexdigest().upper()
    stats["dataSha256"] = data_sha256
    if len(data) != WHATS_MY_NAME_EXPECTED_SIZE_BYTES:
        errors.append(
            "WhatsMyName data asset size changed: "
            f"expected {WHATS_MY_NAME_EXPECTED_SIZE_BYTES}, got {len(data)}"
        )
    if data_sha256 != WHATS_MY_NAME_EXPECTED_SHA256:
        errors.append("WhatsMyName data asset SHA-256 does not match the pinned digest")

    try:
        license_data = license_path.read_bytes()
    except OSError as exc:
        errors.append(f"WhatsMyName license asset cannot be read: {exc}")
    else:
        license_sha256 = hashlib.sha256(license_data).hexdigest().upper()
        stats["licenseSha256"] = license_sha256
        if license_sha256 != WHATS_MY_NAME_EXPECTED_LICENSE_SHA256:
            errors.append("WhatsMyName license SHA-256 does not match the pinned digest")

    try:
        root = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        errors.append(f"WhatsMyName data asset is not valid UTF-8 JSON: {exc}")
        return errors, stats
    if not isinstance(root, dict):
        errors.append("WhatsMyName catalog top-level structure is malformed")
        return errors, stats

    for metadata_key in ("license", "authors", "categories"):
        value = root.get(metadata_key)
        if (
            not isinstance(value, list)
            or not value
            or any(not isinstance(item, str) or not item.strip() for item in value)
        ):
            errors.append(f"WhatsMyName {metadata_key} metadata is malformed")

    sites = root.get("sites")
    if not isinstance(sites, list) or not sites:
        errors.append("WhatsMyName sites array is missing or malformed")
        return errors, stats

    reason_counts: collections.Counter[str] = collections.Counter()
    generated_ids: set[str] = set()
    executable_count = 0
    for record in sites:
        reason = _whats_my_name_exclusion_reason(record)
        if reason is not None:
            reason_counts[reason] += 1
            continue
        executable_count += 1
        assert isinstance(record, dict)
        generated_id = _whats_my_name_provider_id(record)
        if generated_id in generated_ids:
            errors.append(f"WhatsMyName catalog contains duplicate generated rule id {generated_id}")
        generated_ids.add(generated_id)

    excluded_count = len(sites) - executable_count
    stats.update(
        {
            "sourceRecordCount": len(sites),
            "executableRuleCount": executable_count,
            "excludedRecordCount": excluded_count,
            "exclusionReasons": dict(sorted(reason_counts.items())),
            "generatedRuleIdCount": len(generated_ids),
        }
    )
    if len(sites) != WHATS_MY_NAME_EXPECTED_SOURCE_COUNT:
        errors.append(
            "WhatsMyName source record count changed: "
            f"expected {WHATS_MY_NAME_EXPECTED_SOURCE_COUNT}, got {len(sites)}"
        )
    if executable_count != WHATS_MY_NAME_EXPECTED_EXECUTABLE_COUNT:
        errors.append(
            "WhatsMyName executable rule count changed: "
            f"expected {WHATS_MY_NAME_EXPECTED_EXECUTABLE_COUNT}, got {executable_count}"
        )
    return errors, stats


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
    source_errors, source_stats = audit_whats_my_name_catalog()
    errors.extend(source_errors)
    stats["whatsMyName"] = source_stats
    if args.json:
        print(json.dumps({"ok": not errors, "errors": errors, **stats}, indent=2, sort_keys=True))
    else:
        print(f"Provider definitions: {stats['providerCount']}")
        print(f"Profile templates: {stats['profileTemplateCount']}")
        print(f"Service definitions: {stats['serviceCount']}")
        print(f"Structured parse count: {stats['structuredParseCount']}")
        print(
            "WhatsMyName source records: "
            f"{source_stats['sourceRecordCount']} "
            f"({source_stats['executableRuleCount']} executable, "
            f"{source_stats['excludedRecordCount']} excluded)"
        )
        for category, count in stats["categories"].items():
            print(f"  {category}: {count}")
        if errors:
            print("\nAudit failures:", file=sys.stderr)
            for error in errors:
                print(f"- {error}", file=sys.stderr)

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
