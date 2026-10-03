"""Inspect image headers quickly; downscale oversized media before outbound transport."""

import asyncio
import base64
import hashlib
import io
import math
import os
import tempfile
import time
from collections import OrderedDict
from pathlib import Path

import aiohttp
from PIL import Image, ImageOps

MEDIA_DIRECTORY = Path("data/plugin_data/mineastr/optimized-images")
MAX_INPUT_BYTES = 32 * 1024 * 1024
MAX_OUTPUT_BYTES = 16 * 1024 * 1024


def dimensions(prefix: bytes) -> tuple[int, int] | None:
    try:
        with Image.open(io.BytesIO(prefix)) as image:
            return image.size
    except (OSError, ValueError):
        return None


def oversized(size: tuple[int, int]) -> bool:
    return size[0] > 1920 or size[1] > 1080


def compress(data: bytes) -> tuple[bytes, str] | None:
    with Image.open(io.BytesIO(data)) as source:
        width, height = source.size
        if width * height > 32_000_000:
            raise ValueError("image pixel limit")
        if not oversized(source.size):
            return None
        count = getattr(source, "n_frames", 1)
        if count > 256:
            raise ValueError("animation frame limit")
        # Bound retained animation pixels, allowing a resolution below 1080p for long GIFs.
        scale = min(1.0, 1920 / width, 1080 / height,
                    math.sqrt(4_194_304 / (width * height * count)) if count > 1 else 1.0)
        target = (max(1, int(width * scale)), max(1, int(height * scale)))
        output = io.BytesIO()
        if count > 1:
            if source.format != "GIF":
                raise ValueError("unsupported oversized animation format")
            frames, durations = [], []
            loop = source.info.get("loop")
            for index in range(count):
                source.seek(index)
                # Pillow composites disposal before this conversion; encode complete resized frames.
                frames.append(source.convert("RGBA").resize(target, Image.Resampling.LANCZOS))
                durations.append(source.info.get("duration", 100))
            options = {} if loop is None else {"loop": loop}
            frames[0].save(output, format="GIF", save_all=True, append_images=frames[1:],
                           duration=durations, disposal=2, optimize=False, **options)
            mime = "image/gif"
        else:
            image = ImageOps.exif_transpose(source).convert("RGBA")
            image.thumbnail((1920, 1080), Image.Resampling.LANCZOS)
            transparent = image.getextrema()[3][0] < 255
            mime = "image/png" if transparent else "image/jpeg"
            for attempt in range(8):
                output = io.BytesIO()
                if transparent:
                    image.save(output, format="PNG", optimize=True)
                else:
                    image.convert("RGB").save(output, format="JPEG", quality=85, optimize=True)
                if output.tell() <= 1400 * 1024:
                    break
                image = image.resize((max(1, image.width * 3 // 4), max(1, image.height * 3 // 4)), Image.Resampling.LANCZOS)
            else:
                raise ValueError("static image byte limit")
        result = output.getvalue()
        if len(result) > MAX_OUTPUT_BYTES:
            raise ValueError("compressed image byte limit")
        return result, mime


class ImageOptimizer:
    def __init__(self, directory: Path = MEDIA_DIRECTORY):
        self.directory = directory
        self.slots = asyncio.Semaphore(4)
        self.cpu_slots = asyncio.Semaphore(2)
        self.range_slots = asyncio.Semaphore(8)
        self.cache = OrderedDict()
        self.inflight = {}

    async def prepare(self, reference: str) -> tuple[Path, str] | None:
        cached = self.cache.get(reference)
        if cached and cached[0] > time.monotonic() and (cached[1] is None or cached[1][0].is_file()):
            self.cache.move_to_end(reference)
            return cached[1]
        task = self.inflight.get(reference)
        if task is None:
            if len(self.inflight) >= 32:
                raise ValueError("image optimization queue full")
            task = asyncio.create_task(self._prepare(reference), name="mineastr-image-optimize")
            self.inflight[reference] = task
            task.add_done_callback(lambda done: self.inflight.pop(reference, None))
        return await asyncio.shield(task)

    async def _prepare(self, reference: str) -> tuple[Path, str] | None:
        async with self.slots:
            if reference.startswith(("https://", "http://")):
                data = await self._fetch_oversized(reference)
            elif reference.startswith(("base64://", "data:")):
                encoded = reference.split(";base64,", 1)[-1].removeprefix("base64://")
                if len(encoded) > MAX_INPUT_BYTES * 4 // 3 + 4:
                    raise ValueError("image input byte limit")
                data = base64.b64decode(encoded, validate=True)
            else:
                path = Path(reference.removeprefix("file://"))
                if not path.is_file():
                    return None
                if path.stat().st_size > MAX_INPUT_BYTES:
                    raise ValueError("image input byte limit")
                data = await asyncio.to_thread(path.read_bytes)
            result = None
            if data is not None:
                async with self.cpu_slots:
                    converted = await asyncio.to_thread(compress, data)
                    if converted:
                        result = await asyncio.to_thread(self._store, *converted)
            self.cache[reference] = (time.monotonic() + 3600, result)
            self.cache.move_to_end(reference)
            while len(self.cache) > 256:
                self.cache.popitem(last=False)
            return result

    async def _fetch_oversized(self, reference: str) -> bytes | None:
        timeout = aiohttp.ClientTimeout(total=20, sock_connect=2, sock_read=5)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(reference, headers={"Accept-Encoding": "identity"}) as response:
                response.raise_for_status()
                data = bytearray()
                size = None
                async for block in response.content.iter_chunked(16384):
                    data.extend(block)
                    if len(data) > MAX_INPUT_BYTES:
                        raise ValueError("image input byte limit")
                    if size is None:
                        size = dimensions(data)
                        if size is not None and not oversized(size):
                            return None  # Stop after the header; original URL keeps client streaming/range support.
                        if size is None and len(data) > 65536:
                            raise ValueError("image header unavailable")
                    if size is not None and oversized(size) and bytes(data[:6]) in {b"GIF87a", b"GIF89a"}:
                        total = response.content_length or 0
                        validator = response.headers.get("ETag", "")
                        if validator.startswith("W/"):
                            validator = ""
                        validator = validator or response.headers.get("Last-Modified", "")
                        if 524288 < total <= MAX_INPUT_BYTES and validator and "bytes" in response.headers.get("Accept-Ranges", "").lower():
                            response.close()
                            try:
                                return await self._fetch_ranges(session, reference, bytes(data), total, validator)
                            except (aiohttp.ClientError, ValueError, TimeoutError):
                                return await self._fetch_full(session, reference)
                return bytes(data)

    async def _fetch_full(self, session, reference: str) -> bytes:
        async with session.get(reference, headers={"Accept-Encoding": "identity"}) as response:
            response.raise_for_status()
            data = bytearray()
            async for block in response.content.iter_chunked(65536):
                data.extend(block)
                if len(data) > MAX_INPUT_BYTES:
                    raise ValueError("image input byte limit")
            return bytes(data)

    async def _fetch_ranges(self, session, reference: str, prefix: bytes, total: int, validator: str) -> bytes:
        size = max(1, (total - len(prefix) + 3) // 4)
        async def part(start, end):
            async with self.range_slots:
                async with session.get(reference, headers={"Range": f"bytes={start}-{end}", "If-Range": validator,
                                                          "Accept-Encoding": "identity"}) as response:
                    expected = f"bytes {start}-{end}/{total}"
                    if response.status != 206 or response.headers.get("Content-Range") != expected or validator not in {
                        response.headers.get("ETag"), response.headers.get("Last-Modified")
                    }:
                        raise ValueError("image range changed")
                    data = bytearray()
                    async for block in response.content.iter_chunked(65536):
                        data.extend(block)
                        if len(data) > end - start + 1:
                            raise ValueError("image range byte limit")
                    if len(data) != end - start + 1:
                        raise ValueError("image range incomplete")
                    return bytes(data)
        tasks = [asyncio.create_task(part(start, min(total - 1, start + size - 1)))
                 for start in range(len(prefix), total, size)]
        try:
            blocks = await asyncio.gather(*tasks)
            return prefix + b"".join(blocks)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    def _store(self, data: bytes, mime: str) -> tuple[Path, str]:
        self.directory.mkdir(parents=True, exist_ok=True)
        suffix = {"image/gif": ".gif", "image/jpeg": ".jpg", "image/png": ".png"}[mime]
        destination = self.directory / (hashlib.sha256(data).hexdigest() + suffix)
        descriptor, temporary = tempfile.mkstemp(dir=self.directory, prefix="image-", suffix=".tmp")
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        files = sorted((p for p in self.directory.iterdir() if p.suffix in {".gif", ".jpg", ".png"}), key=lambda p: p.stat().st_mtime, reverse=True)
        retained = 0
        for file in files:
            retained += file.stat().st_size
            if file != destination and (retained > 256 * 1024 * 1024 or file.stat().st_mtime < time.time() - 86400):
                file.unlink(missing_ok=True)
        return destination, mime

    async def close(self):
        tasks = tuple(self.inflight.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.inflight.clear()
