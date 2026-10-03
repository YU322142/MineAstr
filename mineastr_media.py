"""Recover image media omitted by platform component adapters, without downloading it."""

from pathlib import PurePosixPath
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


def unique_media(media: list[dict[str, str]]) -> list[dict[str, str]]:
    unique: dict[str, dict[str, str]] = {}
    for item in media:
        reference = str(item.get("url") or "").strip()
        if reference and reference not in unique:
            unique[reference] = item
        if len(unique) == 8:
            break
    return list(unique.values())
