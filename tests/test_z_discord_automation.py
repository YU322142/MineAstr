import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path


def _identity_decorator(*args, **kwargs):
    def decorator(value):
        return value

    return decorator


def _install_main_stubs():
    astrbot = sys.modules.setdefault("astrbot", types.ModuleType("astrbot"))
    api = sys.modules.setdefault("astrbot.api", types.ModuleType("astrbot.api"))
    event = sys.modules.setdefault(
        "astrbot.api.event", types.ModuleType("astrbot.api.event")
    )
    components = sys.modules.setdefault(
        "astrbot.api.message_components",
        types.ModuleType("astrbot.api.message_components"),
    )
    star = types.ModuleType("astrbot.api.star")

    class Logger:
        def debug(self, *args, **kwargs):
            pass

        info = warning = error = debug

    class CustomFilter:
        pass

    class FilterStub:
        on_platform_loaded = staticmethod(_identity_decorator)
        custom_filter = staticmethod(_identity_decorator)
        on_llm_request = staticmethod(_identity_decorator)
        llm_tool = staticmethod(_identity_decorator)

        @staticmethod
        def command_group(*args, **kwargs):
            def decorator(value):
                value.command = _identity_decorator
                return value

            return decorator

    FilterStub.CustomFilter = CustomFilter

    class AstrMessageEvent:
        pass

    class MessageChain:
        def __init__(self, chain=None):
            self.chain = chain or []

    class Plain:
        def __init__(self, text):
            self.text = text

    class Context:
        pass

    class Star:
        def __init__(self, context):
            self.context = context

    api.logger = getattr(api, "logger", Logger())
    event.AstrMessageEvent = AstrMessageEvent
    event.MessageChain = MessageChain
    event.filter = FilterStub()
    components.Plain = Plain
    star.Context = Context
    star.Star = Star
    star.register = _identity_decorator
    astrbot.api = api
    sys.modules["astrbot.api.star"] = star


def _load_main_module():
    _install_main_stubs()
    plugin_root = Path(__file__).resolve().parents[1]
    package_name = "mineastr_discord_test_package"
    package = types.ModuleType(package_name)
    package.__path__ = [str(plugin_root)]
    sys.modules[package_name] = package
    spec = importlib.util.spec_from_file_location(
        f"{package_name}.main", plugin_root / "main.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


MAIN = _load_main_module()


class FakeGuild:
    def __init__(self, guild_id, name="Test Guild"):
        self.id = guild_id
        self.name = name
        self.members = {}

    def get_member(self, user_id):
        return self.members.get(user_id)

    async def fetch_member(self, user_id):
        if user_id not in self.members:
            raise LookupError(user_id)
        return self.members[user_id]


class FakeMember:
    def __init__(self, user_id, guild, nickname):
        self.id = user_id
        self.guild = guild
        self.nick = nickname
        self.edits = []

    async def edit(self, *, nick, reason):
        self.edits.append((nick, reason))
        self.nick = nick


class FakeClient:
    def __init__(self, guilds):
        self.guilds = guilds
        self.intents = types.SimpleNamespace(members=True)
        self.listeners = {}

    def add_listener(self, callback, name):
        self.listeners[name] = callback

    def remove_listener(self, callback, name):
        if self.listeners.get(name) is callback:
            self.listeners.pop(name)


class FakeAdapter:
    def __init__(self, platform_id, client):
        self.platform_id = platform_id
        self.client = client

    def meta(self):
        return types.SimpleNamespace(name="discord", id=self.platform_id)


class FakeContext:
    def __init__(self, adapter):
        self.adapter = adapter
        self.platform_manager = types.SimpleNamespace(platform_insts=[adapter])

    def get_platform_inst(self, platform_id):
        if platform_id == self.adapter.platform_id:
            return self.adapter
        return None


class DiscordAutomationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.guild = FakeGuild(100)
        self.member = FakeMember(42, self.guild, "Original Nick")
        self.guild.members[42] = self.member
        self.adapter = FakeAdapter("discord-main", FakeClient([self.guild]))
        self.plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        self.plugin.context = FakeContext(self.adapter)
        self.plugin.config = {
            "binding_database": str(
                Path(self.temporary_directory.name) / "bindings.sqlite3"
            ),
            "discord_guild_ids": "100",
        }
        self.plugin._binding_store = MAIN.BindingStore(
            self.plugin.config["binding_database"]
        )
        self.plugin._discord_listener_bindings = {}
        await self.plugin._binding_store.initialize()

    async def asyncTearDown(self):
        self.temporary_directory.cleanup()

    async def _bind(self, player_name="Steve"):
        return await self.plugin._binding_store.bind(
            owner_key="discord-main:42",
            platform_id="discord-main",
            user_id="42",
            owner_display="Alice",
            player_name=player_name,
            max_bind_count=2,
        )

    async def test_bind_changes_and_unbind_restores_nickname(self):
        await self._bind()
        result = await self.plugin._refresh_discord_nickname_for_owner(
            "discord-main", "42", "discord-main:42", self.member
        )
        self.assertTrue(result["ok"])
        self.assertEqual(self.member.nick, "Steve")

        await self.plugin._binding_store.unbind("discord-main:42", "Steve")
        result = await self.plugin._refresh_discord_nickname_for_owner(
            "discord-main", "42", "discord-main:42", self.member
        )
        self.assertTrue(result["ok"])
        self.assertEqual(self.member.nick, "Original Nick")

    async def test_member_remove_listener_attaches_to_astrbot_discord_client(self):
        await self.plugin._attach_discord_listeners_with_retry()
        self.assertIn("on_member_remove", self.adapter.client.listeners)
        self.assertIn("discord-main", self.plugin._discord_listener_bindings)

        self.plugin._detach_discord_listeners()
        self.assertNotIn("on_member_remove", self.adapter.client.listeners)

    async def test_member_leave_unbinds_all_and_syncs_each_record(self):
        await self._bind("Steve")
        await self._bind("Alex")
        synced = []

        async def sync_binding(action, record):
            synced.append((action, record.player_name))
            return {"ok": True}

        self.plugin._sync_binding_to_server = sync_binding
        self.guild.members.clear()
        await self.plugin._on_discord_member_remove("discord-main", self.member)

        self.assertEqual(
            await self.plugin._binding_store.get_by_owner("discord-main:42"), []
        )
        self.assertEqual(set(synced), {("unbind", "Steve"), ("unbind", "Alex")})

    def test_discord_nickname_keeps_earliest_whole_names_within_limit(self):
        self.plugin.config["discord_nickname_template"] = "{players}"
        records = [
            types.SimpleNamespace(
                player_name=name,
                owner_display="Alice",
                user_id="42",
            )
            for name in ("FirstPlayer", "SecondPlayer", "ThirdPlayer")
        ]
        self.assertEqual(
            self.plugin._discord_nickname(records), "FirstPlayer, SecondPlayer"
        )


if __name__ == "__main__":
    unittest.main()
