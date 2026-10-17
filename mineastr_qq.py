"""QQ 监听、退群解绑和群名片同步 / QQ listeners, leave-group unbinding and group cards."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    asyncio,
    logger,
    parse_items,
)


class QQAutomation:
    """QQ 监听、退群解绑和群名片同步 / QQ listeners, leave-group unbinding and group cards.

    仅管理 QQ 事件和名片同步；数据库变更复用绑定服务，移除监听器复用原回调实例。
    Own QQ events and cards; delegate database changes and detach the original callback instances.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._qq_listener_bindings = {}

    @staticmethod
    def _is_qq_adapter(adapter: Any) -> bool:
        try:
            return str(adapter.meta().name).casefold() == "aiocqhttp"
        except Exception:
            return False

    def _qq_adapters(self) -> list[tuple[str, Any]]:
        adapters: list[tuple[str, Any]] = []
        for adapter in self.plugin._platform_instances():
            if not self.plugin._is_qq_adapter(adapter):
                continue
            try:
                platform_id = str(adapter.meta().id or "").strip()
            except Exception:
                continue
            if platform_id:
                adapters.append((platform_id, adapter))
        return adapters

    def _attach_qq_listeners(self) -> None:
        if not self.plugin._cfg_bool("qq_auto_unbind_on_leave"):
            return
        for platform_id, adapter in self.plugin._qq_adapters():
            bot = getattr(adapter, "bot", None)
            if bot is None and hasattr(adapter, "get_client"):
                bot = adapter.get_client()
            if bot is None or not hasattr(bot, "subscribe"):
                continue
            current = self._qq_listener_bindings.get(platform_id)
            if current is not None and current[0] is bot:
                continue
            if current is not None:
                old_bot = current[0]
                for event_name, callback in zip(
                    (
                        "notice.group_decrease",
                        "notice.group_recall",
                        "notice.friend_recall",
                    ),
                    current[1:],
                ):
                    if callback is None:
                        continue
                    try:
                        old_bot.unsubscribe(event_name, callback)
                    except Exception:
                        pass

            async def on_group_decrease(
                event: Any, *, _platform_id: str = platform_id
            ) -> None:
                try:
                    await self.plugin._on_qq_member_decrease(_platform_id, event)
                except Exception as exc:
                    logger.warning("MineAstr 处理 QQ 退群事件失败：%s", exc)

            bot.subscribe("notice.group_decrease", on_group_decrease)
            group_recall_callback: Any | None = None
            friend_recall_callback: Any | None = None
            if self.plugin._cfg_bool("bridge_enabled"):

                async def on_group_recall(
                    event: Any, *, _platform_id: str = platform_id
                ) -> None:
                    await self.plugin._on_qq_message_recall(_platform_id, event)

                async def on_friend_recall(
                    event: Any, *, _platform_id: str = platform_id
                ) -> None:
                    await self.plugin._on_qq_message_recall(_platform_id, event)

                bot.subscribe("notice.group_recall", on_group_recall)
                bot.subscribe("notice.friend_recall", on_friend_recall)
                group_recall_callback = on_group_recall
                friend_recall_callback = on_friend_recall
            self._qq_listener_bindings[platform_id] = (
                bot,
                on_group_decrease,
                group_recall_callback,
                friend_recall_callback,
            )
            logger.info(
                "MineAstr 已为 QQ/OneBot 平台 %s 注册退群自动解绑监听。",
                platform_id,
            )

    def _detach_qq_listeners(self) -> None:
        for binding in self._qq_listener_bindings.values():
            if not binding:
                continue
            bot = binding[0]
            for event_name, callback in zip(
                (
                    "notice.group_decrease",
                    "notice.group_recall",
                    "notice.friend_recall",
                ),
                binding[1:],
            ):
                if callback is None:
                    continue
                try:
                    bot.unsubscribe(event_name, callback)
                except Exception:
                    pass
        self._qq_listener_bindings.clear()

    def _qq_group_allowed(self, group_id: str) -> bool:
        configured = set(parse_items(self.plugin._cfg("qq_group_ids")))
        return not configured or group_id in configured

    async def _on_qq_message_recall(self, platform_id: str, event: Any) -> None:
        get_value = getattr(event, "get", None)
        if not callable(get_value):
            return
        message_id = str(get_value("message_id") or "").strip()
        if not message_id:
            return
        group_id = str(get_value("group_id") or "").strip()
        user_id = str(get_value("user_id") or "").strip()
        if group_id and not self.plugin._qq_group_allowed(group_id):
            return
        origin = (
            f"{platform_id}:GroupMessage:{group_id}"
            if group_id
            else f"{platform_id}:FriendMessage:{user_id}"
            if user_id
            else ""
        )
        await self.plugin._relay_recalled_message(
            platform_id,
            message_id,
            origin=origin,
        )

    async def _on_qq_member_decrease(self, platform_id: str, event: Any) -> None:
        if not self.plugin._cfg_bool("qq_auto_unbind_on_leave"):
            return
        get_value = getattr(event, "get", None)
        if not callable(get_value):
            return
        group_id = str(get_value("group_id") or "").strip()
        user_id = str(get_value("user_id") or "").strip()
        self_id = str(get_value("self_id") or "").strip()
        if (
            not group_id
            or not user_id
            or user_id == self_id
            or not self.plugin._qq_group_allowed(group_id)
        ):
            return

        owner_key = f"{platform_id}:{user_id}"
        removed = await self.plugin._binding_store.unbind(owner_key)
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
        logger.info(
            "MineAstr：QQ 用户 %s 离开群 %s，已自动解绑 %s。",
            owner_key,
            group_id,
            ", ".join(record.player_name for record in removed),
        )
        if failures:
            logger.warning(
                "MineAstr：QQ 退群解绑已写入本地，但有 %d 条 Minecraft 同步失败。",
                len(failures),
            )

    async def _update_qq_group_card_after_bind(
        self, event: AstrMessageEvent, record: Any
    ) -> dict[str, Any]:
        if not self.plugin._cfg_bool("qq_auto_group_card"):
            return {"ok": True, "skipped": True}
        try:
            if str(event.get_platform_name()).casefold() != "aiocqhttp":
                return {"ok": True, "skipped": True}
            group_id = str(event.get_group_id() or "").strip()
            platform_id = str(event.get_platform_id() or "").strip()
        except Exception:
            return {"ok": True, "skipped": True}
        if (
            not group_id
            or record.platform_id != platform_id
            or not self.plugin._qq_group_allowed(group_id)
        ):
            return {"ok": True, "skipped": True}
        adapter = None
        try:
            adapter = self.plugin.context.get_platform_inst(platform_id)
        except Exception:
            pass
        bot = getattr(adapter, "bot", None) if adapter is not None else None
        if bot is None or not hasattr(bot, "call_action"):
            return {"ok": False, "error": "对应的 QQ/OneBot 平台实例未运行"}
        numeric_group_id = int(group_id) if group_id.isdigit() else group_id
        numeric_user_id = (
            int(record.user_id)
            if str(record.user_id).isdigit()
            else str(record.user_id)
        )
        try:
            member = await bot.call_action(
                "get_group_member_info",
                group_id=numeric_group_id,
                user_id=numeric_user_id,
                no_cache=True,
            )
        except Exception:
            member = {}
        if not isinstance(member, dict):
            member = {}
        records = await self.plugin._binding_store.get_by_owner(record.owner_key)
        card = self.plugin._bounded_nickname(
            records,
            str(self.plugin._cfg("qq_group_card_template")),
            60,
            {
                "owner": str(record.owner_display or record.user_id),
                "user_id": str(record.user_id),
                "qq": str(record.user_id),
                "nickname": str(member.get("nickname") or record.owner_display),
                "card": str(member.get("card") or ""),
            },
        )
        try:
            await bot.call_action(
                "set_group_card",
                group_id=numeric_group_id,
                user_id=numeric_user_id,
                card=card,
            )
        except Exception as exc:
            return {"ok": False, "error": str(exc) or exc.__class__.__name__}
        return {"ok": True, "card": card}
