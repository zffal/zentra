import asyncio
import logging
from typing import Optional

from fastapi import WebSocket, WebSocketDisconnect

from auth import decode_token
from config import settings
from json_utils import sanitize_json
from metrics_hub import hub

logger = logging.getLogger("zentra.websocket")


def build_payload() -> dict:
    """One-shot synchronous snapshot (fallback when the hub is disabled)."""
    from metrics_hub import build_payload_sync

    return build_payload_sync()


def _authenticate(websocket: WebSocket) -> Optional[dict]:
    token = websocket.query_params.get("token", "")
    if not token:
        header = websocket.headers.get("authorization", "")
        if header.lower().startswith("bearer "):
            token = header[7:].strip()
    if token:
        payload = decode_token(token)
        if payload is None:
            return None
        return {"username": payload.get("sub"), "role": payload.get("role")}
    if settings.REQUIRE_AUTH_FOR_READS:
        return None
    return {"username": "anonymous", "role": "reader"}


async def metrics_websocket(websocket: WebSocket) -> None:
    client = websocket.client
    origin = f"{client.host}:{client.port}" if client else "unknown"

    user = _authenticate(websocket)
    if user is None:
        logger.warning("event=ws_auth_failed client=%s", origin)
        await websocket.close(code=4401, reason="authentication required")
        return

    await websocket.accept()
    logger.info(
        "event=ws_connected client=%s user=%s interval=%ss",
        origin,
        user.get("username"),
        settings.WS_INTERVAL_SECONDS,
    )

    try:
        while True:
            # All clients stream the same shared snapshot; a slow collector is
            # already bounded per-section inside the hub, so no client blocks
            # another and a disconnected client cannot stop the collector.
            snapshot = hub.latest()
            if snapshot is None:
                snapshot = await hub.wait_first(settings.WS_SEND_TIMEOUT_SECONDS)
            if snapshot is None:
                try:
                    snapshot = await asyncio.wait_for(
                        asyncio.to_thread(build_payload),
                        settings.METRICS_COLLECTOR_TIMEOUT_SECONDS,
                    )
                except Exception as exc:
                    logger.error("event=ws_build_failed client=%s error=%s", origin, exc)
                    await asyncio.sleep(1.0)
                    continue

            try:
                await asyncio.wait_for(
                    websocket.send_json(sanitize_json(snapshot)),
                    settings.WS_SEND_TIMEOUT_SECONDS,
                )
            except WebSocketDisconnect:
                raise
            except asyncio.TimeoutError:
                logger.warning("event=ws_send_timeout client=%s", origin)
                await asyncio.sleep(0.5)
                continue
            except Exception as exc:
                logger.error("event=ws_send_failed client=%s error=%s", origin, exc)
                await asyncio.sleep(1.0)
                continue
            await asyncio.sleep(settings.WS_INTERVAL_SECONDS)
    except WebSocketDisconnect:
        logger.info("event=ws_disconnected client=%s", origin)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        logger.error("event=ws_error client=%s error=%s", origin, exc)
        try:
            await websocket.close(code=1011)
        except Exception:
            pass
