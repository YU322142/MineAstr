import json
import unittest
from pathlib import Path


class ConfigSchemaTests(unittest.TestCase):
    def test_metadata_updates_from_fork_plugin_branch(self):
        metadata_path = Path(__file__).resolve().parents[1] / "metadata.yaml"
        metadata = metadata_path.read_text(encoding="utf-8")
        self.assertIn("author: YU322142", metadata)
        self.assertIn(
            'repo: "https://github.com/YU322142/MineAstr/tree/astrbot-plugin"',
            metadata,
        )

    def test_newline_delimited_fields_use_astrbot_textarea_type(self):
        schema_path = Path(__file__).resolve().parents[1] / "_conf_schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        for key in (
            "relay_sessions",
            "chat_to_game_filters",
            "game_to_chat_filters",
            "qq_group_ids",
            "discord_guild_ids",
            "bridge_admin_users",
        ):
            with self.subTest(key=key):
                self.assertEqual(schema[key]["type"], "text")

    def test_platform_adapter_settings_are_not_duplicated_in_plugin_page(self):
        schema_path = Path(__file__).resolve().parents[1] / "_conf_schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
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
                self.assertNotIn(key, schema)

    def test_default_player_name_rule_is_aqqbot_compatible(self):
        schema_path = Path(__file__).resolve().parents[1] / "_conf_schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(schema["player_name_regex"]["default"], r"^\S{1,64}$")


if __name__ == "__main__":
    unittest.main()
