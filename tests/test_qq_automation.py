import tempfile
import types
import unittest
from pathlib import Path

from test_z_discord_automation import MAIN


class FakeQQBot:
    def __init__(self):
        self.subscribers = {}
        self.actions = []

    def subscribe(self, event_name, callback):
        self.subscribers[event_name] = callback

    def unsubscribe(self, event_name, callback):
        if self.subscribers.get(event_name) is callback:
            self.subscribers.pop(event_name)

    async def call_action(self, action, **params):
        self.actions.append((action, params))
        if action == "get_group_member_info":
            return {"nickname": "Yu322142", "card": "Old Card"}
        return {}


class FakeQQAdapter:
    def __init__(self, platform_id, bot):
        self.platform_id = platform_id
        self.bot = bot

    def meta(self):
        return types.SimpleNamespace(name="aiocqhttp", id=self.platform_id)

    def get_client(self):
        return self.bot


class FakeQQContext:
    def __init__(self, adapter):
        self.adapter = adapter
        self.platform_manager = types.SimpleNamespace(platform_insts=[adapter])

    def get_platform_inst(self, platform_id):
        return self.adapter if platform_id == self.adapter.platform_id else None


class FakeQQMessageEvent:
    def get_platform_name(self):
        return "aiocqhttp"

    def get_platform_id(self):
        return "default"

    def get_group_id(self):
        return "10001"


class QQAutomationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.bot = FakeQQBot()
        self.adapter = FakeQQAdapter("default", self.bot)
        self.plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        self.plugin.context = FakeQQContext(self.adapter)
        self.plugin.config = {
            "binding_database": str(
                Path(self.temporary_directory.name) / "bindings.sqlite3"
            ),
            "qq_group_ids": "10001",
            "qq_auto_group_card": True,
            "qq_group_card_template": "{player}",
        }
        self.plugin._binding_store = MAIN.BindingStore(
            self.plugin.config["binding_database"]
        )
        self.plugin._qq_listener_bindings = {}
        await self.plugin._binding_store.initialize()

    async def asyncTearDown(self):
        self.temporary_directory.cleanup()

    async def _bind(self, player_name=".Bedrock玩家"):
        return await self.plugin._binding_store.bind(
            owner_key="default:123456789",
            platform_id="default",
            user_id="123456789",
            owner_display="ExampleQQUser",
            player_name=player_name,
            max_bind_count=2,
        )

    async def test_qq_group_decrease_listener_unbinds_and_syncs(self):
        await self._bind("Steve")
        synced = []

        async def sync_binding(action, record):
            synced.append((action, record.player_name))
            return {"ok": True}

        self.plugin._sync_binding_to_server = sync_binding
        self.plugin._attach_qq_listeners()
        callback = self.bot.subscribers["notice.group_decrease"]
        await callback(
            {"group_id": 10001, "user_id": 123456789, "self_id": 987654321}
        )

        self.assertEqual(
            await self.plugin._binding_store.get_by_owner("default:123456789"), []
        )
        self.assertEqual(synced, [("unbind", "Steve")])
        self.plugin._detach_qq_listeners()
        self.assertNotIn("notice.group_decrease", self.bot.subscribers)

    async def test_qq_bind_updates_group_card(self):
        record = await self._bind()
        result = await self.plugin._update_qq_group_card_after_bind(
            FakeQQMessageEvent(), record
        )
        self.assertTrue(result["ok"])
        self.assertEqual(
            self.bot.actions,
            [
                (
                    "get_group_member_info",
                    {
                        "group_id": 10001,
                        "user_id": 123456789,
                        "no_cache": True,
                    },
                ),
                (
                    "set_group_card",
                    {
                        "group_id": 10001,
                        "user_id": 123456789,
                        "card": ".Bedrock玩家",
                    },
                )
            ],
        )

    def test_aqqbot_compatible_player_name_rule(self):
        self.assertEqual(self.plugin._validated_player_name(".Bedrock玩家"), ".Bedrock玩家")
        with self.assertRaises(MAIN.BindingError):
            self.plugin._validated_player_name("name with spaces")

    def test_verify_code_uses_authenticated_server_name_not_group_name_regex(self):
        self.assertEqual(
            self.plugin._validated_verified_player_name("Bedrock Player"),
            "Bedrock Player",
        )
        with self.assertRaises(MAIN.BindingError):
            self.plugin._validated_verified_player_name("Bad\x00Name")

    def test_qq_card_keeps_earliest_whole_names_within_limit(self):
        records = [
            types.SimpleNamespace(
                player_name=name,
                owner_display="ExampleQQUser",
                user_id="123456789",
            )
            for name in (
                "FirstPlayerName",
                "SecondPlayerName",
                "ThirdPlayerName",
                "FourthPlayerName",
            )
        ]
        self.assertEqual(
            self.plugin._bounded_nickname(records, "{players}", 60),
            "FirstPlayerName, SecondPlayerName, ThirdPlayerName",
        )


if __name__ == "__main__":
    unittest.main()
