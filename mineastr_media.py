"""Recover image media omitted by platform component adapters, without downloading it.

提取平台遗漏的图片和表情元数据；不在这里下载或解码媒体。
Recover omitted image and sticker metadata; leave downloading and decoding to media workers.
"""

import asyncio
import logging
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import parse_qs, urlparse


def _value(value: Any, key: str, default: Any = None) -> Any:
    return value.get(key, default) if isinstance(value, dict) else getattr(value, key, default)


def _path(url: str) -> PurePosixPath:
    try:
        return PurePosixPath(urlparse(url).path)
    except ValueError:
        return PurePosixPath()


def discord_media(message: Any) -> list[dict[str, str]]:
    media: list[dict[str, str]] = []
    attachments = _value(message, "attachments", ()) or ()
    for attachment in attachments:
        url = str(_value(attachment, "url", "") or "").strip()
        name = str(_value(attachment, "filename", "") or _path(url).name or "image")
        mime = str(_value(attachment, "content_type", "") or "").lower()
        if mime.startswith("image/") or (not mime and PurePosixPath(name.lower()).suffix in {".gif", ".png", ".jpg", ".jpeg", ".webp"}):
            media.append({"type": "image", "url": url, "name": name})
    # Discord may expose a replied-to image as an embed while the new message
    # also has its own attachment. The embed is the quote preview, not a second
    # attachment to relay. Keep standalone embeds for ordinary image links.
    has_reply = bool(_value(message, "reference") or _value(message, "message_reference"))
    embeds = () if has_reply and attachments else (_value(message, "embeds", ()) or ())
    for embed in embeds:
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


def _media_identity_keys(item: dict[str, str]) -> set[tuple[str, ...]]:
    """同一附件可有签名链接或代理地址；只识别已知平台的稳定标识。
    Match known platform asset identities without stripping arbitrary URL parameters.
    """
    reference = str(item.get("url") or "").strip()
    keys = {("reference", reference)}
    handle = str(item.get("qq_file") or "").strip()
    if handle:
        keys.add(("qq_file", handle))
    try:
        parsed = urlparse(reference)
        if parsed.scheme not in {"http", "https"}:
            return keys
        parts = parsed.path.split("/")
        if parsed.hostname in {"cdn.discordapp.com", "media.discordapp.net"}:
            if len(parts) >= 5 and parts[1] == "attachments" and all(part.isdigit() for part in parts[2:4]):
                keys.add(("discord_attachment", parts[2], parts[3]))
        elif parsed.hostname == "multimedia.nt.qq.com.cn" and parsed.path == "/download":
            query = parse_qs(parsed.query)
            if len(query.get("fileid", ())) == 1 and len(query.get("appid", ())) == 1:
                keys.add(("qq_download", query["appid"][0], query["fileid"][0], query.get("spec", [""])[0]))
    except ValueError:
        pass
    return keys


def _merge_media(target: dict[str, str], item: dict[str, str]) -> None:
    if item.get("qq_file"):
        target.setdefault("qq_file", item["qq_file"])
    # 保留原始附件，避免用缩放代理或静态预览替代 GIF。
    # Prefer the original Discord attachment over a resized proxy or static preview.
    try:
        if urlparse(target["url"]).hostname == "media.discordapp.net" and urlparse(item["url"]).hostname == "cdn.discordapp.com":
            target["url"] = item["url"]
    except (KeyError, ValueError):
        pass


def unique_media(media: list[dict[str, str]]) -> list[dict[str, str]]:
    """只在一条消息内去重；独立消息仍可重复发送同一张图。
    Deduplicate within one message, never suppress a later independent message.
    """
    unique: list[dict[str, str]] = []
    identities: list[set[tuple[str, ...]]] = []
    for item in media:
        reference = str(item.get("url") or "").strip()
        if not reference:
            continue
        keys = _media_identity_keys(item)
        matches = [index for index, known in enumerate(identities) if not keys.isdisjoint(known)]
        if matches:
            first = matches[0]
            for index in reversed(matches[1:]):
                _merge_media(unique[first], unique.pop(index))
                identities[first].update(identities.pop(index))
            _merge_media(unique[first], item)
            identities[first].update(keys)
        elif len(unique) < 8:
            unique.append(dict(item))
            identities.append(keys)
    return unique
