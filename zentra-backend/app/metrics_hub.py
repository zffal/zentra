import asyncio
import logging
import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

from config import settings

logger = logging.getLogger("zentra.hub")

# Fallback payloads used when a collector fails or times out. They are
# explicitly marked unavailable/stale so a missing metric is never presented
# to clients (or the alert engine) as a fresh reading.
_FALLBACKS: Dict[str, dict] = {
    "cpu": {"usage_percent": 0.0, "available": False, "stale": True},
    "memory": {"memory": {}, "swap": {}, "available": False, "stale": True},
    "storage": {"filesystems": [], "io": {}, "available": False, "stale": True},
    "network": {
        "rates": {
            "download_bytes_per_sec": None,
            "upload_bytes_per_sec": None,
            "unit": "bytes_per_sec",
            "available": False,
            "measured": False,
            "null_reason": "unavailable",
        },
        "total": {},
        "interfaces": [],
        "aggregate": {
            "mode": "physical",
            "interfaces": [],
            "excluded": [],
            "totals": {},
            "rates": {},
        },
        "primary": {"interface": None, "available": False, "rates": {}, "totals": {}},
        "hostname_ips": {"lan": [], "tailscale": []},
        "available": False,
        "stale": True,
    },
    "temperature": {"available": False, "sensors": [], "stale": True},
    "docker": {"available": False, "containers": [], "summary": {}, "stale": True},
    "system": {"available": False, "stale": True},
}


def _collectors() -> List[Tuple[str, Callable[[], object], float]]:
    """Return (name, callable, timeout) triples.

    Each collector is imported lazily so importing this module stays cheap and
    free of side effects.
    """
    import docker_metrics
    import network_metrics
    import storage_metrics
    import system_metrics
    import temperature_metrics

    default_timeout = settings.METRICS_COLLECTOR_TIMEOUT_SECONDS
    return [
        ("system", system_metrics.system_info, default_timeout),
        ("cpu", lambda: system_metrics.cpu_metrics(interval=0.2), default_timeout),
        ("memory", system_metrics.memory_metrics, default_timeout),
        ("storage", storage_metrics.storage_metrics, default_timeout),
        ("network", network_metrics.network_metrics, default_timeout),
        ("temperature", temperature_metrics.temperature_metrics, default_timeout),
        (
            "docker",
            lambda: docker_metrics.list_containers(include_stats=True),
            settings.METRICS_DOCKER_TIMEOUT_SECONDS,
        ),
    ]


def _reduce_storage(storage: Optional[dict]) -> dict:
    if storage is None:
        return dict(_FALLBACKS["storage"])
    return {
        "filesystems": [
            {
                "mountpoint": fs["mountpoint"],
                "device": fs["device"],
                "filesystem": fs["filesystem"],
                "total": fs["total"],
                "used": fs["used"],
                "free": fs["free"],
                "percent": fs["percent"],
            }
            for fs in storage.get("filesystems", [])
        ],
        "io": storage.get("io", {}),
    }


def _reduce_docker(docker: Optional[dict]) -> dict:
    if docker is None:
        return dict(_FALLBACKS["docker"])
    return {
        "available": docker.get("available", False),
        "error": docker.get("error"),
        "summary": docker.get("summary", {}),
        "stats_truncated": docker.get("stats_truncated", False),
        "containers": [
            {
                "id": c["id"],
                "short_id": c["short_id"],
                "name": c["name"],
                "image": c["image"],
                "state": c["state"],
                "status": c["status"],
                "uptime": c["uptime"],
                "health": c.get("health", {}).get("status"),
                "restart_count": c.get("restart_count", 0),
                "stats": c.get("stats"),
            }
            for c in docker.get("containers", [])
        ],
    }


def _alerts_section() -> dict:
    try:
        from alerts import engine

        return {"recent": len(engine.recent(10)), "latest": engine.recent(1)}
    except Exception:
        return {"recent": 0, "latest": []}


def _assemble(parts: Dict[str, Optional[dict]], meta: Dict[str, dict]) -> dict:
    import system_metrics

    try:
        uptime = system_metrics.uptime_seconds()
    except Exception:
        uptime = None

    payload = {
        "type": "metrics",
        "timestamp": int(time.time()),
        "uptime": uptime,
    }
    payload["system"] = parts.get("system") if parts.get("system") is not None else dict(_FALLBACKS["system"])
    payload["cpu"] = parts.get("cpu") if parts.get("cpu") is not None else dict(_FALLBACKS["cpu"])
    payload["memory"] = parts.get("memory") if parts.get("memory") is not None else dict(_FALLBACKS["memory"])
    payload["storage"] = _reduce_storage(parts.get("storage"))
    payload["network"] = parts.get("network") if parts.get("network") is not None else dict(_FALLBACKS["network"])
    payload["temperature"] = parts.get("temperature") if parts.get("temperature") is not None else dict(_FALLBACKS["temperature"])
    payload["docker"] = _reduce_docker(parts.get("docker"))
    payload["alerts"] = _alerts_section()
    payload["collector"] = meta
    return payload


