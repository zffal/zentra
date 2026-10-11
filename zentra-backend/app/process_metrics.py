import logging
import threading
import time
from typing import Dict, List, Optional

import psutil

from config import settings

logger = logging.getLogger("zentra.process")

_proc_lock = threading.Lock()
_proc_cache: Dict[int, psutil.Process] = {}

_ATTRS = [
    "pid",
    "name",
    "username",
    "memory_info",
    "memory_percent",
    "cpu_percent",
    "status",
    "create_time",
    "num_threads",
]


def _iter_processes() -> List[dict]:
    global _proc_cache
    processes: List[dict] = []
    new_cache: Dict[int, psutil.Process] = {}

    with _proc_lock:
        try:
            iterator = psutil.process_iter(_ATTRS)
        except Exception as exc:
            logger.error("event=process_iter_failed error=%s", exc)
            return processes

        for proc in iterator:
            try:
                cached = _proc_cache.get(proc.pid)
                if cached is not None and cached.is_running():
                    process = cached
                else:
                    process = proc
                    process.cpu_percent(None)
                new_cache[proc.pid] = process
                info = proc.info
                memory_info = info.get("memory_info")
                processes.append(
                    {
                        "pid": info.get("pid"),
                        "name": info.get("name") or "unknown",
                        "username": info.get("username"),
                        "cpu_percent": round(info.get("cpu_percent") or 0.0, 1),
                        "memory_percent": round(info.get("memory_percent") or 0.0, 2),
                        "memory_bytes": int(memory_info.rss) if memory_info else 0,
                        "status": info.get("status"),
                        "create_time": info.get("create_time"),
                        "threads": info.get("num_threads"),
                    }
                )
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                continue
            except Exception:
                continue

        _proc_cache = new_cache

    return processes


def list_processes(
    sort: str = "cpu", limit: Optional[int] = None, search: Optional[str] = None
) -> dict:
    limit = limit or settings.PROCESS_DEFAULT_LIMIT
    limit = max(1, min(limit, settings.PROCESS_MAX_LIMIT))

    processes = _iter_processes()

    if search:
        needle = search.lower()
        processes = [
            p
            for p in processes
            if needle in (p.get("name") or "").lower()
            or needle in (p.get("username") or "").lower()
        ]

    if sort == "memory":
        processes.sort(key=lambda p: p.get("memory_bytes", 0), reverse=True)
    elif sort == "name":
        processes.sort(key=lambda p: (p.get("name") or "").lower())
    else:
        processes.sort(key=lambda p: p.get("cpu_percent", 0.0), reverse=True)

    total = len(processes)
    return {
        "total": total,
        "sort": sort,
        "limit": limit,
        "processes": processes[:limit],
        "timestamp": int(time.time()),
    }
