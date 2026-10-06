"""Recover image media omitted by platform component adapters, without downloading it."""

import asyncio
import logging
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlparse


def _value(value: Any, key: str, default: Any = None) -> Any:
    return value.get(key, default) if isinstance(value, dict) else getattr(value, key, default)


def _path(url: str) -> PurePosixPath:
    try:
        return PurePosixPath(urlparse(url).path)
    except ValueError:
        return PurePosixPath()


def discord_media(message: Any) -> list[dict[str, str]]:
    media: list[dict[str, str]] = []
    for attachment in _value(message, "attachments", ()) or ():
        url = str(_value(attachment, "url", "") or "").strip()
        name = str(_value(attachment, "filename", "") or _path(url).name or "image")
        mime = str(_value(attachment, "content_type", "") or "").lower()
        if mime.startswith("image/") or (not mime and PurePosixPath(name.lower()).suffix in {".gif", ".png", ".jpg", ".jpeg", ".webp"}):
            media.append({"type": "image", "url": url, "name": name})
    for embed in _value(message, "embeds", ()) or ():
        image = str(_value(_value(embed, "image"), "url", "") or "").strip()
        thumbnail = str(_value(_value(embed, "thumbnail"), "url", "") or "").strip()
        # Prefer the animated asset over a static embed preview.
        animated = next((url for url in (image, thumbnail) if _path(url).suffix.lower() == ".gif"), "")
        reference = animated or image
        if reference:
            media.append({"type": "image", "url": reference, "name": _path(reference).name or "image"})
    for sticker in _value(message, "stickers", ()) or ():
        url = str(_value(sticker, "url", "") or "").strip()
        format_name = str(_value(sticker, "format", "") or "").lower()
        if "lottie" not in format_name:
            media.append({"type": "image", "url": url, "name": _path(url).name or "sticker"})
    return unique_media(media)


def qq_market_faces(raw: Any) -> list[dict[str, str]]:
    media: list[dict[str, str]] = []
    segments = _value(raw, "message", ())
    if not isinstance(segments, (list, tuple)):
        return media
    for segment in segments:
        if _value(segment, "type") != "mface":
            continue
        data = _value(segment, "data", {})
        url = str(_value(data, "url", "") or "").strip()
        try:
            public = urlparse(url).scheme.lower() in {"http", "https"} and bool(urlparse(url).hostname)
        except ValueError:
            public = False
        if public:
            media.append({"type": "image", "url": url, "name": _path(url).name or "qq-sticker"})
    return unique_media(media)


def qq_images(raw: Any) -> list[dict[str, str]]:
    """Retain OneBot file handles so broken public URLs can use NapCat's cached original."""
    segments = _value(raw, "message", ())
    if not isinstance(segments, (list, tuple)):
        return []
    media = []
    for segment in segments:
        if _value(segment, "type") != "image":
            continue
        data = _value(segment, "data", {})
        url = str(_value(data, "url", "") or "").strip()
        if url:
            item = {"type": "image", "url": url, "name": "image"}
            file = str(_value(data, "file", "") or "").strip()
            if file:
                item["qq_file"] = file
            media.append(item)
    return unique_media(media)


class QQImageSource:
    """Resolve QQ media only on HTTP fetch; chat delivery never waits for get_image."""

    def __init__(self, optimizer: Any, bot: Any, file: str, reference: str):
        self.optimizer = optimizer
        self.bot = bot
        self.file = file
        self.reference = reference
        self.resolved: str | None = None
        self.lock = asyncio.Lock()

    async def _resolve(self) -> str:
        async with self.lock:
            if self.resolved is not None:
                return self.resolved
            reference = self.reference
            try:
                image = await asyncio.wait_for(self.bot.call_action("get_image", file=self.file), 5)
                if isinstance(image, dict):
                    local = str(image.get("file") or "").strip()
                    if local and await asyncio.to_thread(Path(local).is_file):
                        reference = local
                    else:
                        url = str(image.get("url") or "").strip()
                        parsed = urlparse(url)
                        if parsed.scheme in {"http", "https"} and parsed.hostname:
                            reference = url
            except Exception as exc:
                logging.getLogger(__name__).warning("MineAstr QQ image source lookup failed: %s", type(exc).__name__)
            self.resolved = reference
            return reference

    async def prepare(self, reference: str):
        return await self.optimizer.prepare(await self._resolve())

    async def prepare_preview(self, reference: str):
        return await self.optimizer.prepare_preview(await self._resolve())


def unique_media(media: list[dict[str, str]]) -> list[dict[str, str]]:
    unique: dict[str, dict[str, str]] = {}
    for item in media:
        reference = str(item.get("url") or "").strip()
        if reference in unique:
            if item.get("qq_file"):
                unique[reference].setdefault("qq_file", item["qq_file"])
        elif reference and len(unique) < 8:
            unique[reference] = dict(item)
    return list(unique.values())
