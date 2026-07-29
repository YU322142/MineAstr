import asyncio
import hmac
import inspect
import json
import re
import time
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web
from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, MessageChain
from astrbot.api.message_components import Plain
from astrbot.api.platform import (
    AstrBotMessage,
    MessageMember,
    MessageType,
    Platform,
    PlatformMetadata,
    register_platform_adapter,
)

try:
    from astrbot.core.platform.message_session import MessageSesion
except ImportError:
    from astrbot.core.platform.astr_message_event import MessageSesion


PROTOCOL_VERSION = 1
QUERY_TIMEOUT_SECONDS = 5.0
SCREENSHOT_QUERY_TIMEOUT_SECONDS = 30.0
MAX_WEBSOCKET_MESSAGE_BYTES = 2 * 1024 * 1024
LOGO_PATH = str(Path(__file__).resolve().with_name("logo.png"))
MINECRAFT_LEADING_MENTION_RE = re.compile(
    r"^\s*@(?P<target>[^\s@]+)(?P<body>(?:\s+.*)?)$"
)
DEFAULT_MENTION_ALIASES = "AstrBot,Aria,astrbot"
MAX_SENDER_NAME_LENGTH = 64
DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "path": "/ws",
    "token": "change-me",
    "group_id": "minecraft",
    "group_name": "Minecraft",
    "bot_id": "astrbot",
    "bot_display_name": "AstrBot",
    "mention_aliases": DEFAULT_MENTION_ALIASES,
    "max_message_length": 1000,
    "outbound_max_message_length": 2000,
    "websocket_max_message_bytes": 2097152,
    "screenshot_cooldown_seconds": 10,
    "screenshot_timeout_seconds": 30,
}
CONFIG_METADATA = {
    "host": {
        "description": "WebSocket 监听地址",
        "type": "string",
        "hint": "单机保持 127.0.0.1；跨机器或 Docker 部署必须填 0.0.0.0，不要填写公网 IP。公网 IP 只写在 Mod 的 websocketUrl 中。",
        "default": "127.0.0.1",
    },
    "port": {
        "description": "WebSocket 监听端口",
        "type": "int",
        "hint": "需要与 MineAstr Mod 配置中的 websocketUrl 端口一致；端口被占用时可以换成其他未使用端口。",
        "default": 8765,
    },
    "path": {
        "description": "WebSocket 路径",
        "type": "string",
        "hint": "需要与 MineAstr Mod 配置中的 websocketUrl 路径一致；不清楚如何修改时保持 /ws。",
        "default": "/ws",
    },
    "token": {
        "description": "连接认证 Token",
        "type": "string",
        "hint": "Minecraft Mod 连接 AstrBot 时使用，两端必须完全一致；留空或保持 change-me 时会拒绝所有连接，请改成较长的随机字符串。",
        "default": "change-me",
    },
    "group_id": {
        "description": "AstrBot 群组 ID",
        "type": "string",
        "hint": "所有 Minecraft 聊天都会进入这个虚拟群聊；一般保持 minecraft，改动后会被 AstrBot 视为另一个群。",
        "default": "minecraft",
    },
    "group_name": {
        "description": "AstrBot 群组名称",
        "type": "string",
        "hint": "用于显示这个虚拟 Minecraft 群聊的名称，只影响识别和展示。",
        "default": "Minecraft",
    },
    "bot_id": {
        "description": "机器人 ID",
        "type": "string",
        "hint": "AstrBot 在 minecraft 虚拟平台中的机器人账号 ID；一般不需要修改。",
        "default": "astrbot",
    },
    "bot_display_name": {
        "description": "机器人显示名称",
        "type": "string",
        "hint": "AstrBot 回复广播到 Minecraft 时方括号内显示的名称。",
        "default": "AstrBot",
    },
    "mention_aliases": {
        "description": "Minecraft @ 唤醒别名",
        "type": "string",
        "hint": "玩家在 Minecraft 聊天开头使用这些名字 @ 机器人时，会被转换为 AstrBot 唤醒消息。多个别名用英文逗号分隔，例如 AstrBot,Aria。",
        "default": DEFAULT_MENTION_ALIASES,
    },
    "max_message_length": {
        "description": "最大聊天长度",
        "type": "int",
        "hint": "单条 Minecraft 消息转发到 AstrBot 前允许的最大长度；超出部分会被截断，建议保持默认。",
        "default": 1000,
    },
    "outbound_max_message_length": {
        "description": "广播回游戏的最大长度",
        "type": "int",
        "hint": "AstrBot 回复广播到 Minecraft 前允许的最大长度；过长回复会被截断，避免刷屏或触发客户端显示问题。",
        "default": 2000,
    },
    "websocket_max_message_bytes": {
        "description": "WebSocket 单包大小上限",
        "type": "int",
        "hint": "MineAstr 插件接收 Mod WebSocket 消息的最大字节数；截图查询结果也会经过这里，建议保持默认。",
        "default": 2097152,
    },
    "screenshot_cooldown_seconds": {
        "description": "截图请求冷却秒数",
        "type": "int",
        "hint": "同一目标玩家在冷却时间内重复请求截图时，插件会直接拦截，避免连续弹窗和网络压力。",
        "default": 10,
    },
    "screenshot_timeout_seconds": {
        "description": "截图请求超时秒数",
        "type": "int",
        "hint": "等待 Minecraft 客户端返回截图的最长时间；超时后会立即把失败原因返回给模型。",
        "default": 30,
    },
}


