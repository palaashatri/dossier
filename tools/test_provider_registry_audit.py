"""Focused tests for the no-network provider registry maintenance audit."""

from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest
import json
from dataclasses import replace


TOOLS = pathlib.Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from provider_registry_audit import (  # noqa: E402
    Entry,
    audit,
    audit_whats_my_name_catalog,
    convert_whats_my_name_record,
    map_whats_my_name_category,
    parse_catalog,
    validate_whats_my_name_conversion,
)


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

    def test_pinned_whatsmyname_catalog_is_audited_as_source_rules(self) -> None:
        errors, stats = audit_whats_my_name_catalog()

        self.assertEqual([], errors)
        self.assertEqual(716, stats["sourceRecordCount"])
        self.assertEqual(644, stats["executableRuleCount"])
        self.assertEqual(72, stats["excludedRecordCount"])
        self.assertEqual(644, stats["generatedRuleIdCount"])
        self.assertEqual(644, stats["convertedRuleCount"])
        self.assertEqual(644, stats["convertedRuleValidCount"])
        self.assertEqual(0, stats["conversionErrorCount"])
        self.assertIsNone(stats["primaryCatalogDisjoint"])
        self.assertEqual(37, stats["exclusionReasons"]["CategoryNSFW"])
        self.assertEqual(23, stats["exclusionReasons"]["ContainsPostBody"])
        self.assertEqual(7, stats["exclusionReasons"]["NotHttps"])
        self.assertEqual(4, stats["exclusionReasons"]["ProtectionEnabled"])
        self.assertEqual(1, stats["exclusionReasons"]["NotValid"])

    def test_source_catalog_drift_fails_closed_without_changing_provider_count(self) -> None:
        source = b'{"license":["fixture"],"authors":["fixture"],"categories":["social"],"sites":[{"name":"fixture"}]}'
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            data_path = root / "wmn-data.json"
            license_path = root / "LICENSE.md"
            data_path.write_bytes(source)
            license_path.write_bytes(b"fixture license")

            errors, stats = audit_whats_my_name_catalog(data_path, license_path)

        joined = "\n".join(errors)
        self.assertIn("data asset size changed", joined)
        self.assertIn("data asset SHA-256", joined)
        self.assertIn("executable rule count changed", joined)
        self.assertIn("source record count changed", joined)
        self.assertEqual(1, stats["sourceRecordCount"])
        self.assertEqual(0, stats["executableRuleCount"])
        self.assertEqual(1, stats["excludedRecordCount"])

    def test_conversion_matches_kotlin_runtime_and_is_disjoint_from_primary_catalog(self) -> None:
        catalog_path = TOOLS.parent / "app/src/main/java/io/dossier/app/data/platform/ProviderCatalogV2.kt"
        _, primary_ids = parse_catalog(catalog_path.read_text(encoding="utf-8"))

        errors, stats = audit_whats_my_name_catalog(primary_catalog_ids=primary_ids)

        self.assertEqual([], errors)
        self.assertEqual(644, stats["convertedRuleCount"])
        self.assertEqual(644, stats["convertedRuleValidCount"])
        self.assertEqual(0, stats["conversionErrorCount"])
        self.assertEqual(0, stats["primaryCatalogCollisionCount"])
        self.assertTrue(stats["primaryCatalogDisjoint"])

    def test_category_mapping_mirrors_kotlin_and_falls_back_safely(self) -> None:
        expected = {
            "art": "Creative",
            "images": "Creative",
            "blog": "Publishing",
            "news": "Publishing",
            "political": "Publishing",
            "business": "Professional",
            "coding": "CodeHosting",
            "finance": "Commerce",
            "shopping": "Commerce",
            "gaming": "Gaming",
            "music": "Media",
            "video": "Media",
            "tech": "Developer",
            "social": "Social",
            "dating": "Social",
            "unlisted": "PublicDirectory",
        }
        for raw, category in expected.items():
            self.assertEqual(category, map_whats_my_name_category(raw.upper()))
        self.assertEqual("PublicDirectory", map_whats_my_name_category(None))
        self.assertEqual("PublicDirectory", map_whats_my_name_category(42))

    def test_conversion_validator_detects_runtime_contract_drift(self) -> None:
        record = {
            "name": "Fixture",
            "cat": "social",
            "uri_check": "https://fixture.example/{account}",
            "e_code": 200,
            "e_string": "present",
            "m_code": 404,
            "m_string": "missing",
        }
        rule = convert_whats_my_name_record(record)
        self.assertEqual([], validate_whats_my_name_conversion(rule, record))

        tampered = replace(rule, timeout_ms=5_000, exists_marker="wrong")
        issues = validate_whats_my_name_conversion(tampered, record)
        self.assertTrue(any("request policy" in issue for issue in issues))
        self.assertTrue(any("response markers" in issue for issue in issues))

        with self.assertRaisesRegex(ValueError, "NotHttps"):
            convert_whats_my_name_record(
                {**record, "uri_check": "http://fixture.example/{account}"}
            )

    def test_primary_catalog_collision_is_reported_without_inflating_source_counts(self) -> None:
        record = {
            "name": "Fixture",
            "cat": "social",
            "uri_check": "https://fixture.example/{account}",
            "e_code": 200,
            "e_string": "present",
            "m_code": 404,
            "m_string": "missing",
        }
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            data_path = root / "wmn-data.json"
            license_path = root / "LICENSE.md"
            data_path.write_text(
                json.dumps(
                    {
                        "license": ["fixture"],
                        "authors": ["fixture"],
                        "categories": ["social"],
                        "sites": [record],
                    }
                ),
                encoding="utf-8",
            )
            license_path.write_text("fixture license", encoding="utf-8")
            generated_id = convert_whats_my_name_record(record).entry.provider_id
            errors, stats = audit_whats_my_name_catalog(
                data_path,
                license_path,
                primary_catalog_ids=[generated_id],
            )

        self.assertTrue(any("collide" in error for error in errors))
        self.assertEqual(1, stats["convertedRuleCount"])
        self.assertEqual(1, stats["convertedRuleValidCount"])
        self.assertEqual(1, stats["primaryCatalogCollisionCount"])
        self.assertFalse(stats["primaryCatalogDisjoint"])


if __name__ == "__main__":
    unittest.main()
