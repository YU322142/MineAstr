"""有界内存回收和截图文件清理 / Bounded cache maintenance and screenshot-file cleanup."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    Path,
    asyncio,
    logger,
    time,
)

from .mineastr_defaults import (
    RELAY_MESSAGE_RECORD_LIMIT,
    SCREENSHOT_DIR,
)


class CacheMaintenance:
    """有界内存回收和截图文件清理 / Bounded cache maintenance and screenshot-file cleanup.

    只安排清理任务，通过显式插件字段清理各所有者的容器，不创建第二份缓存。
    Schedule maintenance and clear owners' containers through explicit fields without copying caches.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._cache_cleanup_task = None

    def _start_cache_cleanup_task(self) -> None:
        if not self.plugin._cfg_bool("cache_cleanup_enabled"):
            return
        task = getattr(self.plugin, "_cache_cleanup_task", None)
        if task is not None and not task.done():
            return
        self._cache_cleanup_task = asyncio.create_task(
            self.plugin._cache_cleanup_loop(),
            name="mineastr-cache-cleanup",
        )

    async def _cache_cleanup_loop(self) -> None:
        while True:
            interval = self.plugin._bounded_cfg_int(
                "cache_cleanup_interval_seconds", 30, 86400
            )
            await asyncio.sleep(interval)
            if not self.plugin._cfg_bool("cache_cleanup_enabled"):
                continue
            try:
                await self.plugin._run_cache_cleanup()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - maintenance is best effort
                logger.warning("MineAstr 定时清理缓存失败：%s", exc)

    def _clear_memory_caches(self) -> None:
        for attribute in (
            "_game_translation_cache",
            "_game_translation_cache_accessed_at",
            "_image_translation_cache",
            "_image_translation_cache_accessed_at",
            "_translation_contexts",
            "_translation_context_accessed_at",
            "_relay_message_records",
            "_relay_outbound_message_records",
            "_screenshot_last_request_at",
        ):
            value = getattr(self.plugin, attribute, None)
            if hasattr(value, "clear"):
                value.clear()

    @staticmethod
    def _prune_timed_mapping(
        cache: dict[Any, Any],
        accessed_at: dict[Any, float],
        *,
        now: float,
        retention_seconds: int,
    ) -> int:
        removed = 0
        cutoff = now - retention_seconds
        for key in list(cache):
            last_access = accessed_at.get(key)
            if last_access is None:
                accessed_at[key] = now
                continue
            if last_access < cutoff:
                cache.pop(key, None)
                accessed_at.pop(key, None)
                removed += 1
        for key in list(accessed_at):
            if key not in cache:
                accessed_at.pop(key, None)
        return removed

    @staticmethod
    def _relay_record_created_at(record: Any, fallback: float) -> float:
        try:
            return float(record.get("created_at", fallback))
        except (AttributeError, TypeError, ValueError):
            return fallback

    def _prune_memory_caches(
        self,
        *,
        wall_now: float | None = None,
        monotonic_now: float | None = None,
    ) -> dict[str, int]:
        wall_now = time.time() if wall_now is None else wall_now
        monotonic_now = time.monotonic() if monotonic_now is None else monotonic_now
        memory_retention = self.plugin._bounded_cfg_int(
            "memory_cache_retention_seconds", 60, 30 * 86400
        )
        relay_retention = self.plugin._bounded_cfg_int(
            "relay_record_retention_seconds", 300, 30 * 86400
        )

        game_cache = getattr(self.plugin, "_game_translation_cache", {})
        game_accessed = getattr(
            self.plugin, "_game_translation_cache_accessed_at", None
        )
        if not isinstance(game_accessed, dict):
            game_accessed = {}
            self.plugin._game_translation_cache_accessed_at = game_accessed
        image_cache = getattr(self.plugin, "_image_translation_cache", {})
        image_accessed = getattr(
            self.plugin, "_image_translation_cache_accessed_at", None
        )
        if not isinstance(image_accessed, dict):
            image_accessed = {}
            self.plugin._image_translation_cache_accessed_at = image_accessed
        contexts = getattr(self.plugin, "_translation_contexts", {})
        context_accessed = getattr(
            self.plugin, "_translation_context_accessed_at", None
        )
        if not isinstance(context_accessed, dict):
            context_accessed = {}
            self.plugin._translation_context_accessed_at = context_accessed

        stats = {
            "game_translations": self.plugin._prune_timed_mapping(
                game_cache,
                game_accessed,
                now=monotonic_now,
                retention_seconds=memory_retention,
            ),
            "image_translations": self.plugin._prune_timed_mapping(
                image_cache,
                image_accessed,
                now=monotonic_now,
                retention_seconds=memory_retention,
            ),
            "translation_contexts": self.plugin._prune_timed_mapping(
                contexts,
                context_accessed,
                now=monotonic_now,
                retention_seconds=memory_retention,
            ),
            "relay_records": 0,
        }

        records = getattr(self.plugin, "_relay_message_records", {})
        cutoff = wall_now - relay_retention
        for key, record in list(records.items()):
            created_at = self.plugin._relay_record_created_at(record, 0.0)
            if created_at < cutoff:
                records.pop(key, None)
                stats["relay_records"] += 1
        while len(records) > RELAY_MESSAGE_RECORD_LIMIT:
            oldest = min(
                records,
                key=lambda key: self.plugin._relay_record_created_at(records[key], 0.0),
            )
            records.pop(oldest, None)
            stats["relay_records"] += 1

        outbound_records = getattr(self.plugin, "_relay_outbound_message_records", {})
        for record_key, record in list(outbound_records.items()):
            if self.plugin._relay_record_created_at(record, 0.0) < cutoff:
                outbound_records.pop(record_key, None)
        while len(outbound_records) > RELAY_MESSAGE_RECORD_LIMIT:
            oldest = min(
                outbound_records,
                key=lambda key: self.plugin._relay_record_created_at(
                    outbound_records[key], 0.0
                ),
            )
            outbound_records.pop(oldest, None)
        return stats

    @staticmethod
    def _cleanup_screenshot_files_sync(
        directory: Path,
        *,
        wall_now: float,
        retention_seconds: int,
        max_total_bytes: int,
    ) -> tuple[int, int]:
        if not directory.exists():
            return 0, 0
        files: list[tuple[Path, float, int]] = []
        for path in directory.iterdir():
            try:
                if not path.is_file():
                    continue
                stat = path.stat()
            except OSError:
                continue
            files.append((path, stat.st_mtime, stat.st_size))

        deleted_count = 0
        deleted_bytes = 0
        retained: list[tuple[Path, float, int]] = []
        cutoff = wall_now - retention_seconds
        for path, modified_at, size in files:
            if modified_at >= cutoff:
                retained.append((path, modified_at, size))
                continue
            try:
                path.unlink()
            except OSError:
                retained.append((path, modified_at, size))
            else:
                deleted_count += 1
                deleted_bytes += size

        total_bytes = sum(size for _, _, size in retained)
        for path, _, size in sorted(retained, key=lambda item: (item[1], item[0].name)):
            if total_bytes <= max_total_bytes:
                break
            try:
                path.unlink()
            except OSError:
                continue
            deleted_count += 1
            deleted_bytes += size
            total_bytes -= size
        return deleted_count, deleted_bytes

    async def _run_cache_cleanup(self) -> dict[str, int]:
        if not self.plugin._cfg_bool("cache_cleanup_enabled"):
            return {}
        try:
            stats = self.plugin._prune_memory_caches()
            retention_hours = self.plugin._bounded_cfg_int(
                "screenshot_retention_hours", 1, 24 * 365
            )
            max_total_mib = self.plugin._bounded_cfg_int(
                "screenshot_cache_max_mib", 16, 10240
            )
            deleted_files, deleted_bytes = await asyncio.to_thread(
                self.plugin._cleanup_screenshot_files_sync,
                SCREENSHOT_DIR,
                wall_now=time.time(),
                retention_seconds=retention_hours * 3600,
                max_total_bytes=max_total_mib * 1024 * 1024,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - cleanup must not block startup
            logger.warning("MineAstr 清理缓存失败：%s", exc)
            return {}
        stats["screenshot_files"] = deleted_files
        stats["screenshot_bytes"] = deleted_bytes
        if any(stats.values()):
            logger.info(
                "MineAstr 缓存清理完成：翻译=%d，图片翻译=%d，上下文=%d，"
                "转发记录=%d，截图=%d（%d 字节）。",
                stats["game_translations"],
                stats["image_translations"],
                stats["translation_contexts"],
                stats["relay_records"],
                stats["screenshot_files"],
                stats["screenshot_bytes"],
            )
        return stats
