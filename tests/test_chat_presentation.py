import unittest
from types import SimpleNamespace
from unittest.mock import patch
from test_minecraft_protocol import FakeWebSocket
from minecraft_adapter import MinecraftPlatformAdapter
from test_z_discord_automation import MAIN


class ChatPresentationTests(unittest.IsolatedAsyncioTestCase):
    async def test_qq_sticker_only_event_reaches_game_media_protocol(self):
        url = "https://qpic.cn/animated-face.gif"
        event = SimpleNamespace(message_obj=SimpleNamespace(message=[], raw_message={
            "message": [{"type": "mface", "data": {"url": url}}]
        }))
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {"bridge_settings": {"relay_images_to_game": True}}
        payloads = await plugin._game_media_payloads(plugin._event_media(event))
        adapter = MinecraftPlatformAdapter({}, {}, None)
        socket = FakeWebSocket()
        await adapter.connection_manager.register(socket, {"server_id": "minecraft"})
        await adapter.relay_chat("[图片]", "sender", origin="default:GroupMessage:1", media=payloads)
        self.assertEqual(socket.sent[-1]["media"][0]["url"], url)

    async def test_discord_embed_only_event_reaches_game_media_protocol(self):
        url = "https://media.tenor.com/animated.gif"
        event = SimpleNamespace(message_obj=SimpleNamespace(message=[], raw_message=SimpleNamespace(
            embeds=[SimpleNamespace(image=SimpleNamespace(url=url), thumbnail=None)]
        )))
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {"bridge_settings": {"relay_images_to_game": True}}
        payloads = await plugin._game_media_payloads(plugin._event_media(event))
        self.assertEqual(payloads[0]["url"], url)

    async def test_replies_inherit_qq_discord_and_minecraft_origin(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        socket = FakeWebSocket()
        await adapter.connection_manager.register(socket, {"server_id": "minecraft"})
        for origin, expected in (("default:GroupMessage:1", "qq"), ("discord:GroupMessage:2", "discord"), ("minecraft:GroupMessage:minecraft", "minecraft")):
            await adapter.relay_chat("bot answer", "AstrBot", origin=origin)
            self.assertEqual(socket.sent[-1]["sender_platform"], expected)

    async def test_custom_platform_id_uses_plugin_profile(self):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        plugin.config = {"qq_settings": {"qq_notification_settings": {"platform_ids": ["friends-qq"]}},
                         "discord_settings": {"discord_notification_settings": {"platform_ids": ["friends-dc"]}}}
        plugin.context = SimpleNamespace(platform_manager=SimpleNamespace(platform_insts=[]))
        self.assertEqual(plugin._game_chat_platform("friends-qq:GroupMessage:1"), "qq")
        self.assertEqual(plugin._game_chat_platform("friends-dc:GroupMessage:2"), "discord")
        self.assertEqual(plugin._game_chat_platform("minecraft:GroupMessage:minecraft"), "minecraft")

    async def test_custom_callback_preserves_translations_and_media(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        socket = FakeWebSocket()
        await adapter.connection_manager.register(socket, {"server_id": "minecraft"})
        adapter.set_chat_platform_handler(lambda origin: "discord")
        await adapter.relay_chat("Hello", "AstrBot", origin="custom-dc:GroupMessage:1",
                                 translation_options={"translations": {"zh_cn": "你好"}},
                                 media=[{"url": "https://example.com/image.png", "name": "image"}])
        self.assertEqual(socket.sent[-1]["sender_platform"], "discord")
        self.assertEqual(socket.sent[-1]["translations"], {"zh_cn": "你好"})
        self.assertEqual(len(socket.sent[-1]["media"]), 1)

    async def test_native_bot_image_only_reply_is_not_dropped(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        socket = FakeWebSocket()
        await adapter.connection_manager.register(socket, {"server_id": "minecraft"})
        await adapter._handle_chat(socket, {"content": "question", "player_name": "Alice"})
        event = adapter.committed_events[-1]
        event._image_relay_handler = lambda items: [{"url": "https://example.com/image.png", "name": "image"}]
        from astrbot.api.event import MessageChain
        with patch("minecraft_adapter._image_media_from_chain", return_value=[{"url": "https://example.com/image.png"}]):
            await event.send(MessageChain([]))
        self.assertEqual(socket.sent[-1]["sender_platform"], "minecraft")
        self.assertEqual(socket.sent[-1]["content"], "[图片]")
        self.assertEqual(len(socket.sent[-1]["media"]), 1)
