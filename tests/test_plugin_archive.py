"""验证交付安装包的真实依赖闭包 / Validate imports in the shipped ZIP."""

import ast
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


class PluginArchiveTests(unittest.TestCase):
    def test_archive_contains_every_local_import_including_deferred_image_modules(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location(
            "package_plugin", root / "scripts/package_plugin.py"
        )
        packager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(packager)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "plugin.zip"
            packager.build_archive(root, output)
            with zipfile.ZipFile(output) as archive:
                prefix = packager.PLUGIN_DIR + "/"
                self.assertEqual(archive.namelist()[0], prefix)
                modules = {
                    Path(name).stem: archive.read(name).decode("utf-8-sig")
                    for name in archive.namelist()
                    if name.endswith(".py")
                }
                for module, source in modules.items():
                    for node in ast.walk(ast.parse(source)):
                        if (
                            isinstance(node, ast.ImportFrom)
                            and node.level == 1
                            and node.module
                        ):
                            with self.subTest(module=module, dependency=node.module):
                                self.assertIn(node.module.split(".")[0], modules.keys())
                self.assertIn("mineastr_image_optimizer", modules)
                self.assertIn("mineastr_media", modules)
                self.assertIn("mineastr_bindings", modules)
