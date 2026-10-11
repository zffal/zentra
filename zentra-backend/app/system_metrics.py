import logging
import os
import platform
import socket
import time
from typing import Optional

import psutil

logger = logging.getLogger("zentra.system")

_static_cache: dict = {}
_STATIC_TTL = 60.0
_BOOT_TIME: Optional[float] = None


def get_boot_time() -> float:
    global _BOOT_TIME
    if _BOOT_TIME is None:
        try:
            _BOOT_TIME = psutil.boot_time()
        except Exception:
            _BOOT_TIME = time.time()
    return _BOOT_TIME


def uptime_seconds() -> float:
    return max(0.0, time.time() - get_boot_time())


def _cpu_model() -> str:
    try:
        with open("/proc/cpuinfo", "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "unknown"


def _os_pretty() -> str:
    try:
        with open("/etc/os-release", "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if line.startswith("PRETTY_NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return platform.platform()


def system_info() -> dict:
    global _static_cache
    now = time.time()
    if not _static_cache or now - _static_cache.get("_ts", 0) > _STATIC_TTL:
        info = {
            "hostname": socket.gethostname(),
            "os": _os_pretty(),
            "os_version": _os_pretty(),
            "kernel": platform.release(),
            "kernel_version": platform.release(),
            "architecture": platform.machine(),
            "cpu_model": _cpu_model(),
            "cpu_cores": psutil.cpu_count(logical=False) or 0,
            "logical_cores": psutil.cpu_count(logical=True) or 0,
            "boot_time": get_boot_time(),
            "_ts": now,
        }
        _static_cache = info
    result = {k: v for k, v in _static_cache.items() if k != "_ts"}
    result["uptime"] = uptime_seconds()
    result["timestamp"] = int(time.time())
    return result


def cpu_metrics(interval: float = 0.15) -> dict:
    try:
        per_core = psutil.cpu_percent(interval=interval, percpu=True)
        usage = round(sum(per_core) / len(per_core), 1) if per_core else 0.0
    except Exception as exc:
        logger.error("event=cpu_metrics_failed error=%s", exc)
        per_core, usage = [], 0.0

    frequency = None
    try:
        freq = psutil.cpu_freq()
        if freq:
            frequency = {
                "current_mhz": round(freq.current, 1) if freq.current else None,
                "min_mhz": round(freq.min, 1) if freq.min else None,
                "max_mhz": round(freq.max, 1) if freq.max else None,
            }
    except Exception:
        frequency = None

    per_core_frequency = None
    try:
        freqs = psutil.cpu_freq(percpu=True)
        if freqs:
            per_core_frequency = [round(f.current, 1) for f in freqs if f.current]
    except Exception:
        per_core_frequency = None

    try:
        load = [round(v, 2) for v in os.getloadavg()]
    except OSError:
        load = []

    per_core_values = [round(v, 1) for v in per_core]
    return {
        "usage_percent": usage,
        "cores": psutil.cpu_count(logical=False) or 0,
        "logical_cores": psutil.cpu_count(logical=True) or 0,
        "per_core": [
            {"core": index, "core_id": index, "usage_percent": value}
            for index, value in enumerate(per_core_values)
        ],
        "per_core_usage": per_core_values,
        "per_core_frequency_mhz": per_core_frequency,
        "frequency": frequency,
        "load_average": load,
        "timestamp": int(time.time()),
    }


def memory_metrics() -> dict:
    try:
        vm = psutil.virtual_memory()
        memory = {
            "total": vm.total,
            "used": vm.used,
            "available": vm.available,
            "free": vm.free,
            "cached": getattr(vm, "cached", 0),
            "buffers": getattr(vm, "buffers", 0),
            "percent": round(vm.percent, 1),
        }
    except Exception as exc:
        logger.error("event=memory_metrics_failed error=%s", exc)
        memory = {}

    try:
        sm = psutil.swap_memory()
        swap = {
            "total": sm.total,
            "used": sm.used,
            "free": sm.free,
            "percent": round(sm.percent, 1),
        }
    except Exception as exc:
        logger.error("event=swap_metrics_failed error=%s", exc)
        swap = {}

    return {"memory": memory, "swap": swap, "timestamp": int(time.time())}


def summary() -> dict:
    return {
        "system": system_info(),
        "cpu": cpu_metrics(interval=0.1),
        "memory": memory_metrics(),
        "uptime": uptime_seconds(),
        "timestamp": int(time.time()),
    }
