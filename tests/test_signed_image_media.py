import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from urllib.parse import urlparse
import asyncio

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from test_minecraft_protocol import MinecraftPlatformAdapter
from test_z_discord_automation import MAIN


class SignedImageMediaTests(unittest.IsolatedAsyncioTestCase):
    async def test_signed_preview_is_independent_of_full_preparation_and_stays_authorized(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = Path(temporary) / "preview.gif"
            image.write_bytes(b"GIF89a" + b"p" * 32)
            release = asyncio.Event()
            async def prepare(reference):
                await release.wait()
                return image, "image/gif"
            optimizer = SimpleNamespace(prepare=prepare, prepare_preview=AsyncMock(return_value=(image, "image/gif")))
            adapter = MinecraftPlatformAdapter({"token": "unit-test-only-token"}, {}, None)
            app = web.Application()
            app.router.add_get("/mineastr/media/{filename}", adapter._handle_image_media)
            async with TestClient(TestServer(app)) as client:
                signed = adapter.image_source_url("https://example.invalid/large.gif", str(client.make_url("")), optimizer)
                parsed = urlparse(signed)
                path = parsed.path + "?" + parsed.query
                full = asyncio.create_task(client.get(path))
                try:
                    async with await asyncio.wait_for(client.get(path + "&preview=1"), 1) as response:
                        self.assertEqual(response.status, 200)
                        self.assertEqual(await response.read(), image.read_bytes())
                    self.assertFalse(full.done())
                    async with client.get((path + "&preview=1").replace("signature=", "signature=0")) as response:
                        self.assertEqual(response.status, 403)
                finally:
                    release.set()
                    (await full).release()

    async def test_platform_text_is_sent_while_media_preparation_is_blocked(self):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {}
        release = asyncio.Event()
        text_sent = asyncio.Event()
        async def prepare(reference):
            await release.wait()
            return None
        async def send(session, chain):
            text_sent.set()
            return False
        plugin._image_optimizer = SimpleNamespace(prepare=prepare)
        plugin.context = SimpleNamespace(send_message=send)
        task = asyncio.create_task(plugin._send_to_relay_session("qq:GroupMessage:42", "Hello", media=[{"url": "https://example.invalid/image.gif"}]))
        try:
            await asyncio.wait_for(text_sent.wait(), 1)
            self.assertFalse(task.done())
        finally:
            release.set()
            await asyncio.wait_for(task, 1)

    async def test_signed_media_supports_ranges_and_rejects_modified_authorization(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = Path(temporary) / "image.gif"
            image.write_bytes(b"GIF89a" + b"x" * 2000)
            optimizer = SimpleNamespace(prepare=AsyncMock(return_value=(image, "image/gif")))
            adapter = MinecraftPlatformAdapter({"token": "unit-test-only-token"}, {}, None)
            app = web.Application()
            app.router.add_get("/mineastr/media/{filename}", adapter._handle_image_media)
            async with TestClient(TestServer(app)) as client:
                signed = adapter.image_source_url("https://example.invalid/image.gif", str(client.make_url("")), optimizer)
                parsed = urlparse(signed)
                path = parsed.path + "?" + parsed.query
                self.assertNotIn(adapter.token, signed)
                async with client.get(path, headers={"Range": "bytes=0-5"}) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(await response.read(), b"GIF89a")
                    etag = response.headers["ETag"]
                async with client.get(path, headers={"Range": "bytes=6-15", "If-Range": etag}) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.headers["Content-Range"], "bytes 6-15/2006")
                    self.assertEqual(await response.read(), b"x" * 10)
                async with client.get(path.replace("signature=", "signature=0")) as response:
                    self.assertEqual(response.status, 403)

    async def test_small_remote_image_redirects_without_reencoding(self):
        adapter = MinecraftPlatformAdapter({"token": "unit-test-only-token"}, {}, None)
        optimizer = SimpleNamespace(prepare=AsyncMock(return_value=None))
        app = web.Application()
        app.router.add_get("/mineastr/media/{filename}", adapter._handle_image_media)
        async with TestClient(TestServer(app)) as client:
            signed = adapter.image_source_url("https://example.invalid/small.gif", str(client.make_url("")), optimizer)
            parsed = urlparse(signed)
            async with client.get(parsed.path + "?" + parsed.query, allow_redirects=False) as response:
                self.assertEqual(response.status, 302)
                self.assertEqual(response.headers["Location"], "https://example.invalid/small.gif")
