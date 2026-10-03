import asyncio
import json
import unittest
from minecraft_adapter import MinecraftPlatformAdapter, MAX_PENDING_BRIDGE_EVENTS_PER_WEBSOCKET
from test_minecraft_protocol import FakeWebSocket


class WebSocketEventIsolationTests(unittest.IsolatedAsyncioTestCase):
    async def test_slow_server_notification_does_not_hold_policy_or_pong(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        ws = FakeWebSocket()
        started, release = asyncio.Event(), asyncio.Event()
        async def slow_notification(payload):
            started.set()
            await release.wait()
        adapter.add_bridge_event_listener(slow_notification)
        await asyncio.wait_for(adapter._handle_hello(ws, {"protocol": 1, "server_id": "test", "chat_capabilities": ["native_chat_translation"]}), 1)
        self.assertEqual(ws.sent[-1]["type"], "native_chat_policy")
        await asyncio.wait_for(started.wait(), 1)
        await asyncio.wait_for(adapter._handle_text(ws, json.dumps({"type": "ping", "time_ms": 5})), 1)
        self.assertEqual(ws.sent[-1], {"type": "pong", "time_ms": 5})
        tasks = tuple(adapter._websocket_event_tasks[ws])
        release.set()
        await asyncio.gather(*tasks)

    async def test_blocked_notification_does_not_delay_login_check(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        ws = FakeWebSocket()
        await adapter.connection_manager.register(ws, {"server_id": "test"})
        release = asyncio.Event()
        async def event(payload):
            if payload["event"] == "player_join":
                await release.wait()
            return {"allowed": True}
        adapter.add_bridge_event_listener(event)
        await adapter._handle_text(ws, json.dumps({"type": "event", "event": "player_join"}))
        await adapter._handle_text(ws, json.dumps({"type": "event", "event": "player_login_check", "message_id": "login"}))
        for _ in range(10):
            await asyncio.sleep(0)
        self.assertTrue(any(message.get("message_id") == "login" and message.get("allowed") for message in ws.sent))
        tasks = tuple(adapter._websocket_event_tasks.get(ws, ()))
        release.set()
        await asyncio.gather(*tasks)

    async def test_pending_events_are_bounded_and_cancel_cleanly(self):
        adapter = MinecraftPlatformAdapter({}, {}, None)
        ws = FakeWebSocket()
        await adapter.connection_manager.register(ws, {"server_id": "test"})
        async def blocked_event(payload):
            await asyncio.Event().wait()
        adapter.add_bridge_event_listener(blocked_event)
        for index in range(MAX_PENDING_BRIDGE_EVENTS_PER_WEBSOCKET + 1):
            await adapter._handle_text(ws, json.dumps({"type": "event", "event": "player_join", "message_id": str(index)}))
        self.assertEqual(len(adapter._websocket_event_tasks[ws]), MAX_PENDING_BRIDGE_EVENTS_PER_WEBSOCKET)
        self.assertEqual(ws.sent[-1]["error"], "bridge_event_busy")
        self.assertFalse(ws.sent[-1]["allowed"])
        tasks = tuple(adapter._websocket_event_tasks[ws])
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await asyncio.sleep(0)
        self.assertNotIn(ws, adapter._websocket_event_tasks)
