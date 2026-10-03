"""Inspect image headers quickly; downscale oversized media before outbound transport."""

import asyncio
import base64
import hashlib
import io
import math
import os
import tempfile
import threading
import time
from collections import OrderedDict
from pathlib import Path

import aiohttp
from PIL import Image, ImageOps

MEDIA_DIRECTORY = Path("data/plugin_data/mineastr/optimized-images")
MAX_INPUT_BYTES = 128 * 1024 * 1024
MAX_OUTPUT_BYTES = 16 * 1024 * 1024
MAX_PREVIEW_BYTES = 8 * 1024 * 1024


def first_gif_frame(data: bytes | bytearray) -> bytes | None:
    """Return a complete standalone first image; never decode truncated LZW."""
    if len(data) < 13 or data[:6] not in (b"GIF87a", b"GIF89a"):
        return None
    position = 13 + (3 * (1 << ((data[10] & 7) + 1)) if data[10] & 128 else 0)
    def blocks(position):
        while position < len(data):
            count = data[position]
            position += 1
            if not count:
                return position
            position += count
            if position > len(data):
                return None
        return None
    while position < len(data):
        marker = data[position]
        position += 1
        if marker == 0x21:
            if position >= len(data):
                return None
            position = blocks(position + 1)
            if position is None:
                return None
        elif marker == 0x2c:
            if position + 9 > len(data):
                return None
            packed = data[position + 8]
            position += 9 + (3 * (1 << ((packed & 7) + 1)) if packed & 128 else 0)
            if position >= len(data):
                return None
            position = blocks(position + 1)
            return None if position is None else bytes(data[:position]) + b";"
        else:
            return None
    return None


