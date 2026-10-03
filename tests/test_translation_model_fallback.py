import asyncio
import copy
import json
import time
import types
import unittest
from unittest.mock import AsyncMock

from test_z_discord_automation import MAIN

MineAstrPlugin = MAIN.MineAstrPlugin

GOOD = json.dumps(
    {"source_language": "zh_cn", "translations": {"en_us": "Wait at the base."}}
)


class Provider:
    def __init__(self, model_id, output=GOOD, delay=0, error=False, enabled=True):
        self.provider_config = {"id": model_id, "enable": enabled}
        self.output, self.delay, self.error, self.calls = output, delay, error, 0

    async def text_chat(self, **kwargs):
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.error:
            raise RuntimeError("Synthetic provider error")
        return types.SimpleNamespace(completion_text=self.output)


def make_plugin(primary, fallbacks=(), default_id=None, **models):
    plugin = MineAstrPlugin.__new__(MineAstrPlugin)
    plugin.config = {
        group: {key: copy.deepcopy(MAIN.AQQBOT_DEFAULT_CONFIG[key]) for key in keys}
        for group, keys in MAIN.CONFIG_GROUP_KEYS.items()
    }
    plugin.config["bridge_settings"].update(
        relay_sessions="default:GroupMessage:10001\ndiscord:GroupMessage:20002\ndiscord:GroupMessage:20003",
        game_translation_enabled=True,
        discord_channel_settings=[
            {
                "channel_ids": "20002",
                "enabled": True,
                "chat_translation_enabled": True,
                "chat_translation_languages": "zh_cn",
            },
            {
                "channel_ids": "20003",
                "enabled": True,
                "chat_translation_enabled": True,
                "chat_translation_languages": "en_us",
            },
        ],
    )
    plugin.config["qq_settings"]["qq_notification_settings"].update(
        chat_translation_enabled=True, chat_translation_languages="zh_cn"
    )
    plugin.config["discord_settings"]["discord_notification_settings"].update(
        chat_translation_enabled=True, chat_translation_languages="en_us"
    )
    plugin.config["bridge_settings"]["game_translation_provider_id"] = primary
    plugin.config["bridge_settings"]["game_translation_fallback_provider_ids"] = list(
        fallbacks
    )
    plugin.context = types.SimpleNamespace(
        get_using_provider=lambda origin=None: models.get(default_id),
        get_provider_by_id=lambda model_id: models.get(model_id),
    )
    return plugin


async def main():
    passed = 0

    async def response(plugin, **kwargs):
        return await plugin._translation_response(
            "text",
            "system",
            ("zh_cn", "en_us"),
            "minecraft:test",
            timeout_seconds=kwargs.pop("timeout_seconds", 1),
            **kwargs,
        )

    for case, first in [
        ("provider exception", Provider("a", error=True)),
        ("invalid JSON", Provider("a", output="bad")),
        (
            "incomplete JSON",
            Provider("a", output='{"source_language":"zh_cn","translations":{}}'),
        ),
        ("disabled model", Provider("a", enabled=False)),
    ]:
        second = Provider("b")
        plugin = make_plugin("a", ["b"], a=first, b=second)
        assert (await response(plugin)).completion_text == GOOD
        assert second.calls == 1
        print("PASS", case)
        passed += 1

    second = Provider("b")
    plugin = make_plugin("deleted", ["b"], b=second)
    await response(plugin)
    assert second.calls == 1
    print("PASS deleted model")
    passed += 1

    first, second = Provider("a", delay=2), Provider("b")
    plugin = make_plugin("a", ["b"], a=first, b=second)
    started = time.monotonic()
    await response(plugin, timeout_seconds=0.25)
    assert time.monotonic() - started < 0.35 and second.calls == 1
    print("PASS shared timeout budget")
    passed += 1

    first, second, default = (
        Provider("a", error=True),
        Provider("b", error=True),
        Provider("default"),
    )
    plugin = make_plugin(
        "a", ["a", "b", "b", "default"], "default", a=first, b=second, default=default
    )
    assert plugin._translation_provider_ids("mc") == ("a", "b", "default")
    await response(plugin)
    assert (first.calls, second.calls, default.calls) == (1, 1, 1)
    print("PASS ordered fallback, deduplication and session default")
    passed += 1

    first = Provider("a", error=True)
    plugin = make_plugin("a", a=first)
    result = await plugin._translate_text("text", ("en_us",), inject_glossary=False)
    assert result == {}
    print("PASS all failures preserve original")
    passed += 1

    first = Provider("a", delay=2)
    plugin = make_plugin("a", a=first)
    task = asyncio.create_task(response(plugin))
    await asyncio.sleep(0.01)
    task.cancel()
    try:
        await task
        raise AssertionError("Cancellation was swallowed")
    except asyncio.CancelledError:
        pass
    print("PASS cancellation propagates")
    passed += 1

    first = Provider("a", output='{"source_language":"zh_cn","translations":{}}')
    plugin = make_plugin("a", a=first)
    await plugin._translation_response("x", "x", ("zh_cn",), "", timeout_seconds=1)
    print("PASS same-language content needs no translation")
    passed += 1

    first, second = Provider("a"), Provider("b")
    plugin = make_plugin("a", a=first, b=second)
    await plugin._translate_text("cache test", ("en_us",), inject_glossary=False)
    await plugin._translate_text("cache test", ("en_us",), inject_glossary=False)
    assert first.calls == 1
    plugin.config["bridge_settings"]["game_translation_provider_id"] = "b"
    await plugin._translate_text("cache test", ("en_us",), inject_glossary=False)
    assert second.calls == 1
    print("PASS model change invalidates text cache")
    passed += 1

    first, second = (
        Provider("a", output='{"source_language":"zh_cn","translations":{}}'),
        Provider(
            "b",
            output='{"source_language":"zh_cn","source_text":"text","translations":{"en_us":"image text"}}',
        ),
    )
    plugin = make_plugin("a", ["b"], a=first, b=second)
    await response(plugin, image_urls=["data:image/png;base64,test"])
    assert second.calls == 1
    print("PASS image fallback validates recognized text")
    passed += 1

    provider = Provider("a")
    plugin = make_plugin("a", a=provider)
    plugin._relay_sessions = set(
        plugin.config["bridge_settings"]["relay_sessions"].split()
    )
    raw = {
        "content": "我在基地等你",
        "native_original_content": "我在基地等你",
        "native_chat": True,
        "native_chat_id": "unit-test",
        "server_id": "minecraft",
        "player_name": "Tester",
    }
    event = types.SimpleNamespace(
        message_str=raw["content"],
        unified_msg_origin="minecraft:GroupMessage:test",
        is_at_or_wake_command=False,
        get_platform_id=lambda: "minecraft",
        get_sender_name=lambda: "Tester",
        get_sender_id=lambda: "tester",
        stop_event=lambda: None,
    )
    plugin._event_raw_message = lambda event: raw
    plugin._event_reply_context = lambda event: {}
    plugin._is_reply_to_synced_message = lambda *args: False
    plugin._event_media = lambda event: []
    plugin._native_chat_translation_languages = lambda raw: ("zh_cn", "en_us")
    plugin._append_translation_glossary = AsyncMock(
        side_effect=lambda instructions, source: instructions
    )
    plugin._complete_native_minecraft_chat = AsyncMock()
    plugin._send_to_relay_sessions = AsyncMock()
    await plugin.mineastr_relay_message(event)
    assert provider.calls == 1, (
        "Native chat and cross-platform relay must share translation"
    )
    reached = set()
    for sent in plugin._send_to_relay_sessions.call_args_list:
        result = sent.kwargs["translation_result"]
        assert "en_us" in result["translations"]
        for session in sent.kwargs["sessions"]:
            reached.add(session)
            rendered = await plugin._platform_chat_message(
                session, sent.args[0], translation_result=result
            )
            if session.endswith("20003"):
                assert "Wait at the base." in rendered
            else:
                assert "我在基地等你" in rendered
    assert reached == plugin._relay_sessions
    print("PASS actual MC handler translates once and formats QQ/Discord targets")
    passed += 1
    print(f"{passed} translation and fallback checks passed; no external messages sent")


