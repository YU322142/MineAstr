"""LLM 工具参数和实时查询结果 / LLM tool parameters and live query results."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    CallToolResult,
    ImageContent,
    TextContent,
    json,
    logger,
)

from .mineastr_defaults import (
    MINEASTR_EXTERNAL_HINT_KEYWORDS,
    MINEASTR_TOOL_HINT,
)


class MinecraftTools:
    """LLM 工具参数和实时查询结果 / LLM tool parameters and live query results.

    工具参数到适配器请求的边界；权限在请求构造前检查，传输由适配器管理。
    Map tool inputs to adapter requests, check permissions first and leave transport to the adapter.
    """

    def __init__(self, plugin):
        self.plugin = plugin

    async def mineastr_on_llm_request(
        self, event: AstrMessageEvent, request: Any
    ) -> None:
        text = (getattr(event, "message_str", "") or "").lower()
        platform_id = ""
        get_platform_id = getattr(event, "get_platform_id", None)
        if callable(get_platform_id):
            platform_id = str(get_platform_id() or "")
        raw_message = self.plugin._event_raw_message(event)
        if platform_id != "minecraft" and not any(
            keyword in text for keyword in MINEASTR_EXTERNAL_HINT_KEYWORDS
        ):
            return

        current_prompt = getattr(request, "system_prompt", "") or ""
        prompt_parts = [current_prompt] if current_prompt else []
        if raw_message.get("minecraft_mentioned_bot"):
            prompt_parts.append(
                "这是 Minecraft 群聊里用户通过 @ 方式直接唤醒你的消息，请优先按“被点名回复”的方式直接接话，不要把它当成普通闲聊。"
            )
        if MINEASTR_TOOL_HINT not in current_prompt:
            prompt_parts.append(MINEASTR_TOOL_HINT)
        request.system_prompt = "\n\n".join(
            part for part in prompt_parts if part
        ).strip()

    @staticmethod
    def _tool_json(title: str, payload: dict[str, Any]) -> str:
        return f"{title}：\n{json.dumps(payload, ensure_ascii=False, indent=2)}"

    def _tool_image_result(
        self,
        title: str,
        payload: dict[str, Any],
        image_base64: str | None,
        mime_type: str,
    ) -> Any:
        text = self.plugin._tool_json(title, payload)
        if (
            not image_base64
            or CallToolResult is None
            or ImageContent is None
            or TextContent is None
        ):
            return text
        try:
            return CallToolResult(
                content=[
                    TextContent(type="text", text=text),
                    ImageContent(type="image", data=image_base64, mimeType=mime_type),
                ]
            )
        except Exception as exc:
            logger.warning("MineAstr 构造截图工具图片结果失败，已退回文本结果：%s", exc)
            return text

    @staticmethod
    def _event_raw_message(event: AstrMessageEvent) -> dict[str, Any]:
        message_obj = getattr(event, "message_obj", None)
        raw = getattr(message_obj, "raw_message", None)
        return raw if isinstance(raw, dict) else {}

    @staticmethod
    def _event_value(event: AstrMessageEvent, method_name: str) -> str:
        method = getattr(event, method_name, None)
        if not callable(method):
            return ""
        try:
            return str(method() or "").strip()
        except Exception:
            return ""

    def _event_target(
        self,
        event: AstrMessageEvent,
        server_id: str,
        player_uuid: str,
        player_name: str,
    ) -> tuple[str | None, str, str]:
        raw = self.plugin._event_raw_message(event)
        target_server = (
            server_id.strip() or str(raw.get("server_id") or "").strip() or None
        )
        target_uuid = player_uuid.strip() or str(raw.get("player_uuid") or "").strip()
        target_name = player_name.strip() or str(raw.get("player_name") or "").strip()
        return target_server, target_uuid, target_name

    def _requester_identity(self, event: AstrMessageEvent) -> dict[str, str]:
        raw = self.plugin._event_raw_message(event)
        return {
            "requester_id": str(
                raw.get("player_uuid")
                or self.plugin._event_value(event, "get_sender_id")
                or ""
            ).strip(),
            "requester_uuid": str(raw.get("player_uuid") or "").strip(),
            "requester_name": str(
                raw.get("player_name")
                or self.plugin._event_value(event, "get_sender_name")
                or ""
            ).strip(),
            "requester_platform": self.plugin._event_value(event, "get_platform_id")
            or "unknown",
        }

    async def mineastr_get_server_status(
        self, event: AstrMessageEvent, server_id: str = ""
    ) -> str:
        """查询 Minecraft 服务器状态，包括连接状态、服务器名称、版本和在线人数。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用，暂时无法查询 Minecraft 服务器。"
        target = server_id.strip() or None
        try:
            payload = await adapter.query_status(target)
        except Exception as exc:
            logger.warning("MineAstr 查询 Minecraft 状态失败：%s", exc)
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        return self.plugin._tool_json("Minecraft 服务器状态查询结果", payload)

    async def mineastr_get_online_players(
        self, event: AstrMessageEvent, server_id: str = ""
    ) -> str:
        """查询 Minecraft 当前在线玩家列表和玩家数量。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用，暂时无法查询 Minecraft 在线玩家。"
        target = server_id.strip() or None
        try:
            payload = await adapter.query_players(target)
        except Exception as exc:
            logger.warning("MineAstr 查询 Minecraft 在线玩家失败：%s", exc)
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        return self.plugin._tool_json("Minecraft 在线玩家查询结果", payload)

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
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用或版本过旧。"
        target_server, target_uuid, target_name = self.plugin._event_target(
            event, server_id, player_uuid, player_name
        )
        try:
            payload = await adapter.query_player_state(
                target_server, target_uuid, target_name
            )
        except Exception as exc:
            logger.warning("MineAstr 查询玩家状态失败：%s", exc)
            payload = {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return self.plugin._tool_json("Minecraft 玩家实时状态", payload)

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
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用或版本过旧。"
        target_server, target_uuid, target_name = self.plugin._event_target(
            event, server_id, player_uuid, player_name
        )
        try:
            payload = await adapter.query_inventory(
                target_server, target_uuid, target_name, include_ender_chest
            )
        except Exception as exc:
            logger.warning("MineAstr 查询玩家背包失败：%s", exc)
            payload = {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return self.plugin._tool_json("Minecraft 玩家背包查询结果", payload)

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
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用或版本过旧。"
        target_server, target_uuid, target_name = self.plugin._event_target(
            event, server_id, player_uuid, player_name
        )
        try:
            payload = await adapter.query_nearby_entities(
                target_server, target_uuid, target_name, radius
            )
        except Exception as exc:
            logger.warning("MineAstr 查询附近实体失败：%s", exc)
            payload = {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return self.plugin._tool_json("Minecraft 附近实体查询结果", payload)

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
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用或版本过旧。"
        target_server, target_uuid, target_name = self.plugin._event_target(
            event, server_id, player_uuid, player_name
        )
        try:
            payload = await adapter.analyze_region(
                target_server,
                target_uuid,
                target_name,
                horizontal_radius,
                vertical_radius,
                dimension,
                x if use_coordinates else None,
                y if use_coordinates else None,
                z if use_coordinates else None,
            )
        except Exception as exc:
            logger.warning("MineAstr 分析区域特征失败：%s", exc)
            payload = {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return self.plugin._tool_json("Minecraft 区域建筑特征分析", payload)

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
        if not self.plugin._cfg_bool("remote_command_enabled"):
            return "MineAstr 远程命令入口未启用。"
        adapter = self.plugin._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用或版本过旧。"
        raw = self.plugin._event_raw_message(event)
        target_server = (
            server_id.strip() or str(raw.get("server_id") or "").strip() or None
        )
        requester = self.plugin._requester_identity(event)
        try:
            payload = await adapter.run_server_command(
                target_server,
                command,
                requester["requester_id"],
                requester["requester_uuid"],
                requester["requester_name"],
                requester["requester_platform"],
            )
            self.plugin._remember_command_approval(payload)
        except Exception as exc:
            logger.warning("MineAstr 执行受控服务器命令失败：%s", exc)
            payload = {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return self.plugin._tool_json("Minecraft 受控服务器命令结果", payload)

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

        normalized_action = str(action or "list").strip().casefold()
        if normalized_action not in {"list", "approve", "reject"}:
            return "审批 action 只支持 list、approve 或 reject。"
        return await self.plugin._command_approval_action(
            event,
            normalized_action,
            str(selection or "").strip(),
        )
