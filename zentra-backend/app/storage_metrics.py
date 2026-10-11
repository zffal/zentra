import logging
import re
import threading
import time
from typing import Dict, List, Optional

import psutil

logger = logging.getLogger("zentra.storage")

PSEUDO_FS = {
    "tmpfs",
    "devtmpfs",
    "proc",
    "sysfs",
    "cgroup",
    "cgroup2",
    "overlay",
    "squashfs",
    "devpts",
    "securityfs",
    "debugfs",
    "tracefs",
    "pstore",
    "bpf",
    "hugetlbfs",
    "mqueue",
    "configfs",
    "fusectl",
    "binfmt_misc",
    "autofs",
    "efivarfs",
    "nsfs",
    "rpc_pipefs",
    "none",
    "ramfs",
    "fuse.portal",
    "portal",
}


def filesystems() -> List[dict]:
    results: List[dict] = []
    try:
        partitions = psutil.disk_partitions(all=False)
    except Exception as exc:
        logger.error("event=disk_partitions_failed error=%s", exc)
        return results

    for partition in partitions:
        fstype = (partition.fstype or "").lower()
        if fstype in PSEUDO_FS:
            continue
        try:
            usage = psutil.disk_usage(partition.mountpoint)
        except (OSError, PermissionError) as exc:
            logger.debug(
                "event=disk_usage_skipped mountpoint=%s error=%s",
                partition.mountpoint,
                exc,
            )
            continue
        except Exception as exc:
            logger.debug(
                "event=disk_usage_skipped mountpoint=%s error=%s",
                partition.mountpoint,
                exc,
            )
            continue
        results.append(
            {
                "device": partition.device,
                "mountpoint": partition.mountpoint,
                "filesystem": partition.fstype,
                "total": usage.total,
                "used": usage.used,
                "free": usage.free,
                "percent": round(usage.percent, 1),
            }
        )
    return results


def _io_counters(raw) -> Optional[dict]:
    if raw is None:
        return None
    return {
        "read_bytes": raw.read_bytes,
        "write_bytes": raw.write_bytes,
        "read_count": raw.read_count,
        "write_count": raw.write_count,
        "read_time": raw.read_time,
        "write_time": raw.write_time,
    }


_io_lock = threading.Lock()
_io_prev: Dict[str, tuple] = {}
_io_prev_total: Optional[tuple] = None
_io_at: Optional[float] = None

_PARTITION_RE = re.compile(r"^(?P<base>.*?)(?P<p>p)?(?P<num>\d+)$")


def _device_kind(name: str, keys) -> str:
    """Classify a diskstats entry as ``disk``, ``partition`` or ``virtual``.

    Partitions are identified by the presence of their whole-disk parent in the
    same key set (``sda1`` -> ``sda``, ``nvme0n1p1`` -> ``nvme0n1``). This
    avoids double counting when aggregating physical disks.
    """
    if name.startswith(("loop", "ram", "dm-", "md")) or ":" in name:
        return "virtual"
    match = _PARTITION_RE.match(name)
    if match and match.group("num") is not None and match.group("base") in keys:
        return "partition"
    return "disk"


def _rate_delta(current: Optional[int], previous: Optional[int]) -> Optional[int]:
    if current is None or previous is None:
        return None
    if current < previous:
        # Counter reset (reboot, device re-added, wrap): not measurable.
        return None
    return current - previous


