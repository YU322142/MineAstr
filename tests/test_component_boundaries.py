"""组件边界回归：跨实例隔离、共享维护和卸载 / Isolation, maintenance and unload."""

import asyncio
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from test_z_discord_automation import MAIN


def make_plugin():
    plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
    plugin.config = {}
    plugin._listener_adapter = None
    return plugin


class ComponentBoundaryTests(unittest.IsolatedAsyncioTestCase):
    async def test_full_lifecycle_initializes_owned_state_and_reclaims_tasks(self):
        # 只替换框架注册副作用，配置、SQLite 和组件生命周期使用真实实现。
        # Stub registration only; exercise real settings, SQLite and component lifecycle.
        adapter_module = types.ModuleType(f"{MAIN.__package__}.minecraft_adapter")
        adapter_module.MinecraftPlatformAdapter = object
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            sys.modules, {adapter_module.__name__: adapter_module}
        ):
            database = Path(directory) / "bindings.sqlite3"
            plugin = MAIN.MineAstrPlugin(
                types.SimpleNamespace(),
                {"binding_database": str(database), "cache_cleanup_enabled": False},
            )
            tasks = []
            try:
                self.assertFalse(database.exists())
                await plugin.initialize()
                self.assertTrue(database.is_file())
                self.assertTrue(plugin._binding_reconcile_tasks)
                tasks = list(plugin._binding_reconcile_tasks)
                if plugin._discord_attach_task is not None:
                    tasks.append(plugin._discord_attach_task)
                self.assertIs(
                    plugin._binding_store,
                    plugin._component(MAIN.BindingService)._binding_store,
                )
            finally:
                await plugin.terminate()
            self.assertTrue(all(task.done() for task in tasks))

    def test_framework_entries_keep_runtime_parameter_types(self):
        # 框架可能直接读取注解；入口不能把原有类型改成延迟字符串。
        # Frameworks may inspect annotations directly; preserve runtime types.
        annotations = MAIN.MineAstrPlugin.mineastr_get_server_status.__annotations__
        self.assertIs(annotations["event"], MAIN.AstrMessageEvent)
        self.assertIs(annotations["server_id"], str)
        self.assertIs(annotations["return"], str)

    def test_relay_and_translation_state_is_isolated_between_plugin_instances(self):
        first, second = make_plugin(), make_plugin()
        first._store_relay_message_record(
            "qq:1",
            platform_id="qq",
            message_id="1",
            origin="qq:GroupMessage:1",
            sender_name="Alice",
        )
        first._game_translation_cache["text"] = {"translations": {"en_us": "Hi"}}
        first._image_translation_cache["image"] = {"translations": {"en_us": "Hello"}}
        self.assertEqual(second._relay_message_records, {})
        self.assertEqual(second._game_translation_cache, {})
        self.assertEqual(second._image_translation_cache, {})

    def test_maintenance_clears_existing_component_maps_in_place(self):
        plugin = make_plugin()
        maps = [
            plugin._game_translation_cache,
            plugin._image_translation_cache,
            plugin._relay_message_records,
            plugin._screenshot_last_request_at,
        ]
        for cache in maps:
            cache["entry"] = {"created_at": 1}
        plugin._clear_memory_caches()
        self.assertTrue(all(not cache for cache in maps))
        self.assertIs(maps[0], plugin._game_translation_cache)
        self.assertIs(maps[1], plugin._image_translation_cache)

    async def test_registered_command_preserves_async_generator_results(self):
        plugin = make_plugin()
        event = types.SimpleNamespace(plain_result=lambda text: ("text", text))
        results = [value async for value in plugin.mineastr_help(event)]
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0][0], "text")
        self.assertIn("/mc bind", results[0][1])

    async def test_unload_awaits_tasks_from_all_service_owners(self):
        plugin = make_plugin()
        stopped = []

        async def background(name):
            try:
                await asyncio.Event().wait()
            finally:
                stopped.append(name)

        binding = asyncio.create_task(background("binding"))
        plugin._binding_reconcile_tasks.add(binding)
        plugin._cache_cleanup_task = asyncio.create_task(background("cache"))
        plugin._discord_attach_task = asyncio.create_task(background("discord"))
        await asyncio.sleep(0)
        await plugin.terminate()
        self.assertEqual(set(stopped), {"binding", "cache", "discord"})
        self.assertEqual(plugin._binding_reconcile_tasks, set())
        self.assertIsNone(plugin._cache_cleanup_task)
        self.assertIsNone(plugin._discord_attach_task)
