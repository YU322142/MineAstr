"""截图保存、请求策略和并发冷却 / Screenshot persistence, request policy and concurrent cooldowns."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    asyncio,
    base64,
    logger,
    re,
    time,
)

from .mineastr_defaults import (
    MAX_SCREENSHOT_SAVE_BYTES,
    SCREENSHOT_DIR,
)


class ScreenshotService:
    """截图保存、请求策略和并发冷却 / Screenshot persistence, request policy and concurrent cooldowns.

    拥有请求冷却和保存策略；文件处理通过 to_thread 执行，不在事件循环解码。
    Own cooldowns and persistence policy; use to_thread for file handling rather than decoding on the event loop.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._screenshot_last_request_at = {}
        self._screenshot_cooldown_lock = asyncio.Lock()

    @staticmethod
    def _safe_filename(value: Any, fallback: str = "unknown") -> str:
        text = str(value or fallback)
        text = re.sub(r"[^A-Za-z0-9_.-]+", "_", text).strip("._")
        return text or fallback

    async def _save_screenshot_result(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(
            self.plugin._save_screenshot_result_sync, payload
        )

    def _save_screenshot_result_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        data = payload.get("data")
        if not isinstance(data, dict):
            return payload
        image_base64 = data.get("image_base64")
        if not isinstance(image_base64, str) or not image_base64:
            return payload
        if len(image_base64) > MAX_SCREENSHOT_SAVE_BYTES * 2:
            raise ValueError("截图 base64 数据超过插件允许的保存上限。")

        image_bytes = base64.b64decode(image_base64, validate=True)
        if len(image_bytes) > MAX_SCREENSHOT_SAVE_BYTES:
            raise ValueError("截图文件超过插件允许的保存上限。")
        mime_type = str(data.get("mime_type") or "image/jpeg")
        if mime_type != "image/jpeg":
            raise ValueError(f"不支持的截图 MIME 类型：{mime_type}")
        suffix = ".jpg" if mime_type == "image/jpeg" else ".bin"
        server_id = self.plugin._safe_filename(payload.get("server_id"), "minecraft")
        player_name = self.plugin._safe_filename(data.get("player_name"), "player")
        message_id = self.plugin._safe_filename(
            payload.get("message_id"), str(int(time.time() * 1000))
        )
        timestamp = time.strftime("%Y%m%d-%H%M%S")

        SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
        path = (
            SCREENSHOT_DIR
            / f"{timestamp}_{server_id}_{player_name}_{message_id}{suffix}"
        )
        path.write_bytes(image_bytes)

        saved = dict(payload)
        saved_data = dict(data)
        saved_data.pop("image_base64", None)
        saved_data["file_path"] = str(path.resolve())
        saved_data["saved_bytes"] = len(image_bytes)
        saved["data"] = saved_data
        return saved

    @staticmethod
    def _screenshot_cooldown_key(
        server_id: str | None,
        player_uuid: str,
        player_name: str,
    ) -> tuple[str, str, str]:
        return (
            server_id or "minecraft",
            player_uuid or "",
            (player_name or "").lower(),
        )

    async def _mark_screenshot_cooldown(
        self, key: tuple[str, str, str], cooldown_seconds: float
    ) -> float:
        if cooldown_seconds <= 0:
            return 0.0
        lock = getattr(self.plugin, "_screenshot_cooldown_lock", None)
        if lock is None:
            lock = asyncio.Lock()
            self._screenshot_cooldown_lock = lock
        async with lock:
            now = time.monotonic()
            last_request_at = self._screenshot_last_request_at.get(key)
            if last_request_at is not None:
                remaining = cooldown_seconds - (now - last_request_at)
                if remaining > 0:
                    return remaining

            self._screenshot_last_request_at[key] = now
            expire_before = now - max(cooldown_seconds * 3, 60.0)
            stale_keys = [
                stale_key
                for stale_key, requested_at in self._screenshot_last_request_at.items()
                if requested_at < expire_before
            ]
            for stale_key in stale_keys:
                self._screenshot_last_request_at.pop(stale_key, None)
            return 0.0

    async def mineastr_request_screenshot(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        reason: str = "",
    ) -> Any:
        """请求指定 Minecraft 客户端发送低清晰度截图。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空。
            player_name(str): 可选的玩家名。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            player_uuid(str): 可选的玩家 UUID。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            reason(str): 可选的截图原因，会展示给处于询问模式的玩家。
        """
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return (
                "MineAstr 的 minecraft 平台适配器未启用，暂时无法请求 Minecraft 截图。"
            )

        raw = self.plugin._event_raw_message(event)
        target_uuid = player_uuid.strip() or str(raw.get("player_uuid") or "").strip()
        target_name = player_name.strip() or str(raw.get("player_name") or "").strip()
        target_server = (
            server_id.strip() or str(raw.get("server_id") or "").strip() or None
        )
        request_reason = (
            reason.strip() or "AstrBot 需要查看当前 Minecraft 画面以回答玩家问题。"
        )
        cooldown_seconds = float(
            getattr(adapter, "screenshot_cooldown_seconds", 10.0) or 0.0
        )
        cooldown_key = self.plugin._screenshot_cooldown_key(
            target_server, target_uuid, target_name
        )
        cooldown_remaining = await self.plugin._mark_screenshot_cooldown(
            cooldown_key, cooldown_seconds
        )
        if cooldown_remaining > 0:
            wait_seconds = max(1, int(cooldown_remaining + 0.999))
            return self.plugin._tool_json(
                "Minecraft 低清晰度截图请求结果",
                {
                    "ok": False,
                    "result": f"截图请求过于频繁，请等待 {wait_seconds} 秒后再试。",
                    "error": "screenshot_cooldown",
                    "retry_after_seconds": wait_seconds,
                    "server_id": target_server,
                    "player_uuid": target_uuid,
                    "player_name": target_name,
                },
            )

        try:
            payload = await adapter.request_screenshot(
                target_server,
                player_uuid=target_uuid,
                player_name=target_name,
                reason=request_reason,
            )
            image_base64 = None
            mime_type = "image/jpeg"
            if payload.get("ok"):
                data = payload.get("data")
                if isinstance(data, dict):
                    maybe_image = data.get("image_base64")
                    if isinstance(maybe_image, str):
                        image_base64 = maybe_image
                    mime_type = str(data.get("mime_type") or mime_type)
                payload = await self.plugin._save_screenshot_result(payload)
        except asyncio.TimeoutError:
            logger.warning("MineAstr 请求 Minecraft 截图超时。")
            image_base64 = None
            mime_type = "image/jpeg"
            payload = {
                "ok": False,
                "result": "请求截图超时，客户端未响应。",
                "error": "screenshot_timeout",
                "local_status": await adapter.local_status(),
            }
        except Exception as exc:
            logger.warning("MineAstr 请求 Minecraft 截图失败：%s", exc)
            image_base64 = None
            mime_type = "image/jpeg"
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        if (
            image_base64
            and event.unified_msg_origin in self.plugin._relay_sessions
            and str(event.get_platform_id() or "").casefold() != "minecraft"
        ):
            try:
                await self.plugin._send_to_relay_sessions(
                    "[Minecraft 截图]",
                    sessions=self.plugin._relay_target_sessions(
                        event.unified_msg_origin
                    ),
                    media=[
                        {
                            "type": "image",
                            "url": f"base64://{image_base64}",
                            "name": "minecraft-screenshot.jpg",
                        }
                    ],
                )
            except Exception as exc:
                logger.debug("MineAstr 转发 Minecraft 截图到其他聊天平台失败：%s", exc)
        return self.plugin._tool_image_result(
            "Minecraft 低清晰度截图请求结果", payload, image_base64, mime_type
        )
