"""Focused tests for the no-network provider registry maintenance audit."""

from __future__ import annotations

import pathlib
import sys
import unittest


TOOLS = pathlib.Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from provider_registry_audit import Entry, audit, parse_catalog  # noqa: E402


class ProviderRegistryAuditTest(unittest.TestCase):
    def test_current_catalog_parses_with_structured_metadata(self) -> None:
        catalog_path = TOOLS.parent / "app/src/main/java/io/dossier/app/data/platform/ProviderCatalogV2.kt"
        entries, declared_ids = parse_catalog(catalog_path.read_text(encoding="utf-8"))

        self.assertEqual(78, len(declared_ids))
        self.assertEqual(78, len(entries))
        self.assertEqual(70, sum(entry.kind == "profile" for entry in entries))
        self.assertEqual(8, sum(entry.kind == "service" for entry in entries))
        self.assertEqual(("Url", "Archive"), entries[-8].capabilities)
        self.assertEqual(("Username", "Name", "Email", "Image"), entries[-3].capabilities)

        truth = (TOOLS.parent / "TRUTH.md").read_text(encoding="utf-8")
        errors, stats = audit(entries, declared_ids, truth)
        self.assertEqual([], errors)
        self.assertEqual(0, stats["invalidEntryCount"])
        self.assertEqual(78, stats["providerCount"])

    def test_invalid_id_and_case_variant_are_not_silent_inventory_drift(self) -> None:
        source = """
            p("GitHub", "Bad id", ProviderCategory.Social,
              "https://example.test/{username}", 50)
            p("github", "Duplicate", ProviderCategory.Social,
              "https://example.test/{username}", 50)
        """
        entries, declared_ids = parse_catalog(source)
        errors, stats = audit(entries, declared_ids, "Declarative provider definitions: 2")

        self.assertIn("duplicate provider IDs: github", errors)
        self.assertTrue(any("provider id must use lowercase" in error for error in errors))
        self.assertEqual(2, stats["providerCount"])

    def test_profile_template_and_service_metadata_fail_closed(self) -> None:
        source = """
            p("bad-template", "Bad", ProviderCategory.NotARealCategory,
              "http://user:pass@example.test/{username}/{other}", 101)
            service("empty-service", "Service", ProviderCategory.SearchEngine,
              setOf(), 50, SourceReliability.NotARealReliability)
        """
        entries, declared_ids = parse_catalog(source)
        errors, stats = audit(entries, declared_ids, "Declarative provider definitions: 2")
        joined = "\n".join(errors)

        self.assertIn("unknown provider category NotARealCategory", joined)
        self.assertIn("priority 101 outside 0..100", joined)
        self.assertIn("profile template is not HTTPS", joined)
        self.assertIn("unsupported template placeholders: {other}", joined)
        self.assertIn("profile template must not contain URL credentials", joined)
        self.assertIn("empty-service: service must declare at least one query capability", joined)
        self.assertIn("unknown source reliability NotARealReliability", joined)
        self.assertEqual(2, stats["providerCount"])

    def test_parser_drift_is_reported_when_call_shape_is_not_structured(self) -> None:
        source = """
            p("valid", "Valid", ProviderCategory.Social,
              "https://example.test/{username}", 50)
            p("drifted", "Drifted", ProviderCategory.Social,
              PROFILE_TEMPLATE, 50)
        """
        entries, declared_ids = parse_catalog(source)
        errors, _ = audit(entries, declared_ids, "Declarative provider definitions: 2")

        self.assertTrue(any(error.startswith("provider audit parser drift:") for error in errors))

    def test_entry_constructor_remains_usable_for_direct_audit_fixtures(self) -> None:
        entry = Entry(
            provider_id="fixture",
            name="Fixture",
            category="Social",
            priority=50,
            template="https://fixture.example/{username}",
        )
        errors, stats = audit([entry], ["fixture"], "Declarative provider definitions: 1")

        self.assertEqual([], errors)
        self.assertEqual(1, stats["providerCount"])


if __name__ == "__main__":
    unittest.main()
