import asyncio
import io
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from aiohttp import web
from aiohttp.test_utils import TestServer

from mineastr_image_optimizer import ImageOptimizer, compress, first_gif_frame, MAX_OUTPUT_BYTES


class ImageOptimizerTests(unittest.IsolatedAsyncioTestCase):
    def animation(self, count=2, size=(32, 24)):
        frames = [Image.new("RGB", size, (index % 256, index // 256, 100)) for index in range(count)]
        output = io.BytesIO()
        frames[0].save(output, "GIF", save_all=True, append_images=frames[1:], duration=[30 if i % 2 == 0 else 70 for i in range(count)], loop=0, optimize=False)
        return output.getvalue()

    def test_byte_large_gif_is_compressed_even_below_1080p(self):
        data, mime = compress(self.animation() + b"\0" * (MAX_OUTPUT_BYTES + 1))
        self.assertLess(len(data), MAX_OUTPUT_BYTES)
        self.assertEqual(mime, "image/gif")
        with Image.open(io.BytesIO(data)) as image:
            self.assertEqual(image.n_frames, 2)
            self.assertEqual(image.info["loop"], 0)

    def test_long_gif_samples_frames_and_preserves_total_duration(self):
        data, mime = compress(self.animation(512))
        with Image.open(io.BytesIO(data)) as image:
            self.assertEqual(image.n_frames, 256)
            self.assertEqual(image.info["loop"], 0)
            duration = 0
            for index in range(image.n_frames):
                image.seek(index)
                duration += image.info["duration"]
            self.assertEqual(duration, 25600)

    def test_preview_requires_complete_first_image_and_ignores_later_frames(self):
        data = self.animation()
        frame = first_gif_frame(data)
        self.assertIsNotNone(frame)
        self.assertLess(len(frame), len(data))
        for length in range(len(frame) - 1):
            self.assertIsNone(first_gif_frame(data[:length]))
        with Image.open(io.BytesIO(frame)) as image:
            self.assertEqual(image.n_frames, 1)
            image.load()

    async def test_local_gif_above_old_32mb_limit_has_preview_and_complete_animation(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "large.png"
            source.write_bytes(self.animation() + b"\0" * (33 * 1024 * 1024))
            optimizer = ImageOptimizer(Path(temporary) / "cache")
            preview, full = await asyncio.gather(optimizer.prepare_preview(str(source)), optimizer.prepare(str(source)))
            with Image.open(preview[0]) as image:
                self.assertEqual(image.n_frames, 1)
            with Image.open(full[0]) as image:
                self.assertEqual(image.n_frames, 2)
            await optimizer.close()

    async def test_remote_preview_arrives_while_full_download_is_blocked(self):
        data = self.animation()
        first = first_gif_frame(data)
        release = asyncio.Event()
        full_started = asyncio.Event()
        async def serve(request):
            response = web.StreamResponse(headers={"Content-Length": str(len(data))})
            await response.prepare(request)
            await response.write(data[:len(first) - 1])
            if not request.headers.get("Range"):
                full_started.set()
            await release.wait()
            try:
                await response.write(data[len(first) - 1:])
                await response.write_eof()
            except (ConnectionResetError, RuntimeError):
                pass
            return response
        app = web.Application()
        app.router.add_get("/large.png", serve)
        with tempfile.TemporaryDirectory() as temporary:
            async with TestServer(app) as server:
                optimizer = ImageOptimizer(Path(temporary) / "cache")
                reference = str(server.make_url("/large.png"))
                full = asyncio.create_task(optimizer.prepare(reference))
                try:
                    await asyncio.wait_for(full_started.wait(), 2)
                    preview = await asyncio.wait_for(optimizer.prepare_preview(reference), 2)
                    self.assertTrue(preview[0].is_file())
                    self.assertFalse(full.done())
                finally:
                    release.set()
                    await asyncio.wait_for(full, 2)
                    await optimizer.close()

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
