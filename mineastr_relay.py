"""跨平台事件编排 / Cross-platform event orchestration."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    _RELAY_DIAGNOSTICS,
    apply_aqqbot_filters,
    asyncio,
    format_template,
    logger,
    parse_items,
    re,
    sanitize_minecraft_login_name,
    strip_minecraft_colors,
    time,
    trim_message,
)

from .mineastr_defaults import (
    NOTIFICATION_EVENT_CONFIG,
)


class RelayService:
    """跨平台事件编排 / Cross-platform event orchestration.

    协调 Minecraft、平台和机器人事件；路由与投递、历史索引分别委托专用组件。
    Coordinate game, platform and bot events; delegate routing, delivery and history indexing.
    """

    def __init__(self, plugin):
        self.plugin = plugin

    async def _relay_recalled_message(
        self,
        platform_id: str,
        message_id: str,
        *,
        sender_name: str = "",
        origin: str = "",
    ) -> None:
        key = f"{platform_id}:{message_id}"
        record = self.plugin._relay_message_records.get(key)
        if not isinstance(record, dict):
            # A platform-wide delete/recall listener also receives events from
            # channels that MineAstr does not bridge. Only a message recorded
            # after an actual relay is eligible for recall synchronization.
            logger.debug(
                "MineAstr ignored untracked recall/delete event: platform=%s message=%s",
                platform_id,
                message_id,
            )
            return
        sender_name = str(record.get("sender_name") or sender_name).strip()
        event_origin = str(origin or "").strip()
        record_origin = str(record.get("origin") or "").strip()
        if event_origin and record_origin and event_origin != record_origin:
            logger.debug(
                "MineAstr ignored recall/delete with mismatched origin: "
                "platform=%s message=%s event_origin=%s record_origin=%s",
                platform_id,
                message_id,
                event_origin,
                record_origin,
            )
            return
        origin = record_origin or event_origin
        if not origin or origin not in self.plugin._relay_sessions:
            logger.debug(
                "MineAstr ignored recall/delete outside configured relay sessions: "
                "platform=%s message=%s origin=%s",
                platform_id,
                message_id,
                origin,
            )
            return
        # Consume the record before the first await so duplicate callbacks are
        # naturally idempotent.
        self.plugin._relay_message_records.pop(key, None)
        if not sender_name:
            sender_name = platform_id or "用户"
        notice = f"[{sender_name}] 消息已撤回"
        target_sessions = self.plugin._relay_target_sessions(origin)
        await self.plugin._send_to_relay_sessions(notice, sessions=target_sessions)
        adapter = self.plugin._minecraft_adapter()
        if adapter is not None and hasattr(adapter, "relay_chat"):
            try:
                game_sender = await self.plugin._game_sender_name(
                    str(record.get("owner_key") or ""), sender_name
                )
                await adapter.relay_chat(
                    f"[{game_sender}] 消息已撤回",
                    game_sender,
                    origin=origin,
                )
            except Exception as exc:
                logger.debug("MineAstr 撤回通知同步到 Minecraft 失败：%s", exc)

    async def _game_sender_name(self, owner_key: str, fallback: str) -> str:
        """Resolve MC display identity without changing social relay identities."""
        store = getattr(self.plugin, "_binding_store", None)
        if store is None or not owner_key:
            return fallback
        try:
            resolver = getattr(store, "display_player_name", None)
            if callable(resolver):
                return await resolver(owner_key) or fallback
            records = await store.get_by_owner(owner_key)
            return next((r.player_name for r in records if r.player_name), fallback)
        except Exception as exc:
            logger.warning(
                "MineAstr MC 昵称查询失败，使用平台昵称：%s", type(exc).__name__
            )
            return fallback

    async def _game_reply_context(
        self, context: dict[str, str], platform_id: str
    ) -> dict[str, str]:
        if not context or platform_id == "minecraft":
            return context
        record = getattr(self.plugin, "_relay_message_records", {}).get(
            f"{platform_id}:{context.get('message_id', '')}", {}
        )
        owner_key = str(record.get("owner_key") or "")
        if not owner_key and context.get("sender_id"):
            owner_key = f"{platform_id}:{context['sender_id']}"
        if not owner_key:
            return context
        return {
            **context,
            "sender": await self.plugin._game_sender_name(
                owner_key, str(context.get("sender") or record.get("sender_name") or "")
            ),
        }

    async def _on_minecraft_bridge_event(
        self, payload: dict[str, Any]
    ) -> dict[str, Any] | None:
        event_name = str(payload.get("event") or "").strip().lower()
        reported_player_name = str(payload.get("player_name") or "").strip()
        player_name = sanitize_minecraft_login_name(reported_player_name)
        if player_name != reported_player_name:
            logger.warning("MineAstr 已移除登录显示名中的网络地址后缀。")

        if event_name == "server_start":
            server_id = str(payload.get("server_id") or "")
            self.plugin._schedule_binding_reconcile(server_id)
            self.plugin._schedule_command_admin_reconcile(server_id)

        if event_name == "binding_code":
            code = str(payload.get("code") or "").strip()
            if code and player_name and len(code) <= 64 and len(player_name) <= 64:
                now = time.monotonic()
                server_id = str(payload.get("server_id") or "")
                for candidate, data in list(self.plugin._verify_codes.items()):
                    if float(data.get("expires_at", 0)) <= now or (
                        str(data.get("player_name") or "").casefold()
                        == player_name.casefold()
                        and str(data.get("server_id") or "") == server_id
                    ):
                        self.plugin._verify_codes.pop(candidate, None)
                if len(self.plugin._verify_codes) >= 4096:
                    logger.warning(
                        "MineAstr 验证码缓存已满，已拒绝新增验证码而不驱逐其他玩家的有效验证码。"
                    )
                    return None
                expires = max(
                    1, min(86400, self.plugin._cfg_int("verify_code_expire_seconds"))
                )
                self.plugin._verify_codes[code.casefold()] = {
                    "player_name": player_name,
                    "server_id": server_id,
                    "expires_at": now + expires,
                }
            return None

        if event_name == "player_login_check":
            if not self.plugin._cfg_bool(
                "binding_enabled"
            ) or not self.plugin._cfg_bool("need_bind_to_login"):
                logger.info(
                    "MineAstr 登录绑定校验已跳过：player=%r binding_enabled=%s need_bind_to_login=%s allowed=true",
                    player_name,
                    self.plugin._cfg_bool("binding_enabled"),
                    self.plugin._cfg_bool("need_bind_to_login"),
                )
                return {"allowed": True}
            binding = await self.plugin._binding_store.get_by_player(player_name)
            logger.info(
                "MineAstr 登录绑定校验：player=%r bound=%s allowed=%s",
                player_name,
                binding is not None,
                binding is not None,
            )
            language = self.plugin._normalize_notification_language(
                self.plugin._cfg("notification_language")
            )
            reject_message = self.plugin._localized_template(
                "login_reject_message",
                language,
                self.plugin._cfg("login_reject_message"),
            )
            message_key = (
                "disconnect.mineastr.login.not_bound"
                if self.plugin._uses_notification_preset(
                    "login_reject_message", self.plugin._cfg("login_reject_message")
                )
                else ""
            )
            return {
                "allowed": binding is not None,
                "message": "" if binding else reject_message,
                "message_key": "" if binding else message_key,
                "owner_key": binding.owner_key if binding else "",
            }

        if not self.plugin._cfg_bool("notifications_enabled"):
            return None
        if event_name not in NOTIFICATION_EVENT_CONFIG:
            return None

        binding = (
            await self.plugin._binding_store.get_by_player(player_name)
            if player_name
            else None
        )
        binding_text = ""
        if binding:
            binding_text = f"（{binding.owner_display or binding.owner_key}）"
        values = {
            "server": str(
                payload.get("server_name") or payload.get("server_id") or "Minecraft"
            ),
            "server_id": str(payload.get("server_id") or "minecraft"),
            "player": player_name,
            "player_uuid": str(payload.get("player_uuid") or ""),
            "binding": binding_text,
            "owner": binding.owner_key if binding else "",
            "user_id": binding.user_id if binding else "-1",
            "reason": str(payload.get("reason") or payload.get("death_message") or ""),
            "death_message": str(payload.get("death_message") or ""),
            "death_type": str(payload.get("death_type") or ""),
            "attacker": str(payload.get("attacker") or ""),
            "direct_entity": str(payload.get("direct_entity") or ""),
            "weapon": str(payload.get("weapon") or ""),
        }
        await self.plugin._send_event_to_relay_sessions(event_name, values, payload)
        return None

    async def _notify_mentioned_players(
        self, event: AstrMessageEvent, text: str
    ) -> None:
        if not self.plugin._cfg_bool("player_mention_enabled"):
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "notify_player"):
            return
        identity = self.plugin._identity(event)
        players = list(
            dict.fromkeys(re.findall(r"(?<![\w<])@([A-Za-z0-9_]{3,16})", text))
        )
        if not players:
            return
        game_sender = await self.plugin._game_sender_name(
            identity["owner_key"], identity["owner_display"]
        )
        for player in players[:5]:
            try:
                await adapter.notify_player(
                    None,
                    player,
                    game_sender,
                    identity["user_id"],
                    identity["platform_id"],
                    text,
                )
            except Exception as exc:
                logger.debug("MineAstr 提醒玩家 %s 失败：%s", player, exc)

    async def _complete_native_minecraft_chat(
        self,
        raw: dict[str, Any],
        content: str,
        translation_result: dict[str, Any],
        target_languages: tuple[str, ...],
    ) -> None:
        if not bool(raw.get("native_chat")):
            return
        message_id = trim_message(raw.get("native_chat_id"), 64)
        if not message_id:
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "complete_native_chat"):
            logger.warning(
                "MineAstr 无法回传原生聊天翻译：Minecraft 适配器不支持 complete_native_chat"
            )
            return
        server_id = trim_message(raw.get("server_id"), 64) or "minecraft"
        sender_name = trim_message(raw.get("player_name"), 64) or "Player"
        options = self.plugin._game_translation_options_from_result(
            translation_result,
            allowed_languages=target_languages,
            source_text=content,
        )
        try:
            complete_kwargs: dict[str, Any] = {"translation_options": options}
            connection_id = trim_message(raw.get("connection_id"), 64)
            if connection_id:
                complete_kwargs["connection_id"] = connection_id
            await adapter.complete_native_chat(
                server_id, message_id, content, sender_name, **complete_kwargs
            )
        except Exception as exc:
            # The Mod owns the timeout fallback, so a lost response never eats
            # the player's original message.
            logger.warning("MineAstr 回传原生聊天翻译失败，等待 Mod 回退原文：%s", exc)

    async def _relay_minecraft_chat(
        self, event: AstrMessageEvent, raw: dict[str, Any], text: str
    ) -> None:
        """Send original-only destinations while the shared translation runs."""
        _RELAY_DIAGNOSTICS.info(
            "Minecraft relay received: origin=%s native=%s",
            event.unified_msg_origin,
            bool(raw.get("native_chat")),
        )
        received = raw.get("_mineastr_received_monotonic")
        if isinstance(received, (int, float)):
            _RELAY_DIAGNOSTICS.info(
                "Minecraft event queue_seconds=%.3f",
                max(0, time.monotonic() - received),
            )
        filtered = apply_aqqbot_filters(text, self.plugin._cfg("game_to_chat_filters"))
        filtered = strip_minecraft_colors(filtered) if filtered is not None else None
        native_content = strip_minecraft_colors(
            str(
                raw.get("native_original_content") or raw.get("content") or text
            ).strip()
        )
        native_message_id = (
            trim_message(raw.get("native_chat_id"), 64)
            if bool(raw.get("native_chat"))
            else ""
        )
        native_languages = self.plugin._native_chat_translation_languages(raw)
        origin = event.unified_msg_origin
        server_scope = trim_message(raw.get("server_id"), 64) or "minecraft"
        shared_scope = f"relay-unified:{origin}:{server_scope}"
        sessions = (
            self.plugin._relay_target_sessions(origin, source_platform="minecraft")
            if filtered is not None
            else []
        )
        immediate_sessions: list[str] = []
        translated_sessions: list[str] = []
        for session in sessions:
            targets = (
                translated_sessions
                if self.plugin._platform_translation_languages_for_sessions([session])
                else immediate_sessions
            )
            targets.append(session)
        values = {
            "server": str(
                raw.get("server_name") or raw.get("server_id") or "Minecraft"
            ),
            "server_id": str(raw.get("server_id") or "minecraft"),
            "player": str(raw.get("player_name") or event.get_sender_name() or "玩家"),
            "player_uuid": str(raw.get("player_uuid") or event.get_sender_id()),
            "message": filtered or "",
        }
        template = str(self.plugin._cfg("game_to_chat_template"))
        content = format_template(template, values)

        async def send_platforms(targets: list[str], result: dict[str, Any]) -> None:
            transformed = self.plugin._transform_translation_result(
                result,
                lambda translated: format_template(
                    template, {**values, "message": translated}
                ),
            )
            # A slow platform must not hold up another destination or the
            # native-chat response. All sends remain owned by this event.
            await asyncio.gather(
                *(
                    self.plugin._send_to_relay_sessions(
                        content,
                        exclude=origin,
                        sessions=[session],
                        translation_result=transformed,
                    )
                    for session in targets
                )
            )

        async def translate(
            source: str, targets: list[str], *, native: bool, scope: str
        ) -> dict[str, Any]:
            try:
                return await self.plugin._translate_relay_message(
                    source,
                    targets,
                    origin,
                    extra_languages=native_languages if native else (),
                    cache_scope=scope,
                    detect_bilingual_equivalence=native,
                )
            except Exception as exc:
                logger.warning("MineAstr Minecraft 聊天翻译失败，已回退原文：%s", exc)
                return {}

        async def complete_native(result: dict[str, Any]) -> None:
            await self.plugin._complete_native_minecraft_chat(
                raw,
                native_content,
                result,
                native_languages,
            )

        async def shared_translation() -> None:
            result = await translate(
                native_content,
                translated_sessions,
                native=True,
                scope=shared_scope,
            )
            await asyncio.gather(
                complete_native(result),
                send_platforms(translated_sessions, result),
            )

        async def native_translation() -> None:
            result = await translate(
                native_content,
                [],
                native=True,
                scope=f"native-chat:{origin}:{server_scope}",
            )
            await complete_native(result)

        async def platform_translation() -> None:
            result = await translate(
                filtered or "",
                translated_sessions,
                native=False,
                scope=shared_scope,
            )
            await send_platforms(translated_sessions, result)

        jobs = []
        if immediate_sessions:
            jobs.append(send_platforms(immediate_sessions, {}))
        if native_message_id and filtered == native_content:
            # Keep one model call for identical game/platform text and the
            # union of their locales, including bilingual detection.
            jobs.append(shared_translation())
        else:
            if native_message_id:
                jobs.append(native_translation())
            if translated_sessions:
                jobs.append(platform_translation())
        await asyncio.gather(*jobs)

    async def mineastr_relay_message(self, event: AstrMessageEvent) -> None:
        platform_id = str(event.get_platform_id() or "")
        raw = (
            self.plugin._event_raw_message(event) if platform_id == "minecraft" else {}
        )
        reply_context = self.plugin._event_reply_context(event)
        reply_to_synced = self.plugin._is_reply_to_synced_message(event, reply_context)
        if not self.plugin._cfg_bool("bridge_enabled"):
            if reply_to_synced:
                event.stop_event()
            if bool(raw.get("native_chat")):
                fallback = strip_minecraft_colors(
                    str(
                        raw.get("native_original_content") or raw.get("content") or ""
                    ).strip()
                )
                await self.plugin._complete_native_minecraft_chat(raw, fallback, {}, ())
            return
        text = str(event.message_str or "").strip()
        event_media = self.plugin._event_media(event)
        if not text and not event_media:
            if reply_to_synced:
                event.stop_event()
            if bool(raw.get("native_chat")):
                fallback = strip_minecraft_colors(
                    str(
                        raw.get("native_original_content") or raw.get("content") or ""
                    ).strip()
                )
                await self.plugin._complete_native_minecraft_chat(raw, fallback, {}, ())
            return

        if platform_id == "minecraft":
            await self.plugin._relay_minecraft_chat(event, raw, text)
            if (
                not raw.get("minecraft_mentioned_bot")
                and not event.is_at_or_wake_command
            ):
                event.stop_event()
            return

        if event.unified_msg_origin not in self.plugin._relay_sessions:
            return
        if self.plugin._is_mineastr_command(text):
            return
        if text.startswith("/") and not self.plugin._cfg_bool("relay_commands"):
            return
        relay_bot_conversation = self.plugin._cfg_bool(
            "relay_bot_conversations_to_game"
        ) or self.plugin._cfg_bool("relay_wake_messages")
        if (
            event.is_at_or_wake_command
            and not relay_bot_conversation
            and not reply_to_synced
        ):
            return

        prefix = str(self.plugin._cfg("relay_prefix"))
        if prefix:
            if not text.startswith(prefix):
                return
            text = text[len(prefix) :].lstrip()
        filtered = (
            apply_aqqbot_filters(text, self.plugin._cfg("chat_to_game_filters"))
            if text
            else ""
        )
        if filtered is None:
            event.stop_event()
            return
        identity = self.plugin._identity(event)
        media = event_media
        self.plugin._remember_relay_message(
            event,
            sender_name=identity["owner_display"],
            origin=event.unified_msg_origin,
        )
        game_sender = await self.plugin._game_sender_name(
            identity["owner_key"], identity["owner_display"]
        )
        game_template = str(self.plugin._cfg("chat_to_game_template"))
        game_values = {
            "platform": identity["platform_id"],
            "sender": game_sender,
            "user_id": identity["user_id"],
        }

        def game_content(message: str) -> str:
            return format_template(
                game_template,
                {
                    **game_values,
                    "message": message,
                },
            )

        platform_prefix = f"[{identity['owner_display']}] "
        target_sessions = self.plugin._relay_target_sessions(event.unified_msg_origin)
        adapter = self.plugin._minecraft_adapter()
        has_game_target = adapter is not None and hasattr(adapter, "relay_chat")
        translation_result = await self.plugin._translate_relay_message(
            filtered,
            target_sessions,
            event.unified_msg_origin,
            include_game=has_game_target,
        )
        quoted_text = str((reply_context or {}).get("text") or "").strip()
        reply_result = (
            await self.plugin._translate_relay_message(
                quoted_text,
                target_sessions,
                event.unified_msg_origin,
                include_game=has_game_target,
                cache_scope=f"relay-quote:{event.unified_msg_origin}",
            )
            if quoted_text
            else {}
        )

        async def send_to_game() -> None:
            if not has_game_target:
                logger.warning("MineAstr minecraft 平台适配器未启用，无法转发聊天。")
                return
            game_reply = await self.plugin._game_reply_context(
                reply_context, identity["platform_id"]
            )
            reply_prefix = self.plugin._reply_prefix(game_reply)
            game_media = await self.plugin._game_media_payloads(
                media, qq_bot=getattr(event, "bot", None)
            )
            media_marker = (
                "\n[图片]"
                if game_media and filtered
                else ("[图片]" if game_media else "")
            )
            content = game_content(reply_prefix + filtered + media_marker)
            game_translation_result = self.plugin._game_reply_translation_result(
                filtered,
                translation_result,
                game_reply,
                reply_result,
                lambda translated: game_content(translated + media_marker),
            )
            try:
                relay_kwargs = {
                    "origin": event.unified_msg_origin,
                    "translation_options": self.plugin._game_translation_options_from_result(
                        game_translation_result
                    ),
                }
                if game_media:
                    relay_kwargs["media"] = game_media
                await adapter.relay_chat(
                    trim_message(content, self.plugin._cfg_int("max_relay_length")),
                    game_sender,
                    **relay_kwargs,
                )
                await self.plugin._notify_mentioned_players(event, filtered)
            except Exception as exc:
                logger.warning("MineAstr 转发聊天到 Minecraft 失败：%s", exc)
                return

        await asyncio.gather(
            self.plugin._send_to_relay_sessions(
                platform_prefix + filtered,
                sessions=target_sessions,
                translation_result=self.plugin._transform_translation_result(
                    translation_result,
                    lambda translated: platform_prefix + translated,
                ),
                media=media,
                reply_context=reply_context,
                reply_translation_result=reply_result,
            ),
            send_to_game(),
        )
        if reply_to_synced or not event.is_at_or_wake_command:
            event.stop_event()

    async def mineastr_relay_bot_reply_to_game(self, event: AstrMessageEvent) -> None:
        if self.plugin._is_reply_to_synced_message(
            event, self.plugin._event_reply_context(event)
        ):
            return
        source_platform = str(event.get_platform_id() or "")
        if (
            not self.plugin._cfg_bool("bridge_enabled")
            or not self.plugin._cfg_bool("relay_bot_conversations_to_game")
            or (
                source_platform != "minecraft"
                and event.unified_msg_origin not in self.plugin._relay_sessions
            )
            or not event.is_at_or_wake_command
            or self.plugin._is_mineastr_command(str(event.message_str or ""))
        ):
            return
        result = event.get_result()
        if result is None or not hasattr(result, "get_plain_text"):
            return
        text = trim_message(
            str(result.get_plain_text() or "").strip(),
            self.plugin._cfg_int("max_relay_length"),
        )
        media = self.plugin._chain_media(result)
        if not text and not media:
            return
        adapter = self.plugin._minecraft_adapter()
        has_game_target = (
            source_platform != "minecraft"
            and adapter is not None
            and hasattr(adapter, "relay_chat")
        )
        targets = self.plugin._relay_target_sessions(
            event.unified_msg_origin, source_platform="minecraft"
        )
        bot_name = str(getattr(adapter, "bot_display_name", "AstrBot") or "AstrBot")
        reply_context = self.plugin._chain_reply_context(getattr(result, "chain", ()))
        if reply_context and not reply_context.get("text"):
            # Native Reply components often carry only the triggering message ID.
            trigger_id = str(
                getattr(getattr(event, "message_obj", None), "message_id", "") or ""
            )
            if trigger_id and reply_context.get("message_id") == trigger_id:
                reply_context["text"] = trim_message(event.message_str, 240)
                reply_context["sender"] = str(event.get_sender_name() or "")
                reply_context["sender_id"] = str(event.get_sender_id() or "")
        translation_result = await self.plugin._translate_relay_message(
            text, targets, event.unified_msg_origin, include_game=has_game_target
        )
        quoted_text = str(reply_context.get("text") or "").strip()
        reply_result = (
            await self.plugin._translate_relay_message(
                quoted_text,
                targets,
                event.unified_msg_origin,
                include_game=has_game_target,
                cache_scope=f"relay-quote:{event.unified_msg_origin}",
            )
            if quoted_text
            else {}
        )
        logger.info(
            "MineAstr Bot 回复同步：source=%s targets=%s game=%s "
            "body_languages=%s quote_languages=%s",
            source_platform,
            len(targets),
            has_game_target,
            sorted(self.plugin._translation_result_parts(translation_result)[1]),
            sorted(self.plugin._translation_result_parts(reply_result)[1]),
        )
        try:
            await self.plugin._send_to_relay_sessions(
                f"[{bot_name}] " + text if text else f"[{bot_name}]",
                exclude=event.unified_msg_origin,
                sessions=targets,
                translation_result=self.plugin._transform_translation_result(
                    translation_result, lambda translated: f"[{bot_name}] " + translated
                ),
                media=media,
                reply_context=reply_context,
                reply_translation_result=reply_result,
            )
        except Exception as exc:
            logger.warning("MineAstr 转发机器人回复到其他平台失败：%s", exc)
        if not has_game_target:
            return
        try:
            game_media = await self.plugin._game_media_payloads(media)
            game_text = text or ("[图片]" if game_media else "")
            if not game_text and not game_media:
                return
            game_reply = await self.plugin._game_reply_context(
                reply_context, source_platform
            )
            translated_result = self.plugin._game_reply_translation_result(
                game_text,
                translation_result,
                game_reply,
                reply_result,
                lambda value: value,
            )
            await adapter.relay_chat(
                self.plugin._reply_prefix(game_reply) + game_text,
                bot_name,
                origin=event.unified_msg_origin,
                translation_options=self.plugin._game_translation_options_from_result(
                    translated_result
                ),
                media=game_media,
            )
        except Exception as exc:
            logger.warning("MineAstr 转发机器人回复到 Minecraft 失败：%s", exc)

    async def mineastr_bridge_add_command(self, event: AstrMessageEvent):
        """把当前群聊/Discord 频道加入群服互联。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限修改桥接频道。")
            return
        sessions = set(self.plugin._relay_sessions)
        sessions.add(event.unified_msg_origin)
        self.plugin._set_relay_sessions(sessions)
        yield event.plain_result(f"已桥接当前会话：{event.unified_msg_origin}")

    async def mineastr_bridge_remove_command(self, event: AstrMessageEvent):
        """从群服互联中移除当前群聊/Discord 频道。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限修改桥接频道。")
            return
        sessions = set(self.plugin._relay_sessions)
        sessions.discard(event.unified_msg_origin)
        self.plugin._set_relay_sessions(sessions)
        yield event.plain_result(f"已取消桥接：{event.unified_msg_origin}")

    async def mineastr_bridge_list_command(self, event: AstrMessageEvent):
        """查看已桥接的 AstrBot 会话。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限查看桥接配置。")
            return
        yield event.plain_result(
            "已桥接会话：\n"
            + ("\n".join(sorted(self.plugin._relay_sessions)) or "（无）")
        )

    async def mineastr_discord_status_command(self, event: AstrMessageEvent):
        """查看 Discord 退群解绑、昵称同步及成员 Intent 状态。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限查看 Discord 自动化状态。")
            return
        configured_guilds = parse_items(self.plugin._cfg("discord_guild_ids"))
        lines = [
            "Discord 自动化：",
            "退群自动解绑："
            + (
                "开启"
                if self.plugin._cfg_bool("discord_auto_unbind_on_leave")
                else "关闭"
            ),
            "绑定自动昵称："
            + ("开启" if self.plugin._cfg_bool("discord_auto_nickname") else "关闭"),
            "目标服务器："
            + (", ".join(configured_guilds) if configured_guilds else "全部"),
        ]
        adapters = self.plugin._discord_adapters()
        if not adapters:
            lines.append("Discord 平台：未加载")
        for platform_id, adapter in adapters:
            client = getattr(adapter, "client", None)
            ready = bool(
                client is not None
                and callable(getattr(client, "is_ready", None))
                and client.is_ready()
            )
            members_intent = bool(
                getattr(getattr(client, "intents", None), "members", False)
            )
            guild_count = len(list(getattr(client, "guilds", ()) or ()))
            binding = self.plugin._discord_listener_bindings.get(platform_id)
            listener = bool(binding and len(binding) > 1 and binding[1] is not None)
            edit_listener = bool(
                binding and len(binding) > 2 and binding[2] is not None
            )
            lines.append(
                f"{platform_id}：{'在线' if ready else '未就绪'}，"
                f"成员 Intent {'已申请' if members_intent else '未申请'}，"
                f"退群监听 {'已注册' if listener else '未注册'}，"
                f"可见服务器 {guild_count}"
                f"，消息编辑同步 {'已注册' if edit_listener else '未注册'}"
            )
        lines.append(
            "注：指令只能确认客户端已申请成员 Intent；Developer Portal 开关需人工确认。"
        )
        yield event.plain_result("\n".join(lines))
