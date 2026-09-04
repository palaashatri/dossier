#!/usr/bin/env python3
"""Repository hygiene regression tests for Dossier.

These tests intentionally inspect the repository tree rather than runtime behavior.
They prevent developer/device fingerprints and local editor state from re-entering
production source after the September 2026 product reset.
"""

from __future__ import annotations

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PRODUCTION_SOURCE = ROOT / "app/src/main/java"


class RepositoryHygieneAuditTest(unittest.TestCase):
    def test_production_source_has_no_developer_or_device_fingerprints(self) -> None:
        forbidden = {
            "palaashatri": "repository-owner/developer identifier",
            "SM-S931B": "developer device model",
            "Mozilla/5.0 (": "browser impersonation user-agent",
        }
        violations: list[str] = []
        for path in sorted(PRODUCTION_SOURCE.rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            for needle, reason in forbidden.items():
                if needle in text:
                    violations.append(f"{path.relative_to(ROOT)}: {reason} ({needle})")
        self.assertEqual([], violations, "\n".join(violations))

    def test_local_editor_and_agent_state_is_not_committed(self) -> None:
        forbidden_paths = [
            ROOT / ".idea",
            ROOT / ".serena",
            ROOT / ".vscode",
            ROOT / "tools/provider_registry_audit_fixed_marker.txt",
            ROOT / "tools/verify_whatsmyname_catalog.ps1",
        ]
        present = [str(path.relative_to(ROOT)) for path in forbidden_paths if path.exists()]
        self.assertEqual([], present, f"Remove repository-local/generated state: {present}")


if __name__ == "__main__":
    unittest.main()
