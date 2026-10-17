"""配置读取、分组迁移和保存 / Configuration access, grouped migration and persistence."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    logger,
)

from .mineastr_defaults import (
    AQQBOT_DEFAULT_CONFIG,
    CONFIG_GROUP_KEYS,
    CONFIG_KEY_GROUP,
    CONFIG_LAYOUT_VERSION,
)


class PluginSettings:
    """配置读取、分组迁移和保存 / Configuration access, grouped migration and persistence.

    唯一的配置分组和迁移规则；迁移不启动网络任务，保存沿用框架配置接口。
    Centralize grouping and migration without starting network tasks; save through the framework configuration API.
    """

    def __init__(self, plugin):
        self.plugin = plugin

    def _cfg(self, key: str) -> Any:
        default = AQQBOT_DEFAULT_CONFIG[key]
        try:
            group_name = CONFIG_KEY_GROUP.get(key)
            group = self.plugin.config.get(group_name) if group_name else None
            if isinstance(group, dict) and key in group:
                value = group.get(key, default)
            else:
                value = self.plugin.config.get(key, default)
        except (AttributeError, KeyError):
            value = default
        return default if value is None else value

    def _set_cfg(self, key: str, value: Any) -> None:
        group_name = CONFIG_KEY_GROUP.get(key)
        try:
            group = self.plugin.config.get(group_name) if group_name else None
            if isinstance(group, dict):
                group[key] = value
            else:
                self.plugin.config[key] = value
        except (AttributeError, TypeError):
            return

    def _migrate_grouped_config(self) -> bool:
        """Migrate legacy GUI layouts and consolidate translation instructions."""

        try:
            if not any(
                isinstance(self.plugin.config.get(group_name), dict)
                for group_name in CONFIG_GROUP_KEYS
            ):
                return False
            layout_version = int(
                self.plugin.config.get("config_layout_version", 0) or 0
            )
            if layout_version >= CONFIG_LAYOUT_VERSION:
                return False
            if layout_version < 1:
                for group_name, keys in CONFIG_GROUP_KEYS.items():
                    group = self.plugin.config.get(group_name)
                    if not isinstance(group, dict):
                        group = {}
                        self.plugin.config[group_name] = group
                    for key in keys:
                        group[key] = self.plugin.config.get(
                            key, AQQBOT_DEFAULT_CONFIG[key]
                        )

            bridge_settings = self.plugin.config.get("bridge_settings")
            if not isinstance(bridge_settings, dict):
                bridge_settings = {}
                self.plugin.config["bridge_settings"] = bridge_settings
            instruction_blocks: list[str] = []

            def add_instructions(value: Any) -> None:
                instructions = str(value or "").strip()
                if instructions and instructions not in instruction_blocks:
                    instruction_blocks.append(instructions)

            add_instructions(bridge_settings.get("translation_custom_instructions"))
            for group_name, profile_key in (
                ("qq_settings", "qq_notification_settings"),
                ("discord_settings", "discord_notification_settings"),
            ):
                group = self.plugin.config.get(group_name)
                profile = group.get(profile_key) if isinstance(group, dict) else None
                if isinstance(profile, dict):
                    add_instructions(
                        profile.pop("chat_translation_custom_instructions", "")
                    )
            channel_settings = bridge_settings.get("discord_channel_settings")
            if isinstance(channel_settings, list):
                for profile in channel_settings:
                    if isinstance(profile, dict):
                        add_instructions(
                            profile.pop("chat_translation_custom_instructions", "")
                        )
            bridge_settings["translation_custom_instructions"] = "\n\n".join(
                instruction_blocks
            )
            self.plugin.config["config_layout_version"] = CONFIG_LAYOUT_VERSION
            logger.info("MineAstr 已迁移配置并把旧平台/频道翻译提示词合并到全局设置。")
            return True
        except (AttributeError, TypeError, ValueError):
            return False

    def _cfg_bool(self, key: str) -> bool:
        value = self.plugin._cfg(key)
        if isinstance(value, str):
            return value.strip().lower() in {"1", "true", "yes", "on", "是", "开启"}
        return bool(value)

    def _cfg_int(self, key: str) -> int:
        try:
            return int(self.plugin._cfg(key))
        except (TypeError, ValueError):
            return int(AQQBOT_DEFAULT_CONFIG[key])

    def _bounded_cfg_int(self, key: str, minimum: int, maximum: int) -> int:
        return max(minimum, min(maximum, self.plugin._cfg_int(key)))

    def _save_plugin_config(self) -> None:
        save_config = getattr(self.plugin.config, "save_config", None)
        if callable(save_config):
            save_config()
