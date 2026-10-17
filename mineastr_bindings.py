"""绑定操作、验证码和完整快照调度 / Binding mutations, verification codes and complete snapshot scheduling."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    BindingError,
    CooldownTracker,
    PlayerAlreadyBoundError,
    asyncio,
    logger,
    math,
    normalize_owner_spec,
    safe_user_regex_fullmatch,
    time,
)


class BindingService:
    """绑定操作、验证码和完整快照调度 / Binding mutations, verification codes and complete snapshot scheduling.

    验证码、SQLite 入口和按服务器登记的同步任务归本服务；快照必须在锁内读取当前数据库。
    Own codes, the store reference and per-server sync tasks; read current snapshots under the lock.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._binding_store = None
        self._binding_reconcile_tasks = set()
        self._binding_reconcile_by_server = {}
        self._binding_snapshot_lock = None
        self._verify_codes = {}
        self._cooldowns = CooldownTracker()

    @staticmethod
    def _identity(event: AstrMessageEvent) -> dict[str, str]:
        platform_id = str(event.get_platform_id() or "unknown").strip()
        user_id = str(event.get_sender_id() or "unknown").strip()
        sender_name = str(event.get_sender_name() or user_id).strip()
        return {
            "owner_key": f"{platform_id}:{user_id}",
            "platform_id": platform_id,
            "user_id": user_id,
            "owner_display": sender_name,
        }

    def _consume_verify_code(self, code: str) -> str | None:
        now = time.monotonic()
        for candidate, data in list(self._verify_codes.items()):
            if float(data.get("expires_at", 0)) <= now:
                self._verify_codes.pop(candidate, None)
        data = self._verify_codes.pop(code.strip().casefold(), None)
        return str(data.get("player_name") or "").strip() if data else None

    def _validated_player_name(self, value: str) -> str:
        player_name = value.strip()
        if not safe_user_regex_fullmatch(
            str(self.plugin._cfg("player_name_regex")), player_name
        ):
            raise BindingError(
                f"玩家名不符合规则 {self.plugin._cfg('player_name_regex')}。"
            )
        return player_name

    @staticmethod
    def _validated_verified_player_name(value: str) -> str:
        """Validate an authenticated name reported by the Minecraft server.

        A verification code proves which exact login name requested access, so
        the configurable GROUP_NAME regex must not reject that server identity.
        """

        player_name = str(value or "").strip()
        if not player_name or len(player_name) > 64:
            raise BindingError("Minecraft 服务端上报的玩家名为空或超过 64 个字符。")
        if any(
            ord(character) < 32 or ord(character) == 127 for character in player_name
        ):
            raise BindingError("Minecraft 服务端上报的玩家名包含控制字符。")
        return player_name

    async def _create_binding(
        self, identity: dict[str, str], player_name: str
    ) -> tuple[Any, dict[str, Any]]:
        record = await self._binding_store.bind(
            owner_key=identity["owner_key"],
            platform_id=identity["platform_id"],
            user_id=identity["user_id"],
            owner_display=identity["owner_display"],
            player_name=player_name,
            max_bind_count=max(1, self.plugin._cfg_int("max_bind_count")),
        )
        sync_result = await self.plugin._sync_binding_to_server("bind", record)
        if (
            self.plugin._cfg_bool("sync_binding_to_server")
            and self.plugin._cfg_bool("binding_sync_required")
            and not sync_result.get("ok")
        ):
            await self._binding_store.unbind(record.owner_key, record.player_name)
            raise BindingError(
                f"Minecraft 端同步失败，已回滚绑定：{sync_result.get('error') or '未知错误'}"
            )
        return record, sync_result

    async def mineastr_bind_command(self, event: AstrMessageEvent, value: str):
        """绑定当前聊天账号与 Minecraft 玩家。"""
        if not self.plugin._cfg_bool("binding_enabled"):
            yield event.plain_result("账号绑定功能未启用。")
            return
        identity = self.plugin._identity(event)
        remaining = self._cooldowns.check_and_mark(
            "bind",
            identity["owner_key"],
            max(0, self.plugin._cfg_int("bind_cooldown_seconds")),
        )
        if remaining > 0:
            yield event.plain_result(
                f"绑定操作冷却中，请等待 {math.ceil(remaining)} 秒。"
            )
            return

        verify_method = str(self.plugin._cfg("verify_method")).strip().upper()
        verified_by_code = verify_method == "VERIFY_CODE"
        if verified_by_code:
            player_name = self.plugin._consume_verify_code(value)
            if not player_name:
                yield event.plain_result(
                    "验证码不存在或已过期。请先尝试登录服务器获取验证码。"
                )
                return
        else:
            player_name = value
        try:
            player_name = (
                self.plugin._validated_verified_player_name(player_name)
                if verified_by_code
                else self.plugin._validated_player_name(player_name)
            )
            record, sync_result = await self.plugin._create_binding(
                identity, player_name
            )
        except PlayerAlreadyBoundError:
            yield event.plain_result(f"玩家 {player_name} 已被其他聊天账号绑定。")
            return
        except BindingError as exc:
            yield event.plain_result(str(exc))
            return
        note = ""
        if self.plugin._cfg_bool("sync_binding_to_server") and not sync_result.get(
            "ok"
        ):
            note = (
                f"；但 Minecraft 端同步失败：{sync_result.get('error') or '未知错误'}"
            )
        qq_card_result = await self.plugin._update_qq_group_card_after_bind(
            event, record
        )
        if not qq_card_result.get("ok"):
            note += (
                f"；但 QQ 群名片同步失败：{qq_card_result.get('error') or '未知错误'}"
            )
        member = self.plugin._discord_member_from_event(event)
        if member is not None:
            nickname_result = await self.plugin._refresh_discord_nickname_for_owner(
                record.platform_id,
                record.user_id,
                record.owner_key,
                member,
            )
            if not nickname_result.get("ok"):
                note += (
                    "；但 Discord 昵称同步失败："
                    f"{nickname_result.get('error') or '未知错误'}"
                )
        yield event.plain_result(f"绑定成功：{record.player_name}{note}")

    async def mineastr_unbind_command(
        self, event: AstrMessageEvent, player_name: str = ""
    ):
        """解绑当前聊天账号的 Minecraft 玩家。"""
        if not self.plugin._cfg_bool("binding_enabled"):
            yield event.plain_result("账号绑定功能未启用。")
            return
        identity = self.plugin._identity(event)
        remaining = self._cooldowns.check_and_mark(
            "unbind",
            identity["owner_key"],
            max(0, self.plugin._cfg_int("unbind_cooldown_seconds")),
        )
        if remaining > 0:
            yield event.plain_result(
                f"解绑操作冷却中，请等待 {math.ceil(remaining)} 秒。"
            )
            return
        records = await self._binding_store.get_by_owner(identity["owner_key"])
        if not records:
            yield event.plain_result("你还没有绑定 Minecraft 账号。")
            return
        if not player_name and len(records) > 1:
            yield event.plain_result(
                "你绑定了多个账号，请指定玩家名："
                + ", ".join(r.player_name for r in records)
            )
            return
        target_name = player_name or records[0].player_name
        target = next(
            (r for r in records if r.player_name.casefold() == target_name.casefold()),
            None,
        )
        if target is None:
            yield event.plain_result(f"{target_name} 不是你绑定的玩家。")
            return
        required_sync = self.plugin._cfg_bool(
            "sync_binding_to_server"
        ) and self.plugin._cfg_bool("binding_sync_required")
        sync_result: dict[str, Any] = {"ok": True, "skipped": True}
        if required_sync:
            sync_result = await self.plugin._sync_binding_to_server("unbind", target)
            if not sync_result.get("ok"):
                yield event.plain_result(
                    f"Minecraft 端同步失败，未解绑：{sync_result.get('error') or '未知错误'}"
                )
                return
        removed = await self._binding_store.unbind(
            identity["owner_key"], target.player_name
        )
        if not removed:
            yield event.plain_result("绑定记录已不存在。")
            return
        if not required_sync:
            sync_result = await self.plugin._sync_binding_to_server("unbind", target)
        note = ""
        if self.plugin._cfg_bool("sync_binding_to_server") and not sync_result.get(
            "ok"
        ):
            note = (
                f"；但 Minecraft 端同步失败：{sync_result.get('error') or '未知错误'}"
            )
        qq_card_result = await self.plugin._update_qq_group_card_after_bind(
            event, target
        )
        if not qq_card_result.get("ok"):
            note += (
                f"；但 QQ 群名片同步失败：{qq_card_result.get('error') or '未知错误'}"
            )
        member = self.plugin._discord_member_from_event(event)
        if member is not None:
            nickname_result = await self.plugin._refresh_discord_nickname_for_owner(
                target.platform_id,
                target.user_id,
                target.owner_key,
                member,
            )
            if not nickname_result.get("ok"):
                note += (
                    "；但 Discord 昵称恢复失败："
                    f"{nickname_result.get('error') or '未知错误'}"
                )
        yield event.plain_result(f"解绑成功：{target.player_name}{note}")

    async def mineastr_bindings_command(self, event: AstrMessageEvent):
        """查看当前聊天账号的绑定。"""
        identity = self.plugin._identity(event)
        records = await self._binding_store.get_by_owner(identity["owner_key"])
        if not records:
            yield event.plain_result("你还没有绑定 Minecraft 账号。")
            return
        yield event.plain_result("已绑定：" + ", ".join(r.player_name for r in records))

    async def mineastr_who_command(self, event: AstrMessageEvent, player_name: str):
        """查询指定 Minecraft 玩家的绑定。"""
        record = await self._binding_store.get_by_player(player_name)
        if not record:
            yield event.plain_result(f"{player_name} 尚未绑定聊天账号。")
            return
        yield event.plain_result(
            f"{record.player_name} 已绑定到 {record.platform_id} 用户 {record.owner_display or record.user_id}。"
        )

    async def mineastr_admin_bind_command(
        self, event: AstrMessageEvent, owner: str, player_name: str
    ):
        """管理员为指定平台用户绑定玩家。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限管理他人的绑定。")
            return
        try:
            owner_key, platform_id, user_id = normalize_owner_spec(
                owner, str(event.get_platform_id() or "unknown")
            )
            player_name = self.plugin._validated_player_name(player_name)
            record, sync_result = await self.plugin._create_binding(
                {
                    "owner_key": owner_key,
                    "platform_id": platform_id,
                    "user_id": user_id,
                    "owner_display": owner,
                },
                player_name,
            )
        except (ValueError, BindingError) as exc:
            yield event.plain_result(str(exc))
            return
        note = ""
        if self.plugin._cfg_bool("sync_binding_to_server") and not sync_result.get(
            "ok"
        ):
            note = f"；Minecraft 端同步失败：{sync_result.get('error') or '未知错误'}"
        qq_card_result = await self.plugin._update_qq_group_card_after_bind(
            event, record
        )
        if not qq_card_result.get("ok"):
            note += f"；QQ 群名片同步失败：{qq_card_result.get('error') or '未知错误'}"
        if self.plugin._discord_adapter(record.platform_id) is not None:
            nickname_result = await self.plugin._refresh_discord_nickname_for_owner(
                record.platform_id,
                record.user_id,
                record.owner_key,
            )
            if not nickname_result.get("ok"):
                note += (
                    "；Discord 昵称同步失败："
                    f"{nickname_result.get('error') or '未知错误'}"
                )
        yield event.plain_result(
            f"已为 {record.owner_key} 绑定 {record.player_name}{note}"
        )

    async def mineastr_admin_unbind_command(
        self, event: AstrMessageEvent, owner: str, player_name: str
    ):
        """管理员解除指定平台用户的玩家绑定。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限管理他人的绑定。")
            return
        try:
            owner_key, _, _ = normalize_owner_spec(
                owner, str(event.get_platform_id() or "unknown")
            )
        except ValueError as exc:
            yield event.plain_result(str(exc))
            return
        records = await self._binding_store.get_by_owner(owner_key)
        target = next(
            (r for r in records if r.player_name.casefold() == player_name.casefold()),
            None,
        )
        if target is None:
            yield event.plain_result(f"{owner_key} 没有绑定 {player_name}。")
            return
        required_sync = self.plugin._cfg_bool(
            "sync_binding_to_server"
        ) and self.plugin._cfg_bool("binding_sync_required")
        sync_result: dict[str, Any] = {"ok": True, "skipped": True}
        if required_sync:
            sync_result = await self.plugin._sync_binding_to_server("unbind", target)
            if not sync_result.get("ok"):
                yield event.plain_result(
                    f"Minecraft 端同步失败，未解绑：{sync_result.get('error') or '未知错误'}"
                )
                return
        await self._binding_store.unbind(owner_key, target.player_name)
        if not required_sync:
            sync_result = await self.plugin._sync_binding_to_server("unbind", target)
        note = ""
        if self.plugin._cfg_bool("sync_binding_to_server") and not sync_result.get(
            "ok"
        ):
            note = f"；Minecraft 端同步失败：{sync_result.get('error') or '未知错误'}"
        qq_card_result = await self.plugin._update_qq_group_card_after_bind(
            event, target
        )
        if not qq_card_result.get("ok"):
            note += f"；QQ 群名片同步失败：{qq_card_result.get('error') or '未知错误'}"
        if self.plugin._discord_adapter(target.platform_id) is not None:
            nickname_result = await self.plugin._refresh_discord_nickname_for_owner(
                target.platform_id,
                target.user_id,
                target.owner_key,
            )
            if not nickname_result.get("ok"):
                note += (
                    "；Discord 昵称恢复失败："
                    f"{nickname_result.get('error') or '未知错误'}"
                )
        yield event.plain_result(
            f"已解除 {owner_key} 与 {target.player_name} 的绑定{note}"
        )

    async def _sync_binding_to_server(self, action: str, record: Any) -> dict[str, Any]:
        if not self.plugin._cfg_bool("sync_binding_to_server"):
            return {"ok": True, "skipped": True}
        adapter = self.plugin._minecraft_adapter()
        manager = getattr(adapter, "connection_manager", None)
        if (
            adapter is None
            or not hasattr(adapter, "replace_bindings")
            or manager is None
        ):
            return {"ok": False, "error": "Minecraft 适配器不支持绑定快照"}
        try:
            servers = await manager.snapshot()
            results = [
                await self.plugin._reconcile_bindings_to_server(str(item["server_id"]))
                for item in servers
            ]
            return {
                "ok": bool(results) and all(result.get("ok") for result in results),
                "servers": results,
                "error": "" if results else "当前没有已连接的 Minecraft 服务器",
            }
        except Exception as exc:
            return {"ok": False, "error": str(exc) or exc.__class__.__name__}

    async def _periodic_binding_reconcile(self) -> None:
        while True:
            await asyncio.sleep(
                max(
                    30, min(3600, self.plugin._cfg_int("binding_sync_interval_seconds"))
                )
            )
            await self.plugin._schedule_connected_server_reconcile(
                include_command_admins=False
            )

    def _schedule_binding_reconcile(self, server_id: str) -> None:
        if not self.plugin._cfg_bool("sync_binding_to_server") or not server_id:
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "replace_bindings"):
            return
        active = getattr(self.plugin, "_binding_reconcile_by_server", None)
        if active is None:
            active = self._binding_reconcile_by_server = {}
        if server_id in active and not active[server_id].done():
            return
        task = asyncio.create_task(
            self.plugin._reconcile_bindings_to_server(server_id),
            name=f"mineastr-binding-reconcile-{server_id}",
        )
        active[server_id] = task
        self._binding_reconcile_tasks.add(task)
        task.add_done_callback(self._binding_reconcile_tasks.discard)
        task.add_done_callback(
            lambda completed: (
                active.pop(server_id, None)
                if active.get(server_id) is completed
                else None
            )
        )

    async def _schedule_connected_server_reconcile(
        self, include_command_admins: bool = True
    ) -> None:
        if not (
            self.plugin._cfg_bool("sync_binding_to_server")
            or self.plugin._cfg_bool("sync_command_admins_to_server")
        ):
            return
        adapter = self.plugin._minecraft_adapter()
        manager = getattr(adapter, "connection_manager", None)
        if manager is None or not hasattr(manager, "snapshot"):
            return
        try:
            for metadata in await manager.snapshot():
                server_id = str(metadata.get("server_id") or "")
                self.plugin._schedule_binding_reconcile(server_id)
                if include_command_admins:
                    self.plugin._schedule_command_admin_reconcile(server_id)
        except Exception as exc:
            logger.warning("MineAstr 读取已连接服务器列表失败：%s", exc)

    async def _reconcile_bindings_to_server(self, server_id: str) -> dict[str, Any]:
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "replace_bindings"):
            return {"ok": False, "error": "minecraft_adapter_too_old"}
        lock = getattr(self.plugin, "_binding_snapshot_lock", None)
        if lock is None:
            lock = self._binding_snapshot_lock = asyncio.Lock()
        async with lock:
            try:
                records = await self._binding_store.all()
                result = await adapter.replace_bindings(server_id, records)
                if result.get("ok"):
                    logger.info(
                        "MineAstr 已向服务器 %s 整批对账 %d 条绑定。",
                        server_id,
                        len(records),
                    )
                else:
                    logger.warning(
                        "MineAstr 绑定白名单同步失败，MC 保留上次成功名单：server=%s error=%s",
                        server_id,
                        result.get("error"),
                    )
                return result
            except Exception as exc:
                logger.warning(
                    "MineAstr 绑定白名单对账失败：server=%s error=%s", server_id, exc
                )
                return {"ok": False, "error": str(exc) or exc.__class__.__name__}
