import asyncio
import json
import sys
import types
import unittest
from dataclasses import dataclass
from enum import Enum
from types import SimpleNamespace


def _install_astrbot_stubs():
    astrbot = types.ModuleType("astrbot")
    api = types.ModuleType("astrbot.api")
    event = types.ModuleType("astrbot.api.event")
    components = types.ModuleType("astrbot.api.message_components")
    platform = types.ModuleType("astrbot.api.platform")
    core = types.ModuleType("astrbot.core")
    core_platform = types.ModuleType("astrbot.core.platform")
    session_module = types.ModuleType("astrbot.core.platform.message_session")

    class Logger:
        def debug(self, *args, **kwargs):
            pass

        info = warning = error = debug

    class Plain:
        def __init__(self, text):
            self.text = text

    class MessageChain:
        def __init__(self, chain=None):
            self.chain = chain or []

    class AstrMessageEvent:
        def __init__(self, message_str, message_obj, platform_meta, session_id):
            self.message_str = message_str
            self.message_obj = message_obj
            self.platform_meta = platform_meta
            self.session_id = session_id

        async def send(self, message):
            return None

    class MessageType(Enum):
        GROUP_MESSAGE = "GroupMessage"
        FRIEND_MESSAGE = "FriendMessage"

    class AstrBotMessage:
        def __init__(self):
            self.group = None

    @dataclass
    class MessageMember:
        user_id: str
        nickname: str

    @dataclass
    class PlatformMetadata:
        name: str
        description: str
        id: str

    class Platform:
        def __init__(self, config=None, event_queue=None):
            self.platform_config = config or {}
            self.event_queue = event_queue
            self.committed_events = []

        def commit_event(self, value):
            self.committed_events.append(value)

    def register_platform_adapter(*args, **kwargs):
        def decorator(value):
            return value

        return decorator

    class MessageSesion:
        pass

    api.logger = Logger()
    event.AstrMessageEvent = AstrMessageEvent
    event.MessageChain = MessageChain
    components.Plain = Plain
    platform.AstrBotMessage = AstrBotMessage
    platform.MessageMember = MessageMember
    platform.MessageType = MessageType
    platform.Platform = Platform
    platform.PlatformMetadata = PlatformMetadata
    platform.register_platform_adapter = register_platform_adapter
    session_module.MessageSesion = MessageSesion

    sys.modules.update(
        {
            "astrbot": astrbot,
            "astrbot.api": api,
            "astrbot.api.event": event,
            "astrbot.api.message_components": components,
            "astrbot.api.platform": platform,
            "astrbot.core": core,
            "astrbot.core.platform": core_platform,
            "astrbot.core.platform.message_session": session_module,
        }
    )


_install_astrbot_stubs()

from minecraft_adapter import MinecraftConnectionManager, MinecraftPlatformAdapter
from astrbot.api.event import MessageChain
from astrbot.api.message_components import Plain


class FakeWebSocket:
    def __init__(self):
        self.closed = False
        self.sent = []

    async def send_str(self, data):
        self.sent.append(json.loads(data))

    async def close(self):
        self.closed = True


class ConnectionManagerTests(unittest.IsolatedAsyncioTestCase):
    async def test_query_result_must_come_from_selected_connection(self):
        manager = MinecraftConnectionManager("AstrBot", 2000)
        selected = FakeWebSocket()
        attacker = FakeWebSocket()
        await manager.register(
            selected,
            {"server_id": "one", "server_name": "One", "mod_version": "1"},
        )
        await manager.register(
            attacker,
            {"server_id": "two", "server_name": "Two", "mod_version": "1"},
        )

        task = asyncio.create_task(manager.query("status", "one", timeout=1))
        await asyncio.sleep(0)
        request = selected.sent[-1]
        response = {
            "type": "query_result",
            "message_id": request["message_id"],
            "query": "status",
            "ok": True,
        }
        await manager.resolve_query(attacker, response)
        self.assertFalse(task.done())
        await manager.resolve_query(selected, response)
        self.assertTrue((await task)["ok"])

    async def test_targeted_and_broadcast_chat(self):
        manager = MinecraftConnectionManager("AstrBot", 2000)
        first = FakeWebSocket()
        second = FakeWebSocket()
        await manager.register(first, {"server_id": "one"})
        await manager.register(second, {"server_id": "two"})

        await manager.send_chat("target", "Discord/Alice", "two")
        self.assertEqual(first.sent, [])
        self.assertEqual(second.sent[-1]["content"], "target")
        await manager.send_chat("all", "Discord/Alice")
        self.assertEqual(first.sent[-1]["content"], "all")
        self.assertEqual(second.sent[-1]["content"], "all")

        await manager.send_chat(
            "original",
            "Discord/Alice",
            translations={"zh-cn": "译文", "invalid locale!": "ignored"},
            show_original=True,
        )
        translated = first.sent[-1]
        self.assertEqual(translated["translations"], {"zh_cn": "译文"})
        self.assertTrue(translated["show_original"])