class TranslationModelFallbackTests(unittest.IsolatedAsyncioTestCase):
    async def test_model_failures_and_mc_relay_contracts(self):
        await main()

    async def test_slow_destination_does_not_delay_other_platforms(self):
        plugin = make_plugin("unused")
        blocked = asyncio.Event()
        ready = asyncio.Event()

        async def send(session, content):
            if session == "slow":
                await blocked.wait()
            else:
                ready.set()

        plugin._send_to_relay_session = send
        task = asyncio.create_task(
            plugin._send_to_relay_sessions(
                "text", sessions=["slow", "fast"], translation_result={}
            )
        )
        try:
            await asyncio.wait_for(ready.wait(), 0.5)
            self.assertFalse(task.done())
        finally:
            blocked.set()
            await task

    async def test_game_delivery_does_not_wait_for_a_slow_platform_send(self):
        plugin = make_plugin("unused")
        plugin._relay_sessions = set(
            plugin.config["bridge_settings"]["relay_sessions"].split()
        )
        blocked, game_ready = asyncio.Event(), asyncio.Event()
        event = types.SimpleNamespace(
            message_str="Hello",
            unified_msg_origin="default:GroupMessage:10001",
            is_at_or_wake_command=False,
            get_platform_id=lambda: "default",
            get_sender_name=lambda: "Tester",
            get_sender_id=lambda: "test",
            stop_event=lambda: None,
        )
        plugin._event_reply_context = lambda event: {}
        plugin._is_reply_to_synced_message = lambda *args: False
        plugin._event_media = lambda event: []
        plugin._identity = lambda event: {
            "owner_display": "Tester",
            "owner_key": "default:test",
            "platform_id": "default",
            "user_id": "test",
        }
        plugin._remember_relay_message = lambda *args, **kwargs: None
        plugin._game_sender_name = AsyncMock(return_value="Tester")
        plugin._game_reply_context = AsyncMock(return_value={})
        plugin._game_media_payloads = AsyncMock(return_value=[])
        plugin._notify_mentioned_players = AsyncMock()
        plugin._translate_relay_message = AsyncMock(return_value={})

        async def send_platform(*args, **kwargs):
            await blocked.wait()

        async def send_game(*args, **kwargs):
            game_ready.set()

        plugin._send_to_relay_sessions = send_platform
        plugin._minecraft_adapter = lambda: types.SimpleNamespace(relay_chat=send_game)
        task = asyncio.create_task(plugin.mineastr_relay_message(event))
        try:
            await asyncio.wait_for(game_ready.wait(), 0.5)
            self.assertFalse(task.done())
        finally:
            blocked.set()
            await task
