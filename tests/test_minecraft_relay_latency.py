import asyncio
import types
import unittest
from unittest.mock import AsyncMock, patch

from test_z_discord_automation import MAIN


QQ = "default:GroupMessage:10001"
DISCORD = "discord:GroupMessage:20002"


class MinecraftRelayLatencyTests(unittest.IsolatedAsyncioTestCase):
    def make_plugin(self, discord_translation=True):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        qq = MAIN.QQ_NOTIFICATION_DEFAULTS.copy()
        qq.update(chat_translation_enabled=False)
        discord = MAIN.DISCORD_NOTIFICATION_DEFAULTS.copy()
        discord.update(
            chat_translation_enabled=discord_translation,
            chat_translation_languages="en_us",
            chat_translation_show_original=False,
        )
        plugin.config = {
            "bridge_settings": {
                "bridge_enabled": True,
                "game_translation_enabled": True,
                "game_translation_languages": "ja_jp",
                "game_translation_show_original": False,
                "game_to_chat_filters": "",
                "game_to_chat_template": "[MC/{server}] {player}: {message}",
                "max_relay_length": 500,
                "translation_context_messages": 0,
            },
            "qq_settings": {"qq_notification_settings": qq},
            "discord_settings": {"discord_notification_settings": discord},
        }
        plugin._relay_sessions = {QQ, DISCORD}
        plugin._send_to_relay_session = AsyncMock()
        plugin._translate_text = AsyncMock(return_value={
            "source_language": "zh_cn",
            "translations": {"en_us": "Hello", "ja_jp": "こんにちは"},
        })
        adapter = types.SimpleNamespace(complete_native_chat=AsyncMock())
        plugin._minecraft_adapter = lambda: adapter
        event = types.SimpleNamespace(
            unified_msg_origin="minecraft:GroupMessage:minecraft",
            message_str="你好",
            message_obj=types.SimpleNamespace(raw_message={
                "server_id": "survival", "server_name": "Survival",
                "player_uuid": "uuid", "player_name": "Steve",
                "content": "你好", "native_original_content": "你好",
                "native_chat": True, "native_chat_id": "native-1",
                "connection_id": "connection-1", "target_languages": ["ja_jp"],
            }),
            get_platform_id=lambda: "minecraft",
            get_sender_id=lambda: "uuid", get_sender_name=lambda: "Steve",
            is_at_or_wake_command=False, stop_event=lambda: None,
        )
        return plugin, adapter, event

    async def run_gated(self, event, plugin, check, release):
        task = asyncio.create_task(plugin.mineastr_relay_message(event))
        try:
            await check(task)
        finally:
            release.set()
            await asyncio.wait_for(task, 2)

    async def test_original_session_does_not_wait_for_shared_translation(self):
        plugin, adapter, event = self.make_plugin()
        release = asyncio.Event()
        original_sent = asyncio.Event()

        async def translate(*args, **kwargs):
            await release.wait()
            return {"source_language": "zh_cn", "translations": {
                "en_us": "Hello", "ja_jp": "こんにちは",
            }}

        async def send(session, content):
            if session == QQ:
                original_sent.set()

        plugin._translate_text.side_effect = translate
        plugin._send_to_relay_session.side_effect = send

        async def check(task):
            await asyncio.wait_for(original_sent.wait(), 1)
            self.assertFalse(task.done())
            adapter.complete_native_chat.assert_not_awaited()
            self.assertEqual(plugin._send_to_relay_session.await_count, 1)
            plugin._send_to_relay_session.assert_awaited_with(
                QQ, "[MC/Survival] Steve: 你好",
            )

        await self.run_gated(event, plugin, check, release)
        plugin._translate_text.assert_awaited_once()
        self.assertEqual(plugin._translate_text.await_args.args[1], ("en_us", "ja_jp"))
        plugin._send_to_relay_session.assert_any_await(
            DISCORD, "[MC/Survival] Steve: Hello",
        )
        adapter.complete_native_chat.assert_awaited_once_with(
            "survival", "native-1", "你好", "Steve",
            connection_id="connection-1",
            translation_options={"translations": {"ja_jp": "こんにちは"}, "show_original": False},
        )

    async def test_slow_native_completion_does_not_delay_translated_platform(self):
        plugin, adapter, event = self.make_plugin()
        release = asyncio.Event()
        completing = asyncio.Event()
        translated_sent = asyncio.Event()

        async def complete(*args, **kwargs):
            completing.set()
            await release.wait()

        async def send(session, content):
            if session == DISCORD:
                translated_sent.set()

        adapter.complete_native_chat.side_effect = complete
        plugin._send_to_relay_session.side_effect = send

        async def check(task):
            await asyncio.wait_for(completing.wait(), 1)
            await asyncio.wait_for(translated_sent.wait(), 1)
            self.assertFalse(task.done())

        await self.run_gated(event, plugin, check, release)

    async def test_slow_original_destination_does_not_delay_other_destinations(self):
        plugin, adapter, event = self.make_plugin()
        release = asyncio.Event()
        translated_sent = asyncio.Event()
        native_completed = asyncio.Event()

        async def send(session, content):
            if session == QQ:
                await release.wait()
            else:
                translated_sent.set()

        async def complete(*args, **kwargs):
            native_completed.set()

        plugin._send_to_relay_session.side_effect = send
        adapter.complete_native_chat.side_effect = complete

        async def check(task):
            await asyncio.wait_for(translated_sent.wait(), 1)
            await asyncio.wait_for(native_completed.wait(), 1)
            self.assertFalse(task.done())

        await self.run_gated(event, plugin, check, release)

    async def test_changed_filter_translates_native_and_platform_text_concurrently(self):
        plugin, adapter, event = self.make_plugin()
        release = asyncio.Event()
        both_started = asyncio.Event()
        started = []

        async def translate(content, *args, **kwargs):
            started.append(content)
            if len(started) == 2:
                both_started.set()
            await release.wait()
            return {"source_language": "zh_cn", "translations": {
                "en_us": "Filtered hello", "ja_jp": "こんにちは",
            }}

        plugin._translate_text.side_effect = translate

        async def check(task):
            await asyncio.wait_for(both_started.wait(), 1)
            self.assertCountEqual(started, ["你好", "过滤后的你好"])

        with patch.object(MAIN, "apply_aqqbot_filters", return_value="过滤后的你好"):
            await self.run_gated(event, plugin, check, release)
        plugin._send_to_relay_session.assert_any_await(QQ, "[MC/Survival] Steve: 过滤后的你好")
        plugin._send_to_relay_session.assert_any_await(DISCORD, "[MC/Survival] Steve: Filtered hello")
        adapter.complete_native_chat.assert_awaited_once()

    async def test_dropped_platform_message_still_completes_native_chat(self):
        plugin, adapter, event = self.make_plugin()
        with patch.object(MAIN, "apply_aqqbot_filters", return_value=None):
            await plugin.mineastr_relay_message(event)
        plugin._send_to_relay_session.assert_not_awaited()
        adapter.complete_native_chat.assert_awaited_once()
        self.assertEqual(plugin._translate_text.await_args.args[1], ("ja_jp",))

    async def test_translation_failure_sends_each_platform_original_once(self):
        plugin, adapter, event = self.make_plugin()
        plugin._translate_text.side_effect = RuntimeError("model unavailable")
        await plugin.mineastr_relay_message(event)
        self.assertEqual(plugin._send_to_relay_session.await_count, 2)
        for session in (QQ, DISCORD):
            plugin._send_to_relay_session.assert_any_await(session, "[MC/Survival] Steve: 你好")
        self.assertEqual(adapter.complete_native_chat.await_args.kwargs["translation_options"], {})

    async def test_plain_chat_without_translation_never_calls_model(self):
        plugin, adapter, event = self.make_plugin(discord_translation=False)
        event.message_obj.raw_message.pop("native_chat")
        await plugin.mineastr_relay_message(event)
        plugin._translate_text.assert_not_awaited()
        adapter.complete_native_chat.assert_not_awaited()
        self.assertEqual(plugin._send_to_relay_session.await_count, 2)

    async def test_cancelling_event_cancels_inflight_translation(self):
        plugin, adapter, event = self.make_plugin()
        entered = asyncio.Event()
        cancelled = asyncio.Event()

        async def translate(*args, **kwargs):
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        plugin._translate_text.side_effect = translate
        task = asyncio.create_task(plugin.mineastr_relay_message(event))
        await asyncio.wait_for(entered.wait(), 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(cancelled.is_set())
        adapter.complete_native_chat.assert_not_awaited()
        self.assertFalse(any(call.args[0] == DISCORD for call in plugin._send_to_relay_session.await_args_list))