def disk_io() -> dict:
    """Disk I/O counters and throughput.

    Cumulative counters are bytes (``read_bytes`` / ``write_bytes``).
    Throughput is ``read_bytes_per_sec`` / ``write_bytes_per_sec`` (bytes per
    second), computed from counter deltas over monotonic elapsed time.

    Rates are ``null`` when they cannot be measured safely (first sample,
    counter reset, or a device that appeared since the previous sample) and
    ``0.0`` for a genuine idle interval - never an invented nonzero value.
    ``kind`` separates whole disks from partitions/virtual devices and
    ``physical`` aggregates whole disks only, so partitions are not counted
    twice. Capacity lives separately under ``filesystems``.
    """
    global _io_prev, _io_prev_total, _io_at

    result = {
        "available": False,
        "unit": "bytes_per_sec",
        "measured": False,
        "elapsed_seconds": None,
        "timestamp": int(time.time()),
        "total": None,
        "physical": None,
        "disks": {},
    }

    try:
        total = psutil.disk_io_counters(perdisk=False)
    except Exception as exc:
        logger.debug("event=disk_io_failed error=%s", exc)
        total = None
    try:
        per_disk = psutil.disk_io_counters(perdisk=True) or {}
    except Exception as exc:
        logger.debug("event=disk_io_perdisk_failed error=%s", exc)
        per_disk = {}

    if total is None and not per_disk:
        return result

    now = time.monotonic()
    with _io_lock:
        elapsed = (now - _io_at) if _io_at is not None else None
        usable_elapsed = elapsed if (elapsed is not None and elapsed > 0) else None

        if total is not None:
            entry = _io_counters(total)
            prev = _io_prev_total
            read_delta = _rate_delta(total.read_bytes, prev[0] if prev else None)
            write_delta = _rate_delta(total.write_bytes, prev[1] if prev else None)
            if usable_elapsed and read_delta is not None and write_delta is not None:
                entry["read_bytes_per_sec"] = round(read_delta / usable_elapsed, 1)
                entry["write_bytes_per_sec"] = round(write_delta / usable_elapsed, 1)
                result["measured"] = True
            else:
                entry["read_bytes_per_sec"] = None
                entry["write_bytes_per_sec"] = None
            result["total"] = entry
            _io_prev_total = (total.read_bytes, total.write_bytes, now)

        physical = {
            "read_bytes": 0,
            "write_bytes": 0,
            "read_bytes_per_sec": 0.0,
            "write_bytes_per_sec": 0.0,
        }
        physical_measured = False

        keys = per_disk.keys()
        disks: Dict[str, dict] = {}
        new_prev: Dict[str, tuple] = {}
        for name, counters in per_disk.items():
            entry = _io_counters(counters)
            kind = _device_kind(name, keys)
            entry["kind"] = kind
            previous = _io_prev.get(name)
            read_delta = _rate_delta(counters.read_bytes, previous[0] if previous else None)
            write_delta = _rate_delta(counters.write_bytes, previous[1] if previous else None)
            if usable_elapsed and read_delta is not None and write_delta is not None:
                entry["read_bytes_per_sec"] = round(read_delta / usable_elapsed, 1)
                entry["write_bytes_per_sec"] = round(write_delta / usable_elapsed, 1)
            else:
                entry["read_bytes_per_sec"] = None
                entry["write_bytes_per_sec"] = None
            disks[name] = entry
            new_prev[name] = (counters.read_bytes, counters.write_bytes, now)

            if kind == "disk":
                physical["read_bytes"] += counters.read_bytes
                physical["write_bytes"] += counters.write_bytes
                if entry["read_bytes_per_sec"] is not None:
                    physical["read_bytes_per_sec"] += entry["read_bytes_per_sec"]
                    physical["write_bytes_per_sec"] += entry["write_bytes_per_sec"]
                    physical_measured = True

        _io_prev = new_prev
        _io_at = now
        result["disks"] = disks
        if disks:
            physical["read_bytes_per_sec"] = round(physical["read_bytes_per_sec"], 1)
            physical["write_bytes_per_sec"] = round(physical["write_bytes_per_sec"], 1)
            if not physical_measured:
                physical["read_bytes_per_sec"] = None
                physical["write_bytes_per_sec"] = None
            result["physical"] = physical
            if physical_measured:
                result["measured"] = True

    result["available"] = total is not None or bool(per_disk)
    result["elapsed_seconds"] = round(elapsed, 3) if elapsed is not None else None
    return result


def storage_metrics() -> dict:
    return {
        "filesystems": filesystems(),
        "io": disk_io(),
        "timestamp": int(time.time()),
    }