def build_payload_sync() -> dict:
    """Synchronous one-shot snapshot (fallback path / tests)."""
    parts: Dict[str, Optional[dict]] = {}
    meta: Dict[str, dict] = {}
    for name, func, _timeout in _collectors():
        started = time.time()
        try:
            parts[name] = func()
            meta[name] = {"ok": True, "duration_ms": round((time.time() - started) * 1000, 1)}
        except Exception as exc:
            logger.error("event=collector_failed section=%s error=%s", name, exc)
            parts[name] = None
            meta[name] = {
                "ok": False,
                "duration_ms": round((time.time() - started) * 1000, 1),
                "error": str(exc)[:200],
            }
    return _assemble(parts, meta)


async def _collect_one(name: str, func: Callable[[], object], timeout: float):
    started = time.time()
    try:
        value = await asyncio.wait_for(asyncio.to_thread(func), timeout)
        return name, value, {"ok": True, "duration_ms": round((time.time() - started) * 1000, 1)}, None
    except asyncio.TimeoutError:
        logger.error("event=collector_timeout section=%s timeout=%ss", name, timeout)
        return name, None, {
            "ok": False,
            "duration_ms": round((time.time() - started) * 1000, 1),
            "error": "timeout",
        }, "timeout"
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        logger.error("event=collector_failed section=%s error=%s", name, exc)
        return name, None, {
            "ok": False,
            "duration_ms": round((time.time() - started) * 1000, 1),
            "error": str(exc)[:200],
        }, str(exc)[:200]


class MetricsHub:
    """Single background collector shared by all websocket clients.

    Running one collector instead of one-per-connection removes duplicate work
    (including the expensive Docker stats sweep) and decouples a slow collector
    from individual subscribers. Each collector has its own timeout, so a hung
    section cannot stall the rest of the snapshot.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._snapshot: Optional[dict] = None
        self._collected_at = 0.0
        self._sequence = 0
        self._collections = 0
        self._last_error: Optional[str] = None
        self._task: Optional[asyncio.Task] = None
        self._first_event: Optional[asyncio.Event] = None
        self._docker_parts: Optional[dict] = None
        self._docker_at = 0.0

    def latest(self) -> Optional[dict]:
        with self._lock:
            return self._snapshot

    def status(self) -> dict:
        with self._lock:
            collected_at = self._collected_at
            sequence = self._sequence
            collections = self._collections
            last_error = self._last_error
        age = round(time.time() - collected_at, 2) if collected_at else None
        return {
            "enabled": settings.METRICS_HUB_ENABLED,
            "running": self._task is not None and not self._task.done(),
            "sequence": sequence,
            "collections": collections,
            "collected_at": int(collected_at) if collected_at else None,
            "age_seconds": age,
            "stale": age is None or age > settings.METRICS_STALE_SECONDS,
            "last_error": last_error,
        }

    async def start(self) -> None:
        if not settings.METRICS_HUB_ENABLED:
            logger.info("event=metrics_hub_disabled")
            return
        if self._task is not None and not self._task.done():
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            logger.error("event=metrics_hub_start_failed reason=no_running_loop")
            return
        self._first_event = asyncio.Event()
        self._task = loop.create_task(self._run())

    async def stop(self) -> None:
        task = self._task
        self._task = None
        if task is not None:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass

    async def wait_first(self, timeout: float) -> Optional[dict]:
        event = self._first_event
        if event is None:
            return self.latest()
        if self.latest() is None:
            try:
                await asyncio.wait_for(event.wait(), timeout)
            except asyncio.TimeoutError:
                pass
        return self.latest()

    async def _run(self) -> None:
        logger.info(
            "event=metrics_hub_started interval=%ss", settings.WS_INTERVAL_SECONDS
        )
        while True:
            started = time.time()
            try:
                await self._collect_once()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("event=metrics_hub_cycle_failed error=%s", exc)
                with self._lock:
                    self._last_error = str(exc)[:200]
            elapsed = time.time() - started
            await asyncio.sleep(max(0.2, settings.WS_INTERVAL_SECONDS - elapsed))

    async def _collect_once(self) -> None:
        now = time.time()
        to_run = []
        use_cached_docker = False
        for name, func, timeout in _collectors():
            if (
                name == "docker"
                and self._docker_parts is not None
                and now - self._docker_at < settings.METRICS_DOCKER_REFRESH_SECONDS
            ):
                use_cached_docker = True
                continue
            to_run.append((name, func, timeout))

        results = (
            await asyncio.gather(*[_collect_one(n, f, t) for n, f, t in to_run])
            if to_run
            else []
        )
        parts: Dict[str, Optional[dict]] = {}
        meta: Dict[str, dict] = {}
        failure = None
        for name, value, section_meta, error in results:
            parts[name] = value
            meta[name] = section_meta
            if name == "docker" and value is not None:
                self._docker_parts = value
                self._docker_at = time.time()
            if error and failure is None:
                failure = f"{name}: {error}"

        if use_cached_docker:
            parts["docker"] = self._docker_parts
            meta["docker"] = {
                "ok": True,
                "cached": True,
                "age_seconds": round(now - self._docker_at, 2),
            }

        now = time.time()
        with self._lock:
            self._sequence += 1
            payload = _assemble(parts, meta)
            payload["sequence"] = self._sequence
            payload["collected_at"] = int(now)
            self._snapshot = payload
            self._collected_at = now
            self._collections += 1
            self._last_error = failure
        if self._first_event is not None:
            self._first_event.set()


hub = MetricsHub()
