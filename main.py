"""MineAstr 插件注册与生命周期 / Plugin registration and lifecycle.

阅读各业务模块了解实现；此文件保留框架扫描需要的命令、事件和工具签名。
Read domain modules for implementation; keep framework-scanned entry signatures here.
"""

# 保留旧模块的显式导出，兼容已有集成与测试。
# Explicit legacy exports preserve existing integrations and tests.
from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    BindingError,
    BindingStore,
    CallToolResult,
    Callable,
    Context,
    CooldownTracker,
    DEFAULT_BINDING_DATABASE,
    GlossaryProvider,
    Image,
    ImageContent,
    MessageChain,
    Path,
    Plain,
    PlayerAlreadyBoundError,
    QQImageSource,
    Reply,
    RotatingFileHandler,
    Star,
    TextContent,
    _RELAY_DIAGNOSTICS,
    append_glossary_instructions,
    apply_aqqbot_filters,
    asyncio,
    base64,
    copy,
    deque,
    discord_media,
    filter,
    format_template,
    get_astrbot_data_path,
    hashlib,
    json,
    logger,
    logging,
    math,
    normalize_owner_spec,
    parse_items,
    qq_images,
    qq_market_faces,
    re,
    register,
    safe_user_regex_fullmatch,
    sanitize_minecraft_login_name,
    strip_minecraft_colors,
    time,
    trim_message,
    unique_media,
    urllib,
)
from .mineastr_defaults import (
    AQQBOT_DEFAULT_CONFIG,
    CONFIG_GROUP_KEYS,
    CONFIG_KEY_GROUP,
    CONFIG_LAYOUT_VERSION,
    DAMAGE_REASON_PRESETS,
    DEFAULT_GAME_IMAGE_INLINE_MAX_BYTES,
    DEFAULT_GAME_IMAGE_MAX_ITEMS,
    DEFAULT_PLAYER_NAME_REGEX,
    DISCORD_NOTIFICATION_DEFAULTS,
    GAME_TRANSLATION_CACHE_LIMIT,
    IMAGE_TRANSLATION_CACHE_LIMIT,
    IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS,
    IMAGE_TRANSLATION_PROTOCOL_BUDGET_SECONDS,
    LEGACY_NOTIFICATION_DEFAULTS,
    LEGACY_PLAYER_NAME_REGEX,
    LOCALIZED_NOTIFICATION_TEMPLATE_DEFAULTS,
    MAX_GAME_IMAGE_INLINE_BYTES,
    MAX_SCREENSHOT_SAVE_BYTES,
    MINEASTR_EXTERNAL_HINT_KEYWORDS,
    MINEASTR_TOOL_HINT,
    NOTIFICATION_EVENT_CONFIG,
    NOTIFICATION_PRESETS,
    QQ_NOTIFICATION_DEFAULTS,
    RELAY_MESSAGE_RECORD_LIMIT,
    SAFE_COMMAND_ADMIN_RE,
    SCREENSHOT_DIR,
    TRANSLATION_PROMPT_MAX_LENGTH,
    _ACTIVE_RELAY_SESSIONS,
)

from .mineastr_components import ServiceField, component_for
from .mineastr_settings import PluginSettings
from .mineastr_translation import TranslationService
from .mineastr_image_translation import ImageTranslationService
from .mineastr_cache import CacheMaintenance
from .mineastr_qq import QQAutomation
from .mineastr_discord import DiscordAutomation
from .mineastr_plugin_media import PluginMedia
from .mineastr_notifications import NotificationService
from .mineastr_commands import CommandService
from .mineastr_bindings import BindingService
from .mineastr_screenshots import ScreenshotService
from .mineastr_plugin_tools import MinecraftTools
from .mineastr_relay import RelayService
from .mineastr_fanout import PlatformFanout
from .mineastr_relay_records import RelayRecords


class MineAstrRelayFilter(filter.CustomFilter):
    """Only wake the relay handler for Minecraft or explicitly linked sessions."""

    def filter(self, event: AstrMessageEvent, cfg: Any) -> bool:
        return (
            str(event.get_platform_id() or "") == "minecraft"
            or event.unified_msg_origin in _ACTIVE_RELAY_SESSIONS
        )