def preview_gif(data: bytes) -> tuple[bytes, str]:
    with Image.open(io.BytesIO(data)) as source:
        if source.width * source.height > 32_000_000:
            raise ValueError("image pixel limit")
        image = source.convert("RGBA")
        image.thumbnail((512, 512), Image.Resampling.LANCZOS)
        output = io.BytesIO()
        image.save(output, "GIF")
        return output.getvalue(), "image/gif"


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
        count = getattr(source, "n_frames", 1)
        gif = source.format == "GIF"
        needs_conversion = oversized(source.size) or (not gif and len(data) > 1400 * 1024) or (gif and (
            len(data) > MAX_OUTPUT_BYTES or count > 256 or width * height * count > 134_217_728))
        if not needs_conversion:
            return None
        if count > 4096 or width * height * count > 1_073_741_824:
            raise ValueError("animation frame limit")
        output_count = min(count, 256)
        # Bound retained animation pixels, allowing a resolution below 1080p for long GIFs.
        scale = min(1.0, 1920 / width, 1080 / height,
                    math.sqrt(4_194_304 / (width * height * output_count)) if count > 1 else 1.0)
        target = (max(1, int(width * scale)), max(1, int(height * scale)))
        output = io.BytesIO()
        if count > 1:
            if source.format != "GIF":
                raise ValueError("unsupported oversized animation format")
            frames, durations = [], []
            loop = source.info.get("loop")
            selected = {index * count // output_count for index in range(output_count)}
            for index in range(count):
                source.seek(index)
                # Pillow composites disposal before this conversion; encode complete resized frames.
                duration = max(10, int(source.info.get("duration", 100)))
                if index in selected:
                    frames.append(source.convert("RGBA").resize(target, Image.Resampling.LANCZOS))
                    durations.append(duration)
                else:
                    durations[-1] += duration
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
    @staticmethod
    def _finish(mapping, reference, done):
        mapping.pop(reference, None)
        if not done.cancelled():
            done.exception()  # HTTP disconnects must not leave unobserved background failures.

    def __init__(self, directory: Path = MEDIA_DIRECTORY):
        self.directory = directory
        self.slots = asyncio.Semaphore(4)
        self.cpu_slots = asyncio.Semaphore(2)
        self.range_slots = asyncio.Semaphore(8)
        self.cache = OrderedDict()
        self.inflight = {}
        self.preview_cache = OrderedDict()
        self.preview_inflight = {}
        self.preview_slots = asyncio.Semaphore(2)
        self.store_lock = threading.Lock()

    async def prepare_preview(self, reference: str) -> tuple[Path, str] | None:
        cached = self.preview_cache.get(reference)
        if cached and cached[0] > time.monotonic() and (cached[1] is None or cached[1][0].is_file()):
            self.preview_cache.move_to_end(reference)
            return cached[1]
        task = self.preview_inflight.get(reference)
        if task is None:
            if len(self.preview_inflight) >= 32:
                raise ValueError("image preview queue full")
            task = asyncio.create_task(self._prepare_preview(reference), name="mineastr-image-preview")
            self.preview_inflight[reference] = task
            task.add_done_callback(lambda done: self._finish(self.preview_inflight, reference, done))
        return await asyncio.shield(task)

    async def _prepare_preview(self, reference: str) -> tuple[Path, str] | None:
        async with self.preview_slots:
            data = await self._fetch_preview(reference)
            result = None if data is None else await asyncio.to_thread(self._store, *await asyncio.to_thread(preview_gif, data))
            self.preview_cache[reference] = (time.monotonic() + 3600, result)
            self.preview_cache.move_to_end(reference)
            while len(self.preview_cache) > 256:
                self.preview_cache.popitem(last=False)
            return result

    async def _fetch_preview(self, reference: str) -> bytes | None:
        if reference.startswith(("https://", "http://")):
            timeout = aiohttp.ClientTimeout(total=30, sock_connect=5, sock_read=10)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.get(reference, headers={"Range": f"bytes=0-{MAX_PREVIEW_BYTES-1}", "Accept-Encoding": "identity"}) as response:
                    response.raise_for_status()
                    if response.status == 206 and not response.headers.get("Content-Range", "").startswith("bytes 0-"):
                        raise ValueError("invalid preview range")
                    data = bytearray()
                    async for block in response.content.iter_chunked(16384):
                        data.extend(block)
                        if len(data) >= 6 and data[:6] not in (b"GIF87a", b"GIF89a"):
                            return None
                        if len(data) > MAX_PREVIEW_BYTES:
                            raise ValueError("GIF first frame byte limit")
                        frame = first_gif_frame(data)
                        if frame is not None:
                            return frame
                    raise ValueError("GIF first frame incomplete")
        if reference.startswith(("base64://", "data:")):
            encoded = reference.split(";base64,", 1)[-1].removeprefix("base64://")
            # Only decode enough prefix for the preview, independent of the full input limit.
            data = base64.b64decode(encoded[:MAX_PREVIEW_BYTES * 4 // 3 // 4 * 4], validate=True)
        else:
            def read():
                with Path(reference.removeprefix("file://")).open("rb") as handle:
                    return handle.read(MAX_PREVIEW_BYTES)
            data = await asyncio.to_thread(read)
        if data[:6] not in (b"GIF87a", b"GIF89a"):
            return None
        frame = first_gif_frame(data)
        if frame is None:
            raise ValueError("GIF first frame incomplete")
        return frame

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
            task.add_done_callback(lambda done: self._finish(self.inflight, reference, done))
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
                    elif data[:6] in (b"GIF87a", b"GIF89a"):
                        result = await asyncio.to_thread(self._store, data, "image/gif")
                    elif not reference.startswith(("https://", "http://")):
                        with Image.open(io.BytesIO(data)) as image:
                            mime = {"PNG": "image/png", "JPEG": "image/jpeg"}.get(image.format)
                        if mime:
                            result = await asyncio.to_thread(self._store, data, mime)
            self.cache[reference] = (time.monotonic() + 3600, result)
            self.cache.move_to_end(reference)
            while len(self.cache) > 256:
                self.cache.popitem(last=False)
            return result

    async def _fetch_oversized(self, reference: str) -> bytes | None:
        timeout = aiohttp.ClientTimeout(total=90, sock_connect=5, sock_read=15)
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
                        gif = bytes(data[:6]) in {b"GIF87a", b"GIF89a"}
                        if size is not None and not oversized(size) and not gif:
                            return None  # Stop after the header; original URL keeps client streaming/range support.
                        if size is None and len(data) > 65536:
                            raise ValueError("image header unavailable")
                    if size is not None and bytes(data[:6]) in {b"GIF87a", b"GIF89a"}:
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
        with self.store_lock:
            return self._store_locked(data, mime)

    def _store_locked(self, data: bytes, mime: str) -> tuple[Path, str]:
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
        tasks = tuple(self.inflight.values()) + tuple(self.preview_inflight.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.inflight.clear()
        self.preview_inflight.clear()
