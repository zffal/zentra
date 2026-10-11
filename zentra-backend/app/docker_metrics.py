import datetime
import logging
import re
import threading
import time
from typing import Dict, List, Optional

from config import settings

logger = logging.getLogger("zentra.docker")

try:
    import docker
    from docker.errors import DockerException, NotFound, APIError
except Exception:  # pragma: no cover
    docker = None
    DockerException = Exception
    NotFound = Exception
    APIError = Exception

_client = None
_client_lock = threading.Lock()
_client_last_ok = 0.0
_client_error: Optional[str] = None
_CLIENT_REFRESH_SECONDS = 5.0

_stats_cache: Dict[str, dict] = {}
_prev_cpu_samples: Dict[str, dict] = {}
_stats_lock = threading.Lock()
_op_lock = threading.RLock()

_ID_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")


def get_client():
    global _client, _client_error, _client_last_ok
    if docker is None:
        _client_error = "docker sdk not installed"
        return None
    with _client_lock:
        if _client is not None and time.time() - _client_last_ok < _CLIENT_REFRESH_SECONDS:
            return _client
        if _client is not None:
            try:
                _client.ping()
                _client_last_ok = time.time()
                return _client
            except Exception as exc:
                logger.warning("event=docker_ping_failed error=%s", exc)
                _client = None
        try:
            _client = docker.from_env(timeout=10, max_pool_size=32)
            _client.ping()
            _client_last_ok = time.time()
            _client_error = None
            logger.info("event=docker_connected")
            return _client
        except Exception as exc:
            _client = None
            _client_error = str(exc)[:300]
            logger.error("event=docker_unavailable error=%s", _client_error)
            return None


def _invalidate_client() -> None:
    global _client, _client_last_ok
    with _client_lock:
        _client = None
        _client_last_ok = 0.0


def docker_available() -> bool:
    return get_client() is not None


def validate_container_id(container_id: str) -> str:
    if not container_id or not _ID_PATTERN.match(container_id):
        raise ValueError("invalid container id")
    return container_id


def _parse_time(value) -> Optional[float]:
    if not value:
        return None
    try:
        text = value.replace("Z", "+00:00")
        parsed = datetime.datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        return parsed.timestamp()
    except (ValueError, TypeError):
        return None


def _health_from_attrs(attrs: dict) -> dict:
    state_info = attrs.get("State") or {}
    health_info = state_info.get("Health") or {}
    return {
        "status": health_info.get("Status") or ("healthy" if state_info.get("Status") == "running" else "unhealthy" if state_info.get("Status") == "exited" else None),
        "failing_streak": health_info.get("FailingStreak", 0),
        "log": (health_info.get("Log") or [])[-3:],
    }


def _container_base(container) -> dict:
    attrs = container.attrs
    created = _parse_time(attrs.get("Created"))
    started = _parse_time((attrs.get("State") or {}).get("StartedAt"))
    now = time.time()
    return {
        "id": attrs.get("Id", ""),
        "short_id": attrs.get("Id", "")[:12],
        "name": (attrs.get("Name") or "").lstrip("/"),
        "image": attrs.get("Config", {}).get("Image") or attrs.get("Image", ""),
        "image_id": attrs.get("Image", ""),
        "state": (attrs.get("State") or {}).get("Status", "unknown"),
        "status": attrs.get("Status", ""),
        "created": created,
        "started_at": started,
        "uptime": round(now - started, 1) if started else None,
        "health": _health_from_attrs(attrs),
        "restart_count": attrs.get("RestartCount", 0),
        "exit_code": (attrs.get("State") or {}).get("ExitCode"),
        "pid": (attrs.get("State") or {}).get("Pid"),
        "platform": attrs.get("Platform", ""),
        "labels": {
            k: v
            for k, v in (attrs.get("Config", {}).get("Labels") or {}).items()
            if k in ("com.docker.compose.service", "com.docker.compose.project")
        },
    }


def _stats_for(
    container_id: str, container=None, deadline: Optional[float] = None
) -> Optional[dict]:
    ttl = settings.DOCKER_STATS_TTL_SECONDS
    with _stats_lock:
        cached = _stats_cache.get(container_id)
        if cached and time.time() - cached["ts"] < ttl:
            return cached["data"]

    # A collection sweep must not run past its budget. Once the deadline is
    # reached we serve the last cached sample (or None) instead of blocking on
    # another potentially slow Docker call.
    if deadline is not None and time.time() >= deadline:
        if cached:
            return cached["data"]
        return None

    client = get_client()
    if client is None:
        return None
    try:
        with _op_lock:
            target = container or client.containers.get(container_id)
            try:
                raw = target.stats(stream=False, one_shot=True)
            except TypeError:
                raw = target.stats(stream=False)
    except NotFound:
        return None
    except Exception as exc:
        logger.debug("event=container_stats_failed id=%s error=%s", container_id, exc)
        return None

    with _stats_lock:
        prev = _prev_cpu_samples.get(container_id)
        data, sample = _parse_stats(raw, prev)
        _prev_cpu_samples[container_id] = sample
        _stats_cache[container_id] = {"ts": time.time(), "data": data}
    return data