def _config_value(config: dict[str, Any], key: str) -> Any:
    return config.get(key, DEFAULT_CONFIG[key])


def _trim_content(value: Any, max_len: int) -> str:
    content = str(value or "").replace("\r", "").strip()
    if len(content) > max_len:
        return content[:max_len]
    return content


def _trim_outbound_content(value: Any, max_len: int) -> str:
    content = str(value or "").replace("\r", "").strip()
    if max_len > 0 and len(content) > max_len:
        return content[: max(0, max_len - 1)] + "…"
    return content


def _trim_sender_name(value: Any, fallback: str) -> str:
    sender = str(value or fallback).replace("\r", "").replace("\n", " ").strip()
    if len(sender) > MAX_SENDER_NAME_LENGTH:
        return sender[:MAX_SENDER_NAME_LENGTH]
    return sender or fallback


def _normalize_translations(value: Any, max_len: int) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    translations: dict[str, str] = {}
    for raw_language, raw_text in value.items():
        language = str(raw_language or "").strip().replace("-", "_").casefold()
        if not re.fullmatch(r"[a-z0-9_]{2,16}", language):
            continue
        translated = _trim_outbound_content(raw_text, max_len)
        if translated:
            translations[language] = translated
    return translations


def _parse_aliases(value: Any) -> set[str]:
    aliases: set[str] = set()
    for item in str(value or "").split(","):
        alias = item.strip().casefold()
        if alias:
            aliases.add(alias)
    return aliases


def _plain_text_from_chain(message: MessageChain) -> str:
    parts: list[str] = []
    chain = getattr(message, "chain", message)
    for item in chain:
        if isinstance(item, Plain):
            parts.append(item.text)
        elif hasattr(item, "text"):
            parts.append(str(item.text))
        else:
            logger.warning(
                "MineAstr 已忽略不支持的出站消息片段：%s", type(item).__name__
            )
    return "".join(parts).strip()


def _query_error_message(exc: BaseException) -> str:
    if isinstance(exc, asyncio.TimeoutError):
        return "等待 Minecraft 服务器查询结果超时"
    return str(exc) or exc.__class__.__name__


