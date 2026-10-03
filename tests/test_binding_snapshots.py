import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from test_z_discord_automation import MAIN


class BindingSnapshotsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        self.plugin.config = {"sync_binding_to_server": True}
        self.plugin._binding_store = MAIN.BindingStore(str(Path(self.temporary.name) / "bindings.sqlite3"))
        await self.plugin._binding_store.initialize()
        self.record = await self.plugin._binding_store.bind(owner_key="qq:42", platform_id="qq", user_id="42", owner_display="Owner", player_name="Steve", max_bind_count=1)
        self.adapter = SimpleNamespace(
            connection_manager=SimpleNamespace(snapshot=AsyncMock(return_value=[{"server_id": "mc"}])),
            replace_bindings=AsyncMock(return_value={"ok": True}),
        )
        self.plugin._minecraft_adapter = lambda: self.adapter

    async def asyncTearDown(self):
        self.temporary.cleanup()

    async def test_mutation_sync_reads_current_database_instead_of_stale_record(self):
        await self.plugin._binding_store.unbind("qq:42")
        result = await self.plugin._sync_binding_to_server("bind", self.record)
        self.assertTrue(result["ok"])
        self.adapter.replace_bindings.assert_awaited_once_with("mc", [])

    async def test_concurrent_snapshots_are_serialized_and_query_errors_are_returned(self):
        gate = asyncio.Event()
        active = 0
        peak = 0

        async def replace(server_id, records):
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            await gate.wait()
            active -= 1
            return {"ok": False, "error": "connection_closed"}

        self.adapter.replace_bindings.side_effect = replace
        first = asyncio.create_task(self.plugin._reconcile_bindings_to_server("mc"))
        second = asyncio.create_task(self.plugin._reconcile_bindings_to_server("mc"))
        await asyncio.sleep(0)
        gate.set()
        results = await asyncio.gather(first, second)
        self.assertEqual(peak, 1)
        self.assertTrue(all(result["error"] == "connection_closed" for result in results))

    async def test_periodic_sync_uses_interval_and_cancels_on_unload(self):
        self.plugin.config["binding_sync_interval_seconds"] = 45
        self.plugin._schedule_connected_server_reconcile = AsyncMock()
        with patch.object(MAIN.asyncio, "sleep", AsyncMock(side_effect=[None, asyncio.CancelledError()])) as sleep:
            with self.assertRaises(asyncio.CancelledError):
                await self.plugin._periodic_binding_reconcile()
        self.assertEqual(sleep.await_args_list[0].args, (45,))
        self.plugin._schedule_connected_server_reconcile.assert_awaited_once_with(include_command_admins=False)
