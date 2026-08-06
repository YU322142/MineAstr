import json
import unittest
from pathlib import Path


class ConfigSchemaTests(unittest.TestCase):
    SUPPORTED_TYPES = {
        "int",
        "float",
        "bool",
        "string",
        "text",
        "list",
        "file",
        "object",
        "template_list",
    }
    GROUP_NAMES = (
        "bridge_settings",
        "binding_settings",
        "qq_settings",
        "discord_settings",
        "admin_command_settings",
        "notification_settings",
    )

    @classmethod
    def _schema(cls):
        schema_path = Path(__file__).resolve().parents[1] / "_conf_schema.json"
        return json.loads(schema_path.read_text(encoding="utf-8"))

    @classmethod
    def _visible_field(cls, schema, key):
        matches = [
            schema[group_name]["items"][key]
            for group_name in cls.GROUP_NAMES
            if key in schema[group_name]["items"]
        ]
        if len(matches) != 1:
            raise AssertionError(f"expected one grouped GUI field for {key}: {matches}")
        return matches[0]

    def test_metadata_updates_from_fork_plugin_branch(self):
        metadata_path = Path(__file__).resolve().parents[1] / "metadata.yaml"
        metadata = metadata_path.read_text(encoding="utf-8")
        self.assertIn("author: YU322142", metadata)
        self.assertIn("version: v0.6.24", metadata)
        self.assertIn(
            'repo: "https://github.com/YU322142/MineAstr/tree/astrbot-plugin"',
            metadata,
        )
        main = (metadata_path.parent / "main.py").read_text(encoding="utf-8")
        self.assertIn('    "0.6.24",\n)', main)

    def test_newline_delimited_fields_use_astrbot_textarea_type(self):
        schema = self._schema()
        for key in (
            "relay_sessions",
            "chat_to_game_filters",
            "game_to_chat_filters",
            "game_translation_languages",
            "translation_custom_instructions",
            "qq_group_ids",
            "discord_guild_ids",
            "bridge_admin_users",
        ):
            with self.subTest(key=key):
                self.assertEqual(self._visible_field(schema, key)["type"], "text")

    def test_platform_adapter_settings_are_not_duplicated_in_plugin_page(self):
        schema = self._schema()
        grouped_keys = {
            key
            for group_name in self.GROUP_NAMES
            for key in schema[group_name]["items"]
        }
        for key in (
            "host",
            "port",
            "path",
            "token",
            "group_id",
            "group_name",
            "bot_id",
            "bot_display_name",
            "mention_aliases",
            "max_message_length",
            "outbound_max_message_length",
            "websocket_max_message_bytes",
            "screenshot_cooldown_seconds",
            "screenshot_timeout_seconds",
        ):
            with self.subTest(key=key):
                self.assertNotIn(key, grouped_keys)

    def test_default_player_name_rule_is_aqqbot_compatible(self):
        schema = self._schema()
        field = self._visible_field(schema, "player_name_regex")
        self.assertEqual(field["default"], r"^\S{1,64}$")

    def test_gui_is_grouped_and_legacy_values_remain_hidden_for_migration(self):
        schema = self._schema()
        for group_name in self.GROUP_NAMES:
            with self.subTest(group=group_name):
                self.assertEqual(schema[group_name]["type"], "object")
                self.assertTrue(schema[group_name]["items"])
        for key in (
            "relay_sessions",
            "verify_method",
            "qq_group_ids",
            "discord_guild_ids",
            "bridge_admin_users",
            "remote_command_enabled",
        ):
            with self.subTest(legacy_key=key):
                self.assertTrue(schema[key]["invisible"])
                self._visible_field(schema, key)

    def test_every_schema_type_is_supported_by_astrbot(self):
        def check_fields(fields, path=""):
            for key, field in fields.items():
                field_path = f"{path}.{key}" if path else key
                self.assertIn(
                    field["type"],
                    self.SUPPORTED_TYPES,
                    f"{field_path} uses unsupported type {field['type']}",
                )
                if field["type"] == "object":
                    self.assertIn("items", field, f"{field_path} is missing items")
                    check_fields(field["items"], field_path)
                if field["type"] == "template_list":
                    self.assertIn(
                        "templates", field, f"{field_path} is missing templates"
                    )
                    for template_key, template in field["templates"].items():
                        self.assertIn("items", template)
                        check_fields(
                            template["items"],
                            f"{field_path}.templates.{template_key}",
                        )

        check_fields(self._schema())

    def test_notification_gui_has_language_event_switches_and_platform_profiles(self):
        schema = self._schema()
        language = self._visible_field(schema, "notification_language")
        self.assertEqual(language["options"], ["zh_CN", "en_US"])
        for key in (
            "notify_server_start_enabled",
            "notify_server_stop_enabled",
            "notify_player_join_enabled",
            "notify_player_leave_enabled",
            "notify_player_death_enabled",
        ):
            with self.subTest(key=key):
                self.assertEqual(self._visible_field(schema, key)["type"], "bool")

        for key, expected_id in (
            ("qq_notification_settings", "default"),
            ("discord_notification_settings", "discord"),
        ):
            with self.subTest(key=key):
                settings = self._visible_field(schema, key)
                self.assertEqual(settings["type"], "object")
                self.assertEqual(
                    settings["items"]["platform_ids"]["default"], expected_id
                )
                self.assertIn("language", settings["items"])
                self.assertEqual(settings["items"]["language"]["type"], "text")
                self.assertIn("notifications_enabled", settings["items"])
                self.assertIn("notify_player_death_enabled", settings["items"])
                localized = settings["items"]["localized_templates"]
                self.assertEqual(localized["type"], "object")
                for language in ("zh_CN", "en_US"):
                    self.assertEqual(
                        localized["items"][language]["items"][
                            "notify_player_death"
                        ]["type"],
                        "text",
                    )

    def test_notification_defaults_use_mc_prefix_and_login_has_a_switch(self):
        schema = self._schema()
        for key in (
            "notify_server_start",
            "notify_server_stop",
            "notify_player_join",
            "notify_player_leave",
            "notify_player_death",
            "login_reject_message",
        ):
            with self.subTest(key=key):
                self.assertTrue(self._visible_field(schema, key)["default"].startswith("[MC]"))
        self.assertEqual(self._visible_field(schema, "need_bind_to_login")["type"], "bool")

    def test_game_translation_gui_exposes_client_language_and_original_controls(self):
        schema = self._schema()
        self.assertFalse(
            self._visible_field(schema, "game_translation_enabled")["default"]
        )
        self.assertEqual(
            self._visible_field(schema, "game_translation_languages")["type"],
            "text",
        )
        self.assertTrue(
            self._visible_field(schema, "game_translation_show_original")["default"]
        )
        self.assertEqual(
            self._visible_field(schema, "translation_custom_instructions")["type"],
            "text",
        )
        self.assertTrue(
            self._visible_field(schema, "relay_bot_conversations_to_game")[
                "default"
            ]
        )
        for key in ("qq_notification_settings", "discord_notification_settings"):
            items = self._visible_field(schema, key)["items"]
            self.assertEqual(items["chat_translation_languages"]["type"], "text")
            self.assertTrue(
                items["chat_translation_custom_instructions"]["invisible"]
            )

    def test_command_admin_sync_is_explicit_and_enabled_for_approval_flow(self):
        schema = self._schema()
        field = self._visible_field(schema, "sync_command_admins_to_server")
        self.assertEqual(field["type"], "bool")
        self.assertTrue(field["default"])

    def test_discord_channel_profiles_are_an_unbounded_template_list(self):
        schema = self._schema()
        field = self._visible_field(schema, "discord_channel_settings")
        self.assertEqual(field["type"], "template_list")
        self.assertEqual(field["default"], [])
        items = field["templates"]["discord_channel"]["items"]
        self.assertEqual(items["channel_ids"]["type"], "text")
        self.assertEqual(items["chat_translation_languages"]["type"], "text")
        self.assertTrue(
            items["chat_translation_custom_instructions"]["invisible"]
        )
        self.assertIn("localized_templates", items)


if __name__ == "__main__":
    unittest.main()
