"""Exercise extraction and actual fanout chains without sending platform messages."""
import asyncio
import importlib
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import web
from aiohttp.test_utils import TestServer
from PIL import Image as PillowImage

import test_minecraft_protocol  # Install the adapter's shared framework stubs before the facade stubs.
from test_z_discord_automation import MAIN
from mineastr_image_optimizer import ImageOptimizer

FANOUT = importlib.import_module(f"{MAIN.__package__}.mineastr_fanout")
MEDIA = importlib.import_module(f"{MAIN.__package__}.mineastr_plugin_media")


class Image:
    def __init__(self, file="", filename="image", url=""):
        self.file, self.filename, self.url = file, filename, url

    @classmethod
    def fromURL(cls, url, **kwargs):
        return cls(url=url, **kwargs)


class PlatformImageDedupTests(unittest.IsolatedAsyncioTestCase):
    def make_plugin(self):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {"bridge_settings": {"max_relay_length": 500}}
        plugin.context = NS(send_message=AsyncMock(return_value=True))
        self.enterContext(patch.object(FANOUT, "Image", Image))
        self.enterContext(patch.object(MEDIA, "Image", Image))
        return plugin

    def sent_images(self, plugin):
        return [(call.args[0], [part for part in call.args[1].chain if isinstance(part, Image)]) for call in plugin.context.send_message.await_args_list]

    async def test_duplicate_entries_reach_each_platform_once(self):
        plugin = self.make_plugin()
        item = {"type": "image", "url": "https://example.com/one.png", "name": "one.png"}
        for session in ("default:GroupMessage:1", "discord:GroupMessage:2"):
            await plugin._send_to_relay_session(session, "", media=[item, dict(item)])
        self.assertEqual([len(images) for _, images in self.sent_images(plugin)], [1, 1])

    async def test_content_identical_optimized_images_are_attached_only_once(self):
        plugin = self.make_plugin()
        plugin._image_optimizer = NS(prepare=AsyncMock(return_value=(Path("cache/same-content.gif"), "image/gif")))
        await plugin._send_to_relay_session("discord:GroupMessage:2", "caption", media=[
            {"url": "https://example.com/original.gif", "name": "one.gif"},
            {"url": "https://example.net/mirror.gif", "name": "two.gif"},
        ])
        sends = self.sent_images(plugin)
        self.assertEqual([len(images) for _, images in sends], [0, 1])
        self.assertEqual(Path(sends[-1][1][0].file), Path("cache/same-content.gif"))

    async def test_discord_component_proxy_and_raw_attachment_form_one_outbound_image(self):
        plugin = self.make_plugin()
        original = "https://cdn.discordapp.com/attachments/10/20/loop.gif?ex=abc&hm=original"
        proxy = "https://media.discordapp.net/attachments/10/20/loop.gif?width=512&height=512"
        event = NS(message_obj=NS(message=[Image(file=proxy, filename="loop.gif")], raw_message=NS(
            attachments=[NS(url=original, filename="loop.gif", content_type="image/gif")], embeds=[], stickers=[])))
        media = plugin._event_media(event)
        await plugin._send_to_relay_session("default:GroupMessage:1", "", media=media)
        images = self.sent_images(plugin)[0][1]
        self.assertEqual(len(images), 1)
        self.assertEqual(images[0].url, original)

    async def test_independent_messages_can_repeat_the_same_picture(self):
        plugin = self.make_plugin()
        media = [{"url": "https://example.com/repeated.png"}]
        for _ in range(2):
            await plugin._send_to_relay_session("default:GroupMessage:1", "", media=media)
        self.assertEqual([len(images) for _, images in self.sent_images(plugin)], [1, 1])

    async def test_slow_image_preparation_does_not_delay_text_or_duplicate_it(self):
        plugin = self.make_plugin()
        entered, release = asyncio.Event(), asyncio.Event()
        async def prepare(reference):
            entered.set()
            await release.wait()
            return Path("cache/one.png"), "image/png"
        plugin._image_optimizer = NS(prepare=prepare)
        task = asyncio.create_task(plugin._send_to_relay_session("discord:GroupMessage:2", "caption", media=[{"url": "https://example.com/one.png"}]))
        try:
            await asyncio.wait_for(entered.wait(), 1)
            self.assertEqual([len(images) for _, images in self.sent_images(plugin)], [0])
            self.assertEqual(plugin.context.send_message.await_args.args[1].chain[0].text, "caption")
        finally:
            release.set()
            await task
        self.assertEqual([len(images) for _, images in self.sent_images(plugin)], [0, 1])

    async def test_reply_with_media_stays_one_platform_message(self):
        plugin = self.make_plugin()
        plugin._image_optimizer = NS(prepare=AsyncMock(return_value=(Path("cache/one.png"), "image/png")))
        await plugin._send_to_relay_session(
            "discord:GroupMessage:2",
            "answer",
            media=[{"url": "https://example.com/one.png", "name": "one.png"}],
            reply_context={"sender": "Alice", "text": "quoted image"},
        )
        sends = self.sent_images(plugin)
        self.assertEqual(len(sends), 1)
        self.assertEqual(len(sends[0][1]), 1)
        self.assertIn("quoted image", plugin.context.send_message.await_args.args[1].chain[0].text)

    async def test_real_http_gif_aliases_share_one_animated_outbound_attachment(self):
        plugin = self.make_plugin()
        encoded = io.BytesIO()
        PillowImage.new("RGB", (16, 16), "red").save(encoded, format="GIF", save_all=True,
            append_images=[PillowImage.new("RGB", (16, 16), "blue")], duration=[50, 50], loop=0)
        async def image_response(request):
            return web.Response(body=encoded.getvalue(), content_type="image/gif")
        app = web.Application()
        app.router.add_get("/first.gif", image_response)
        app.router.add_get("/second.gif", image_response)
        with tempfile.TemporaryDirectory() as temporary:
            plugin._image_optimizer = ImageOptimizer(Path(temporary))
            try:
                async with TestServer(app) as server:
                    await plugin._send_to_relay_session("discord:GroupMessage:2", "", media=[
                        {"url": str(server.make_url("/first.gif"))},
                        {"url": str(server.make_url("/second.gif"))},
                    ])
                images = self.sent_images(plugin)[0][1]
                self.assertEqual(len(images), 1)
                with PillowImage.open(images[0].file) as image:
                    self.assertEqual(image.n_frames, 2)
            finally:
                await plugin._image_optimizer.close()
