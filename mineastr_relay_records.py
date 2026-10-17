"""已转发消息索引、引用识别与容量约束 / Relayed-message indexing, reply recognition and capacity limits."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    AstrMessageEvent,
    hashlib,
    re,
    time,
)

from .mineastr_defaults import (
    RELAY_MESSAGE_RECORD_LIMIT,
)


class RelayRecords:
    """已转发消息索引、引用识别与容量约束 / Relayed-message indexing, reply recognition and capacity limits.

    只保留撤回及引用识别所需元数据；每次写入立即检查过期和容量。
    Retain only recall/reply metadata and enforce retention and capacity on every write.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._relay_message_records = {}
        self._relay_outbound_message_records = {}

    @staticmethod
    def _relay_source_message_id(event: AstrMessageEvent) -> str:
        message_obj = getattr(event, "message_obj", None)
        return str(
            getattr(message_obj, "message_id", None)
            or getattr(message_obj, "id", None)
            or ""
        ).strip()

    def _remember_relay_message(
        self,
        event: AstrMessageEvent,
        *,
        sender_name: str,
        origin: str,
    ) -> str:
        message_id = self.plugin._relay_source_message_id(event)
        if not message_id:
            return ""
        key = f"{event.get_platform_id()}:{message_id}"
        self.plugin._store_relay_message_record(
            key,
            platform_id=str(event.get_platform_id() or ""),
            message_id=message_id,
            origin=origin,
            sender_name=sender_name,
            owner_key=self.plugin._identity(event)["owner_key"],
        )
        return key

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
        records = getattr(self.plugin, "_relay_message_records", None)
        if not isinstance(records, dict):
            records = {}
            self._relay_message_records = records
        now = time.time()
        records[key] = {
            "platform_id": platform_id,
            "message_id": message_id,
            "origin": origin,
            "sender_name": sender_name,
            "created_at": now,
        }
        if owner_key:
            records[key]["owner_key"] = owner_key
        cutoff = now - self.plugin._bounded_cfg_int(
            "relay_record_retention_seconds", 300, 30 * 86400
        )
        for record_key, record in list(records.items()):
            created_at = self.plugin._relay_record_created_at(record, 0.0)
            if created_at < cutoff:
                records.pop(record_key, None)
        while len(records) > RELAY_MESSAGE_RECORD_LIMIT:
            oldest = min(
                records.items(),
                key=lambda item: self.plugin._relay_record_created_at(item[1], 0.0),
            )[0]
            records.pop(oldest, None)

    @staticmethod
    def _relay_reply_fingerprint(text: Any) -> str:
        normalized = re.sub(r"\s+", " ", str(text or "")).strip()
        if not normalized:
            return ""
        return hashlib.sha256(normalized.encode("utf-8")).hexdigest()

    def _store_outbound_relay_message(self, session: str, text: Any) -> None:
        origin = str(session or "").strip()
        fingerprint = self.plugin._relay_reply_fingerprint(text)
        if not origin or not fingerprint:
            return
        records = getattr(self.plugin, "_relay_outbound_message_records", None)
        if not isinstance(records, dict):
            records = {}
            self._relay_outbound_message_records = records
        now = time.time()
        records[f"{origin}:{time.monotonic_ns()}"] = {
            "origin": origin,
            "fingerprint": fingerprint,
            "created_at": now,
        }
        cutoff = now - self.plugin._bounded_cfg_int(
            "relay_record_retention_seconds", 300, 30 * 86400
        )
        for record_key, record in list(records.items()):
            if self.plugin._relay_record_created_at(record, 0.0) < cutoff:
                records.pop(record_key, None)
        while len(records) > RELAY_MESSAGE_RECORD_LIMIT:
            oldest = min(
                records.items(),
                key=lambda item: self.plugin._relay_record_created_at(item[1], 0.0),
            )[0]
            records.pop(oldest, None)

    def _is_reply_to_synced_message(
        self,
        event: AstrMessageEvent,
        reply_context: dict[str, str] | None,
    ) -> bool:
        quoted_text = str((reply_context or {}).get("text") or "").strip()
        fingerprint = self.plugin._relay_reply_fingerprint(quoted_text)
        origin = str(getattr(event, "unified_msg_origin", "") or "").strip()
        if not fingerprint or not origin:
            return False
        records = getattr(self.plugin, "_relay_outbound_message_records", {})
        if not isinstance(records, dict):
            return False
        cutoff = time.time() - self.plugin._bounded_cfg_int(
            "relay_record_retention_seconds", 300, 30 * 86400
        )
        for record in records.values():
            if not isinstance(record, dict):
                continue
            if self.plugin._relay_record_created_at(record, 0.0) < cutoff:
                continue
            if (
                str(record.get("origin") or "").strip() == origin
                and str(record.get("fingerprint") or "") == fingerprint
            ):
                return True
        return False