def _parse_stats(raw: dict, prev: Optional[dict] = None) -> tuple:
    result = {
        "cpu_percent": 0.0,
        "memory_usage": 0,
        "memory_limit": 0,
        "memory_percent": 0.0,
        "network_rx": 0,
        "network_tx": 0,
        "block_read": 0,
        "block_write": 0,
    }
    if not raw:
        return result, {"total": 0, "system": 0, "ts": time.time()}

    cpu = raw.get("cpu_stats") or {}
    cpu_total = (cpu.get("cpu_usage", {}) or {}).get("total_usage", 0) or 0
    system_total = cpu.get("system_cpu_usage", 0) or 0
    online = cpu.get("online_cpus") or len(
        (cpu.get("cpu_usage", {}) or {}).get("percpu_usage") or []
    )

    if prev and prev.get("system") and system_total > prev["system"]:
        cpu_delta = cpu_total - prev["total"]
        system_delta = system_total - prev["system"]
        if cpu_delta > 0 and system_delta > 0:
            percent = (cpu_delta / system_delta) * (online or 1) * 100.0
            result["cpu_percent"] = round(min(percent, (online or 1) * 100.0), 2)

    mem = raw.get("memory_stats") or {}
    usage = mem.get("usage", 0) or 0
    cache = (mem.get("stats") or {}).get("cache", 0) or 0
    limit = mem.get("limit", 0) or 0
    effective = usage - cache if usage > cache else usage
    result["memory_usage"] = max(0, effective)
    result["memory_limit"] = limit
    if limit:
        result["memory_percent"] = round(min(100.0, effective / limit * 100.0), 2)

    networks = raw.get("networks") or {}
    rx = sum((n or {}).get("rx_bytes", 0) or 0 for n in networks.values())
    tx = sum((n or {}).get("tx_bytes", 0) or 0 for n in networks.values())
    result["network_rx"] = rx
    result["network_tx"] = tx

    read = 0
    write = 0
    for entry in raw.get("blkio_stats", {}).get("io_service_bytes_recursive") or []:
        op = (entry.get("op") or "").lower()
        value = entry.get("value", 0) or 0
        if op == "read":
            read += value
        elif op == "write":
            write += value
    result["block_read"] = read
    result["block_write"] = write
    return result, {"total": cpu_total, "system": system_total, "ts": time.time()}


def _with_stats(base: dict, container=None, deadline: Optional[float] = None) -> dict:
    stats = _stats_for(base["id"], container=container, deadline=deadline)
    if stats is None:
        base["stats"] = None
        base["stats_available"] = False
    else:
        base["stats"] = stats
        base["stats_available"] = True
    return base


def list_containers(include_stats: bool = True) -> dict:
    client = get_client()
    if client is None:
        return {
            "available": False,
            "error": _client_error or "docker unavailable",
            "containers": [],
            "summary": {"total": 0, "running": 0, "stopped": 0, "unhealthy": 0},
            "timestamp": int(time.time()),
        }

    result = []
    running = 0
    stopped = 0
    unhealthy = 0
    # Only hold the operation lock for the listing call. Per-container stats
    # acquisition is serialized internally by _stats_for so that a slow stats
    # sweep cannot block container actions for its entire duration.
    try:
        with _op_lock:
            containers = client.containers.list(all=True)
    except Exception as exc:
        logger.error("event=docker_list_failed error=%s", exc)
        _invalidate_client()
        return {
            "available": False,
            "error": str(exc)[:300],
            "containers": [],
            "summary": {"total": 0, "running": 0, "stopped": 0, "unhealthy": 0},
            "timestamp": int(time.time()),
        }

    deadline = (
        time.time() + settings.DOCKER_STATS_BUDGET_SECONDS if include_stats else None
    )
    stats_truncated = False
    for container in containers:
        try:
            base = _container_base(container)
            if base["state"] == "running":
                running += 1
            else:
                stopped += 1
            if base["health"].get("status") == "unhealthy":
                unhealthy += 1
            if include_stats:
                base = _with_stats(base, container, deadline=deadline)
                if not base.get("stats_available") and not base.get("stats"):
                    stats_truncated = True
            else:
                base["stats"] = None
                base["stats_available"] = False
            result.append(base)
        except Exception as exc:
            logger.debug("event=container_skipped error=%s", exc)
            continue

    result.sort(key=lambda c: c["name"])
    return {
        "available": True,
        "containers": result,
        "summary": {
            "total": len(result),
            "running": running,
            "stopped": stopped,
            "unhealthy": unhealthy,
        },
        "stats_truncated": stats_truncated,
        "timestamp": int(time.time()),
    }


