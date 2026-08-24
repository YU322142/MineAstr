import importlib.util
from pathlib import Path
import unittest


SCRIPT_PATH = (
    Path(__file__).resolve().parents[1] / "scripts" / "build_translation_glossary.py"
)
SPEC = importlib.util.spec_from_file_location("mineastr_glossary_builder", SCRIPT_PATH)
BUILDER = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(BUILDER)


class GlossaryBuilderTests(unittest.TestCase):
    def test_keeps_actual_display_names_including_nested_registry_names(self):
        self.assertTrue(
            BUILDER.is_important(
                "block.create.item_vault", "Item Vault", "物品保险库"
            )
        )
        self.assertTrue(
            BUILDER.is_important(
                "item.apotheosis.gem.irons_spellbooks:blood",
                "Blood Gem",
                "鲜血宝石",
            )
        )

    def test_excludes_tooltips_descriptions_and_identity_mappings(self):
        self.assertFalse(
            BUILDER.is_important(
                "block.create.railway_casing.tooltip",
                "TRAIN CASING",
                "列车机壳",
            )
        )
        self.assertFalse(
            BUILDER.is_important(
                "effect.example.speed.description",
                "Makes the player move faster.",
                "使玩家移动得更快。",
            )
        )
        self.assertFalse(
            BUILDER.is_important("enchantment.level.1", "I", "I")
        )


if __name__ == "__main__":
    unittest.main()