class AdapterEventTests(unittest.IsolatedAsyncioTestCase):
    async def test_reconnect_replaces_server_binding_cache(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        websocket = FakeWebSocket()
        await adapter.connection_manager.register(
            websocket,
            {"server_id": "survival", "server_name": "Survival"},
        )
        record = SimpleNamespace(
            player_name="Steve",
            owner_key="discord-main:42",
            owner_display="Alice",
        )

        task = asyncio.create_task(adapter.replace_bindings("survival", [record]))
        await asyncio.sleep(0)
        reset_request = websocket.sent[-1]
        self.assertEqual(reset_request["action"], "reset")
        await adapter.connection_manager.resolve_query(
            websocket,
            {
                "type": "query_result",
                "query": "binding",
                "message_id": reset_request["message_id"],
                "ok": True,
            },
        )

        await asyncio.sleep(0)
        bind_request = websocket.sent[-1]
        self.assertEqual(bind_request["action"], "bind")
        self.assertEqual(bind_request["player_name"], "Steve")
        await adapter.connection_manager.resolve_query(
            websocket,
            {
                "type": "query_result",
                "query": "binding",
                "message_id": bind_request["message_id"],
                "ok": True,
            },
        )
        result = await task
        self.assertTrue(result["ok"])
        self.assertEqual(result["applied"], 1)

    async def test_reconnect_replaces_trusted_command_users(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        websocket = FakeWebSocket()
        await adapter.connection_manager.register(
            websocket,
            {"server_id": "survival", "server_name": "Survival"},
        )

        task = asyncio.create_task(
            adapter.replace_trusted_command_users(
                "survival",
                ["default:42", "discord:99", "bad value", "default:42"],
            )
        )
        await asyncio.sleep(0)
        request = websocket.sent[-1]
        self.assertEqual(request["query"], "trusted_users")
        self.assertEqual(request["action"], "replace")
        self.assertEqual(request["users"], ["default:42", "discord:99"])
        await adapter.connection_manager.resolve_query(
            websocket,
            {
                "type": "query_result",
                "query": "trusted_users",
                "message_id": request["message_id"],
                "ok": True,
                "data": {"synced_count": 2},
            },
        )
        result = await task
        self.assertTrue(result["ok"])
        self.assertEqual(result["data"]["synced_count"], 2)

    async def test_login_check_listener_controls_event_result(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        websocket = FakeWebSocket()
        await adapter.connection_manager.register(
            websocket,
            {"server_id": "survival", "server_name": "Survival"},
        )

        async def deny_login(payload):
            self.assertEqual(payload["server_id"], "survival")
            return {
                "allowed": False,
                "message": "请先绑定",
                "message_key": "disconnect.mineastr.login.not_bound",
            }

        adapter.add_bridge_event_listener(deny_login)
        await adapter._handle_bridge_event(
            websocket,
            {
                "type": "event",
                "event": "player_login_check",
                "message_id": "login-1",
                "player_name": "Steve",
            },
        )
        response = websocket.sent[-1]
        self.assertEqual(response["type"], "event_result")
        self.assertEqual(response["message_id"], "login-1")
        self.assertFalse(response["allowed"])
        self.assertEqual(
            response["message_key"], "disconnect.mineastr.login.not_bound"
        )

    async def test_chat_uses_registered_server_identity(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        websocket = FakeWebSocket()
        await adapter.connection_manager.register(
            websocket,
            {"server_id": "trusted", "server_name": "Trusted Server"},
        )
        await adapter._handle_chat(
            websocket,
            {
                "type": "chat",
                "server_id": "spoofed",
                "server_name": "Spoofed",
                "player_uuid": "uuid",
                "player_name": "Steve",
                "content": "hello",
            },
        )
        event = adapter.committed_events[-1]
        self.assertEqual(event.message_obj.raw_message["server_id"], "trusted")
        self.assertEqual(event.message_obj.raw_message["server_name"], "Trusted Server")

    async def test_minecraft_event_reply_uses_translation_handler(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        websocket = FakeWebSocket()
        await adapter.connection_manager.register(
            websocket,
            {"server_id": "trusted", "server_name": "Trusted Server"},
        )
        origins = []

        async def translate(content, origin):
            origins.append((content, origin))
            return {
                "translations": {"zh_cn": "回答"},
                "show_original": True,
            }

        adapter.set_chat_translation_handler(translate)
        await adapter._handle_chat(
            websocket,
            {
                "type": "chat",
                "player_uuid": "uuid",
                "player_name": "Steve",
                "content": "@AstrBot hello",
            },
        )
        event = adapter.committed_events[-1]
        await event.send(MessageChain([Plain("answer")]))

        self.assertEqual(origins, [("answer", "minecraft")])
        self.assertEqual(websocket.sent[-1]["content"], "answer")
        self.assertEqual(websocket.sent[-1]["translations"], {"zh_cn": "回答"})
        self.assertTrue(websocket.sent[-1]["show_original"])


if __name__ == "__main__":
    unittest.main()
