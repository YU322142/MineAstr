import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from aqqbot_compat import BindingStore
from test_z_discord_automation import MAIN


class GameDisplayNameTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = BindingStore(Path(self.directory.name) / "bindings.sqlite3")
        await self.store.initialize()
        self.plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        self.plugin._binding_store = self.store
        self.plugin._relay_message_records = {}

    async def asyncTearDown(self):
        self.directory.cleanup()

    async def bind(self, owner="default:42", name="Steve"):
        platform, user = owner.split(":", 1)
        return await self.store.bind(owner_key=owner, platform_id=platform,
                                     user_id=user, owner_display="社交昵称",
                                     player_name=name, max_bind_count=3)

    async def test_bound_qq_and_discord_prefer_mc_name(self):
        for owner, name in (("default:42", "Steve"), ("custom-discord:9", "Alex")):
            await self.bind(owner, name)
            self.assertEqual(await self.plugin._game_sender_name(owner, "社交昵称"), name)
        self.assertEqual(await self.plugin._game_sender_name("default:8", "未绑定昵称"), "未绑定昵称")

    async def test_binding_invalidates_negative_cache_and_unbinding_restores_fallback(self):
        self.assertEqual(await self.plugin._game_sender_name("default:42", "QQ"), "QQ")
        await self.bind()
        self.assertEqual(await self.plugin._game_sender_name("default:42", "QQ"), "Steve")
        await self.store.unbind("default:42")
        self.assertEqual(await self.plugin._game_sender_name("default:42", "QQ"), "QQ")

    async def test_multiple_bindings_use_oldest_then_next_after_unbind(self):
        await self.bind(name="Zebra")
        await self.bind(name="Alpha")
        self.assertEqual(await self.store.display_player_name("default:42"), "Zebra")
        await self.store.unbind("default:42", "Zebra")
        self.assertEqual(await self.store.display_player_name("default:42"), "Alpha")

    async def test_concurrent_display_reads_share_lookup_and_cache(self):
        await self.bind()
        with patch.object(self.store, "get_by_owner", wraps=self.store.get_by_owner) as lookup:
            result = await asyncio.gather(*(self.store.display_player_name("default:42") for _ in range(20)))
            self.assertEqual(result, ["Steve"] * 20)
            self.assertEqual(lookup.await_count, 1)

    async def test_display_cache_expiry_and_capacity(self):
        await self.bind()
        self.assertEqual(await self.store.display_player_name("default:42"), "Steve")
        self.store._display_names["default:42"] = (0.0, "ExpiredName")
        with patch.object(self.store, "get_by_owner", wraps=self.store.get_by_owner) as lookup:
            self.assertEqual(await self.store.display_player_name("default:42"), "Steve")
            self.assertEqual(lookup.await_count, 1)
        with patch.object(self.store, "get_by_owner", new=AsyncMock(return_value=[])):
            for index in range(513):
                await self.store.display_player_name(f"default:unknown-{index}")
        self.assertEqual(len(self.store._display_names), 512)
        self.assertNotIn("default:42", self.store._display_names)

    async def test_migration_invalidates_display_and_authentication_never_uses_display_cache(self):
        await self.bind(name="OldName")
        self.assertEqual(await self.store.display_player_name("default:42"), "OldName")
        await self.store.migrate_player_names(lambda name: "NewName")
        self.assertEqual(await self.store.display_player_name("default:42"), "NewName")
        self.assertIsNone(await self.store.get_by_player("OldName"))
        self.assertEqual((await self.store.get_by_player("NewName")).owner_key, "default:42")

    async def test_quote_maps_sender_id_without_mutating_social_context(self):
        await self.bind()
        social = {"sender_id": "42", "sender": "社交昵称", "text": "翻译后的引用", "message_id": "100"}
        game = await self.plugin._game_reply_context(social, "default")
        self.assertEqual(game["sender"], "Steve")
        self.assertEqual(game["text"], "翻译后的引用")
        self.assertEqual(social["sender"], "社交昵称")
        self.assertIs(await self.plugin._game_reply_context(social, "minecraft"), social)

    async def test_quote_uses_record_owner_and_never_guesses_by_nickname(self):
        await self.bind()
        self.plugin._relay_message_records["default:100"] = {"owner_key": "default:42", "sender_name": "社交昵称"}
        context = {"sender": "社交昵称", "message_id": "100", "text": "hello"}
        self.assertEqual((await self.plugin._game_reply_context(context, "default"))["sender"], "Steve")
        unknown = {**context, "message_id": "101"}
        self.assertIs(await self.plugin._game_reply_context(unknown, "default"), unknown)

    async def test_actual_relay_uses_mc_name_in_header_and_template_only_for_game(self):
        await self.bind()
        plugin = self.plugin
        plugin.config = {"bridge_settings": {"bridge_enabled": True,
                         "chat_to_game_template": "{sender}: {message}", "max_relay_length": 500}}
        plugin._relay_sessions = {"default:GroupMessage:100"}
        plugin._send_to_relay_sessions = AsyncMock()
        plugin._translate_relay_message = AsyncMock(return_value={})
        plugin._notify_mentioned_players = AsyncMock()
        adapter = SimpleNamespace(relay_chat=AsyncMock())
        plugin._minecraft_adapter = lambda: adapter
        event = SimpleNamespace(unified_msg_origin="default:GroupMessage:100", message_str="hello",
                                is_at_or_wake_command=False, get_platform_id=lambda: "default",
                                get_sender_id=lambda: "42", get_sender_name=lambda: "社交昵称",
                                stop_event=lambda: None)
        await plugin.mineastr_relay_message(event)
        self.assertEqual(adapter.relay_chat.await_args.args, ("Steve: hello", "Steve"))
        self.assertEqual(plugin._send_to_relay_sessions.await_args.args[0], "[社交昵称] hello")

    async def test_recall_uses_mc_name_in_game_and_social_name_on_other_platforms(self):
        await self.bind()
        plugin = self.plugin
        plugin.config = {}
        plugin._relay_sessions = {"default:GroupMessage:100", "discord:GroupMessage:200"}
        plugin._send_to_relay_sessions = AsyncMock()
        adapter = SimpleNamespace(relay_chat=AsyncMock())
        plugin._minecraft_adapter = lambda: adapter
        plugin._store_relay_message_record("default:1000", platform_id="default", message_id="1000",
                                          origin="default:GroupMessage:100", sender_name="社交昵称",
                                          owner_key="default:42")
        await plugin._relay_recalled_message("default", "1000")
        self.assertEqual(adapter.relay_chat.await_args.args, ("[Steve] 消息已撤回", "Steve"))
        self.assertEqual(plugin._send_to_relay_sessions.await_args.args[0], "[社交昵称] 消息已撤回")

    async def test_player_mention_displays_mc_name(self):
        await self.bind()
        self.plugin.config = {"bridge_settings": {"player_mention_enabled": True}}
        adapter = SimpleNamespace(notify_player=AsyncMock())
        self.plugin._minecraft_adapter = lambda: adapter
        event = SimpleNamespace(get_platform_id=lambda: "default", get_sender_id=lambda: "42",
                                get_sender_name=lambda: "社交昵称")
        await self.plugin._notify_mentioned_players(event, "@Alex hello")
        self.assertEqual(adapter.notify_player.await_args.args[2], "Steve")
