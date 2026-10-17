"""Discord 监听、昵称及消息编辑撤回 / Discord listeners, nicknames, edits and deletions."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    apply_aqqbot_filters,
    asyncio,
    discord_media,
    format_template,
    logger,
    parse_items,
    re,
    trim_message,
)


class DiscordAutomation:
    """Discord 监听、昵称及消息编辑撤回 / Discord listeners, nicknames, edits and deletions.

    监听器绑定和重试任务归本服务；卸载先取消重试，再移除已安装的监听器。
    Own listener bindings and retries; cancel retry tasks before detaching listeners on unload.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._discord_listener_bindings = {}
        self._discord_attach_task = None

    @staticmethod
    def _is_discord_adapter(adapter: Any) -> bool:
        try:
            return str(adapter.meta().name).casefold() == "discord"
        except Exception:
            return False

    def _discord_adapters(self) -> list[tuple[str, Any]]:
        adapters: list[tuple[str, Any]] = []
        for adapter in self.plugin._platform_instances():
            if not self.plugin._is_discord_adapter(adapter):
                continue
            try:
                platform_id = str(adapter.meta().id or "").strip()
            except Exception:
                continue
            if platform_id:
                adapters.append((platform_id, adapter))
        return adapters

    def _schedule_discord_listener_attach(self) -> None:
        if not (
            self.plugin._cfg_bool("discord_auto_unbind_on_leave")
            or self.plugin._cfg_bool("bridge_enabled")
        ):
            return
        if (
            self._discord_attach_task is not None
            and not self._discord_attach_task.done()
        ):
            return
        self._discord_attach_task = asyncio.create_task(
            self.plugin._attach_discord_listeners_with_retry(),
            name="mineastr-discord-member-listener",
        )

    async def _attach_discord_listeners_with_retry(self) -> None:
        """Attach after the Discord adapter has created its Pycord client."""

        try:
            for _ in range(120):
                waiting = False
                for platform_id, adapter in self.plugin._discord_adapters():
                    client = getattr(adapter, "client", None)
                    if client is None or not hasattr(client, "add_listener"):
                        waiting = True
                        continue
                    current = self._discord_listener_bindings.get(platform_id)
                    if current is not None and current[0] is client:
                        continue
                    if current is not None:
                        self.plugin._remove_discord_listener_binding(current)

                    member_callback: Any | None = None
                    edit_callback: Any | None = None
                    delete_callback: Any | None = None
                    if self.plugin._cfg_bool("discord_auto_unbind_on_leave"):

                        async def on_member_remove(
                            member: Any, *, _platform_id: str = platform_id
                        ) -> None:
                            await self.plugin._on_discord_member_remove(
                                _platform_id, member
                            )

                        client.add_listener(on_member_remove, "on_member_remove")
                        member_callback = on_member_remove

                    if self.plugin._cfg_bool("bridge_enabled"):

                        async def on_message_edit(
                            before: Any,
                            after: Any,
                            *,
                            _platform_id: str = platform_id,
                        ) -> None:
                            await self.plugin._on_discord_message_edit(
                                _platform_id, before, after
                            )

                        client.add_listener(on_message_edit, "on_message_edit")
                        edit_callback = on_message_edit

                        async def on_message_delete(
                            message: Any,
                            *,
                            _platform_id: str = platform_id,
                        ) -> None:
                            await self.plugin._on_discord_message_delete(
                                _platform_id, message
                            )

                        client.add_listener(on_message_delete, "on_message_delete")
                        delete_callback = on_message_delete

                    self._discord_listener_bindings[platform_id] = (
                        client,
                        member_callback,
                        edit_callback,
                        delete_callback,
                    )
                    logger.info(
                        "MineAstr Discord listeners registered: platform=%s events=%s",
                        platform_id,
                        ", ".join(
                            event_name
                            for event_name, callback in (
                                ("on_member_remove", member_callback),
                                ("on_message_edit", edit_callback),
                                ("on_message_delete", delete_callback),
                            )
                            if callback is not None
                        ),
                    )
                if not waiting:
                    return
                await asyncio.sleep(0.5)
            if self.plugin._discord_adapters():
                logger.warning(
                    "MineAstr 等待 Discord 客户端初始化超时，Discord 监听尚未注册。"
                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning("MineAstr 注册 Discord 成员监听失败：%s", exc)

    def _detach_discord_listeners(self) -> None:
        for binding in self._discord_listener_bindings.values():
            self.plugin._remove_discord_listener_binding(binding)
        self._discord_listener_bindings.clear()

    @staticmethod
    def _remove_discord_listener_binding(binding: tuple[Any, ...]) -> None:
        if not binding:
            return
        client = binding[0]
        event_names = (
            "on_member_remove",
            "on_message_edit",
            "on_message_delete",
        )
        for index, callback in enumerate(binding[1:]):
            if callback is None:
                continue
            try:
                client.remove_listener(callback, event_names[index])
            except Exception:
                pass

    def _discord_guild_allowed(self, guild_id: str) -> bool:
        configured = set(parse_items(self.plugin._cfg("discord_guild_ids")))
        return not configured or guild_id in configured

    def _discord_adapter(self, platform_id: str) -> Any | None:
        try:
            adapter = self.plugin.context.get_platform_inst(platform_id)
        except Exception:
            adapter = None
        return (
            adapter
            if adapter is not None and self.plugin._is_discord_adapter(adapter)
            else None
        )

    @staticmethod
    def _discord_member_from_event(event: AstrMessageEvent) -> Any | None:
        try:
            if str(event.get_platform_name()).casefold() != "discord":
                return None
        except Exception:
            return None
        raw = getattr(getattr(event, "message_obj", None), "raw_message", None)
        member = getattr(raw, "author", None) or getattr(raw, "user", None)
        return member if getattr(member, "guild", None) is not None else None

    async def _discord_members_for_owner(
        self,
        platform_id: str,
        user_id: str,
        member_hint: Any | None = None,
    ) -> list[Any]:
        adapter = self.plugin._discord_adapter(platform_id)
        client = getattr(adapter, "client", None) if adapter is not None else None
        if client is None:
            return [member_hint] if member_hint is not None else []
        try:
            numeric_user_id = int(user_id)
        except (TypeError, ValueError):
            return []

        members: dict[str, Any] = {}
        if member_hint is not None:
            guild = getattr(member_hint, "guild", None)
            guild_id = str(getattr(guild, "id", ""))
            if guild_id and self.plugin._discord_guild_allowed(guild_id):
                members[guild_id] = member_hint
        for guild in list(getattr(client, "guilds", ()) or ()):
            guild_id = str(getattr(guild, "id", ""))
            if not guild_id or not self.plugin._discord_guild_allowed(guild_id):
                continue
            member = getattr(guild, "get_member", lambda _user_id: None)(
                numeric_user_id
            )
            if member is None and hasattr(guild, "fetch_member"):
                try:
                    member = await guild.fetch_member(numeric_user_id)
                except Exception:
                    member = None
            if member is not None:
                members[guild_id] = member
        return list(members.values())

    @staticmethod
    def _bounded_nickname(
        records: list[Any],
        template: str,
        max_length: int,
        extra_values: dict[str, str] | None = None,
    ) -> str:
        if not records or max_length <= 0:
            return ""
        player_names = [str(record.player_name) for record in records]
        base_values = {
            "owner": str(records[0].owner_display or records[0].user_id),
            "user_id": str(records[0].user_id),
        }
        if extra_values:
            base_values.update(extra_values)
        best = ""
        for count in range(1, len(player_names) + 1):
            values = {
                **base_values,
                "player": player_names[0],
                "players": ", ".join(player_names[:count]),
            }
            candidate = format_template(template, values)
            candidate = re.sub(r"[\r\n\t]+", " ", candidate).strip()
            if len(candidate) > max_length:
                break
            best = candidate
        if best:
            return best
        first = format_template(
            template,
            {
                **base_values,
                "player": player_names[0],
                "players": player_names[0],
            },
        )
        first = re.sub(r"[\r\n\t]+", " ", first).strip() or player_names[0]
        if len(first) <= max_length:
            return first
        return first[: max(1, max_length - 1)] + ("…" if max_length > 1 else "")

    def _discord_nickname(self, records: list[Any]) -> str:
        return self.plugin._bounded_nickname(
            records,
            str(self.plugin._cfg("discord_nickname_template")),
            32,
        )

    async def _refresh_discord_nickname_for_owner(
        self,
        platform_id: str,
        user_id: str,
        owner_key: str,
        member_hint: Any | None = None,
    ) -> dict[str, Any]:
        if not self.plugin._cfg_bool("discord_auto_nickname") or not platform_id:
            return {"ok": True, "skipped": True, "updated": 0}
        adapter = self.plugin._discord_adapter(platform_id)
        if adapter is None and member_hint is None:
            return {
                "ok": False,
                "error": "对应的 Discord 平台实例未运行",
                "updated": 0,
            }
        records = await self.plugin._binding_store.get_by_owner(owner_key)
        members = await self.plugin._discord_members_for_owner(
            platform_id, user_id, member_hint
        )
        if not members:
            return {
                "ok": False,
                "error": "未在允许的 Discord 服务器中找到成员",
                "updated": 0,
            }

        updated = 0
        errors: list[str] = []
        reason = str(self.plugin._cfg("discord_nickname_reason"))[:512] or None
        for member in members:
            guild = getattr(member, "guild", None)
            guild_id = str(getattr(guild, "id", ""))
            if not guild_id or not self.plugin._discord_guild_allowed(guild_id):
                continue
            guild_name = str(getattr(guild, "name", guild_id))
            try:
                if records:
                    nickname = self.plugin._discord_nickname(records)
                    if getattr(member, "nick", None) == nickname:
                        continue
                    await self.plugin._binding_store.remember_discord_nickname(
                        owner_key, guild_id, getattr(member, "nick", None)
                    )
                    await member.edit(nick=nickname, reason=reason)
                    updated += 1
                    continue

                state = await self.plugin._binding_store.get_discord_nickname(
                    owner_key, guild_id
                )
                if state is None:
                    continue
                if self.plugin._cfg_bool("discord_restore_nickname_on_unbind"):
                    await member.edit(nick=state.original_nickname, reason=reason)
                    updated += 1
                await self.plugin._binding_store.pop_discord_nickname(
                    owner_key, guild_id
                )
            except Exception as exc:
                errors.append(f"{guild_name}: {str(exc) or exc.__class__.__name__}")
        return {
            "ok": not errors,
            "updated": updated,
            "errors": errors,
            "error": "；".join(errors),
        }

    async def _on_discord_member_remove(self, platform_id: str, member: Any) -> None:
        if not self.plugin._cfg_bool("discord_auto_unbind_on_leave"):
            return
        guild = getattr(member, "guild", None)
        guild_id = str(getattr(guild, "id", ""))
        user_id = str(getattr(member, "id", ""))
        if (
            not guild_id
            or not user_id
            or not self.plugin._discord_guild_allowed(guild_id)
        ):
            return

        owner_key = f"{platform_id}:{user_id}"
        removed = await self.plugin._binding_store.unbind(owner_key)
        await self.plugin._binding_store.pop_discord_nickname(owner_key, guild_id)
        if not removed:
            return
        sync_results = await asyncio.gather(
            *(
                self.plugin._sync_binding_to_server("unbind", record)
                for record in removed
            ),
            return_exceptions=True,
        )
        failures = [
            result
            for result in sync_results
            if isinstance(result, Exception)
            or (isinstance(result, dict) and not result.get("ok"))
        ]
        await self.plugin._refresh_discord_nickname_for_owner(
            platform_id, user_id, owner_key
        )
        logger.info(
            "MineAstr：Discord 用户 %s 离开服务器 %s，已自动解绑 %s。",
            owner_key,
            guild_id,
            ", ".join(record.player_name for record in removed),
        )
        if failures:
            logger.warning(
                "MineAstr：Discord 退群解绑已写入本地，但有 %d 条 Minecraft 同步失败。",
                len(failures),
            )

    @staticmethod
    def _discord_message_text(message: Any) -> str:
        """Read the text Discord exposes for an edited message."""
        clean_content = getattr(message, "clean_content", None)
        content = (
            clean_content
            if clean_content is not None
            else getattr(message, "content", "")
        )
        return str(content or "").strip()

    @staticmethod
    def _discord_message_media(message: Any) -> list[dict[str, str]]:
        return discord_media(message)

    @staticmethod
    def _discord_message_origin(platform_id: str, message: Any) -> str:
        channel = getattr(message, "channel", None)
        channel_id = str(getattr(channel, "id", "") or "").strip()
        if not channel_id:
            return ""
        message_type = (
            "GroupMessage" if getattr(message, "guild", None) else "FriendMessage"
        )
        return f"{platform_id}:{message_type}:{channel_id}"

    async def _on_discord_message_edit(
        self, platform_id: str, before: Any, after: Any
    ) -> None:
        """Relay Discord edits as a marked replacement message.

        Minecraft chat packets have no message-edit operation. Sending the new
        text again, with an explicit marker, is the only lossless behavior for
        clients that already rendered the old chat line.
        """
        if not self.plugin._cfg_bool("bridge_enabled"):
            return
        author = getattr(after, "author", None)
        if bool(getattr(author, "bot", False)) and not self.plugin._cfg_bool(
            "relay_bot_conversations_to_game"
        ):
            return
        guild = getattr(after, "guild", None)
        guild_id = str(getattr(guild, "id", "") or "").strip()
        if not guild_id or not self.plugin._discord_guild_allowed(guild_id):
            return

        origin = self.plugin._discord_message_origin(platform_id, after)
        if not origin or origin not in self.plugin._relay_sessions:
            return
        previous_text = self.plugin._discord_message_text(before)
        text = self.plugin._discord_message_text(after)
        if not text or text == previous_text:
            return
        if self.plugin._is_mineastr_command(text):
            return
        if text.startswith("/") and not self.plugin._cfg_bool("relay_commands"):
            return

        prefix = str(self.plugin._cfg("relay_prefix"))
        if prefix:
            if not text.startswith(prefix):
                return
            text = text[len(prefix) :].lstrip()
        if not text:
            return
        filtered = apply_aqqbot_filters(text, self.plugin._cfg("chat_to_game_filters"))
        if filtered is None:
            return

        sender_id = str(getattr(author, "id", "unknown") or "unknown")
        sender_name = str(
            getattr(author, "display_name", None)
            or getattr(author, "name", None)
            or sender_id
        ).strip()
        media = self.plugin._discord_message_media(after)
        message_id = str(getattr(after, "id", "") or "").strip()
        if message_id:
            self.plugin._store_relay_message_record(
                f"{platform_id}:{message_id}",
                platform_id=platform_id,
                message_id=message_id,
                origin=origin,
                sender_name=sender_name,
                owner_key=f"{platform_id}:{sender_id}",
            )
        target_sessions = self.plugin._relay_target_sessions(origin)
        adapter = self.plugin._minecraft_adapter()
        has_game_target = adapter is not None and hasattr(adapter, "relay_chat")
        translation_result = await self.plugin._translate_relay_message(
            filtered,
            target_sessions,
            origin,
            include_game=has_game_target,
        )

        edited_prefix = "[Edited] "
        platform_prefix = f"[{sender_name}] "
        await self.plugin._send_to_relay_sessions(
            platform_prefix + edited_prefix + filtered,
            sessions=target_sessions,
            translation_result=self.plugin._transform_translation_result(
                translation_result,
                lambda translated: platform_prefix + edited_prefix + translated,
            ),
            media=media,
        )
        if not has_game_target:
            logger.warning(
                "MineAstr Minecraft adapter is unavailable; Discord edit was not sent to game"
            )
            return

        game_sender = await self.plugin._game_sender_name(
            f"{platform_id}:{sender_id}", sender_name
        )
        game_template = str(self.plugin._cfg("chat_to_game_template"))
        game_values = {
            "platform": platform_id,
            "sender": game_sender,
            "user_id": sender_id,
        }

        def game_content(message: str) -> str:
            return format_template(
                game_template,
                {**game_values, "message": message},
            )

        game_media = await self.plugin._game_media_payloads(media)
        media_marker = "\n[图片]" if game_media else ""
        try:
            relay_kwargs = {
                "origin": origin,
                "translation_options": self.plugin._game_translation_options_from_result(
                    self.plugin._transform_translation_result(
                        translation_result,
                        lambda translated: game_content(
                            edited_prefix + translated + media_marker
                        ),
                    )
                ),
            }
            if game_media:
                relay_kwargs["media"] = game_media
            await adapter.relay_chat(
                trim_message(
                    game_content(edited_prefix + filtered + media_marker),
                    self.plugin._cfg_int("max_relay_length"),
                ),
                game_sender,
                **relay_kwargs,
            )
        except Exception as exc:
            logger.warning(
                "MineAstr failed to relay Discord edit to Minecraft: %s", exc
            )

    async def _on_discord_message_delete(self, platform_id: str, message: Any) -> None:
        if not self.plugin._cfg_bool("bridge_enabled"):
            return
        message_id = str(getattr(message, "id", "") or "").strip()
        if not message_id:
            return
        await self.plugin._relay_recalled_message(platform_id, message_id)