def get_container(container_id: str) -> dict:
    container_id = validate_container_id(container_id)
    client = get_client()
    if client is None:
        return {"available": False, "error": _client_error or "docker unavailable"}
    try:
        with _op_lock:
            container = client.containers.get(container_id)
            container.reload()
    except NotFound:
        return {"available": True, "found": False, "error": "container not found"}
    except Exception as exc:
        logger.error("event=container_get_failed id=%s error=%s", container_id, exc)
        _invalidate_client()
        return {"available": True, "found": False, "error": str(exc)[:300]}

    base = _container_base(container)
    base = _with_stats(base, container)
    base["found"] = True
    base["available"] = True

    ports = (container.attrs.get("NetworkSettings") or {}).get("Ports") or {}
    base["ports"] = ports
    networks = (container.attrs.get("NetworkSettings") or {}).get("Networks") or {}
    base["networks"] = {
        name: {
            "ip": (cfg or {}).get("IPAddress"),
            "gateway": (cfg or {}).get("Gateway"),
            "mac": (cfg or {}).get("MacAddress"),
        }
        for name, cfg in networks.items()
    }
    return base


def container_logs(
    container_id: str, tail: Optional[int] = None, timestamps: bool = True
) -> dict:
    container_id = validate_container_id(container_id)
    tail = tail or settings.LOGS_DEFAULT_TAIL
    tail = max(1, min(tail, settings.LOGS_MAX_TAIL))

    client = get_client()
    if client is None:
        return {"available": False, "error": _client_error or "docker unavailable"}
    try:
        with _op_lock:
            container = client.containers.get(container_id)
            raw = container.logs(tail=tail, timestamps=timestamps)
    except NotFound:
        return {"available": True, "found": False, "error": "container not found"}
    except Exception as exc:
        logger.error("event=container_logs_failed id=%s error=%s", container_id, exc)
        _invalidate_client()
        return {"available": True, "found": False, "error": str(exc)[:300]}

    if isinstance(raw, bytes):
        text = raw.decode("utf-8", errors="replace")
    else:
        text = str(raw)
    lines = text.splitlines()
    if len(lines) > 5000:
        lines = lines[-5000:]
    return {
        "available": True,
        "found": True,
        "container": container.name.lstrip("/"),
        "tail": tail,
        "timestamps": timestamps,
        "count": len(lines),
        "lines": lines,
        "timestamp": int(time.time()),
    }


def _action(container_id: str, action: str) -> dict:
    container_id = validate_container_id(container_id)
    client = get_client()
    if client is None:
        return {"success": False, "error": _client_error or "docker unavailable"}
    try:
        container = client.containers.get(container_id)
    except NotFound:
        return {"success": False, "error": "container not found"}
    except Exception as exc:
        logger.error("event=container_lookup_failed id=%s error=%s", container_id, exc)
        return {"success": False, "error": str(exc)[:300]}

    name = container.name.lstrip("/")
    try:
        if action == "start":
            container.start()
        elif action == "stop":
            container.stop(timeout=10)
        elif action == "restart":
            container.restart(timeout=10)
        else:
            return {"success": False, "error": "unsupported action"}
    except NotFound:
        return {"success": False, "error": "container not found"}
    except Exception as exc:
        logger.error(
            "event=container_action_failed action=%s container=%s error=%s",
            action,
            name,
            exc,
        )
        _invalidate_client()
        return {"success": False, "error": str(exc)[:300]}

    with _stats_lock:
        _stats_cache.pop(container_id, None)
    logger.info("event=container_action action=%s container=%s result=ok", action, name)
    return {
        "success": True,
        "action": action,
        "container": name,
        "short_id": container_id[:12],
        "timestamp": int(time.time()),
    }


def start_container(container_id: str) -> dict:
    return _action(container_id, "start")


def stop_container(container_id: str) -> dict:
    return _action(container_id, "stop")


def restart_container(container_id: str) -> dict:
    return _action(container_id, "restart")


def snapshot_states() -> Dict[str, dict]:
    client = get_client()
    states: Dict[str, dict] = {}
    if client is None:
        return states
    try:
        for container in client.containers.list(all=True):
            attrs = container.attrs
            states[container.name.lstrip("/")] = {
                "state": (attrs.get("State") or {}).get("Status", "unknown"),
                "restart_count": attrs.get("RestartCount", 0),
            }
    except Exception as exc:
        logger.debug("event=snapshot_states_failed error=%s", exc)
    return states
