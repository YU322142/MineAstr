"""通知配置、语言模板与死亡原因 / Notification profiles, language templates and death messages."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    copy,
    format_template,
    parse_items,
    re,
)

from .mineastr_defaults import (
    DAMAGE_REASON_PRESETS,
    LEGACY_NOTIFICATION_DEFAULTS,
    NOTIFICATION_EVENT_CONFIG,
    NOTIFICATION_PRESETS,
)


class NotificationService:
    """通知配置、语言模板与死亡原因 / Notification profiles, language templates and death messages.

    仅负责平台通知配置与本地化格式；连接和实际投递交给其他组件。
    Resolve notification profiles and localized text; delegate connectivity and delivery.
    """

    def __init__(self, plugin):
        self.plugin = plugin

    @staticmethod
    def _session_platform_id(session: str) -> str:
        return str(session or "").split(":", 1)[0].strip().casefold()

    @staticmethod
    def _session_channel_candidates(session: str) -> set[str]:
        normalized = str(session or "").strip().casefold()
        if not normalized:
            return set()
        parts = [part.strip() for part in normalized.split(":") if part.strip()]
        candidates = {normalized}
        if len(parts) >= 3:
            candidates.add(parts[-1])
            candidates.add(":".join(parts[2:]))
        return candidates

    @staticmethod
    def _normalize_notification_language(value: Any) -> str:
        normalized = str(value or "zh_CN").strip().replace("-", "_")
        for language in NOTIFICATION_PRESETS:
            if language.casefold() == normalized.casefold():
                return language
        return "zh_CN"

    @staticmethod
    def _notification_languages(value: Any) -> tuple[str, ...]:
        languages: list[str] = []
        for item in parse_items(value):
            normalized = str(item or "").strip().replace("-", "_")
            selected = next(
                (
                    language
                    for language in NOTIFICATION_PRESETS
                    if language.casefold() == normalized.casefold()
                ),
                None,
            )
            if selected and selected not in languages:
                languages.append(selected)
        return tuple(languages or ("zh_CN",))

    def _platform_notification_profile(
        self, platform_id: str, session: str = ""
    ) -> dict[str, Any] | None:
        target = platform_id.strip().casefold()
        for key in ("qq_notification_settings", "discord_notification_settings"):
            profile = self.plugin._cfg(key)
            if not isinstance(profile, dict):
                continue
            configured_ids = {
                item.casefold() for item in parse_items(profile.get("platform_ids"))
            }
            if target and target in configured_ids:
                if key == "discord_notification_settings" and session:
                    candidates = self.plugin._session_channel_candidates(session)
                    channel_settings = self.plugin._cfg("discord_channel_settings")
                    if isinstance(channel_settings, list):
                        for configured in channel_settings:
                            if not isinstance(configured, dict):
                                continue
                            if configured.get("enabled") is False:
                                continue
                            channel_ids = {
                                item.casefold()
                                for item in parse_items(configured.get("channel_ids"))
                            }
                            if channel_ids and not channel_ids.isdisjoint(candidates):
                                merged = copy.deepcopy(profile)
                                merged.update(
                                    {
                                        item_key: item_value
                                        for item_key, item_value in configured.items()
                                        if item_key
                                        not in {
                                            "__template_key",
                                            "template",
                                            "enabled",
                                            "channel_ids",
                                            "name",
                                        }
                                    }
                                )
                                return merged
                return profile
        return None

    @staticmethod
    def _uses_notification_preset(key: str, configured: Any) -> bool:
        custom = str(configured or "").strip()
        known_defaults = {presets[key] for presets in NOTIFICATION_PRESETS.values()}
        legacy = LEGACY_NOTIFICATION_DEFAULTS.get(key)
        if legacy:
            known_defaults.add(legacy)
        return not custom or custom in known_defaults

    @staticmethod
    def _localized_template(key: str, language: str, configured: Any) -> str:
        language = NotificationService._normalize_notification_language(language)
        selected = NOTIFICATION_PRESETS[language][key]
        custom = str(configured or "").strip()
        return (
            selected
            if NotificationService._uses_notification_preset(key, configured)
            else custom
        )

    @staticmethod
    def _profile_localized_template(
        profile: dict[str, Any], language: str, template_key: str
    ) -> str:
        localized = profile.get("localized_templates")
        if not isinstance(localized, dict):
            return ""
        language_templates = localized.get(language)
        if not isinstance(language_templates, dict):
            return ""
        return str(language_templates.get(template_key) or "").strip()

    @staticmethod
    def _death_reason(payload: dict[str, Any], player_name: str, language: str) -> str:
        raw = str(payload.get("reason") or payload.get("death_message") or "").strip()
        if player_name and raw.casefold().startswith(player_name.casefold()):
            suffix = raw[len(player_name) :]
            if not suffix or suffix[0].isspace():
                raw = suffix.strip()
        language = NotificationService._normalize_notification_language(language)
        damage_type = str(payload.get("death_type") or "").strip()
        attacker = str(
            payload.get("attacker")
            or payload.get("direct_entity")
            or ("未知实体" if language == "zh_CN" else "unknown entity")
        ).strip()
        template = DAMAGE_REASON_PRESETS.get(language, {}).get(damage_type)
        if template:
            return template.format(attacker=attacker)
        if language == "zh_CN":
            english_patterns = (
                (r"^died$", "未知原因"),
                (r"^drowned$", "溺水"),
                (r"^starved to death$", "饥饿"),
                (r"^fell from a high place$", "从高处坠落"),
                (r"^hit the ground too hard$", "重重摔在地上"),
                (r"^burned to death$", "被烧死"),
                (r"^tried to swim in lava$", "试图在熔岩里游泳"),
                (r"^was slain by (.+)$", r"被 \1 杀死"),
                (r"^was shot by (.+)$", r"被 \1 射杀"),
                (r"^was blown up by (.+)$", r"被 \1 炸死"),
            )
            for pattern, replacement in english_patterns:
                if re.fullmatch(pattern, raw, flags=re.IGNORECASE):
                    return re.sub(pattern, replacement, raw, flags=re.IGNORECASE)
            return raw or "未知原因"
        return raw or damage_type or "unknown cause"

    async def _send_event_to_relay_sessions(
        self,
        event_name: str,
        values: dict[str, str],
        payload: dict[str, Any],
    ) -> None:
        enabled_key, template_key = NOTIFICATION_EVENT_CONFIG[event_name]
        for session in sorted(self.plugin._relay_sessions):
            platform_id = self.plugin._session_platform_id(session)
            profile = self.plugin._platform_notification_profile(platform_id, session)
            if profile is not None and profile.get("notifications_enabled") is False:
                continue
            enabled = self.plugin._cfg_bool(enabled_key)
            if profile is not None and isinstance(profile.get(enabled_key), bool):
                enabled = bool(profile[enabled_key])
            if not enabled:
                continue
            languages = self.plugin._notification_languages(
                (profile.get("language") if profile is not None else None)
                or self.plugin._cfg("notification_language")
            )
            common_template = (
                str(profile.get(template_key) or "").strip()
                if profile is not None
                else ""
            )
            if common_template:
                languages = languages[:1]

            rendered_messages: list[str] = []
            for language in languages:
                if common_template:
                    configured_template = common_template
                elif profile is not None:
                    configured_template = self.plugin._profile_localized_template(
                        profile, language, template_key
                    )
                else:
                    configured_template = self.plugin._cfg(template_key)
                template = self.plugin._localized_template(
                    template_key, language, configured_template
                )
                localized_values = dict(values)
                if event_name == "player_death":
                    localized_values["reason"] = self.plugin._death_reason(
                        payload, values.get("player", ""), language
                    )
                rendered = format_template(template, localized_values)
                if rendered and rendered not in rendered_messages:
                    rendered_messages.append(rendered)
            if rendered_messages:
                await self.plugin._send_to_relay_session(
                    session, "\n".join(rendered_messages)
                )
