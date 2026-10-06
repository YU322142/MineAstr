import asyncio
import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from PIL import Image

from mineastr_media import QQImageSource, qq_images, unique_media
from mineastr_image_optimizer import ImageOptimizer
from minecraft_adapter import MinecraftPlatformAdapter
from test_z_discord_automation import MAIN


class QQImageSourceTests(unittest.IsolatedAsyncioTestCase):
    def encoded(self, format="JPEG", size=(806, 876), animated=False):
        output = io.BytesIO()
        first = Image.new("RGB", size, "red")
        options = {"save_all": True, "append_images": [Image.new("RGB", size, "blue")], "duration": [40, 60], "loop": 0} if animated else {}
        first.save(output, format, **options)
        return output.getvalue()

    async def test_broken_qq_origin_uses_complete_local_jpeg_over_signed_http(self):
        with tempfile.TemporaryDirectory() as temporary:
            local = Path(temporary) / "napcat-original"
            original = self.encoded()
            local.write_bytes(original)
            optimizer = ImageOptimizer(Path(temporary) / "optimized")
            bot = SimpleNamespace(call_action=AsyncMock(return_value={"file": str(local), "url": "https://gchat.qpic.cn/broken"}))
            source = QQImageSource(optimizer, bot, "qq-file-id", "https://gchat.qpic.cn/broken")
            adapter = MinecraftPlatformAdapter({"token": "test-image-secret"}, {}, None)
            app = web.Application()
            app.router.add_get("/mineastr/media/{filename}", adapter._handle_image_media)
            async with TestClient(TestServer(app)) as client:
                signed = adapter.image_source_url(source.reference, str(client.make_url("")), source)
                bot.call_action.assert_not_called()
                self.assertNotIn(str(local), signed)
                response = await client.get(signed.removeprefix(str(client.make_url(""))), headers={"Range": "bytes=0-262143"})
                self.assertEqual(response.status, 206)
                received = await response.read()
                self.assertEqual(received, original)
                with Image.open(io.BytesIO(received)) as image:
                    image.load()
                    self.assertEqual(image.size, (806, 876))
            bot.call_action.assert_awaited_once_with("get_image", file="qq-file-id")
            await optimizer.close()

    async def test_preview_and_full_gif_share_one_lookup_and_preserve_animation(self):
        with tempfile.TemporaryDirectory() as temporary:
            local = Path(temporary) / "animation"
            local.write_bytes(self.encoded("GIF", (32, 24), True))
            optimizer = ImageOptimizer(Path(temporary) / "optimized")
            bot = SimpleNamespace(call_action=AsyncMock(return_value={"file": str(local)}))
            source = QQImageSource(optimizer, bot, "gif-handle", "https://gchat.qpic.cn/broken")
            first, full = await asyncio.gather(source.prepare_preview(source.reference), source.prepare(source.reference))
            with Image.open(first[0]) as preview:
                self.assertEqual(preview.n_frames, 1)
            with Image.open(full[0]) as animation:
                self.assertEqual(animation.n_frames, 2)
                self.assertEqual(animation.info["loop"], 0)
            bot.call_action.assert_awaited_once()
            await optimizer.close()

    async def test_missing_local_cache_uses_refreshed_public_url(self):
        optimizer = SimpleNamespace(prepare=AsyncMock(return_value=None))
        bot = SimpleNamespace(call_action=AsyncMock(return_value={"file": "/missing/napcat-image", "url": "https://qq.example/fresh"}))
        source = QQImageSource(optimizer, bot, "file-id", "https://qq.example/old")
        await source.prepare(source.reference)
        optimizer.prepare.assert_awaited_once_with("https://qq.example/fresh")

    async def test_lookup_error_keeps_original_without_exposing_credentials(self):
        optimizer = SimpleNamespace(prepare=AsyncMock(return_value=None))
        bot = SimpleNamespace(call_action=AsyncMock(side_effect=RuntimeError("private URL must not be logged")))
        source = QQImageSource(optimizer, bot, "file-id", "https://qq.example/old")
        with self.assertLogs("mineastr_media", level="WARNING") as logs:
            await source.prepare(source.reference)
        self.assertNotIn("private URL", " ".join(logs.output))
        optimizer.prepare.assert_awaited_once_with(source.reference)

    async def test_game_payload_keeps_lookup_outside_message_delivery(self):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {"bridge_settings": {"relay_images_to_game": True, "game_image_public_base_url": "http://example.test"}}
        plugin._image_optimizer = SimpleNamespace()
        adapter = MinecraftPlatformAdapter({"token": "test-image-secret"}, {}, None)
        plugin._minecraft_adapter = lambda: adapter
        bot = SimpleNamespace(call_action=AsyncMock())
        payload = await plugin._game_media_payloads([{"url": "https://qq.example/broken", "qq_file": "opaque-file"}], qq_bot=bot)
        bot.call_action.assert_not_called()
        self.assertNotIn("opaque-file", payload[0]["url"])
        self.assertIsInstance(next(iter(adapter._image_sources.values()))[1], MAIN.QQImageSource)

    def test_raw_file_handle_survives_component_deduplication(self):
        url = "https://gchat.qpic.cn/broken"
        raw = {"message": [{"type": "image", "data": {"url": url, "file": "qq-handle"}}]}
        component = {"type": "image", "url": url, "name": "original-name"}
        media = unique_media([component] + qq_images(raw))
        self.assertEqual(media, [{"type": "image", "url": url, "name": "original-name", "qq_file": "qq-handle"}])
        self.assertNotIn("qq_file", component)


if __name__ == "__main__":
    unittest.main()