class MinecraftConnectionManager:
    def __init__(self, bot_display_name: str, outbound_max_message_length: int):
        self._bot_display_name = bot_display_name
        self._outbound_max_message_length = max(1, outbound_max_message_length)
        self._connections: dict[web.WebSocketResponse, dict[str, Any]] = {}
        self._pending_queries: dict[
            str, tuple[web.WebSocketResponse, asyncio.Future[dict[str, Any]]]
        ] = {}
        self._lock = asyncio.Lock()

    @property
    def connected_count(self) -> int:
        return len(self._connections)

    async def register(
        self, ws: web.WebSocketResponse, hello: dict[str, Any]
    ) -> tuple[dict[str, Any], bool]:
        now = int(time.time() * 1000)
        metadata = {
            "server_id": _trim_sender_name(hello.get("server_id"), "minecraft"),
            "server_name": _trim_sender_name(
                hello.get("server_name"), "Minecraft Server"
            ),
            "mod_version": _trim_sender_name(hello.get("mod_version"), "unknown"),
            "connected_at": now,
            "last_seen_at": now,
        }
        async with self._lock:
            is_new = ws not in self._connections
            self._connections[ws] = metadata
        return dict(metadata), is_new

    async def unregister(self, ws: web.WebSocketResponse) -> dict[str, Any] | None:
        async with self._lock:
            metadata = self._connections.pop(ws, None)
            pending_ids = [
                message_id
                for message_id, (pending_ws, _) in self._pending_queries.items()
                if pending_ws is ws
            ]
            for message_id in pending_ids:
                _, future = self._pending_queries.pop(message_id)
                if not future.done():
                    future.set_exception(RuntimeError("Minecraft WebSocket 已断开"))
        return dict(metadata) if metadata else None

    async def is_registered(self, ws: web.WebSocketResponse) -> bool:
        async with self._lock:
            return ws in self._connections

    async def metadata_for(self, ws: web.WebSocketResponse) -> dict[str, Any] | None:
        async with self._lock:
            metadata = self._connections.get(ws)
            return dict(metadata) if metadata else None

    async def snapshot(self) -> list[dict[str, Any]]:
        async with self._lock:
            return [dict(meta) for meta in self._connections.values()]

    async def close(self) -> None:
        async with self._lock:
            connections = list(self._connections.keys())
            pending = list(self._pending_queries.values())
            self._connections.clear()
            self._pending_queries.clear()
        for _, future in pending:
            if not future.done():
                future.set_exception(RuntimeError("MineAstr WebSocket 服务正在关闭"))
        for ws in connections:
            await ws.close()

    async def send_chat(
        self,
        content: str,
        sender_name: str | None = None,
        server_id: str | None = None,
        *,
        translations: dict[str, str] | None = None,
        show_original: bool = False,
    ) -> None:
        content = _trim_outbound_content(content, self._outbound_max_message_length)
        if not content:
            return
        payload = {
            "type": "chat",
            "message_id": str(uuid.uuid4()),
            "sender_name": _trim_sender_name(sender_name, self._bot_display_name),
            "content": content,
        }
        localized = _normalize_translations(
            translations, self._outbound_max_message_length
        )
        if localized:
            payload["translations"] = localized
            payload["show_original"] = bool(show_original)
        if server_id:
            ws, _ = await self._select_connection(server_id)
            await ws.send_str(json.dumps(payload, ensure_ascii=False))
        else:
            await self._broadcast(payload)

    async def send_pong(
        self, ws: web.WebSocketResponse, time_ms: int | None = None
    ) -> None:
        await ws.send_str(
            json.dumps({"type": "pong", "time_ms": time_ms or int(time.time() * 1000)})
        )

    async def send_error(self, ws: web.WebSocketResponse, message: str) -> None:
        await ws.send_str(json.dumps({"type": "error", "message": message}))

    async def mark_seen(self, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            if ws in self._connections:
                self._connections[ws]["last_seen_at"] = int(time.time() * 1000)

    async def resolve_query(
        self, ws: web.WebSocketResponse, payload: dict[str, Any]
    ) -> None:
        message_id = str(payload.get("message_id") or "")
        if not message_id:
            return
        async with self._lock:
            pending = self._pending_queries.get(message_id)
            if pending and pending[0] is ws:
                self._pending_queries.pop(message_id, None)
        if not pending:
            logger.debug("MineAstr 已忽略未知查询结果：%s", message_id)
            return
        pending_ws, future = pending
        if pending_ws is not ws:
            logger.warning(
                "MineAstr 已拒绝来自错误服务器连接的查询结果：%s", message_id
            )
            return
        if not future.done():
            future.set_result(payload)

    async def query(
        self,
        query_type: str,
        server_id: str | None = None,
        params: dict[str, Any] | None = None,
        timeout: float = QUERY_TIMEOUT_SECONDS,
    ) -> dict[str, Any]:
        ws, _ = await self._select_connection(server_id)
        return await self._query_ws(ws, query_type, params=params, timeout=timeout)

    async def query_all(
        self,
        query_type: str,
        params: dict[str, Any] | None = None,
        timeout: float = QUERY_TIMEOUT_SECONDS,
    ) -> list[dict[str, Any]]:
        async with self._lock:
            targets = [
                (ws, dict(meta))
                for ws, meta in self._connections.items()
                if not ws.closed
            ]
        if not targets:
            return []

        tasks = [
            self._query_ws(ws, query_type, params=params, timeout=timeout)
            for ws, _ in targets
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        normalized: list[dict[str, Any]] = []
        for (_, meta), result in zip(targets, results):
            if isinstance(result, Exception):
                normalized.append(
                    {
                        "type": "query_result",
                        "query": query_type,
                        "ok": False,
                        "server_id": meta.get("server_id", "minecraft"),
                        "server_name": meta.get("server_name", "Minecraft Server"),
                        "error": _query_error_message(result),
                    }
                )
            else:
                normalized_result = dict(result)
                normalized_result.setdefault(
                    "server_id", meta.get("server_id", "minecraft")
                )
                normalized_result.setdefault(
                    "server_name", meta.get("server_name", "Minecraft Server")
                )
                normalized.append(normalized_result)
        return normalized

    async def _broadcast(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False)
        async with self._lock:
            connections = list(self._connections.keys())
        for ws in connections:
            if ws.closed:
                await self.unregister(ws)
                continue
            try:
                await ws.send_str(data)
            except Exception as exc:
                logger.warning("MineAstr 发送 WebSocket 数据失败：%s", exc)
                await self.unregister(ws)

    async def _select_connection(
        self, server_id: str | None
    ) -> tuple[web.WebSocketResponse, dict[str, Any]]:
        async with self._lock:
            connections = [
                (ws, dict(meta))
                for ws, meta in self._connections.items()
                if not ws.closed
            ]
        if not connections:
            raise RuntimeError("当前没有已连接的 Minecraft 服务器")
        if server_id:
            for ws, meta in connections:
                if str(meta.get("server_id")) == server_id:
                    return ws, meta
            raise RuntimeError(f"未找到 server_id={server_id} 的 Minecraft 服务器")
        return connections[0]

    async def _query_ws(
        self,
        ws: web.WebSocketResponse,
        query_type: str,
        params: dict[str, Any] | None = None,
        timeout: float = QUERY_TIMEOUT_SECONDS,
    ) -> dict[str, Any]:
        if ws.closed:
            raise RuntimeError("Minecraft WebSocket 已关闭")
        message_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = (
            asyncio.get_running_loop().create_future()
        )
        async with self._lock:
            self._pending_queries[message_id] = (ws, future)
        payload = {
            "type": "query",
            "message_id": message_id,
            "query": query_type,
            "time_ms": int(time.time() * 1000),
        }
        if params:
            payload.update(params)
        try:
            await ws.send_str(json.dumps(payload, ensure_ascii=False))
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            async with self._lock:
                self._pending_queries.pop(message_id, None)


class MinecraftPlatformEvent(AstrMessageEvent):
    def __init__(
        self,
        message_str: str,
        message_obj: AstrBotMessage,
        platform_meta: PlatformMetadata,
        session_id: str,
        connection_manager: MinecraftConnectionManager,
        bot_display_name: str,
        translation_options: Callable[
            [str, str], Awaitable[dict[str, Any]]
        ] | None = None,
    ):
        super().__init__(message_str, message_obj, platform_meta, session_id)
        self._connection_manager = connection_manager
        self._bot_display_name = bot_display_name
        self._translation_options = translation_options
        self._translation_origin_fallback = session_id

    async def send(self, message: MessageChain):
        content = _plain_text_from_chain(message)
        if content:
            options: dict[str, Any] = {}
            if self._translation_options is not None:
                origin = str(
                    getattr(self, "unified_msg_origin", "")
                    or self._translation_origin_fallback
                )
                options = await self._translation_options(content, origin)
            await self._connection_manager.send_chat(
                content, self._bot_display_name, **options
            )
        await super().send(message)


@register_platform_adapter(
    "minecraft",
    "Minecraft 群聊桥接",
    default_config_tmpl=DEFAULT_CONFIG,
    adapter_display_name="Minecraft 群聊桥接",
    logo_path=LOGO_PATH,
    config_metadata=CONFIG_METADATA,
)
class MinecraftPlatformAdapter(Platform):
    def __init__(
        self,
        platform_config: dict[str, Any],
        platform_settings: dict[str, Any],
        event_queue,
    ):
        try:
            super().__init__(platform_config or {}, event_queue)
        except TypeError:
            super().__init__(event_queue)
        self.config = {**DEFAULT_CONFIG, **(platform_config or {})}
        self.settings = platform_settings or {}
        self.host = str(_config_value(self.config, "host"))
        self.port = int(_config_value(self.config, "port"))
        self.path = str(_config_value(self.config, "path"))
        if not self.path.startswith("/"):
            self.path = "/" + self.path
        self.token = str(_config_value(self.config, "token")).strip()
        self.group_id = str(_config_value(self.config, "group_id"))
        self.group_name = str(_config_value(self.config, "group_name"))
        self.bot_id = str(_config_value(self.config, "bot_id"))
        self.bot_display_name = str(_config_value(self.config, "bot_display_name"))
        self.mention_aliases = _parse_aliases(
            _config_value(self.config, "mention_aliases")
        )
        self.max_message_length = max(
            1, int(_config_value(self.config, "max_message_length"))
        )
        self.outbound_max_message_length = max(
            1, int(_config_value(self.config, "outbound_max_message_length"))
        )
        self.websocket_max_message_bytes = min(
            MAX_WEBSOCKET_MESSAGE_BYTES,
            max(8192, int(_config_value(self.config, "websocket_max_message_bytes"))),
        )
        self.screenshot_cooldown_seconds = max(
            0.0, float(_config_value(self.config, "screenshot_cooldown_seconds"))
        )
        self.screenshot_timeout_seconds = max(
            1.0, float(_config_value(self.config, "screenshot_timeout_seconds"))
        )
        self.connection_manager = MinecraftConnectionManager(
            self.bot_display_name, self.outbound_max_message_length
        )
        self._runner: web.AppRunner | None = None
        self._bridge_event_listeners: list[
            Callable[
                [dict[str, Any]],
                Awaitable[dict[str, Any] | None] | dict[str, Any] | None,
            ]
        ] = []
        self._chat_translation_handler: Callable[
            [str, str], Awaitable[dict[str, Any]] | dict[str, Any]
        ] | None = None

    def set_chat_translation_handler(
        self,
        handler: Callable[
            [str, str], Awaitable[dict[str, Any]] | dict[str, Any]
        ] | None,
    ) -> None:
        self._chat_translation_handler = handler

    async def _chat_translation_options(
        self, content: str, origin: str
    ) -> dict[str, Any]:
        handler = self._chat_translation_handler
        if handler is None:
            return {}
        try:
            options = handler(content, origin)
            if inspect.isawaitable(options):
                options = await options
            if not isinstance(options, dict):
                return {}
            translations = _normalize_translations(
                options.get("translations"), self.outbound_max_message_length
            )
            if not translations:
                return {}
            return {
                "translations": translations,
                "show_original": bool(options.get("show_original", False)),
            }
        except Exception as exc:
            logger.warning("MineAstr 游戏内翻译处理器失败，已发送原文：%s", exc)
            return {}

    def add_bridge_event_listener(
        self,
        listener: Callable[
            [dict[str, Any]],
            Awaitable[dict[str, Any] | None] | dict[str, Any] | None,
        ],
    ) -> None:
        if listener not in self._bridge_event_listeners:
            self._bridge_event_listeners.append(listener)

    def remove_bridge_event_listener(
        self,
        listener: Callable[
            [dict[str, Any]],
            Awaitable[dict[str, Any] | None] | dict[str, Any] | None,
        ],
    ) -> None:
        if listener in self._bridge_event_listeners:
            self._bridge_event_listeners.remove(listener)

    async def _notify_bridge_event(
        self, payload: dict[str, Any]
    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        for listener in list(self._bridge_event_listeners):
            try:
                result = listener(dict(payload))
                if inspect.isawaitable(result):
                    result = await result
                if isinstance(result, dict):
                    results.append(result)
            except Exception as exc:
                logger.warning("MineAstr 桥接事件监听器执行失败：%s", exc)
        return results

    def _bot_mention_aliases(self) -> set[str]:
        aliases = {
            str(self.bot_id or "").casefold(),
            str(self.bot_display_name or "").casefold(),
        }
        aliases.update(self.mention_aliases)
        aliases.discard("")
        return aliases

    def _parse_minecraft_message(
        self, content: str
    ) -> tuple[list[Any], str, str | None]:
        text = content.strip()
        match = MINECRAFT_LEADING_MENTION_RE.match(text)
        if not match:
            return [Plain(text)], text, None

        target = str(match.group("target") or "").strip().rstrip("，,。.!！？?；;:：")
        body = str(match.group("body") or "").lstrip()
        if target.casefold() not in self._bot_mention_aliases():
            return [Plain(text)], text, None

        # AstrBot 的唤醒阶段会优先看 wake_prefix。为了兼容不同环境里
        # At 组件与 self_id 识别不一致的情况，这里把 Minecraft 里的
        # "@xxx 内容" 转成一个内部唤醒消息，而不是完全依赖 At。
        wake_body = body or ""
        message_str = f"/{wake_body}" if not wake_body.startswith("/") else wake_body
        chain: list[Any] = [Plain(wake_body)]
        logger.debug("MineAstr 已识别 Minecraft 提及到机器人：%s", target)
        return chain, message_str, target

    def meta(self) -> PlatformMetadata:
        return PlatformMetadata(
            name="minecraft",
            description="通过 MineAstr WebSocket 接入 Minecraft 聊天",
            id="minecraft",
        )

    async def run(self):
        if not self._token_is_configured():
            logger.error(
                "MineAstr WebSocket Token 未配置或仍为 change-me；为防止未授权访问，所有连接都会被拒绝。"
            )
        app = web.Application()
        app.router.add_get(self.path, self._handle_websocket)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info(
            "MineAstr WebSocket 正在监听 ws://%s:%s%s", self.host, self.port, self.path
        )

        try:
            await asyncio.Event().wait()
        finally:
            await self.connection_manager.close()
            if self._runner:
                await self._runner.cleanup()

    async def send_by_session(
        self, session: MessageSesion, message_chain: MessageChain
    ):
        content = _plain_text_from_chain(message_chain)
        if not content:
            return
        options = await self._chat_translation_options(content, str(session))
        await self.connection_manager.send_chat(
            content, self.bot_display_name, **options
        )

    async def relay_chat(
        self,
        content: str,
        sender_name: str,
        server_id: str | None = None,
        *,
        origin: str = "",
        translation_options: dict[str, Any] | None = None,
    ) -> None:
        if translation_options is None:
            options = await self._chat_translation_options(content, origin)
        else:
            translations = _normalize_translations(
                translation_options.get("translations"),
                self.outbound_max_message_length,
            )
            options = (
                {
                    "translations": translations,
                    "show_original": bool(
                        translation_options.get("show_original", False)
                    ),
                }
                if translations
                else {}
            )
        await self.connection_manager.send_chat(
            content, sender_name, server_id, **options
        )

    async def query_status(self, server_id: str | None = None) -> dict[str, Any]:
        if server_id:
            return await self.connection_manager.query("status", server_id)
        return {
            "type": "query_result",
            "query": "status",
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.query_all("status"),
        }

    async def query_players(self, server_id: str | None = None) -> dict[str, Any]:
        if server_id:
            return await self.connection_manager.query("players", server_id)
        return {
            "type": "query_result",
            "query": "players",
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.query_all("players"),
        }

    async def query_performance(self, server_id: str | None = None) -> dict[str, Any]:
        if server_id:
            return await self.connection_manager.query("performance", server_id)
        return {
            "type": "query_result",
            "query": "performance",
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.query_all("performance"),
        }

    async def query_player_state(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "player_state",
            server_id,
            params={
                "player_uuid": player_uuid.strip(),
                "player_name": player_name.strip(),
            },
        )

    async def query_inventory(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        include_ender_chest: bool = False,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "inventory",
            server_id,
            params={
                "player_uuid": player_uuid.strip(),
                "player_name": player_name.strip(),
                "include_ender_chest": bool(include_ender_chest),
            },
        )

    async def query_nearby_entities(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        radius: float = 12.0,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "nearby_entities",
            server_id,
            params={
                "player_uuid": player_uuid.strip(),
                "player_name": player_name.strip(),
                "radius": max(1.0, min(32.0, float(radius))),
            },
        )

    async def analyze_region(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        horizontal_radius: int = 8,
        vertical_radius: int = 6,
        dimension: str = "",
        x: int | None = None,
        y: int | None = None,
        z: int | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "player_uuid": player_uuid.strip(),
            "player_name": player_name.strip(),
            "horizontal_radius": max(1, min(24, int(horizontal_radius))),
            "vertical_radius": max(1, min(16, int(vertical_radius))),
        }
        if x is not None and y is not None and z is not None:
            params.update(
                {
                    "dimension": dimension.strip() or "minecraft:overworld",
                    "x": int(x),
                    "y": int(y),
                    "z": int(z),
                }
            )
        return await self.connection_manager.query(
            "region_features", server_id, params=params, timeout=10.0
        )

    async def run_server_command(
        self,
        server_id: str | None,
        command: str = "",
        requester_id: str = "",
        requester_uuid: str = "",
        requester_name: str = "",
        requester_platform: str = "",
        *,
        action: str = "request",
        approval_id: str = "",
    ) -> dict[str, Any]:
        normalized_action = action.strip().casefold() or "request"
        params = {
            "action": normalized_action,
            "command": command.strip(),
            "approval_id": approval_id.strip(),
            "requester_id": requester_id.strip(),
            "requester_uuid": requester_uuid.strip(),
            "requester_name": requester_name.strip(),
            "requester_platform": requester_platform.strip(),
        }
        if server_id or normalized_action == "request":
            return await self.connection_manager.query(
                "command", server_id, params=params, timeout=10.0
            )

        results = await self.connection_manager.query_all(
            "command", params=params, timeout=10.0
        )
        if normalized_action == "list":
            approvals: list[dict[str, Any]] = []
            for result in results:
                if not result.get("ok"):
                    continue
                data = result.get("data")
                if not isinstance(data, dict) or not isinstance(
                    data.get("approvals"), list
                ):
                    continue
                for item in data["approvals"]:
                    if isinstance(item, dict):
                        normalized = dict(item)
                        normalized.setdefault(
                            "server_id", str(result.get("server_id") or "")
                        )
                        normalized.setdefault(
                            "server_name", str(result.get("server_name") or "")
                        )
                        approvals.append(normalized)
            return {
                "type": "query_result",
                "query": "command",
                "ok": bool(results) and any(result.get("ok") for result in results),
                "data": {"status": "pending_list", "approvals": approvals},
                "servers": results,
                "error": "" if results else "当前没有已连接的 Minecraft 服务器",
            }
        successful = next((result for result in results if result.get("ok")), None)
        if successful is not None:
            return successful
        return {
            "type": "query_result",
            "query": "command",
            "ok": False,
            "servers": results,
            "error": (
                "当前没有已连接的 Minecraft 服务器"
                if not results
                else "没有服务器接受该命令审批操作"
            ),
        }

    async def request_screenshot(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        reason: str = "",
    ) -> dict[str, Any]:
        params = {
            "player_uuid": player_uuid.strip(),
            "player_name": player_name.strip(),
            "reason": reason.strip() or "AstrBot 请求查看当前 Minecraft 画面。",
            "max_width": 240,
            "max_height": 135,
            "format": "jpeg",
        }
        return await self.connection_manager.query(
            "screenshot",
            server_id,
            params=params,
            timeout=self.screenshot_timeout_seconds,
        )

    async def notify_player(
        self,
        server_id: str | None,
        player_name: str,
        sender_name: str,
        sender_id: str,
        sender_platform: str,
        message: str,
    ) -> dict[str, Any]:
        params = {
            "player_name": player_name.strip(),
            "sender_name": sender_name.strip(),
            "sender_id": sender_id.strip(),
            "sender_platform": sender_platform.strip(),
            "message": message.strip(),
        }
        if server_id:
            return await self.connection_manager.query(
                "notify_player", server_id, params=params
            )
        results = await self.connection_manager.query_all(
            "notify_player", params=params
        )
        return {
            "type": "query_result",
            "query": "notify_player",
            "ok": any(result.get("ok") for result in results),
            "servers": results,
        }

    async def sync_binding(
        self,
        server_id: str | None,
        action: str,
        player_name: str,
        owner_key: str,
        owner_display: str,
    ) -> dict[str, Any]:
        params = {
            "action": action.strip().lower(),
            "player_name": player_name.strip(),
            "owner_key": owner_key.strip(),
            "owner_display": owner_display.strip(),
        }
        if server_id:
            return await self.connection_manager.query(
                "binding", server_id, params=params
            )
        results = await self.connection_manager.query_all("binding", params=params)
        return {
            "type": "query_result",
            "query": "binding",
            "ok": bool(results) and all(result.get("ok") for result in results),
            "servers": results,
            "error": "" if results else "当前没有已连接的 Minecraft 服务器",
        }

    async def replace_bindings(
        self, server_id: str, records: list[Any]
    ) -> dict[str, Any]:
        """Replace one connected server's binding cache after (re)connect."""

        reset = await self.connection_manager.query(
            "binding", server_id, params={"action": "reset"}
        )
        if not reset.get("ok"):
            return reset
        applied = 0
        for record in records:
            result = await self.connection_manager.query(
                "binding",
                server_id,
                params={
                    "action": "bind",
                    "player_name": str(record.player_name),
                    "owner_key": str(record.owner_key),
                    "owner_display": str(record.owner_display),
                },
            )
            if not result.get("ok"):
                return {
                    "ok": False,
                    "error": result.get("error") or "binding_reconcile_failed",
                    "applied": applied,
                }
            applied += 1
        return {"ok": True, "applied": applied, "server_id": server_id}

    async def replace_trusted_command_users(
        self, server_id: str, users: list[str], revision: int = 0
    ) -> dict[str, Any]:
        """Replace the Mod's in-memory AstrBot administrator trust set."""

        normalized: list[str] = []
        seen: set[str] = set()
        for user in users:
            value = str(user or "").strip()
            folded = value.casefold()
            if (
                not value
                or len(value) > 128
                or any(character.isspace() or ord(character) < 32 for character in value)
                or folded in seen
            ):
                continue
            seen.add(folded)
            normalized.append(value)
        return await self.connection_manager.query(
            "trusted_users",
            server_id,
            params={
                "action": "replace",
                "users": normalized[:256],
                "revision": max(0, int(revision)),
            },
        )

    async def local_status(self) -> dict[str, Any]:
        return {
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.snapshot(),
        }

    async def _handle_websocket(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            return web.Response(status=401, text="未授权")

        ws = web.WebSocketResponse(
            heartbeat=30, max_msg_size=self.websocket_max_message_bytes
        )
        await ws.prepare(request)
        logger.info("MineAstr WebSocket 客户端已连接。")

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await self._handle_text(ws, msg.data)
                elif msg.type == WSMsgType.ERROR:
                    logger.warning("MineAstr WebSocket 出错：%s", ws.exception())
        finally:
            metadata = await self.connection_manager.unregister(ws)
            if metadata:
                await self._notify_bridge_event(
                    {
                        "type": "event",
                        "event": "server_stop",
                        **metadata,
                        "time_ms": int(time.time() * 1000),
                    }
                )
            logger.info("MineAstr WebSocket 客户端已断开")
        return ws

    def _authorized(self, request: web.Request) -> bool:
        if not self._token_is_configured():
            return False
        expected = f"Bearer {self.token}".encode()
        actual = str(request.headers.get("Authorization") or "").encode()
        return hmac.compare_digest(expected, actual)

    def _token_is_configured(self) -> bool:
        return bool(self.token) and self.token.casefold() != "change-me"

    async def _handle_text(self, ws: web.WebSocketResponse, data: str) -> None:
        try:
            payload = json.loads(data)
        except json.JSONDecodeError:
            await self.connection_manager.send_error(ws, "无效的 JSON")
            return
        if not isinstance(payload, dict):
            await self.connection_manager.send_error(
                ws, "WebSocket 消息必须是 JSON 对象"
            )
            return

        try:
            payload_type = payload.get("type")
            if payload_type == "hello":
                await self._handle_hello(ws, payload)
            elif payload_type == "chat":
                if not await self.connection_manager.is_registered(ws):
                    await self.connection_manager.send_error(
                        ws, "请先发送 hello 完成注册"
                    )
                    return
                await self.connection_manager.mark_seen(ws)
                await self._handle_chat(ws, payload)
            elif payload_type == "ping":
                await self.connection_manager.mark_seen(ws)
                await self.connection_manager.send_pong(ws, payload.get("time_ms"))
            elif payload_type == "query_result":
                if not await self.connection_manager.is_registered(ws):
                    await self.connection_manager.send_error(
                        ws, "请先发送 hello 完成注册"
                    )
                    return
                await self.connection_manager.mark_seen(ws)
                await self.connection_manager.resolve_query(ws, payload)
            elif payload_type == "event":
                if not await self.connection_manager.is_registered(ws):
                    await self.connection_manager.send_error(
                        ws, "请先发送 hello 完成注册"
                    )
                    return
                await self.connection_manager.mark_seen(ws)
                await self._handle_bridge_event(ws, payload)
            else:
                await self.connection_manager.send_error(
                    ws, f"不支持的消息类型：{payload_type}"
                )
        except (TypeError, ValueError, RuntimeError) as exc:
            logger.warning("MineAstr 处理 WebSocket 消息失败：%s", exc)
            await self.connection_manager.send_error(ws, str(exc))

    async def _handle_hello(
        self, ws: web.WebSocketResponse, payload: dict[str, Any]
    ) -> None:
        try:
            protocol = int(payload.get("protocol", 0))
        except (TypeError, ValueError):
            await self.connection_manager.send_error(ws, "协议版本必须是整数")
            return
        if protocol != PROTOCOL_VERSION:
            await self.connection_manager.send_error(
                ws, f"不支持的协议版本：{protocol}"
            )
            return
        metadata, is_new = await self.connection_manager.register(ws, payload)
        logger.info(
            "MineAstr 已注册服务器 %s（%s）",
            payload.get("server_id", "minecraft"),
            payload.get("server_name", "Minecraft Server"),
        )
        if is_new:
            await self._notify_bridge_event(
                {
                    "type": "event",
                    "event": "server_start",
                    **metadata,
                    "time_ms": int(time.time() * 1000),
                }
            )

    async def _handle_bridge_event(
        self, ws: web.WebSocketResponse, payload: dict[str, Any]
    ) -> None:
        event_name = str(payload.get("event") or "").strip().lower()
        allowed_events = {
            "player_join",
            "player_leave",
            "player_death",
            "binding_code",
            "player_login_check",
        }
        if event_name not in allowed_events:
            raise ValueError(f"不支持的服务器事件：{event_name}")

        metadata = await self.connection_manager.metadata_for(ws)
        if not metadata:
            raise RuntimeError("Minecraft 服务器尚未注册")
        enriched = dict(payload)
        enriched.update(
            {
                "type": "event",
                "event": event_name,
                "server_id": metadata.get("server_id", "minecraft"),
                "server_name": metadata.get("server_name", "Minecraft Server"),
            }
        )
        results = await self._notify_bridge_event(enriched)

        if event_name == "player_login_check":
            response: dict[str, Any] = {
                "allowed": True,
                "message": "MineAstr AstrBot 侧未启用登录绑定检查。",
            }
            for candidate in results:
                if "allowed" in candidate:
                    response.update(candidate)
                    break
            await ws.send_str(
                json.dumps(
                    {
                        "type": "event_result",
                        "event": event_name,
                        "message_id": str(payload.get("message_id") or ""),
                        "ok": True,
                        **response,
                    },
                    ensure_ascii=False,
                )
            )

    async def _handle_chat(
        self, ws: web.WebSocketResponse, payload: dict[str, Any]
    ) -> None:
        content = _trim_content(payload.get("content"), self.max_message_length)
        if not content:
            return
        metadata = await self.connection_manager.metadata_for(ws)
        if not metadata:
            raise RuntimeError("Minecraft 服务器尚未注册")
        trusted_payload = dict(payload)
        trusted_payload["server_id"] = metadata.get("server_id", "minecraft")
        trusted_payload["server_name"] = metadata.get("server_name", "Minecraft Server")
        message = self._convert_chat(trusted_payload, content)
        event = MinecraftPlatformEvent(
            message_str=message.message_str,
            message_obj=message,
            platform_meta=self.meta(),
            session_id=message.session_id,
            connection_manager=self.connection_manager,
            bot_display_name=self.bot_display_name,
            translation_options=self._chat_translation_options,
        )
        self.commit_event(event)

    def _convert_chat(self, payload: dict[str, Any], content: str) -> AstrBotMessage:
        message = AstrBotMessage()
        player_uuid = str(
            payload.get("player_uuid") or payload.get("player_name") or "unknown"
        )
        player_name = str(payload.get("player_name") or player_uuid)
        message_chain, message_str, mention_target = self._parse_minecraft_message(
            content
        )
        message.type = MessageType.GROUP_MESSAGE
        message.group_id = self.group_id
        if message.group:
            message.group.group_name = self.group_name
        message.message_str = message_str
        message.message = message_chain
        raw_message = dict(payload)
        if mention_target:
            raw_message["minecraft_mentioned_bot"] = True
            raw_message["minecraft_mention_target"] = mention_target
        message.raw_message = raw_message
        message.self_id = self.bot_id
        message.session_id = self.group_id
        message.message_id = str(payload.get("message_id") or uuid.uuid4())
        message.sender = MessageMember(user_id=player_uuid, nickname=player_name)
        return message
