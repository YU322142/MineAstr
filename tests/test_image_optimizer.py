import asyncio
import io
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from aiohttp import web
from aiohttp.test_utils import TestServer

from mineastr_image_optimizer import ImageOptimizer, compress


class ImageOptimizerTests(unittest.IsolatedAsyncioTestCase):
    def image(self, size, color="red"):
        output = io.BytesIO()
        Image.new("RGBA", size, color).save(output, "PNG")
        return output.getvalue()

    def test_small_images_preserve_original_and_large_portrait_fits_1080p(self):
        self.assertIsNone(compress(self.image((480, 408))))
        data, mime = compress(self.image((2160, 3840)))
        with Image.open(io.BytesIO(data)) as image:
            self.assertLessEqual(image.width, 1920)
            self.assertLessEqual(image.height, 1080)
            self.assertAlmostEqual(image.width / image.height, 2160 / 3840, places=2)
        self.assertEqual(mime, "image/jpeg")

    def test_gif_resize_retains_animation_durations_loop_and_transparency(self):
        first = Image.new("RGBA", (2000, 1100), (0, 0, 0, 0))
        first.paste((255, 0, 0), (100, 100, 900, 900))
        second = first.copy()
        second.paste((0, 0, 255), (100, 100, 900, 900))
        output = io.BytesIO()
        first.save(output, "GIF", save_all=True, append_images=[second], duration=[30, 70], loop=0, disposal=2)
        data, mime = compress(output.getvalue())
        with Image.open(io.BytesIO(data)) as image:
            self.assertEqual(image.n_frames, 2)
            self.assertEqual(image.info["loop"], 0)
            self.assertLessEqual(image.width, 1920)
            self.assertLessEqual(image.height, 1080)
            self.assertEqual(image.info["duration"], 30)
            self.assertEqual(image.convert("RGBA").getpixel((0, 0))[3], 0)
            image.seek(1)
            self.assertEqual(image.info["duration"], 70)
        self.assertEqual(mime, "image/gif")

    async def test_concurrent_identical_sources_share_one_conversion_and_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source.png"
            source.write_bytes(self.image((3840, 2160)))
            optimizer = ImageOptimizer(Path(temporary) / "cache")
            first, second = await asyncio.gather(optimizer.prepare(str(source)), optimizer.prepare(str(source)))
            self.assertEqual(first, second)
            self.assertEqual(len(list((Path(temporary) / "cache").glob("*.jpg"))), 1)
            self.assertEqual(await optimizer.prepare(str(source)), first)
            await optimizer.close()

    async def test_large_remote_gif_downloads_ranges_concurrently(self):
        encoded = io.BytesIO()
        Image.new("RGB", (2000, 1100), "red").save(encoded, "GIF")
        data = encoded.getvalue() + b"\0" * 800_000
        entered = 0
        ready = asyncio.Event()
        async def serve(request):
            nonlocal entered
            headers = {"Accept-Ranges": "bytes", "ETag": '"fixture-v1"'}
            if "Range" not in request.headers:
                return web.Response(body=data, headers=headers)
            start, end = map(int, request.headers["Range"][6:].split("-"))
            entered += 1
            if entered >= 2:
                ready.set()
            await asyncio.wait_for(ready.wait(), 2)
            headers["Content-Range"] = f"bytes {start}-{end}/{len(data)}"
            return web.Response(status=206, body=data[start:end + 1], headers=headers)
        app = web.Application()
        app.router.add_get("/image.gif", serve)
        async with TestServer(app) as server:
            optimizer = ImageOptimizer()
            fetched = await optimizer._fetch_oversized(str(server.make_url("/image.gif")))
            self.assertEqual(fetched, data)
            self.assertEqual(entered, 4)
            await optimizer.close()
