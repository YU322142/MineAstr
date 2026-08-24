from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from mineastr_glossary import (
    GlossaryIndex,
    GlossaryProvider,
    append_glossary_instructions,
)


class GlossaryTests(unittest.TestCase):
    def test_generated_entries_keep_duplicate_english_by_registry_key(self):
        path = self._write_json(
            {
                "entries": [
                    {
                        "key": "block.create.brass_funnel",
                        "en_us": "Brass Funnel",
                        "zh_cn": "黄铜漏斗",
                    },
                    {
                        "key": "item.example.brass_funnel",
                        "en_us": "Brass Funnel",
                        "zh_cn": "示例黄铜漏斗",
                    },
                ]
            }
        )
        matches = GlossaryIndex.from_path(path).lookup("Use a Brass Funnel here")
        self.assertEqual(2, len(matches))

    def test_raw_language_catalog_is_supported(self):
        path = self._write_json(
            {
                "languages": {
                    "en_us": {"block.create.item_vault": "Item Vault"},
                    "zh_cn": {"block.create.item_vault": "物品保险库"},
                }
            }
        )
        matches = GlossaryIndex.from_path(path).lookup("Item Vault")
        self.assertEqual("物品保险库", matches[0].zh_cn)

    def test_empty_path_disables_glossary_instead_of_reading_cwd(self):
        provider = GlossaryProvider("")
        self.assertEqual([], provider.get().lookup("Item Vault"))

    def test_invalid_json_is_non_fatal_and_logged(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "glossary.json"
            path.write_text("{broken", encoding="utf-8")
            messages: list[str] = []
            provider = GlossaryProvider(str(path), logger=messages.append)
            self.assertEqual([], provider.get().lookup("Item Vault"))
            self.assertTrue(messages)

    def test_missing_configured_file_is_logged_once_and_can_appear_later(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "glossary.json"
            messages: list[str] = []
            provider = GlossaryProvider(str(path), logger=messages.append)
            self.assertEqual([], provider.get().lookup("Item Vault"))
            self.assertEqual([], provider.get().lookup("Item Vault"))
            self.assertEqual(1, len(messages))
            path.write_text(
                json.dumps(
                    {
                        "entries": [
                            {
                                "key": "block.create.item_vault",
                                "en_us": "Item Vault",
                                "zh_cn": "物品保险库",
                            }
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            self.assertEqual(
                "物品保险库", provider.get().lookup("Item Vault")[0].zh_cn
            )

    def test_provider_hot_reloads_changed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "glossary.json"
            path.write_text(
                json.dumps(
                    {
                        "entries": [
                            {"key": "a", "en_us": "Alpha", "zh_cn": "甲"}
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            provider = GlossaryProvider(str(path))
            self.assertEqual("甲", provider.get().lookup("Alpha")[0].zh_cn)
            path.write_text(
                json.dumps(
                    {
                        "entries": [
                            {"key": "b", "en_us": "Beta", "zh_cn": "乙"}
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            self.assertEqual("乙", provider.get().lookup("Beta")[0].zh_cn)

    def test_prompt_append_preserves_base_and_obeys_limit(self):
        result = append_glossary_instructions(
            "base rule", "- Item Vault = 物品保险库", max_chars=120
        )
        self.assertTrue(result.startswith("base rule"))
        self.assertLessEqual(len(result), 120)

    @staticmethod
    def _write_json(value: object) -> Path:
        directory = Path(tempfile.mkdtemp())
        path = directory / "glossary.json"
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
