"""WebSocket connection manager for real-time sync between devices."""
import asyncio
import logging
from typing import Set

from fastapi import WebSocket

log = logging.getLogger("homestock.ws")


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: Set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        async with self._lock:
            self._connections.add(websocket)
        log.info("WS client connected (%d total)", len(self._connections))

    async def disconnect(self, websocket: WebSocket) -> None:
        async with self._lock:
            self._connections.discard(websocket)
        log.info("WS client disconnected (%d total)", len(self._connections))

    async def broadcast(self, message: dict) -> None:
        async with self._lock:
            targets = list(self._connections)
        dead = []
        for ws in targets:
            try:
                await ws.send_json(message)
            except Exception:  # noqa: BLE001 - connection dropped
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self._connections.discard(ws)


manager = ConnectionManager()

# The main event loop, captured at startup so sync route handlers (which run in
# a worker thread) can safely schedule broadcasts onto it.
_main_loop: asyncio.AbstractEventLoop | None = None


def set_event_loop(loop: asyncio.AbstractEventLoop) -> None:
    global _main_loop
    _main_loop = loop


def broadcast_sync(entity: str, action: str, obj_id: int, data: dict | None = None) -> None:
    """Fire-and-forget broadcast usable from sync (non-async) route handlers."""
    payload = {"entity": entity, "action": action, "id": obj_id, "data": data}
    if _main_loop is None:
        log.debug("Event loop not set; skipping broadcast of %s/%s", entity, action)
        return
    try:
        asyncio.run_coroutine_threadsafe(manager.broadcast(payload), _main_loop)
    except RuntimeError as exc:
        log.debug("Broadcast skipped (%s): %s", f"{entity}/{action}", exc)
