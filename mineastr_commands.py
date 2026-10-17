"""命令请求、管理员同步和审批展示 / Command requests, administrator sync and approval presentation."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    asyncio,
    json,
    logger,
    parse_items,
    re,
)

from .mineastr_defaults import (
    SAFE_COMMAND_ADMIN_RE,
)


class CommandService:
    """命令请求、管理员同步和审批展示 / Command requests, administrator sync and approval presentation.

    管理员修订、审批和同步锁归本服务；框架装饰器仅保留在 main.py 入口。
    Own administrator revisions, approvals and the sync lock; keep framework decorators in main.py.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._command_admin_sync_lock = asyncio.Lock()
        self._command_admin_revision = 0
        self._reported_invalid_command_admin_count = 0
        self._pending_command_approvals = {}

    def _is_bridge_admin(self, event: AstrMessageEvent) -> bool:
        if event.is_admin():
            return True
        identity = self.plugin._identity(event)
        configured = set(parse_items(self.plugin._cfg("bridge_admin_users")))
        return identity["owner_key"] in configured or identity["user_id"] in configured

    def _is_mineastr_command(self, text: str) -> bool:
        normalized = text.strip().lstrip("/").casefold()
        root = normalized.split(" ", 1)[0]
        return root in {"mc", "mineastr", "minecraft"}

    @staticmethod
    def _command_tail(event: AstrMessageEvent) -> str:
        parts = re.sub(r"\s+", " ", event.message_str.strip()).split(" ", 2)
        return parts[2].strip() if len(parts) >= 3 else ""

    def _configured_command_admins(self, additional_admins: Any = ()) -> list[str]:
        candidates = list(parse_items(self.plugin._cfg("bridge_admin_users")))
        get_config = getattr(self.plugin.context, "get_config", None)
        if callable(get_config):
            try:
                core_config = get_config()
                if isinstance(core_config, dict):
                    candidates.extend(parse_items(core_config.get("admins_id")))
            except (AttributeError, TypeError, ValueError):
                pass
        candidates.extend(parse_items(additional_admins))
        admins: list[str] = []
        seen: set[str] = set()
        invalid_count = 0
        for candidate in candidates:
            value = str(candidate or "").strip()
            normalized = value.casefold()
            if (
                not value
                or len(value) > 128
                or any(
                    character.isspace() or ord(character) < 32 for character in value
                )
                or not SAFE_COMMAND_ADMIN_RE.fullmatch(value)
                or normalized in seen
            ):
                if value and normalized not in seen:
                    invalid_count += 1
                continue
            seen.add(normalized)
            admins.append(value)
        previous_invalid_count = int(
            getattr(self.plugin, "_reported_invalid_command_admin_count", 0)
        )
        if invalid_count and invalid_count != previous_invalid_count:
            logger.warning(
                "MineAstr 已跳过 %d 个格式无效的命令管理员标识；"
                "仅允许字母、数字及 _ . : @ -。",
                invalid_count,
            )
        self._reported_invalid_command_admin_count = invalid_count
        return admins[:256]

    def _schedule_command_admin_reconcile(self, server_id: str) -> None:
        if not self.plugin._cfg_bool("sync_command_admins_to_server") or not server_id:
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "replace_trusted_command_users"):
            logger.warning("MineAstr minecraft 平台适配器不支持管理员可信名单同步。")
            return
        task = asyncio.create_task(
            self.plugin._reconcile_command_admins_to_server(server_id),
            name=f"mineastr-admin-reconcile-{server_id}",
        )
        self.plugin._binding_reconcile_tasks.add(task)
        task.add_done_callback(self.plugin._binding_reconcile_tasks.discard)

    async def _reconcile_command_admins_to_server(self, server_id: str) -> None:
        await asyncio.sleep(0)
        await self.plugin._sync_command_admins_now(server_id)

    async def _sync_command_admins_now(
        self,
        server_id: str | None = None,
        additional_admins: Any = (),
    ) -> dict[str, Any]:
        """Refresh Bot administrators immediately before privileged approval."""

        if not self.plugin._cfg_bool("sync_command_admins_to_server"):
            return {"ok": False, "error": "command_admin_sync_disabled"}
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "replace_trusted_command_users"):
            return {"ok": False, "error": "minecraft_adapter_too_old"}
        lock = getattr(self.plugin, "_command_admin_sync_lock", None)
        if lock is None:
            lock = asyncio.Lock()
            self._command_admin_sync_lock = lock
        async with lock:
            admins = self.plugin._configured_command_admins(additional_admins)
            revision = int(getattr(self.plugin, "_command_admin_revision", 0)) + 1
            self._command_admin_revision = revision
            server_ids: list[str] = []
            if server_id:
                server_ids.append(server_id)
            else:
                manager = getattr(adapter, "connection_manager", None)
                if manager is not None and hasattr(manager, "snapshot"):
                    try:
                        server_ids.extend(
                            str(item.get("server_id") or "")
                            for item in await manager.snapshot()
                            if str(item.get("server_id") or "")
                        )
                    except Exception as exc:
                        return {"ok": False, "error": str(exc)}
            if not server_ids:
                return {"ok": False, "error": "no_connected_server"}
            results: list[dict[str, Any]] = []
            for target_server in dict.fromkeys(server_ids):
                try:
                    result = await adapter.replace_trusted_command_users(
                        target_server, admins, revision=revision
                    )
                    results.append(result)
                    if result.get("ok"):
                        logger.warning(
                            "MineAstr 已向服务器 %s 实时同步 %d 个命令管理员（revision=%d）；服务端静态可信名单保持不变。",
                            target_server,
                            len(admins),
                            revision,
                        )
                    else:
                        logger.warning(
                            "MineAstr 向服务器 %s 同步命令管理员失败：%s",
                            target_server,
                            result.get("error") or "未知错误",
                        )
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    logger.warning(
                        "MineAstr 向服务器 %s 同步命令管理员失败：%s",
                        target_server,
                        exc,
                    )
                    results.append({"ok": False, "error": str(exc)})
            return {
                "ok": bool(results) and all(result.get("ok") for result in results),
                "revision": revision,
                "admins": len(admins),
                "results": results,
            }

    @staticmethod
    def _query_results(payload: dict[str, Any]) -> list[dict[str, Any]]:
        servers = payload.get("servers")
        if isinstance(servers, list):
            return [item for item in servers if isinstance(item, dict)]
        return [payload]

    @staticmethod
    def _query_data(payload: dict[str, Any]) -> dict[str, Any]:
        data = payload.get("data")
        return data if isinstance(data, dict) else payload

    def _remember_command_approval(self, payload: dict[str, Any]) -> None:
        data = self.plugin._query_data(payload)
        if data.get("status") != "approval_required":
            return
        approval_id = str(data.get("approval_id") or "").strip()
        server_id = str(payload.get("server_id") or data.get("server_id") or "").strip()
        if not approval_id:
            return
        pending = getattr(self.plugin, "_pending_command_approvals", None)
        if not isinstance(pending, dict):
            pending = {}
            self._pending_command_approvals = pending
        pending[approval_id] = server_id
        while len(pending) > 512:
            pending.pop(next(iter(pending)))

    @staticmethod
    def _format_command_result(payload: dict[str, Any]) -> str:
        if not payload.get("ok", False):
            server_errors = [
                str(item.get("error") or "未知错误")
                for item in payload.get("servers", [])
                if isinstance(item, dict) and item.get("error")
            ]
            return "命令操作失败：" + str(
                payload.get("error")
                or ("；".join(dict.fromkeys(server_errors)))
                or "未知错误"
            )
        data = CommandService._query_data(payload)
        status = str(data.get("status") or "")
        command = str(data.get("command") or "")
        if status == "approval_required":
            return (
                "该指令不在公开白名单中，尚未执行。\n"
                f"待审批指令：/{command}\n"
                f"审批 ID：{data.get('approval_id')}\n"
                "管理员发送 /mc approve 查看编号列表，再使用 "
                "/mc approve <序号> 批准；"
                f"有效期约 {data.get('expires_in_seconds', 0)} 秒。"
            )
        if status == "rejected":
            return f"已拒绝待审批指令：/{command}"
        if status == "pending_list":
            approvals = data.get("approvals")
            if not isinstance(approvals, list) or not approvals:
                return "当前没有待审批的服务器指令。"
            lines = ["待审批服务器指令："]
            for index, item in enumerate(approvals, start=1):
                if not isinstance(item, dict):
                    continue
                server_name = str(
                    item.get("server_name") or item.get("server_id") or "Minecraft"
                )
                lines.append(
                    f"{index}. [{server_name}] /{item.get('command')} "
                    f"（申请者 {item.get('requester')}，"
                    f"ID {item.get('approval_id')}）"
                )
            lines.append("使用 /mc approve <序号> 批准，或 /mc reject <序号> 拒绝。")
            return "\n".join(lines)
        if status == "executed" or "success" in data:
            output = data.get("output")
            output_text = (
                "\n".join(str(item) for item in output)
                if isinstance(output, list)
                else str(output or "")
            )
            summary = output_text.strip() or f"结果值：{data.get('result', 0)}"
            return f"已执行：/{command}\n{summary}"
        return json.dumps(payload, ensure_ascii=False)

    async def _command_approval_action(
        self, event: AstrMessageEvent, action: str, approval_id: str
    ) -> str:
        if not self.plugin._cfg_bool("remote_command_enabled"):
            return "远程命令功能未启用。"
        if not self.plugin._is_bridge_admin(event):
            return "你没有权限审批服务器命令。"
        action = str(action or "").strip().casefold()
        approval_id = str(approval_id or "").strip()
        pending = getattr(self.plugin, "_pending_command_approvals", None)
        if not isinstance(pending, dict):
            pending = {}
            self._pending_command_approvals = pending
        if action in {"approve", "reject"} and not approval_id:
            action = "list"
        elif action in {"approve", "reject"} and approval_id.casefold() in {
            "latest",
            "最新",
        }:
            if not pending:
                return "本地还没有审批列表，请先发送 /mc approve 获取列表。"
            approval_id = next(reversed(pending))
        elif action in {"approve", "reject"} and approval_id.isdecimal():
            index = int(approval_id)
            approval_ids = list(pending)
            if index < 1 or index > len(approval_ids):
                return "审批序号不存在，请先发送 /mc approve 刷新列表。"
            approval_id = approval_ids[index - 1]
        elif action in {"approve", "reject"} and not re.fullmatch(
            r"[0-9A-Fa-f-]{8,64}", approval_id
        ):
            return f"用法：/mc {action} [序号或审批 ID]"
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "Minecraft 平台适配器未启用。"
        requester = self.plugin._requester_identity(event)
        requester_id = requester["requester_id"]
        requester_platform = requester["requester_platform"]
        current_approver_identities = [requester_id]
        if requester_id and requester_platform not in {"", "unknown"}:
            current_approver_identities.append(f"{requester_platform}:{requester_id}")
        server_id = str(pending.get(approval_id) or "").strip() or None
        await self.plugin._sync_command_admins_now(
            server_id,
            current_approver_identities,
        )
        try:
            payload = await adapter.run_server_command(
                server_id,
                requester_id=requester["requester_id"],
                requester_uuid=requester["requester_uuid"],
                requester_name=requester["requester_name"],
                requester_platform=requester["requester_platform"],
                action=action,
                approval_id=approval_id,
            )
        except Exception as exc:
            return f"命令审批失败：{exc}"
        if payload.get("ok") and action in {"approve", "reject"}:
            pending.pop(approval_id, None)
        if action == "list" and payload.get("ok"):
            data = self.plugin._query_data(payload)
            refreshed_pending: dict[str, str] = {}
            for item in data.get("approvals", []):
                if isinstance(item, dict):
                    item_id = str(item.get("approval_id") or "")
                    if item_id:
                        refreshed_pending[item_id] = str(
                            item.get("server_id") or payload.get("server_id") or ""
                        )
            pending.clear()
            pending.update(refreshed_pending)
        return self.plugin._format_command_result(payload)

    def _format_status(self, payload: dict[str, Any]) -> str:
        results = self.plugin._query_results(payload)
        if not results:
            return "当前没有已连接的 Minecraft 服务器。"
        lines: list[str] = []
        for result in results:
            if not result.get("ok", True):
                lines.append(
                    f"{result.get('server_name') or result.get('server_id') or '服务器'}："
                    f"查询失败（{result.get('error') or '未知错误'}）"
                )
                continue
            data = self.plugin._query_data(result)
            name = str(
                data.get("server_name")
                or result.get("server_name")
                or data.get("server_id")
                or result.get("server_id")
                or "Minecraft"
            )
            version = (
                data.get("minecraft_version") or data.get("mc_version") or "未知版本"
            )
            online = data.get(
                "online_players",
                data.get("online_count", data.get("player_count", "?")),
            )
            maximum = data.get("max_players", data.get("player_limit", "?"))
            extra: list[str] = []
            if data.get("tps") is not None:
                extra.append(f"TPS {data['tps']}")
            if data.get("mspt") is not None:
                extra.append(f"MSPT {data['mspt']}")
            suffix = f"；{'，'.join(extra)}" if extra else ""
            lines.append(f"{name}：MC {version}，在线 {online}/{maximum}{suffix}")
        return "\n".join(lines)

    def _format_players(self, payload: dict[str, Any]) -> str:
        results = self.plugin._query_results(payload)
        if not results:
            return "当前没有已连接的 Minecraft 服务器。"
        lines: list[str] = []
        for result in results:
            if not result.get("ok", True):
                lines.append(
                    f"{result.get('server_name') or result.get('server_id') or '服务器'}："
                    f"查询失败（{result.get('error') or '未知错误'}）"
                )
                continue
            data = self.plugin._query_data(result)
            raw_players = data.get("players") or data.get("online_players") or []
            names: list[str] = []
            if isinstance(raw_players, list):
                for player in raw_players:
                    if isinstance(player, dict):
                        name = player.get("name") or player.get("player_name")
                    else:
                        name = player
                    if name:
                        names.append(str(name))
            name = str(
                data.get("server_name")
                or result.get("server_name")
                or data.get("server_id")
                or result.get("server_id")
                or "Minecraft"
            )
            count = data.get("count", data.get("online_count", len(names)))
            lines.append(
                f"{name} 在线玩家（{count}）：{', '.join(names) if names else '无'}"
            )
        return "\n".join(lines)

    def _format_performance(self, payload: dict[str, Any]) -> str:
        results = self.plugin._query_results(payload)
        if not results:
            return "当前没有已连接的 Minecraft 服务器。"
        lines: list[str] = []
        for result in results:
            if not result.get("ok", True):
                lines.append(
                    f"{result.get('server_name') or result.get('server_id') or '服务器'}："
                    f"查询失败（{result.get('error') or '旧版 Mod 不支持 performance 查询'}）"
                )
                continue
            data = self.plugin._query_data(result)
            name = str(
                data.get("server_name")
                or result.get("server_name")
                or data.get("server_id")
                or result.get("server_id")
                or "Minecraft"
            )
            fields = []
            for key, label in (
                ("tps", "TPS"),
                ("mspt", "MSPT"),
                ("cpu_percent", "CPU"),
                ("memory_used_mb", "内存 MB"),
            ):
                if data.get(key) is not None:
                    fields.append(f"{label} {data[key]}")
            lines.append(
                f"{name}：{', '.join(fields) if fields else json.dumps(data, ensure_ascii=False)}"
            )
        return "\n".join(lines)

    async def mineastr_help(self, event: AstrMessageEvent):
        """显示 MineAstr 指令帮助。"""
        yield event.plain_result(
            "MineAstr 指令：\n"
            "/mc status [server_id] - 服务器状态\n"
            "/mc list [server_id] - 在线玩家\n"
            "/mc performance [server_id] - TPS/MSPT/CPU\n"
            "/mc bind <玩家名或验证码> - 绑定账号\n"
            "/mc unbind [玩家名] - 解绑账号\n"
            "/mc bindings - 查看自己的绑定\n"
            "/mc who <玩家名> - 查询玩家绑定\n"
            "/mc discord_status - Discord 自动化状态（管理员）\n"
            "/mc command <命令> - 执行受控命令（默认关闭）\n"
            "管理员：/mc bridge_add、bridge_remove、bridge_list、admin_bind、admin_unbind、say"
        )

    async def mineastr_status_command(
        self, event: AstrMessageEvent, server_id: str = ""
    ):
        """查询服务器状态。"""
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            yield event.plain_result("Minecraft 平台适配器未启用。")
            return
        try:
            payload = await adapter.query_status(server_id.strip() or None)
            yield event.plain_result(self.plugin._format_status(payload))
        except Exception as exc:
            yield event.plain_result(f"查询失败：{exc}")

    async def mineastr_list_command(self, event: AstrMessageEvent, server_id: str = ""):
        """查询在线玩家。"""
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            yield event.plain_result("Minecraft 平台适配器未启用。")
            return
        try:
            payload = await adapter.query_players(server_id.strip() or None)
            yield event.plain_result(self.plugin._format_players(payload))
        except Exception as exc:
            yield event.plain_result(f"查询失败：{exc}")

    async def mineastr_performance_command(
        self, event: AstrMessageEvent, server_id: str = ""
    ):
        """查询服务器 TPS、MSPT 与 CPU 等性能数据。"""
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "query_performance"):
            yield event.plain_result("当前 Minecraft 适配器不支持性能查询。")
            return
        try:
            payload = await adapter.query_performance(server_id.strip() or None)
            yield event.plain_result(self.plugin._format_performance(payload))
        except Exception as exc:
            yield event.plain_result(f"查询失败：{exc}")

    async def mineastr_command_command(self, event: AstrMessageEvent):
        """提交一条服务器命令；公开白名单立即执行，其他命令等待管理员审批。"""
        if not self.plugin._cfg_bool("remote_command_enabled"):
            yield event.plain_result("远程命令功能未启用。")
            return
        command = self.plugin._command_tail(event).lstrip("/")
        if not command:
            yield event.plain_result("用法：/mc command <服务器命令>")
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            yield event.plain_result("Minecraft 平台适配器未启用。")
            return
        requester = self.plugin._requester_identity(event)
        try:
            payload = await adapter.run_server_command(
                None,
                command,
                requester["requester_id"],
                requester["requester_uuid"],
                requester["requester_name"],
                requester["requester_platform"],
            )
            self.plugin._remember_command_approval(payload)
            yield event.plain_result(self.plugin._format_command_result(payload))
        except Exception as exc:
            yield event.plain_result(f"命令执行失败：{exc}")

    async def mineastr_approve_command(
        self, event: AstrMessageEvent, approval_id: str = ""
    ):
        """列出待审批命令，或按序号/ID 批准并执行。"""
        yield event.plain_result(
            await self.plugin._command_approval_action(event, "approve", approval_id)
        )

    async def mineastr_reject_command(
        self, event: AstrMessageEvent, approval_id: str = ""
    ):
        """列出待审批命令，或按序号/ID 拒绝。"""
        yield event.plain_result(
            await self.plugin._command_approval_action(event, "reject", approval_id)
        )

    async def mineastr_approvals_command(self, event: AstrMessageEvent):
        """列出待管理员审批的服务器命令。"""
        yield event.plain_result(
            await self.plugin._command_approval_action(event, "list", "")
        )

    async def mineastr_say_command(self, event: AstrMessageEvent):
        """以聊天平台身份向 Minecraft 广播消息。"""
        if not self.plugin._is_bridge_admin(event):
            yield event.plain_result("你没有权限使用广播命令。")
            return
        content = self.plugin._command_tail(event)
        if not content:
            yield event.plain_result("用法：/mc say <消息>")
            return
        adapter = self.plugin._minecraft_adapter()
        if adapter is None or not hasattr(adapter, "relay_chat"):
            yield event.plain_result("Minecraft 平台适配器未启用。")
            return
        identity = self.plugin._identity(event)
        await adapter.relay_chat(
            content,
            await self.plugin._game_sender_name(
                identity["owner_key"], identity["owner_display"]
            ),
            origin=event.unified_msg_origin,
        )
        yield event.plain_result("已发送到 Minecraft。")
