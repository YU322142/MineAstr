"""等义双语图片的跳过、缓存和协议回传 / Bilingual image no-op, caching and transport."""
import base64
import copy
import json
from types import SimpleNamespace as NS
import unittest
from unittest.mock import AsyncMock

from test_minecraft_protocol import FakeWebSocket, MinecraftPlatformAdapter
from test_z_discord_automation import MAIN


class BilingualImageTests(unittest.IsolatedAsyncioTestCase):
    def make_plugin(self, output, languages="zh_cn", fallback=None):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {
            group: {key: copy.deepcopy(MAIN.AQQBOT_DEFAULT_CONFIG[key]) for key in keys}
            for group, keys in MAIN.CONFIG_GROUP_KEYS.items()
        }
        plugin.config["bridge_settings"].update(
            game_translation_enabled=True, game_translation_languages=languages,
            game_translation_show_original=True, game_translation_provider_id="vision",
            game_translation_fallback_provider_ids=["fallback"] if fallback else [],
        )
        primary = NS(text_chat=AsyncMock(return_value=NS(completion_text=json.dumps(output))))
        models = {"vision": primary}
        if fallback:
            models["fallback"] = NS(text_chat=AsyncMock(return_value=NS(completion_text=json.dumps(fallback))))
        plugin.context = NS(get_provider_by_id=models.get, get_using_provider=lambda origin: primary)
        plugin._append_translation_glossary = AsyncMock(return_value="")
        plugin._translate_text = AsyncMock(side_effect=AssertionError("Unexpected glossary correction"))
        return plugin, models

    def request(self, **extra):
        return {"server_id": "survival", "image_base64": base64.b64encode(b"image").decode(), **extra}

    def skip_output(self):
        return {"source_language": "multilingual", "source_text": "出生点仓库\nSpawn Warehouse",
                "already_bilingual": True, "translations": {}}

    async def test_equivalent_bilingual_image_is_cached_without_glossary_or_ocr_overlay(self):
        plugin, models = self.make_plugin(self.skip_output())
        first = await plugin._translate_image_request(self.request(prompt="Translate everything"))
        second = await plugin._translate_image_request(self.request(prompt="Translate everything"))
        self.assertTrue(first["already_bilingual"])
        self.assertEqual(first["translations"], {})
        self.assertEqual(first["source_text"], "")
        self.assertFalse(first["show_original"])
        self.assertEqual(first, second)
        first["translations"]["zh_cn"] = "mutated"
        self.assertEqual((await plugin._translate_image_request(self.request(prompt="Translate everything")))["translations"], {})
        models["vision"].text_chat.assert_awaited_once()
        plugin._append_translation_glossary.assert_not_awaited()
        plugin._translate_text.assert_not_awaited()
        arguments = models["vision"].text_chat.await_args.kwargs
        self.assertEqual(len(arguments["image_urls"]), 1)
        self.assertIn("all requested target languages already represented", arguments["system_prompt"])
        self.assertIn("additional untranslated content", arguments["system_prompt"])

    async def test_non_equivalent_or_partial_bilingual_image_keeps_translation(self):
        for source in ("出口\nEntrance", "仓库 Warehouse\n请勿进入", "Create 物品保险库"):
            with self.subTest(source=source):
                output = {"source_language": "multilingual", "source_text": source,
                          "translations": {"zh_cn": "完整中文内容"}, "already_bilingual": False}
                plugin, _ = self.make_plugin(output)
                result = await plugin._translate_image_request(self.request())
                self.assertEqual(result["translations"], {"zh_cn": "完整中文内容"})
                self.assertEqual(result["source_text"], source)
                self.assertNotIn("already_bilingual", result)
                plugin._append_translation_glossary.assert_awaited_once()

    async def test_bilingual_image_still_translates_uncovered_target_and_separates_cache(self):
        plugin, models = self.make_plugin(self.skip_output())
        self.assertTrue((await plugin._translate_image_request(self.request()))["already_bilingual"])
        models["vision"].text_chat.return_value = NS(completion_text=json.dumps({
            "source_language": "multilingual", "source_text": "出生点仓库\nSpawn Warehouse",
            "translations": {"fr_fr": "Entrepôt du point de départ"},
        }))
        result = await plugin._translate_image_request(self.request(target_languages=["fr_fr"]))
        self.assertNotIn("already_bilingual", result)
        self.assertEqual(result["translations"], {"fr_fr": "Entrepôt du point de départ"})
        self.assertEqual(models["vision"].text_chat.await_count, 2)

    async def test_translation_is_preserved_when_provider_also_sets_a_skip_marker(self):
        output = {**self.skip_output(), "translations": {"zh_cn": "仍需翻译的正文"}}
        plugin, _ = self.make_plugin(output)
        result = await plugin._translate_image_request(self.request())
        self.assertEqual(result["translations"], {"zh_cn": "仍需翻译的正文"})
        self.assertNotIn("already_bilingual", result)
        self.assertEqual(result["source_text"], output["source_text"])

    async def test_invalid_skip_claims_are_retried_instead_of_cached(self):
        invalid = [
            {**self.skip_output(), "already_bilingual": "true"},
            {**self.skip_output(), "source_language": "en_us"},
            {**self.skip_output(), "source_text": ""},
        ]
        for output in invalid:
            with self.subTest(output=output):
                plugin, models = self.make_plugin(output, fallback=self.skip_output())
                result = await plugin._translate_image_request(self.request())
                self.assertTrue(result["already_bilingual"])
                models["vision"].text_chat.assert_awaited_once()
                models["fallback"].text_chat.assert_awaited_once()

    async def test_failed_decision_does_not_poison_the_image_cache(self):
        plugin, models = self.make_plugin({"source_language": "multilingual", "translations": {}})
        self.assertEqual(await plugin._translate_image_request(self.request()), {})
        failed_attempts = models["vision"].text_chat.await_count
        models["vision"].text_chat.return_value = NS(completion_text=json.dumps(self.skip_output()))
        self.assertTrue((await plugin._translate_image_request(self.request()))["already_bilingual"])
        self.assertEqual(models["vision"].text_chat.await_count, failed_attempts + 1)

    async def test_image_skip_reaches_websocket_as_success_not_failure(self):
        plugin, models = self.make_plugin(self.skip_output())
        adapter = MinecraftPlatformAdapter({}, {}, None)
        socket = FakeWebSocket()
        await adapter.connection_manager.register(socket, {"server_id": "survival"})
        adapter.set_image_translation_handler(plugin._translate_image_request)
        for message_id in ("first", "second"):
            await adapter._handle_image_translate_request(socket, self.request(message_id=message_id))
            response = socket.sent[-1]
            self.assertEqual(response["message_id"], message_id)
            self.assertEqual(response["type"], "image_translate_result")
            self.assertTrue(response["ok"])
            self.assertTrue(response["already_bilingual"])
            self.assertEqual(response["translations"], {})
            self.assertEqual(response["source_text"], "")
            self.assertNotIn("error", response)
        models["vision"].text_chat.assert_awaited_once()