@register(
    "astrbot_plugin_mineastr",
    "MineAstr",
    "将 Minecraft 与 AstrBot 的 QQ/Discord 群聊互联，并提供账号绑定、通知、状态查询、受控命令与 LLM 工具。",
    "0.8.1",
)
class MineAstrPlugin(Star):
    """AstrBot 生命周期和注册门面 / Lifecycle and registered-entry facade.

    业务处理委托给组合服务；字段描述器明确兼容旧访问路径的状态归属。
    Services own behavior and state; descriptors preserve existing private access paths.
    """

    def _component(self, service_type):
        return component_for(self, service_type)

    # 兼容旧字段路径；可变状态只在对应服务初始化一次。
    # Preserve legacy field paths; initialize mutable state once in its owner.
    _glossary_provider = ServiceField(TranslationService, "_glossary_provider")
    _game_translation_cache = ServiceField(
        TranslationService, "_game_translation_cache"
    )
    _game_translation_cache_accessed_at = ServiceField(
        TranslationService, "_game_translation_cache_accessed_at"
    )
    _image_translation_cache = ServiceField(
        ImageTranslationService, "_image_translation_cache"
    )
    _image_translation_cache_accessed_at = ServiceField(
        ImageTranslationService, "_image_translation_cache_accessed_at"
    )
    _translation_contexts = ServiceField(TranslationService, "_translation_contexts")
    _translation_context_accessed_at = ServiceField(
        TranslationService, "_translation_context_accessed_at"
    )
    _cache_cleanup_task = ServiceField(CacheMaintenance, "_cache_cleanup_task")
    _qq_listener_bindings = ServiceField(QQAutomation, "_qq_listener_bindings")
    _discord_listener_bindings = ServiceField(
        DiscordAutomation, "_discord_listener_bindings"
    )
    _discord_attach_task = ServiceField(DiscordAutomation, "_discord_attach_task")
    _command_admin_sync_lock = ServiceField(CommandService, "_command_admin_sync_lock")
    _command_admin_revision = ServiceField(CommandService, "_command_admin_revision")
    _reported_invalid_command_admin_count = ServiceField(
        CommandService, "_reported_invalid_command_admin_count"
    )
    _pending_command_approvals = ServiceField(
        CommandService, "_pending_command_approvals"
    )
    _binding_store = ServiceField(BindingService, "_binding_store")
    _binding_reconcile_tasks = ServiceField(BindingService, "_binding_reconcile_tasks")
    _binding_reconcile_by_server = ServiceField(
        BindingService, "_binding_reconcile_by_server"
    )
    _binding_snapshot_lock = ServiceField(BindingService, "_binding_snapshot_lock")
    _verify_codes = ServiceField(BindingService, "_verify_codes")
    _cooldowns = ServiceField(BindingService, "_cooldowns")
    _screenshot_last_request_at = ServiceField(
        ScreenshotService, "_screenshot_last_request_at"
    )
    _screenshot_cooldown_lock = ServiceField(
        ScreenshotService, "_screenshot_cooldown_lock"
    )
    _relay_sessions = ServiceField(PlatformFanout, "_relay_sessions")
    _relay_message_records = ServiceField(RelayRecords, "_relay_message_records")
    _relay_outbound_message_records = ServiceField(
        RelayRecords, "_relay_outbound_message_records"
    )

    async def _sync_binding_to_server(self, action: str, record: Any) -> dict[str, Any]:
        return await self._component(BindingService)._sync_binding_to_server(
            action, record
        )

    async def _periodic_binding_reconcile(self) -> None:
        return await self._component(BindingService)._periodic_binding_reconcile()

    def _schedule_binding_reconcile(self, server_id: str) -> None:
        return self._component(BindingService)._schedule_binding_reconcile(server_id)

    async def _schedule_connected_server_reconcile(
        self, include_command_admins: bool = True
    ) -> None:
        return await self._component(
            BindingService
        )._schedule_connected_server_reconcile(include_command_admins)

    async def _reconcile_bindings_to_server(self, server_id: str) -> dict[str, Any]:
        return await self._component(BindingService)._reconcile_bindings_to_server(
            server_id
        )

    def __init__(self, context: Context, config: Any | None = None):
        super().__init__(context)
        self.config = config if config is not None else {}
        try:
            config_changed = self._migrate_grouped_config()
            if str(self._cfg("player_name_regex")) == LEGACY_PLAYER_NAME_REGEX:
                self._set_cfg("player_name_regex", DEFAULT_PLAYER_NAME_REGEX)
                config_changed = True
                logger.info("MineAstr 已把旧版正版玩家名规则迁移为 AQQBot 兼容规则。")
            if str(self._cfg("discord_nickname_template")) == "{player}":
                self._set_cfg("discord_nickname_template", "{players}")
                config_changed = True
            for key, legacy_default in LEGACY_NOTIFICATION_DEFAULTS.items():
                if str(self._cfg(key)) == legacy_default:
                    self._set_cfg(key, NOTIFICATION_PRESETS["zh_CN"][key])
                    config_changed = True
            if config_changed:
                save_config = getattr(self.config, "save_config", None)
                if callable(save_config):
                    save_config()
        except (AttributeError, TypeError):
            pass
        self._glossary_provider = GlossaryProvider(
            lambda: str(self._cfg("translation_glossary_path") or ""),
            logger=lambda message: logger.warning(message),
        )
        self._listener_adapter: Any | None = None
        self._binding_store = BindingStore(str(self._cfg("binding_database")))
        self._refresh_relay_sessions()
        _RELAY_DIAGNOSTICS.info(
            "Plugin loaded: game_translation=%s targets=%s target_languages=%s provider=%s",
            self._cfg_bool("game_translation_enabled"),
            sorted(self._relay_sessions),
            self._platform_translation_languages_for_sessions(
                sorted(self._relay_sessions)
            ),
            self._cfg("game_translation_provider_id") or "session-default",
        )
        from .minecraft_adapter import MinecraftPlatformAdapter  # noqa: F401

    async def initialize(self):
        from .mineastr_image_optimizer import ImageOptimizer

        self._image_optimizer = ImageOptimizer()
        await self._binding_store.initialize()
        migrated, conflicts = await self._binding_store.migrate_player_names(
            sanitize_minecraft_login_name
        )
        if migrated:
            logger.warning("MineAstr 已迁移 %d 条误带网络地址的旧绑定记录。", migrated)
        if conflicts:
            logger.warning(
                "MineAstr 有 %d 条旧绑定因纯玩家名已被占用而未自动迁移。", conflicts
            )
        self._attach_adapter_listener()
        await self._schedule_connected_server_reconcile()
        periodic = asyncio.create_task(
            self._periodic_binding_reconcile(), name="mineastr-periodic-binding-sync"
        )
        self._binding_reconcile_tasks.add(periodic)
        periodic.add_done_callback(self._binding_reconcile_tasks.discard)
        self._attach_qq_listeners()
        self._schedule_discord_listener_attach()
        await self._run_cache_cleanup()
        self._start_cache_cleanup_task()
        logger.info(
            "MineAstr 插件已初始化：AQQBot 兼容功能与 AstrBot QQ/Discord 桥接已加载。"
        )

    async def terminate(self):
        optimizer = getattr(self, "_image_optimizer", None)
        if optimizer is not None:
            await optimizer.close()
        cleanup_task = getattr(self, "_cache_cleanup_task", None)
        if cleanup_task is not None:
            cleanup_task.cancel()
            try:
                await cleanup_task
            except asyncio.CancelledError:
                pass
            except Exception as exc:  # noqa: BLE001 - unload must continue
                logger.warning("MineAstr 缓存清理任务异常结束：%s", exc)
            self._cache_cleanup_task = None
        self._clear_memory_caches()
        for task in self._binding_reconcile_tasks:
            task.cancel()
        if self._binding_reconcile_tasks:
            await asyncio.gather(*self._binding_reconcile_tasks, return_exceptions=True)
        self._binding_reconcile_tasks.clear()
        if self._discord_attach_task is not None:
            self._discord_attach_task.cancel()
            try:
                await self._discord_attach_task
            except asyncio.CancelledError:
                pass
            self._discord_attach_task = None
        self._detach_discord_listeners()
        self._detach_qq_listeners()
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "remove_bridge_event_listener"
        ):
            self._listener_adapter.remove_bridge_event_listener(
                self._on_minecraft_bridge_event
            )
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_chat_translation_handler"
        ):
            self._listener_adapter.set_chat_translation_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_chat_platform_handler"
        ):
            self._listener_adapter.set_chat_platform_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_sign_translation_handler"
        ):
            self._listener_adapter.set_sign_translation_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_image_translation_handler"
        ):
            self._listener_adapter.set_image_translation_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_image_relay_handler"
        ):
            self._listener_adapter.set_image_relay_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_native_chat_policy_handler"
        ):
            self._listener_adapter.set_native_chat_policy_handler(None)
        self._listener_adapter = None
        _ACTIVE_RELAY_SESSIONS.difference_update(self._relay_sessions)
        logger.info("MineAstr 插件已终止。")

    def _cfg(self, key: str) -> Any:
        return self._component(PluginSettings)._cfg(key)

    def _set_cfg(self, key: str, value: Any) -> None:
        return self._component(PluginSettings)._set_cfg(key, value)

    def _migrate_grouped_config(self) -> bool:
        """Migrate legacy GUI layouts and consolidate translation instructions."""
        return self._component(PluginSettings)._migrate_grouped_config()

    def _cfg_bool(self, key: str) -> bool:
        return self._component(PluginSettings)._cfg_bool(key)

    def _cfg_int(self, key: str) -> int:
        return self._component(PluginSettings)._cfg_int(key)

    def _bounded_cfg_int(self, key: str, minimum: int, maximum: int) -> int:
        return self._component(PluginSettings)._bounded_cfg_int(key, minimum, maximum)

    @staticmethod
    def _translation_clock() -> float:
        """Monotonic clock isolated for deadline tests and provider budgeting."""
        return TranslationService._translation_clock()

    def _translation_glossary_provider(self) -> GlossaryProvider:
        return self._component(TranslationService)._translation_glossary_provider()

    async def _append_translation_glossary(
        self,
        instructions: str,
        source: str,
        *,
        image: bool = False,
    ) -> str:
        """Add only query-relevant terminology; never send the whole JSON file."""
        return await self._component(TranslationService)._append_translation_glossary(
            instructions, source, image=image
        )

    async def _translation_glossary_cache_token(
        self,
    ) -> tuple[str, int, int] | None:
        return await self._component(
            TranslationService
        )._translation_glossary_cache_token()

    def _start_cache_cleanup_task(self) -> None:
        return self._component(CacheMaintenance)._start_cache_cleanup_task()

    async def _cache_cleanup_loop(self) -> None:
        return await self._component(CacheMaintenance)._cache_cleanup_loop()

    def _clear_memory_caches(self) -> None:
        return self._component(CacheMaintenance)._clear_memory_caches()

    @staticmethod
    def _prune_timed_mapping(
        cache: dict[Any, Any],
        accessed_at: dict[Any, float],
        *,
        now: float,
        retention_seconds: int,
    ) -> int:
        return CacheMaintenance._prune_timed_mapping(
            cache, accessed_at, now=now, retention_seconds=retention_seconds
        )

    @staticmethod
    def _relay_record_created_at(record: Any, fallback: float) -> float:
        return CacheMaintenance._relay_record_created_at(record, fallback)

    def _prune_memory_caches(
        self,
        *,
        wall_now: float | None = None,
        monotonic_now: float | None = None,
    ) -> dict[str, int]:
        return self._component(CacheMaintenance)._prune_memory_caches(
            wall_now=wall_now, monotonic_now=monotonic_now
        )

    @staticmethod
    def _cleanup_screenshot_files_sync(
        directory: Path,
        *,
        wall_now: float,
        retention_seconds: int,
        max_total_bytes: int,
    ) -> tuple[int, int]:
        return CacheMaintenance._cleanup_screenshot_files_sync(
            directory,
            wall_now=wall_now,
            retention_seconds=retention_seconds,
            max_total_bytes=max_total_bytes,
        )

    async def _run_cache_cleanup(self) -> dict[str, int]:
        return await self._component(CacheMaintenance)._run_cache_cleanup()

    def _refresh_relay_sessions(self) -> None:
        return self._component(PlatformFanout)._refresh_relay_sessions()

    def _save_plugin_config(self) -> None:
        return self._component(PluginSettings)._save_plugin_config()

    def _set_relay_sessions(self, sessions: set[str]) -> None:
        return self._component(PlatformFanout)._set_relay_sessions(sessions)

    def _attach_adapter_listener(self) -> None:
        adapter = self._minecraft_adapter()
        if adapter is self._listener_adapter:
            return
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "remove_bridge_event_listener"
        ):
            self._listener_adapter.remove_bridge_event_listener(
                self._on_minecraft_bridge_event
            )
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_chat_translation_handler"
        ):
            self._listener_adapter.set_chat_translation_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_chat_platform_handler"
        ):
            self._listener_adapter.set_chat_platform_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_image_relay_handler"
        ):
            self._listener_adapter.set_image_relay_handler(None)
        if self._listener_adapter is not None and hasattr(
            self._listener_adapter, "set_native_chat_policy_handler"
        ):
            self._listener_adapter.set_native_chat_policy_handler(None)
        self._listener_adapter = None
        if adapter is not None and hasattr(adapter, "add_bridge_event_listener"):
            adapter.add_bridge_event_listener(self._on_minecraft_bridge_event)
            if hasattr(adapter, "set_chat_platform_handler"):
                adapter.set_chat_platform_handler(self._game_chat_platform)
            if hasattr(adapter, "set_chat_translation_handler"):
                adapter.set_chat_translation_handler(self._translate_game_message)
            if hasattr(adapter, "set_sign_translation_handler"):
                adapter.set_sign_translation_handler(self._translate_sign_request)
            if hasattr(adapter, "set_image_translation_handler"):
                adapter.set_image_translation_handler(self._translate_image_request)
            if hasattr(adapter, "set_image_relay_handler"):
                adapter.set_image_relay_handler(self._game_media_payloads)
            if hasattr(adapter, "set_native_chat_policy_handler"):
                adapter.set_native_chat_policy_handler(self._native_chat_policy)
            self._listener_adapter = adapter

    def _game_chat_platform(self, origin: str) -> str:
        """A bot reply keeps the recipient's origin platform, including custom adapter IDs."""
        return self._component(PlatformFanout)._game_chat_platform(origin)

    def _platform_instances(self) -> list[Any]:
        manager = getattr(self.context, "platform_manager", None)
        instances = getattr(manager, "platform_insts", ())
        return list(instances) if instances else []

    @staticmethod
    def _is_discord_adapter(adapter: Any) -> bool:
        return DiscordAutomation._is_discord_adapter(adapter)

    @staticmethod
    def _is_qq_adapter(adapter: Any) -> bool:
        return QQAutomation._is_qq_adapter(adapter)

    def _qq_adapters(self) -> list[tuple[str, Any]]:
        return self._component(QQAutomation)._qq_adapters()

    def _attach_qq_listeners(self) -> None:
        return self._component(QQAutomation)._attach_qq_listeners()

    def _detach_qq_listeners(self) -> None:
        return self._component(QQAutomation)._detach_qq_listeners()

    def _qq_group_allowed(self, group_id: str) -> bool:
        return self._component(QQAutomation)._qq_group_allowed(group_id)

    @staticmethod
    def _relay_source_message_id(event: AstrMessageEvent) -> str:
        return RelayRecords._relay_source_message_id(event)

    def _remember_relay_message(
        self,
        event: AstrMessageEvent,
        *,
        sender_name: str,
        origin: str,
    ) -> str:
        return self._component(RelayRecords)._remember_relay_message(
            event, sender_name=sender_name, origin=origin
        )

    def _store_relay_message_record(
        self,
        key: str,
        *,
        platform_id: str,
        message_id: str,
        origin: str,
        sender_name: str,
        owner_key: str = "",
    ) -> None:
        return self._component(RelayRecords)._store_relay_message_record(
            key,
            platform_id=platform_id,
            message_id=message_id,
            origin=origin,
            sender_name=sender_name,
            owner_key=owner_key,
        )

    @staticmethod
    def _relay_reply_fingerprint(text: Any) -> str:
        return RelayRecords._relay_reply_fingerprint(text)

    def _store_outbound_relay_message(self, session: str, text: Any) -> None:
        return self._component(RelayRecords)._store_outbound_relay_message(
            session, text
        )

    def _is_reply_to_synced_message(
        self,
        event: AstrMessageEvent,
        reply_context: dict[str, str] | None,
    ) -> bool:
        return self._component(RelayRecords)._is_reply_to_synced_message(
            event, reply_context
        )

    async def _relay_recalled_message(
        self,
        platform_id: str,
        message_id: str,
        *,
        sender_name: str = "",
        origin: str = "",
    ) -> None:
        return await self._component(RelayService)._relay_recalled_message(
            platform_id, message_id, sender_name=sender_name, origin=origin
        )

    async def _on_qq_message_recall(self, platform_id: str, event: Any) -> None:
        return await self._component(QQAutomation)._on_qq_message_recall(
            platform_id, event
        )

    async def _on_qq_member_decrease(self, platform_id: str, event: Any) -> None:
        return await self._component(QQAutomation)._on_qq_member_decrease(
            platform_id, event
        )

    async def _update_qq_group_card_after_bind(
        self, event: AstrMessageEvent, record: Any
    ) -> dict[str, Any]:
        return await self._component(QQAutomation)._update_qq_group_card_after_bind(
            event, record
        )

    def _discord_adapters(self) -> list[tuple[str, Any]]:
        return self._component(DiscordAutomation)._discord_adapters()

    def _schedule_discord_listener_attach(self) -> None:
        return self._component(DiscordAutomation)._schedule_discord_listener_attach()

    async def _attach_discord_listeners_with_retry(self) -> None:
        """Attach after the Discord adapter has created its Pycord client."""
        return await self._component(
            DiscordAutomation
        )._attach_discord_listeners_with_retry()

    def _detach_discord_listeners(self) -> None:
        return self._component(DiscordAutomation)._detach_discord_listeners()

    @staticmethod
    def _remove_discord_listener_binding(binding: tuple[Any, ...]) -> None:
        return DiscordAutomation._remove_discord_listener_binding(binding)

    def _discord_guild_allowed(self, guild_id: str) -> bool:
        return self._component(DiscordAutomation)._discord_guild_allowed(guild_id)

    def _discord_adapter(self, platform_id: str) -> Any | None:
        return self._component(DiscordAutomation)._discord_adapter(platform_id)

    @staticmethod
    def _discord_member_from_event(event: AstrMessageEvent) -> Any | None:
        return DiscordAutomation._discord_member_from_event(event)

    async def _discord_members_for_owner(
        self,
        platform_id: str,
        user_id: str,
        member_hint: Any | None = None,
    ) -> list[Any]:
        return await self._component(DiscordAutomation)._discord_members_for_owner(
            platform_id, user_id, member_hint
        )

    @staticmethod
    def _bounded_nickname(
        records: list[Any],
        template: str,
        max_length: int,
        extra_values: dict[str, str] | None = None,
    ) -> str:
        return DiscordAutomation._bounded_nickname(
            records, template, max_length, extra_values
        )

    def _discord_nickname(self, records: list[Any]) -> str:
        return self._component(DiscordAutomation)._discord_nickname(records)

    async def _refresh_discord_nickname_for_owner(
        self,
        platform_id: str,
        user_id: str,
        owner_key: str,
        member_hint: Any | None = None,
    ) -> dict[str, Any]:
        return await self._component(
            DiscordAutomation
        )._refresh_discord_nickname_for_owner(
            platform_id, user_id, owner_key, member_hint
        )

    async def _on_discord_member_remove(self, platform_id: str, member: Any) -> None:
        return await self._component(DiscordAutomation)._on_discord_member_remove(
            platform_id, member
        )

    @staticmethod
    def _discord_message_text(message: Any) -> str:
        """Read the text Discord exposes for an edited message."""
        return DiscordAutomation._discord_message_text(message)

    @staticmethod
    def _discord_message_media(message: Any) -> list[dict[str, str]]:
        return DiscordAutomation._discord_message_media(message)

    @staticmethod
    def _discord_message_origin(platform_id: str, message: Any) -> str:
        return DiscordAutomation._discord_message_origin(platform_id, message)

    async def _on_discord_message_edit(
        self, platform_id: str, before: Any, after: Any
    ) -> None:
        """Relay Discord edits as a marked replacement message.

        Minecraft chat packets have no message-edit operation. Sending the new
        text again, with an explicit marker, is the only lossless behavior for
        clients that already rendered the old chat line.
        """
        return await self._component(DiscordAutomation)._on_discord_message_edit(
            platform_id, before, after
        )

    async def _on_discord_message_delete(self, platform_id: str, message: Any) -> None:
        return await self._component(DiscordAutomation)._on_discord_message_delete(
            platform_id, message
        )

    @filter.on_platform_loaded()
    async def mineastr_on_platform_loaded(self) -> None:
        self._attach_adapter_listener()
        self._attach_qq_listeners()
        self._schedule_discord_listener_attach()

    @staticmethod
    def _identity(event: AstrMessageEvent) -> dict[str, str]:
        return BindingService._identity(event)

    async def _game_sender_name(self, owner_key: str, fallback: str) -> str:
        """Resolve MC display identity without changing social relay identities."""
        return await self._component(RelayService)._game_sender_name(
            owner_key, fallback
        )

    async def _game_reply_context(
        self, context: dict[str, str], platform_id: str
    ) -> dict[str, str]:
        return await self._component(RelayService)._game_reply_context(
            context, platform_id
        )

    def _is_bridge_admin(self, event: AstrMessageEvent) -> bool:
        return self._component(CommandService)._is_bridge_admin(event)

    def _is_mineastr_command(self, text: str) -> bool:
        return self._component(CommandService)._is_mineastr_command(text)

    @staticmethod
    def _command_tail(event: AstrMessageEvent) -> str:
        return CommandService._command_tail(event)

    @staticmethod
    def _translation_languages(value: Any) -> tuple[str, ...]:
        return TranslationService._translation_languages(value)

    def _game_translation_languages(self) -> tuple[str, ...]:
        return self._component(TranslationService)._game_translation_languages()

    @staticmethod
    def _normalize_translation_language(value: Any) -> str:
        return TranslationService._normalize_translation_language(value)

    @staticmethod
    def _same_translation_language(source: str, target: str) -> bool:
        return TranslationService._same_translation_language(source, target)

    @staticmethod
    def _normalize_translation_text(value: Any) -> str:
        return TranslationService._normalize_translation_text(value)

    @staticmethod
    def _same_translation_text(source: Any, translated: Any) -> bool:
        return TranslationService._same_translation_text(source, translated)

    @staticmethod
    def _contains_han_and_latin_candidate(value: Any) -> bool:
        """Return whether text is worth an AI Chinese/English equivalence check."""
        return TranslationService._contains_han_and_latin_candidate(value)

    @staticmethod
    def _parse_translation_response(
        raw: Any,
        languages: tuple[str, ...],
        max_length: int,
        *,
        allow_already_bilingual: bool = False,
    ) -> dict[str, Any]:
        return TranslationService._parse_translation_response(
            raw, languages, max_length, allow_already_bilingual=allow_already_bilingual
        )

    @staticmethod
    def _translation_result_parts(
        result: Any,
    ) -> tuple[str, dict[str, str]]:
        return TranslationService._translation_result_parts(result)

    def _translation_context_limit(self) -> int:
        return self._component(TranslationService)._translation_context_limit()

    @staticmethod
    def _translation_context_key(origin: str, cache_scope: str) -> str:
        return TranslationService._translation_context_key(origin, cache_scope)

    def _translation_context_snapshot(
        self, origin: str, cache_scope: str
    ) -> tuple[tuple[str, str], ...]:
        return self._component(TranslationService)._translation_context_snapshot(
            origin, cache_scope
        )

    def _remember_translation_context(
        self,
        origin: str,
        cache_scope: str,
        text: str,
        *,
        speaker: str = "",
    ) -> None:
        return self._component(TranslationService)._remember_translation_context(
            origin, cache_scope, text, speaker=speaker
        )

    @staticmethod
    def _event_chain(event: AstrMessageEvent) -> list[Any]:
        return PluginMedia._event_chain(event)

    @classmethod
    def _event_media(cls, event: AstrMessageEvent) -> list[dict[str, str]]:
        return PluginMedia._event_media(event)

    @staticmethod
    def _image_mime_type(data: bytes) -> str:
        return PluginMedia._image_mime_type(data)

    @staticmethod
    def _safe_public_image_url(reference: str) -> str:
        return PluginMedia._safe_public_image_url(reference)

    @staticmethod
    def _read_local_image(reference: str) -> tuple[bytes, str] | None:
        return PluginMedia._read_local_image(reference)

    async def _game_media_payloads(
        self, media: list[dict[str, Any]] | None, *, qq_bot: Any = None
    ) -> list[dict[str, Any]]:
        """Convert AstrBot image components to the path-free MineAstr media protocol."""
        return await self._component(PluginMedia)._game_media_payloads(
            media, qq_bot=qq_bot
        )

    @classmethod
    def _chain_media(cls, chain: Any) -> list[dict[str, str]]:
        return PluginMedia._chain_media(chain)

    @classmethod
    def _event_reply_context(cls, event: AstrMessageEvent) -> dict[str, str]:
        return PluginMedia._event_reply_context(event)

    @classmethod
    def _chain_reply_context(cls, chain: Any) -> dict[str, str]:
        return PluginMedia._chain_reply_context(chain)

    @staticmethod
    def _reply_prefix(
        reply_context: dict[str, str] | None, text: str | None = None
    ) -> str:
        return PluginMedia._reply_prefix(reply_context, text)

    def _game_reply_translation_result(
        self,
        content: str,
        result: dict[str, Any],
        reply_context: dict[str, str] | None,
        reply_result: dict[str, Any],
        transform: Callable[[str], str],
    ) -> dict[str, Any]:
        return self._component(TranslationService)._game_reply_translation_result(
            content, result, reply_context, reply_result, transform
        )

    def _translation_provider_ids(self, origin: str) -> tuple[str, ...]:
        """Resolve configured models and the session default without duplicates."""
        return self._component(TranslationService)._translation_provider_ids(origin)

    async def _translation_response(
        self,
        prompt: str,
        system_prompt: str,
        languages: tuple[str, ...],
        origin: str,
        *,
        timeout_seconds: float,
        image_urls: list[str] | None = None,
        allow_already_bilingual: bool = False,
    ) -> Any:
        """Try selected models within one deadline and validate their responses."""
        return await self._component(TranslationService)._translation_response(
            prompt,
            system_prompt,
            languages,
            origin,
            timeout_seconds=timeout_seconds,
            image_urls=image_urls,
            allow_already_bilingual=allow_already_bilingual,
        )

    async def _translate_text(
        self,
        content: str,
        languages: tuple[str, ...],
        origin: str = "",
        *,
        custom_instructions: str = "",
        cache_scope: str = "game",
        context: tuple[tuple[str, str], ...] = (),
        detect_bilingual_equivalence: bool = False,
        inject_glossary: bool = True,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        return await self._component(TranslationService)._translate_text(
            content,
            languages,
            origin,
            custom_instructions=custom_instructions,
            cache_scope=cache_scope,
            context=context,
            detect_bilingual_equivalence=detect_bilingual_equivalence,
            inject_glossary=inject_glossary,
            timeout_seconds=timeout_seconds,
        )

    def _native_chat_policy(self) -> dict[str, Any]:
        return self._component(TranslationService)._native_chat_policy()

    def _native_chat_translation_languages(
        self, payload: dict[str, Any]
    ) -> tuple[str, ...]:
        return self._component(TranslationService)._native_chat_translation_languages(
            payload
        )

    async def _translate_game_message(
        self, content: str, origin: str = ""
    ) -> dict[str, Any]:
        return await self._component(TranslationService)._translate_game_message(
            content, origin
        )

    async def _translate_sign_request(self, request: dict[str, Any]) -> dict[str, Any]:
        """Translate a sign through the same AstrBot game-message pipeline.

        The Fabric side persists the returned locale map in the Minecraft world.
        Keeping this method on the plugin side means provider selection, timeout,
        locale filtering, source-language de-duplication, and terminology rules
        remain identical to normal in-game messages.
        """
        return await self._component(TranslationService)._translate_sign_request(
            request
        )

    async def _translate_image_request(self, request: dict[str, Any]) -> dict[str, Any]:
        """Translate an external image through AstrBot's multimodal provider."""
        return await self._component(ImageTranslationService)._translate_image_request(
            request
        )

    def _game_translation_options_from_result(
        self,
        result: Any,
        *,
        allowed_languages: tuple[str, ...] | None = None,
        source_text: str = "",
    ) -> dict[str, Any]:
        return self._component(
            TranslationService
        )._game_translation_options_from_result(
            result, allowed_languages=allowed_languages, source_text=source_text
        )

    @staticmethod
    def _transform_translation_result(
        result: Any, transform: Callable[[str], str]
    ) -> dict[str, Any]:
        return TranslationService._transform_translation_result(result, transform)

    def _relay_route_targets(self, origin: str) -> set[str] | None:
        return self._component(PlatformFanout)._relay_route_targets(origin)

    def _relay_target_sessions(
        self, exclude: str = "", source_platform: str = ""
    ) -> list[str]:
        return self._component(PlatformFanout)._relay_target_sessions(
            exclude, source_platform
        )

    def _platform_translation_languages_for_sessions(
        self, sessions: list[str]
    ) -> list[str]:
        return self._component(
            PlatformFanout
        )._platform_translation_languages_for_sessions(sessions)

    async def _translate_relay_message(
        self,
        content: str,
        sessions: list[str],
        origin: str,
        *,
        include_game: bool = False,
        extra_languages: tuple[str, ...] = (),
        cache_scope: str = "",
        detect_bilingual_equivalence: bool = False,
    ) -> dict[str, Any]:
        return await self._component(PlatformFanout)._translate_relay_message(
            content,
            sessions,
            origin,
            include_game=include_game,
            extra_languages=extra_languages,
            cache_scope=cache_scope,
            detect_bilingual_equivalence=detect_bilingual_equivalence,
        )

    async def _platform_chat_message(
        self,
        session: str,
        content: str,
        origin: str = "",
        *,
        translation_result: dict[str, Any] | None = None,
    ) -> str:
        return await self._component(PlatformFanout)._platform_chat_message(
            session, content, origin, translation_result=translation_result
        )

    def _platform_translation_only(
        self,
        session: str,
        content: str,
        translation_result: dict[str, Any] | None,
    ) -> str:
        """Render a quoted fragment without adding an original-text label."""
        return self._component(PlatformFanout)._platform_translation_only(
            session, content, translation_result
        )

    async def _send_to_relay_sessions(
        self,
        content: str,
        *,
        exclude: str = "",
        source_platform: str = "",
        sessions: list[str] | None = None,
        translation_result: dict[str, Any] | None = None,
        media: list[dict[str, str]] | None = None,
        reply_context: dict[str, str] | None = None,
        reply_translation_result: dict[str, Any] | None = None,
    ) -> None:
        return await self._component(PlatformFanout)._send_to_relay_sessions(
            content,
            exclude=exclude,
            source_platform=source_platform,
            sessions=sessions,
            translation_result=translation_result,
            media=media,
            reply_context=reply_context,
            reply_translation_result=reply_translation_result,
        )

    async def _optimized_platform_media(
        self, media: list[dict[str, str]]
    ) -> list[dict[str, str]]:
        return await self._component(PlatformFanout)._optimized_platform_media(media)

    async def _send_to_relay_session(
        self,
        session: str,
        content: str,
        *,
        media: list[dict[str, str]] | None = None,
        reply_context: dict[str, str] | None = None,
    ) -> None:
        return await self._component(PlatformFanout)._send_to_relay_session(
            session, content, media=media, reply_context=reply_context
        )

    @staticmethod
    def _session_platform_id(session: str) -> str:
        return NotificationService._session_platform_id(session)

    @staticmethod
    def _session_channel_candidates(session: str) -> set[str]:
        return NotificationService._session_channel_candidates(session)

    @staticmethod
    def _normalize_notification_language(value: Any) -> str:
        return NotificationService._normalize_notification_language(value)

    @staticmethod
    def _notification_languages(value: Any) -> tuple[str, ...]:
        return NotificationService._notification_languages(value)

    def _platform_notification_profile(
        self, platform_id: str, session: str = ""
    ) -> dict[str, Any] | None:
        return self._component(NotificationService)._platform_notification_profile(
            platform_id, session
        )

    @staticmethod
    def _uses_notification_preset(key: str, configured: Any) -> bool:
        return NotificationService._uses_notification_preset(key, configured)

    @staticmethod
    def _localized_template(key: str, language: str, configured: Any) -> str:
        return NotificationService._localized_template(key, language, configured)

    @staticmethod
    def _profile_localized_template(
        profile: dict[str, Any], language: str, template_key: str
    ) -> str:
        return NotificationService._profile_localized_template(
            profile, language, template_key
        )

    @staticmethod
    def _death_reason(payload: dict[str, Any], player_name: str, language: str) -> str:
        return NotificationService._death_reason(payload, player_name, language)

    async def _send_event_to_relay_sessions(
        self,
        event_name: str,
        values: dict[str, str],
        payload: dict[str, Any],
    ) -> None:
        return await self._component(NotificationService)._send_event_to_relay_sessions(
            event_name, values, payload
        )

    def _configured_command_admins(self, additional_admins: Any = ()) -> list[str]:
        return self._component(CommandService)._configured_command_admins(
            additional_admins
        )

    def _schedule_command_admin_reconcile(self, server_id: str) -> None:
        return self._component(CommandService)._schedule_command_admin_reconcile(
            server_id
        )

    async def _reconcile_command_admins_to_server(self, server_id: str) -> None:
        return await self._component(
            CommandService
        )._reconcile_command_admins_to_server(server_id)

    async def _sync_command_admins_now(
        self,
        server_id: str | None = None,
        additional_admins: Any = (),
    ) -> dict[str, Any]:
        """Refresh Bot administrators immediately before privileged approval."""
        return await self._component(CommandService)._sync_command_admins_now(
            server_id, additional_admins
        )

    def _consume_verify_code(self, code: str) -> str | None:
        return self._component(BindingService)._consume_verify_code(code)

    async def _on_minecraft_bridge_event(
        self, payload: dict[str, Any]
    ) -> dict[str, Any] | None:
        return await self._component(RelayService)._on_minecraft_bridge_event(payload)

    async def _notify_mentioned_players(
        self, event: AstrMessageEvent, text: str
    ) -> None:
        return await self._component(RelayService)._notify_mentioned_players(
            event, text
        )

    async def _complete_native_minecraft_chat(
        self,
        raw: dict[str, Any],
        content: str,
        translation_result: dict[str, Any],
        target_languages: tuple[str, ...],
    ) -> None:
        return await self._component(RelayService)._complete_native_minecraft_chat(
            raw, content, translation_result, target_languages
        )

    async def _relay_minecraft_chat(
        self, event: AstrMessageEvent, raw: dict[str, Any], text: str
    ) -> None:
        """Send original-only destinations while the shared translation runs."""
        return await self._component(RelayService)._relay_minecraft_chat(
            event, raw, text
        )

    @filter.custom_filter(MineAstrRelayFilter, priority=-100)
    async def mineastr_relay_message(self, event: AstrMessageEvent) -> None:
        return await self._component(RelayService).mineastr_relay_message(event)

    @filter.after_message_sent(priority=1000)
    async def mineastr_relay_bot_reply_to_game(self, event: AstrMessageEvent) -> None:
        return await self._component(RelayService).mineastr_relay_bot_reply_to_game(
            event
        )

    @staticmethod
    def _query_results(payload: dict[str, Any]) -> list[dict[str, Any]]:
        return CommandService._query_results(payload)

    @staticmethod
    def _query_data(payload: dict[str, Any]) -> dict[str, Any]:
        return CommandService._query_data(payload)

    def _remember_command_approval(self, payload: dict[str, Any]) -> None:
        return self._component(CommandService)._remember_command_approval(payload)

    @staticmethod
    def _format_command_result(payload: dict[str, Any]) -> str:
        return CommandService._format_command_result(payload)

    async def _command_approval_action(
        self, event: AstrMessageEvent, action: str, approval_id: str
    ) -> str:
        return await self._component(CommandService)._command_approval_action(
            event, action, approval_id
        )

    def _format_status(self, payload: dict[str, Any]) -> str:
        return self._component(CommandService)._format_status(payload)

    def _format_players(self, payload: dict[str, Any]) -> str:
        return self._component(CommandService)._format_players(payload)

    def _format_performance(self, payload: dict[str, Any]) -> str:
        return self._component(CommandService)._format_performance(payload)

    def _validated_player_name(self, value: str) -> str:
        return self._component(BindingService)._validated_player_name(value)

    @staticmethod
    def _validated_verified_player_name(value: str) -> str:
        """Validate an authenticated name reported by the Minecraft server.

        A verification code proves which exact login name requested access, so
        the configurable GROUP_NAME regex must not reject that server identity.
        """
        return BindingService._validated_verified_player_name(value)

    async def _create_binding(
        self, identity: dict[str, str], player_name: str
    ) -> tuple[Any, dict[str, Any]]:
        return await self._component(BindingService)._create_binding(
            identity, player_name
        )

    @filter.command_group("mc", alias={"mineastr", "minecraft"})
    def mc(self):
        """MineAstr / AQQBot 兼容指令。"""
        pass

    @mc.command("help", alias={"帮助"})
    async def mineastr_help(self, event: AstrMessageEvent):
        """显示 MineAstr 指令帮助。"""
        async for result in self._component(CommandService).mineastr_help(event):
            yield result

    @mc.command("status", alias={"状态"})
    async def mineastr_status_command(
        self, event: AstrMessageEvent, server_id: str = ""
    ):
        """查询服务器状态。"""
        async for result in self._component(CommandService).mineastr_status_command(
            event, server_id
        ):
            yield result

    @mc.command("list", alias={"在线玩家", "玩家"})
    async def mineastr_list_command(self, event: AstrMessageEvent, server_id: str = ""):
        """查询在线玩家。"""
        async for result in self._component(CommandService).mineastr_list_command(
            event, server_id
        ):
            yield result

    @mc.command("performance", alias={"性能", "tps", "mspt"})
    async def mineastr_performance_command(
        self, event: AstrMessageEvent, server_id: str = ""
    ):
        """查询服务器 TPS、MSPT 与 CPU 等性能数据。"""
        async for result in self._component(
            CommandService
        ).mineastr_performance_command(event, server_id):
            yield result

    @mc.command("bind", alias={"绑定"})
    async def mineastr_bind_command(self, event: AstrMessageEvent, value: str):
        """绑定当前聊天账号与 Minecraft 玩家。"""
        async for result in self._component(BindingService).mineastr_bind_command(
            event, value
        ):
            yield result

    @mc.command("unbind", alias={"解绑"})
    async def mineastr_unbind_command(
        self, event: AstrMessageEvent, player_name: str = ""
    ):
        """解绑当前聊天账号的 Minecraft 玩家。"""
        async for result in self._component(BindingService).mineastr_unbind_command(
            event, player_name
        ):
            yield result

    @mc.command("bindings", alias={"我的绑定", "绑定信息"})
    async def mineastr_bindings_command(self, event: AstrMessageEvent):
        """查看当前聊天账号的绑定。"""
        async for result in self._component(BindingService).mineastr_bindings_command(
            event
        ):
            yield result

    @mc.command("who", alias={"查询绑定"})
    async def mineastr_who_command(self, event: AstrMessageEvent, player_name: str):
        """查询指定 Minecraft 玩家的绑定。"""
        async for result in self._component(BindingService).mineastr_who_command(
            event, player_name
        ):
            yield result

    @mc.command("command", alias={"sudo", "执行"})
    async def mineastr_command_command(self, event: AstrMessageEvent):
        """提交一条服务器命令；公开白名单立即执行，其他命令等待管理员审批。"""
        async for result in self._component(CommandService).mineastr_command_command(
            event
        ):
            yield result

    @mc.command("approve", alias={"批准指令", "审批指令"})
    async def mineastr_approve_command(
        self, event: AstrMessageEvent, approval_id: str = ""
    ):
        """列出待审批命令，或按序号/ID 批准并执行。"""
        async for result in self._component(CommandService).mineastr_approve_command(
            event, approval_id
        ):
            yield result

    @mc.command("reject", alias={"拒绝指令"})
    async def mineastr_reject_command(
        self, event: AstrMessageEvent, approval_id: str = ""
    ):
        """列出待审批命令，或按序号/ID 拒绝。"""
        async for result in self._component(CommandService).mineastr_reject_command(
            event, approval_id
        ):
            yield result

    @mc.command("approvals", alias={"待审批指令"})
    async def mineastr_approvals_command(self, event: AstrMessageEvent):
        """列出待管理员审批的服务器命令。"""
        async for result in self._component(CommandService).mineastr_approvals_command(
            event
        ):
            yield result

    @mc.command("say", alias={"广播"})
    async def mineastr_say_command(self, event: AstrMessageEvent):
        """以聊天平台身份向 Minecraft 广播消息。"""
        async for result in self._component(CommandService).mineastr_say_command(event):
            yield result

    @mc.command("bridge_add", alias={"桥接当前频道"})
    async def mineastr_bridge_add_command(self, event: AstrMessageEvent):
        """把当前群聊/Discord 频道加入群服互联。"""
        async for result in self._component(RelayService).mineastr_bridge_add_command(
            event
        ):
            yield result

    @mc.command("bridge_remove", alias={"取消桥接当前频道"})
    async def mineastr_bridge_remove_command(self, event: AstrMessageEvent):
        """从群服互联中移除当前群聊/Discord 频道。"""
        async for result in self._component(
            RelayService
        ).mineastr_bridge_remove_command(event):
            yield result

    @mc.command("bridge_list", alias={"桥接列表"})
    async def mineastr_bridge_list_command(self, event: AstrMessageEvent):
        """查看已桥接的 AstrBot 会话。"""
        async for result in self._component(RelayService).mineastr_bridge_list_command(
            event
        ):
            yield result

    @mc.command("discord_status", alias={"discord状态"})
    async def mineastr_discord_status_command(self, event: AstrMessageEvent):
        """查看 Discord 退群解绑、昵称同步及成员 Intent 状态。"""
        async for result in self._component(
            RelayService
        ).mineastr_discord_status_command(event):
            yield result

    @mc.command("admin_bind", alias={"管理绑定"})
    async def mineastr_admin_bind_command(
        self, event: AstrMessageEvent, owner: str, player_name: str
    ):
        """管理员为指定平台用户绑定玩家。"""
        async for result in self._component(BindingService).mineastr_admin_bind_command(
            event, owner, player_name
        ):
            yield result

    @mc.command("admin_unbind", alias={"管理解绑"})
    async def mineastr_admin_unbind_command(
        self, event: AstrMessageEvent, owner: str, player_name: str
    ):
        """管理员解除指定平台用户的玩家绑定。"""
        async for result in self._component(
            BindingService
        ).mineastr_admin_unbind_command(event, owner, player_name):
            yield result

    @filter.on_llm_request()
    async def mineastr_on_llm_request(
        self, event: AstrMessageEvent, request: Any
    ) -> None:
        return await self._component(MinecraftTools).mineastr_on_llm_request(
            event, request
        )

    def _minecraft_adapter(self) -> Any | None:
        getter = getattr(self.context, "get_platform_inst", None)
        if not callable(getter):
            return None
        adapter = getter("minecraft")
        if adapter is None:
            return None
        if (
            not hasattr(adapter, "query_status")
            or not hasattr(adapter, "query_players")
            or not hasattr(adapter, "query_player_state")
            or not hasattr(adapter, "query_inventory")
            or not hasattr(adapter, "query_nearby_entities")
            or not hasattr(adapter, "analyze_region")
            or not hasattr(adapter, "run_server_command")
            or not hasattr(adapter, "request_screenshot")
        ):
            return None
        return adapter

    @staticmethod
    def _tool_json(title: str, payload: dict[str, Any]) -> str:
        return MinecraftTools._tool_json(title, payload)

    def _tool_image_result(
        self,
        title: str,
        payload: dict[str, Any],
        image_base64: str | None,
        mime_type: str,
    ) -> Any:
        return self._component(MinecraftTools)._tool_image_result(
            title, payload, image_base64, mime_type
        )

    @staticmethod
    def _event_raw_message(event: AstrMessageEvent) -> dict[str, Any]:
        return MinecraftTools._event_raw_message(event)

    @staticmethod
    def _event_value(event: AstrMessageEvent, method_name: str) -> str:
        return MinecraftTools._event_value(event, method_name)

    def _event_target(
        self,
        event: AstrMessageEvent,
        server_id: str,
        player_uuid: str,
        player_name: str,
    ) -> tuple[str | None, str, str]:
        return self._component(MinecraftTools)._event_target(
            event, server_id, player_uuid, player_name
        )

    def _requester_identity(self, event: AstrMessageEvent) -> dict[str, str]:
        return self._component(MinecraftTools)._requester_identity(event)

    @staticmethod
    def _safe_filename(value: Any, fallback: str = "unknown") -> str:
        return ScreenshotService._safe_filename(value, fallback)

    async def _save_screenshot_result(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._component(ScreenshotService)._save_screenshot_result(payload)

    def _save_screenshot_result_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._component(ScreenshotService)._save_screenshot_result_sync(payload)

    @staticmethod
    def _screenshot_cooldown_key(
        server_id: str | None,
        player_uuid: str,
        player_name: str,
    ) -> tuple[str, str, str]:
        return ScreenshotService._screenshot_cooldown_key(
            server_id, player_uuid, player_name
        )

    async def _mark_screenshot_cooldown(
        self, key: tuple[str, str, str], cooldown_seconds: float
    ) -> float:
        return await self._component(ScreenshotService)._mark_screenshot_cooldown(
            key, cooldown_seconds
        )

    @filter.llm_tool(name="mineastr_get_server_status")
    async def mineastr_get_server_status(
        self, event: AstrMessageEvent, server_id: str = ""
    ) -> str:
        """查询 Minecraft 服务器状态，包括连接状态、服务器名称、版本和在线人数。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        return await self._component(MinecraftTools).mineastr_get_server_status(
            event, server_id
        )

    @filter.llm_tool(name="mineastr_get_online_players")
    async def mineastr_get_online_players(
        self, event: AstrMessageEvent, server_id: str = ""
    ) -> str:
        """查询 Minecraft 当前在线玩家列表和玩家数量。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        return await self._component(MinecraftTools).mineastr_get_online_players(
            event, server_id
        )

    @filter.llm_tool(name="mineastr_get_player_state")
    async def mineastr_get_player_state(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
    ) -> str:
        """查询在线玩家当前生命、饥饿、位置、维度、游戏模式、经验和状态效果。

        Args:
            server_id(str): 可选服务器 ID；单服时留空。
            player_name(str): 可选玩家名；Minecraft 会话中留空默认当前发言玩家。
            player_uuid(str): 可选玩家 UUID；优先级高于玩家名。
        """
        return await self._component(MinecraftTools).mineastr_get_player_state(
            event, server_id, player_name, player_uuid
        )

    @filter.llm_tool(name="mineastr_get_player_inventory")
    async def mineastr_get_player_inventory(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        include_ender_chest: bool = False,
    ) -> str:
        """查询在线玩家背包、快捷栏、护甲和副手的物品摘要，不返回完整 NBT。

        Args:
            server_id(str): 可选服务器 ID；单服时留空。
            player_name(str): 可选玩家名；Minecraft 会话中留空默认当前发言玩家。
            player_uuid(str): 可选玩家 UUID；优先级高于玩家名。
            include_ender_chest(bool): 是否同时查询末影箱；仅在用户明确询问末影箱时设为 true。
        """
        return await self._component(MinecraftTools).mineastr_get_player_inventory(
            event, server_id, player_name, player_uuid, include_ender_chest
        )

    @filter.llm_tool(name="mineastr_get_nearby_entities")
    async def mineastr_get_nearby_entities(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        radius: float = 12.0,
    ) -> str:
        """查询玩家附近实体的种类、数量、距离与生命值摘要。

        Args:
            server_id(str): 可选服务器 ID；单服时留空。
            player_name(str): 可选玩家名；Minecraft 会话中留空默认当前发言玩家。
            player_uuid(str): 可选玩家 UUID；优先级高于玩家名。
            radius(float): 查询半径，范围 1 到 32 格。
        """
        return await self._component(MinecraftTools).mineastr_get_nearby_entities(
            event, server_id, player_name, player_uuid, radius
        )

    @filter.llm_tool(name="mineastr_analyze_region")
    async def mineastr_analyze_region(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        horizontal_radius: int = 8,
        vertical_radius: int = 6,
        use_coordinates: bool = False,
        dimension: str = "minecraft:overworld",
        x: int = 0,
        y: int = 64,
        z: int = 0,
    ) -> str:
        """分析已加载区域的方块调色板、建筑部件、粗略三维占用形状和表面高度。

        Args:
            server_id(str): 可选服务器 ID；单服时留空。
            player_name(str): 玩家中心点名称；Minecraft 会话中留空默认当前发言玩家。
            player_uuid(str): 玩家中心点 UUID；优先级高于玩家名。
            horizontal_radius(int): 水平半径，建议 4 到 12，服务端硬上限 24。
            vertical_radius(int): 垂直半径，建议 4 到 10，服务端硬上限 16。
            use_coordinates(bool): 仅在需要分析明确坐标而非玩家周围时设为 true。
            dimension(str): 坐标模式的维度 ID，例如 minecraft:overworld。
            x(int): 坐标模式中心 X。
            y(int): 坐标模式中心 Y。
            z(int): 坐标模式中心 Z。
        """
        return await self._component(MinecraftTools).mineastr_analyze_region(
            event,
            server_id,
            player_name,
            player_uuid,
            horizontal_radius,
            vertical_radius,
            use_coordinates,
            dimension,
            x,
            y,
            z,
        )

    @filter.llm_tool(name="mineastr_run_server_command")
    async def mineastr_run_server_command(
        self,
        event: AstrMessageEvent,
        command: str,
        server_id: str = "",
    ) -> str:
        """提交当前用户明确要求的精确服务器命令；仅在用户明确要求时调用。

        Mod 会立即执行公开白名单命令；其他命令只生成待审批申请。管理员可使用 /mc approve
        查看编号列表，再按序号审批；也可在明确要求时调用审批工具。

        Args:
            command(str): 用户明确要求执行的完整命令，不要添加或改写额外命令。
            server_id(str): 可选服务器 ID；单服时留空。
        """
        return await self._component(MinecraftTools).mineastr_run_server_command(
            event, command, server_id
        )

    @filter.llm_tool(name="mineastr_manage_command_approvals")
    async def mineastr_manage_command_approvals(
        self,
        event: AstrMessageEvent,
        action: str = "list",
        selection: str = "",
    ) -> str:
        """由当前真实管理员列出、批准或拒绝待审批服务器命令。

        只有当前对话用户明确要求审批时才可使用 approve/reject。插件会再次校验
        当前用户的 AstrBot/MineAstr 管理员身份，并且只按 Mod 保存的原始命令执行。

        Args:
            action(str): list、approve 或 reject；未指定时仅列出，不执行命令。
            selection(str): 列表序号、latest 或完整审批 ID；list 时留空。
        """
        return await self._component(MinecraftTools).mineastr_manage_command_approvals(
            event, action, selection
        )

    @filter.llm_tool(name="mineastr_request_screenshot")
    async def mineastr_request_screenshot(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        reason: str = "",
    ) -> Any:
        """请求指定 Minecraft 客户端发送低清晰度截图。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空。
            player_name(str): 可选的玩家名。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            player_uuid(str): 可选的玩家 UUID。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            reason(str): 可选的截图原因，会展示给处于询问模式的玩家。
        """
        return await self._component(ScreenshotService).mineastr_request_screenshot(
            event, server_id, player_name, player_uuid, reason
        )
