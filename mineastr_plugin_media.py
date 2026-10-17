"""消息组件、引用与安全图片载荷 / Message components, replies and safe image payloads."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    Image,
    Path,
    Plain,
    QQImageSource,
    Reply,
    asyncio,
    base64,
    discord_media,
    hashlib,
    logger,
    qq_images,
    qq_market_faces,
    re,
    trim_message,
    unique_media,
    urllib,
)

from .mineastr_defaults import (
    MAX_GAME_IMAGE_INLINE_BYTES,
)


class PluginMedia:
    """消息组件、引用与安全图片载荷 / Message components, replies and safe image payloads.

    处理消息链与媒体表示；平台原始元数据提取和后台优化各有独立模块。
    Handle message chains and media representations; raw extraction and worker optimization live separately.
    """

    def __init__(self, plugin):
        self.plugin = plugin

    @staticmethod
    def _event_chain(event: AstrMessageEvent) -> list[Any]:
        message_obj = getattr(event, "message_obj", None)
        chain = getattr(message_obj, "message", None)
        if chain is None:
            chain = getattr(message_obj, "chain", None)
        if chain is None:
            return []
        try:
            return list(chain)
        except TypeError:
            return []

    @classmethod
    def _event_media(cls, event: AstrMessageEvent) -> list[dict[str, str]]:
        raw = getattr(getattr(event, "message_obj", None), "raw_message", None)
        return unique_media(
            cls._chain_media(cls._event_chain(event))
            + qq_images(raw)
            + qq_market_faces(raw)
            + discord_media(raw)
        )

    @staticmethod
    def _image_mime_type(data: bytes) -> str:
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            return "image/png"
        if data.startswith(b"\xff\xd8\xff"):
            return "image/jpeg"
        if data.startswith((b"GIF87a", b"GIF89a")):
            return "image/gif"
        if data.startswith(b"BM"):
            return "image/bmp"
        if data.startswith(b"\x00\x00\x01\x00"):
            return "image/x-icon"
        if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            return "image/webp"
        return ""

    @staticmethod
    def _safe_public_image_url(reference: str) -> str:
        try:
            parsed = urllib.parse.urlparse(reference.strip())
        except ValueError:
            return ""
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            return ""
        return reference.strip()

    @staticmethod
    def _read_local_image(reference: str) -> tuple[bytes, str] | None:
        value = reference.strip()
        if not value:
            return None
        encoded = value
        if encoded.startswith("data:") and ";base64," in encoded:
            encoded = encoded.split(";base64,", 1)[1]
        if encoded.startswith("base64://"):
            encoded = encoded[len("base64://") :]
        if encoded != value or value.startswith("base64://"):
            try:
                data = base64.b64decode(encoded, validate=True)
            except (ValueError, base64.binascii.Error):
                return None
            mime = PluginMedia._image_mime_type(data)
            return (data, mime) if mime else None

        path_value = value
        if value.lower().startswith("file:"):
            try:
                parsed = urllib.parse.urlparse(value)
                path_value = urllib.parse.unquote(parsed.path)
                if re.match(r"^/[A-Za-z]:/", path_value):
                    path_value = path_value[1:]
            except ValueError:
                return None
        try:
            path = Path(path_value).expanduser()
            if not path.is_file() or path.stat().st_size <= 0:
                return None
            if path.stat().st_size > MAX_GAME_IMAGE_INLINE_BYTES:
                return None
            data = path.read_bytes()
        except (OSError, ValueError):
            return None
        mime = PluginMedia._image_mime_type(data)
        return (data, mime) if mime else None

    async def _game_media_payloads(
        self, media: list[dict[str, Any]] | None, *, qq_bot: Any = None
    ) -> list[dict[str, Any]]:
        """Convert AstrBot image components to the path-free MineAstr media protocol."""
        if not self.plugin._cfg_bool("relay_images_to_game"):
            return []
        max_items = self.plugin._bounded_cfg_int("game_image_max_items", 1, 8)
        inline_limit = self.plugin._bounded_cfg_int(
            "game_image_inline_max_bytes", 65_536, MAX_GAME_IMAGE_INLINE_BYTES
        )
        prepared: list[dict[str, Any]] = []
        inline_bytes = 0
        for item in media or ():
            if len(prepared) >= max_items or not isinstance(item, dict):
                break
            reference = str(item.get("url") or item.get("file") or "").strip()
            if not reference:
                continue
            name = str(item.get("name") or "image").strip()[:96] or "image"
            public_url = self.plugin._safe_public_image_url(reference)
            optimizer = getattr(self.plugin, "_image_optimizer", None)
            if optimizer is not None and qq_bot is not None and item.get("qq_file"):
                optimizer = QQImageSource(
                    optimizer, qq_bot, str(item["qq_file"]), reference
                )
            base_url = str(self.plugin._cfg("game_image_public_base_url") or "").strip()
            adapter = (
                self.plugin._minecraft_adapter() if optimizer is not None else None
            )
            if (
                public_url
                and optimizer is not None
                and base_url
                and adapter is not None
                and hasattr(adapter, "image_source_url")
            ):
                # Forward a signed reference immediately; expensive normalization runs on HTTP fetch,
                # keeping the original chat row and text independent of download/encoding time.
                prepared.append(
                    {
                        "type": "image",
                        "url": adapter.image_source_url(reference, base_url, optimizer),
                        "name": name,
                    }
                )
                continue
            if public_url:
                prepared.append({"type": "image", "url": public_url, "name": name})
                continue
            if (
                optimizer is not None
                and base_url
                and adapter is not None
                and hasattr(adapter, "image_source_url")
            ):
                # Local/base64 GIFs also use HTTP; never place large animations in the WS inline budget.
                prepared.append(
                    {
                        "type": "image",
                        "url": adapter.image_source_url(reference, base_url, optimizer),
                        "name": name,
                    }
                )
                continue
            if optimizer is not None:
                try:
                    converted = await optimizer.prepare(reference)
                    if converted is not None:
                        reference = str(converted[0])
                        if (
                            base_url
                            and adapter is not None
                            and hasattr(adapter, "image_source_url")
                        ):
                            prepared.append(
                                {
                                    "type": "image",
                                    "url": adapter.image_source_url(
                                        reference, base_url, optimizer
                                    ),
                                    "name": name,
                                }
                            )
                            continue
                except Exception as exc:
                    logger.warning(
                        "MineAstr local image preparation failed: %s",
                        type(exc).__name__,
                    )
                    continue
            loaded = await asyncio.to_thread(self.plugin._read_local_image, reference)
            if not loaded:
                logger.warning(
                    "MineAstr 跳过无法安全读取的图片，不会把本地路径发送到游戏：%s",
                    name,
                )
                continue
            data, mime_type = loaded
            if len(data) > inline_limit or inline_bytes + len(data) > inline_limit:
                logger.warning("MineAstr 跳过超过内联上限的图片：%s", name)
                continue
            inline_bytes += len(data)
            prepared.append(
                {
                    "type": "image",
                    "data_base64": base64.b64encode(data).decode("ascii"),
                    "mime_type": mime_type,
                    "size": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "name": name,
                }
            )
        return prepared

    @classmethod
    def _chain_media(cls, chain: Any) -> list[dict[str, str]]:
        components = getattr(chain, "chain", chain)
        if components is None:
            return []
        try:
            components = list(components)
        except TypeError:
            return []
        media: list[dict[str, str]] = []
        for component in components:
            if not isinstance(component, Image):
                continue
            reference = str(
                getattr(component, "url", None)
                or getattr(component, "file", None)
                or getattr(component, "path", None)
                or ""
            ).strip()
            if reference:
                media.append(
                    {
                        "type": "image",
                        "url": reference,
                        "name": str(
                            getattr(component, "filename", None) or "image"
                        ).strip()
                        or "image",
                    }
                )
        return media[:8]

    @classmethod
    def _event_reply_context(cls, event: AstrMessageEvent) -> dict[str, str]:
        return cls._chain_reply_context(cls._event_chain(event))

    @classmethod
    def _chain_reply_context(cls, chain: Any) -> dict[str, str]:
        for component in getattr(chain, "chain", chain) or ():
            if not isinstance(component, Reply):
                continue
            quoted_text = str(
                getattr(component, "message_str", None)
                or getattr(component, "text", None)
                or "".join(
                    str(getattr(item, "text", "") or "")
                    for item in getattr(component, "chain", None) or ()
                    if isinstance(item, Plain)
                )
                or ""
            ).strip()
            sender = str(
                getattr(component, "sender_nickname", None)
                or getattr(component, "sender_name", None)
                or ""
            ).strip()
            message_id = str(getattr(component, "id", None) or "").strip()
            if quoted_text or sender or message_id:
                context = {
                    "message_id": message_id,
                    "sender": sender,
                    "text": trim_message(quoted_text, 240),
                }
                sender_id = str(getattr(component, "sender_id", None) or "").strip()
                if sender_id:
                    context["sender_id"] = sender_id
                return context
        return {}

    @staticmethod
    def _reply_prefix(
        reply_context: dict[str, str] | None, text: str | None = None
    ) -> str:
        if not reply_context:
            return ""
        sender = str(reply_context.get("sender") or "").strip()
        quoted = str(reply_context.get("text") or "").strip() if text is None else text
        if not sender and not quoted:
            return ""
        quote = "↪ " + sender
        if quoted:
            quote += (": " if sender else "") + quoted
        return quote + "\n"
