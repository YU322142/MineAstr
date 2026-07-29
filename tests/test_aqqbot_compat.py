import asyncio
import tempfile
import unittest
from pathlib import Path

from aqqbot_compat import (
    BindingLimitError,
    BindingStore,
    PlayerAlreadyBoundError,
    apply_aqqbot_filters,
    format_template,
    normalize_owner_spec,
    safe_user_regex_fullmatch,
    sanitize_minecraft_login_name,
    strip_minecraft_colors,
)


class BindingStoreTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.store = BindingStore(
            Path(self.temporary_directory.name) / "bindings.sqlite3"
        )
        await self.store.initialize()

    async def asyncTearDown(self):
        self.temporary_directory.cleanup()

    async def test_bind_lookup_and_unbind_are_case_insensitive(self):
        record = await self.store.bind(
            owner_key="discord:42",
            platform_id="discord",
            user_id="42",
            owner_display="Alice",
            player_name="Steve",
            max_bind_count=1,
        )
        self.assertEqual(record.player_name, "Steve")
        self.assertEqual(
            (await self.store.get_by_player("sTeVe")).owner_key, "discord:42"
        )
        self.assertEqual(
            [item.player_name for item in await self.store.get_by_owner("discord:42")],
            ["Steve"],
        )
        removed = await self.store.unbind("discord:42", "STEVE")
        self.assertEqual([item.player_name for item in removed], ["Steve"])
        self.assertIsNone(await self.store.get_by_player("Steve"))

    async def test_player_is_globally_unique(self):
        await self.store.bind(
            owner_key="discord:42",
            platform_id="discord",
            user_id="42",
            owner_display="Alice",
            player_name="Alex",
            max_bind_count=2,
        )
        with self.assertRaises(PlayerAlreadyBoundError):
            await self.store.bind(
                owner_key="discord:43",
                platform_id="discord",
                user_id="43",
                owner_display="Bob",
                player_name="alex",
                max_bind_count=2,
            )

    async def test_max_bind_count_is_atomic(self):
        await self.store.bind(
            owner_key="discord:42",
            platform_id="discord",
            user_id="42",
            owner_display="Alice",
            player_name="Steve",
            max_bind_count=1,
        )
        with self.assertRaises(BindingLimitError):
            await self.store.bind(
                owner_key="discord:42",
                platform_id="discord",
                user_id="42",
                owner_display="Alice",
                player_name="Alex",
                max_bind_count=1,
            )

    async def test_concurrent_claim_has_one_winner(self):
        async def claim(owner: str):
            return await self.store.bind(
                owner_key=owner,
                platform_id="discord",
                user_id=owner.rsplit(":", 1)[-1],
                owner_display=owner,
                player_name="Notch",
                max_bind_count=1,
            )

        results = await asyncio.gather(
            claim("discord:1"), claim("discord:2"), return_exceptions=True
        )
        self.assertEqual(sum(not isinstance(item, Exception) for item in results), 1)
        self.assertEqual(
            sum(isinstance(item, PlayerAlreadyBoundError) for item in results), 1
        )

    async def test_unbind_all_returns_every_owner_binding(self):
        for player_name in ("Steve", "Alex"):
            await self.store.bind(
                owner_key="discord:42",
                platform_id="discord",
                user_id="42",
                owner_display="Alice",
                player_name=player_name,
                max_bind_count=2,
            )
        removed = await self.store.unbind("discord:42")
        self.assertEqual({item.player_name for item in removed}, {"Steve", "Alex"})
        self.assertEqual(await self.store.get_by_owner("discord:42"), [])

    async def test_discord_original_nickname_is_not_overwritten(self):
        first = await self.store.remember_discord_nickname(
            "discord:42", "guild-1", "Original"
        )
        second = await self.store.remember_discord_nickname(
            "discord:42", "guild-1", "MineAstrName"
        )
        self.assertEqual(first.original_nickname, "Original")
        self.assertEqual(second.original_nickname, "Original")

        removed = await self.store.pop_discord_nickname("discord:42", "guild-1")
        self.assertIsNotNone(removed)
        self.assertEqual(removed.original_nickname, "Original")
        self.assertIsNone(
            await self.store.get_discord_nickname("discord:42", "guild-1")
        )

    async def test_discord_none_nickname_can_be_restored(self):
        await self.store.remember_discord_nickname("discord:42", "guild-1", None)
        state = await self.store.get_discord_nickname("discord:42", "guild-1")
        self.assertIsNotNone(state)
        self.assertIsNone(state.original_nickname)

    async def test_migrates_legacy_login_display_name_without_address(self):
        await self.store.bind(
            owner_key="default:42",
            platform_id="default",
            user_id="42",
            owner_display="Alice",
            player_name="NekoYu_322142 (/[2001:db8::1]:4528)",
            max_bind_count=1,
        )
        migrated, conflicts = await self.store.migrate_player_names(
            sanitize_minecraft_login_name
        )
        self.assertEqual((migrated, conflicts), (1, 0))
        record = await self.store.get_by_player("NekoYu_322142")
        self.assertIsNotNone(record)
        self.assertEqual(record.player_name, "NekoYu_322142")


class CompatibilityHelperTests(unittest.TestCase):
    def test_user_regex_rejects_nested_quantifier_redos_patterns(self):
        self.assertFalse(safe_user_regex_fullmatch(r"^(a+)+$", "a" * 64 + "X"))
        self.assertTrue(safe_user_regex_fullmatch(r"^\S{1,64}$", "NekoYu_322142"))

    def test_template_values_are_not_recursively_interpreted(self):
        self.assertEqual(
            format_template("[{platform}] {message}", {
                "platform": "discord",
                "message": "{owner_key} stays literal",
                "owner_key": "secret",
            }),
            "[discord] {owner_key} stays literal",
        )

    def test_sanitizes_legacy_login_endpoint_suffix(self):
        self.assertEqual(
            sanitize_minecraft_login_name(
                "NekoYu_322142 (/[2001:db8:0:1::1234]:4528)"
            ),
            "NekoYu_322142",
        )
        self.assertEqual(
            sanitize_minecraft_login_name("Steve (/203.0.113.8:25565)"),
            "Steve",
        )
        self.assertEqual(sanitize_minecraft_login_name("Normal_Name"), "Normal_Name")

    def test_local_filter_and_regex_rules(self):
        rules = "\n".join(
            [
                "$filter:{bad} $replaceTo:{***}",
                r"$regex:{\d{11}} $replaceTo:{电话号码}",
            ]
        )
        self.assertEqual(apply_aqqbot_filters("bad 13800138000", rules), "*** 电话号码")

    def test_cancel_filter(self):
        self.assertIsNone(
            apply_aqqbot_filters("do not relay", "$filter:{not} $replaceTo:{!CANCEL}")
        )

    def test_remote_filter_is_not_loaded(self):
        rule = "$url:{http://127.0.0.1/words.json} $replaceTo:{***}"
        self.assertEqual(apply_aqqbot_filters("safe", rule), "safe")

    def test_discord_mention_owner_normalization(self):
        self.assertEqual(
            normalize_owner_spec("<@!123456>", "discord"),
            ("discord:123456", "discord", "123456"),
        )

    def test_format_and_minecraft_colors(self):
        self.assertEqual(
            format_template(
                "{player}: {message} {unknown}", {"player": "Alex", "message": "hi"}
            ),
            "Alex: hi {unknown}",
        )
        self.assertEqual(strip_minecraft_colors("§aGreen &cRed"), "Green Red")


if __name__ == "__main__":
    unittest.main()
