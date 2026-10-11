"""Persistent, date-wise network usage accounting.

A background sampler reads cumulative interface counters, converts each
consecutive pair into a byte delta, and attributes that delta to the calendar
day (in a configurable timezone) in which the sample was taken. Daily totals
are accumulated in memory and flushed to a single JSON document on a batched
cadence and on shutdown, so the service never writes on every metrics tick.

Robustness rules:

* First sample after startup / first sight of an interface -> baseline only,
  no bytes counted (never invents a delta).
* Counter decrease (reboot / interface re-created) -> the window is skipped,
  never counted as negative or as an artificial zero.
* Interface disappearance -> baseline dropped; reappearance is a new baseline.
* Interface rename -> treated as a new series (history is keyed by name).

Limitations (by design): usage is only recorded while the backend is running;
a host-interface total is *not* a whole-network/router total (LAN peers talking
to each other never touch this host).
"""

import asyncio
import json
import logging
import threading
import time
from datetime import date, datetime, timedelta, timezone
from typing import Dict, List, Optional

import psutil

from config import DATA_DIR, settings
from network_metrics import is_physical

try:  # Python 3.9+
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None

logger = logging.getLogger("zentra.network_usage")

USAGE_FILE = DATA_DIR / "network_usage.json"
USAGE_VERSION = 1


def rate_alias(rates: Optional[dict]) -> dict:
    """Normalise a live-rates block, adding common field-name aliases.

    Live rates use ``download_bytes_per_sec`` / ``upload_bytes_per_sec``;
    clients reading shorter ``download`` / ``upload`` names are supported too.
    An unmeasured value stays ``None`` (never a fabricated ``0``).
    """
    rates = rates if isinstance(rates, dict) else {}
    download = rates.get("download_bytes_per_sec")
    upload = rates.get("upload_bytes_per_sec")
    return {
        "download_bytes_per_sec": download,
        "upload_bytes_per_sec": upload,
        "download": download,
        "upload": upload,
        "unit": "bytes_per_sec",
        "available": bool(rates.get("available", download is not None)),
        "measured": bool(rates.get("measured", download is not None)),
        "null_reason": rates.get("null_reason"),
    }


def _resolve_tz(name: str):
    if ZoneInfo is None or not name or name.upper() in ("UTC", "ETC/UTC"):
        return timezone.utc, "UTC"
    try:
        return ZoneInfo(name), name
    except Exception:
        logger.warning(
            "event=network_usage_bad_timezone value=%s fallback=UTC", name
        )
        return timezone.utc, "UTC"


def parse_date(value: str) -> date:
    """Parse YYYY-MM-DD; raises ValueError on anything else."""
    return date.fromisoformat(value)


def parse_month(value: str) -> date:
    """Parse YYYY-MM into the first day of that month."""
    try:
        return datetime.strptime(value, "%Y-%m").date()
    except ValueError:
        raise ValueError(f"invalid month {value!r}, expected YYYY-MM")


def resolve_range(
    start: Optional[str],
    end: Optional[str],
    tzinfo,
    max_days: int,
    default_days: int = 29,
) -> tuple:
    """Validate a daily usage window; raises ValueError on bad input."""
    today = datetime.fromtimestamp(time.time(), tz=tzinfo).date()
    end_date = parse_date(end) if end else today
    start_date = (
        parse_date(start) if start else end_date - timedelta(days=default_days)
    )
    if start_date > end_date:
        raise ValueError("start must not be after end")
    if (end_date - start_date).days + 1 > max_days:
        raise ValueError(f"range too large; max {max_days} days")
    return start_date, end_date


def resolve_month_range(
    start: Optional[str], end: Optional[str], tzinfo, max_months: int
) -> tuple:
    """Validate a monthly usage window; raises ValueError on bad input."""
    today = datetime.fromtimestamp(time.time(), tz=tzinfo).date()
    end_month = parse_month(end) if end else today.replace(day=1)
    start_month = (
        parse_month(start)
        if start
        else (end_month - timedelta(days=365)).replace(day=1)
    )
    if start_month > end_month:
        raise ValueError("start must not be after end")
    months = (
        (end_month.year - start_month.year) * 12
        + (end_month.month - start_month.month)
        + 1
    )
    if months > max_months:
        raise ValueError(f"range too large; max {max_months} months")
    return start_month, end_month


class NetworkUsage:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._tz, self._tz_name = _resolve_tz(settings.NETWORK_USAGE_TZ)
        # series[interface][YYYY-MM-DD] = {"rx": int, "tx": int, "samples": int}
        self._series: Dict[str, Dict[str, dict]] = {}
        self._prev: Dict[str, tuple] = {}
        self._last_flush = 0.0
        self._created_at = int(time.time())
        self._updated_at: Optional[int] = None
        self._samples = 0
        self._skipped = 0
        self._errors = 0
        self._task: Optional[asyncio.Task] = None
        self._stopping = False
        self._load()

    @property
    def tzinfo(self):
        return self._tz

    # ------------------------------------------------------------------ #
    # Persistence
    # ------------------------------------------------------------------ #
    def _load(self) -> None:
        try:
            if not USAGE_FILE.exists():
                return
            with USAGE_FILE.open("r", encoding="utf-8") as handle:
                data = json.load(handle)
            if not isinstance(data, dict):
                return
            self._created_at = int(data.get("created_at", self._created_at))
            self._updated_at = data.get("updated_at")
            series = data.get("series", {})
            if isinstance(series, dict):
                for iface, days in series.items():
                    if not isinstance(days, dict):
                        continue
                    clean: Dict[str, dict] = {}
                    for day, bucket in days.items():
                        if not isinstance(bucket, dict):
                            continue
                        clean[day] = {
                            "rx": int(bucket.get("rx", 0)),
                            "tx": int(bucket.get("tx", 0)),
                            "samples": int(bucket.get("samples", 0)),
                            "first_ts": int(bucket.get("first_ts", 0)),
                            "last_ts": int(bucket.get("last_ts", 0)),
                        }
                    self._series[iface] = clean
            logger.info(
                "event=network_usage_loaded interfaces=%d days=%d",
                len(self._series),
                sum(len(v) for v in self._series.values()),
            )
        except Exception as exc:
            logger.warning("event=network_usage_load_failed error=%s", exc)

    def _prune_locked(self, today: date) -> None:
        keep = max(1, settings.NETWORK_USAGE_RETENTION_DAYS)
        cutoff = today - timedelta(days=keep - 1)
        cutoff_s = cutoff.isoformat()
        for iface in list(self._series.keys()):
            days = self._series[iface]
            for day in list(days.keys()):
                if day < cutoff_s:
                    del days[day]
            if not days:
                del self._series[iface]

    def _persist_locked(self, now: float) -> None:
        today = self._today(now)
        self._prune_locked(today)
        self._updated_at = int(now)
        payload = {
            "version": USAGE_VERSION,
            "timezone": self._tz_name,
            "created_at": self._created_at,
            "updated_at": self._updated_at,
            "monitored": settings.NETWORK_MONITORED_INTERFACE or None,
            "unit": "bytes",
            "series": self._series,
        }
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            tmp = USAGE_FILE.with_suffix(".json.tmp")
            with tmp.open("w", encoding="utf-8") as handle:
                json.dump(payload, handle, indent=2)
            tmp.replace(USAGE_FILE)
            self._last_flush = now
        except Exception as exc:
            logger.warning("event=network_usage_persist_failed error=%s", exc)

    def flush(self) -> None:
        with self._lock:
            self._persist_locked(time.time())

    # ------------------------------------------------------------------ #
    # Sampling
    # ------------------------------------------------------------------ #
    def _today(self, now: float) -> date:
        return datetime.fromtimestamp(now, tz=self._tz).date()

    def _target_names(self, io: dict) -> List[str]:
        monitored = settings.NETWORK_MONITORED_INTERFACE
        if monitored:
            return [monitored]
        return [name for name in io.keys() if is_physical(name)]

    def sample(self, now: Optional[float] = None) -> None:
        now = time.time() if now is None else now
        try:
            io = psutil.net_io_counters(pernic=True) or {}
        except Exception as exc:
            with self._lock:
                self._errors += 1
            logger.warning("event=network_usage_sample_failed error=%s", exc)
            return

        targets = set(self._target_names(io))
        day = self._today(now).isoformat()
        with self._lock:
            # Drop baselines for interfaces that vanished.
            for name in list(self._prev.keys()):
                if name not in io:
                    self._prev.pop(name, None)

            for name in targets:
                cur = io.get(name)
                if cur is None:
                    self._prev.pop(name, None)
                    continue
                recv = int(cur.bytes_recv)
                sent = int(cur.bytes_sent)
                prev = self._prev.get(name)
                if prev is not None:
                    d_recv = recv - prev[0]
                    d_sent = sent - prev[1]
                    if d_recv < 0 or d_sent < 0:
                        self._skipped += 1
                        logger.info(
                            "event=network_usage_counter_reset interface=%s", name
                        )
                    else:
                        bucket = self._series.setdefault(name, {}).setdefault(
                            day,
                            {
                                "rx": 0,
                                "tx": 0,
                                "samples": 0,
                                "first_ts": int(now),
                                "last_ts": int(now),
                            },
                        )
                        bucket["rx"] += d_recv
                        bucket["tx"] += d_sent
                        bucket["samples"] += 1
                        if not bucket.get("first_ts"):
                            bucket["first_ts"] = int(now)
                        bucket["last_ts"] = int(now)
                self._prev[name] = (recv, sent)

            self._samples += 1
            self._updated_at = int(now)
            if now - self._last_flush >= max(1.0, settings.NETWORK_USAGE_FLUSH_SECONDS):
                self._persist_locked(now)

    # ------------------------------------------------------------------ #
    # Lifecycle
    # ------------------------------------------------------------------ #
    async def start(self) -> None:
        if not settings.NETWORK_USAGE_ENABLED:
            logger.info("event=network_usage_disabled")
            return
        if self._task is not None and not self._task.done():
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            logger.error("event=network_usage_start_failed reason=no_running_loop")
            return
        # Establish the baseline immediately so the first interval is not lost.
        try:
            await asyncio.to_thread(self.sample)
        except Exception as exc:
            logger.warning("event=network_usage_prime_failed error=%s", exc)
        self._stopping = False
        self._task = loop.create_task(self._run())
        logger.info(
            "event=network_usage_started interval=%ss tz=%s monitored=%s",
            settings.NETWORK_USAGE_SAMPLE_SECONDS,
            self._tz_name,
            settings.NETWORK_MONITORED_INTERFACE or "physical",
        )

    async def stop(self) -> None:
        self._stopping = True
        task = self._task
        self._task = None
        if task is not None:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass
        try:
            await asyncio.to_thread(self.flush)
        except Exception as exc:
            logger.warning("event=network_usage_flush_failed error=%s", exc)

    async def _run(self) -> None:
        interval = max(1.0, settings.NETWORK_USAGE_SAMPLE_SECONDS)
        while not self._stopping:
            started = time.time()
            try:
                await asyncio.to_thread(self.sample)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("event=network_usage_cycle_failed error=%s", exc)
            elapsed = time.time() - started
            await asyncio.sleep(max(0.5, interval - elapsed))

    # ------------------------------------------------------------------ #
    # Queries
    # ------------------------------------------------------------------ #
    def _query_targets(self, interface: Optional[str]) -> List[str]:
        if interface and interface not in ("physical", "aggregate", "default", "all"):
            return [interface]
        monitored = settings.NETWORK_MONITORED_INTERFACE
        if monitored and interface != "all":
            return [monitored]
        if interface == "all":
            return list(self._series.keys())
        return [n for n in self._series.keys() if is_physical(n)]

    def _day_total_locked(self, day: str, targets: List[str]) -> Optional[dict]:
        rx = tx = samples = 0
        first_ts = None
        last_ts = None
        found = False
        for name in targets:
            bucket = self._series.get(name, {}).get(day)
            if bucket:
                found = True
                rx += bucket["rx"]
                tx += bucket["tx"]
                samples += bucket["samples"]
                ft = bucket.get("first_ts") or 0
                lt = bucket.get("last_ts") or 0
                if ft:
                    first_ts = ft if first_ts is None else min(first_ts, ft)
                if lt:
                    last_ts = lt if last_ts is None else max(last_ts, lt)
        if not found:
            return None
        return {
            "rx": rx,
            "tx": tx,
            "samples": samples,
            "first_ts": first_ts,
            "last_ts": last_ts,
        }

    def _accounting_since_locked(self) -> Optional[str]:
        days = [d for series in self._series.values() for d in series.keys()]
        return min(days) if days else None

    def daily(self, start: date, end: date, interface: Optional[str] = None) -> dict:
        with self._lock:
            targets = self._query_targets(interface)
            days = []
            cursor = start
            while cursor <= end:
                ds = cursor.isoformat()
                total = self._day_total_locked(ds, targets)
                if total is None:
                    days.append(
                        {
                            "date": ds,
                            "rx_bytes": None,
                            "tx_bytes": None,
                            "total_bytes": None,
                            "samples": 0,
                            "first_sample_at": None,
                            "last_sample_at": None,
                            "status": "no_data",
                        }
                    )
                else:
                    days.append(
                        {
                            "date": ds,
                            "rx_bytes": total["rx"],
                            "tx_bytes": total["tx"],
                            "total_bytes": total["rx"] + total["tx"],
                            "samples": total["samples"],
                            "first_sample_at": total["first_ts"],
                            "last_sample_at": total["last_ts"],
                            "status": "ok",
                        }
                    )
                cursor += timedelta(days=1)

            ok = [d for d in days if d["status"] == "ok"]
            totals = {
                "rx_bytes": sum(d["rx_bytes"] for d in ok),
                "tx_bytes": sum(d["tx_bytes"] for d in ok),
                "total_bytes": sum(d["total_bytes"] for d in ok),
            }
            return {
                "available": True,
                "unit": "bytes",
                "timezone": self._tz_name,
                "start": start.isoformat(),
                "end": end.isoformat(),
                "interface": interface or (settings.NETWORK_MONITORED_INTERFACE or "physical"),
                "interfaces": targets,
                "days": days,
                "totals": totals,
                "missing_days": [d["date"] for d in days if d["status"] != "ok"],
                "accounting_since": self._accounting_since_locked(),
                "generated_at": int(time.time()),
            }

    def monthly(self, start: date, end: date, interface: Optional[str] = None) -> dict:
        with self._lock:
            targets = self._query_targets(interface)
            months = []
            cursor = start.replace(day=1)
            end_month = end.replace(day=1)
            while cursor <= end_month:
                key = cursor.strftime("%Y-%m")
                rx = tx = 0
                present_days = set()
                for name in targets:
                    for day, bucket in self._series.get(name, {}).items():
                        if day.startswith(key):
                            rx += bucket["rx"]
                            tx += bucket["tx"]
                            present_days.add(day)
                days_with_data = len(present_days)
                months.append(
                    {
                        "month": key,
                        "rx_bytes": rx,
                        "tx_bytes": tx,
                        "total_bytes": rx + tx,
                        "days_with_data": days_with_data,
                        "status": "ok" if days_with_data else "no_data",
                    }
                )
                if cursor.month == 12:
                    cursor = cursor.replace(year=cursor.year + 1, month=1)
                else:
                    cursor = cursor.replace(month=cursor.month + 1)

            ok = [m for m in months if m["status"] == "ok"]
            totals = {
                "rx_bytes": sum(m["rx_bytes"] for m in ok),
                "tx_bytes": sum(m["tx_bytes"] for m in ok),
                "total_bytes": sum(m["total_bytes"] for m in ok),
            }
            return {
                "available": True,
                "unit": "bytes",
                "timezone": self._tz_name,
                "start": start.strftime("%Y-%m"),
                "end": end.strftime("%Y-%m"),
                "interface": interface or (settings.NETWORK_MONITORED_INTERFACE or "physical"),
                "interfaces": targets,
                "months": months,
                "totals": totals,
                "accounting_since": self._accounting_since_locked(),
                "generated_at": int(time.time()),
            }

    def top(
        self, start: date, end: date, interface: Optional[str] = None, limit: int = 10
    ) -> dict:
        with self._lock:
            targets = self._query_targets(interface)
            rows = []
            cursor = start
            while cursor <= end:
                ds = cursor.isoformat()
                total = self._day_total_locked(ds, targets)
                if total is not None:
                    rows.append(
                        {
                            "date": ds,
                            "rx_bytes": total["rx"],
                            "tx_bytes": total["tx"],
                            "total_bytes": total["rx"] + total["tx"],
                        }
                    )
                cursor += timedelta(days=1)
            rows.sort(key=lambda r: r["total_bytes"], reverse=True)
            top_rows = rows[: max(1, limit)]
            return {
                "available": True,
                "unit": "bytes",
                "timezone": self._tz_name,
                "start": start.isoformat(),
                "end": end.isoformat(),
                "interface": interface or (settings.NETWORK_MONITORED_INTERFACE or "physical"),
                "interfaces": targets,
                "limit": limit,
                "days": top_rows,
                "generated_at": int(time.time()),
            }

    def interface_catalog(self) -> List[dict]:
        """Monitorable interfaces for selectors (aggregate pseudo-entry first).

        Only physical interfaces (or the explicitly configured monitored
        interface) are listed, so an ``all`` aggregate never double-counts
        loopback, bridges or veth pairs.
        """
        try:
            import network_metrics

            snap = network_metrics.network_metrics()
        except Exception:
            network_metrics = None
            snap = {}
        live = {e.get("name"): e for e in snap.get("interfaces", [])}
        monitored = settings.NETWORK_MONITORED_INTERFACE
        with self._lock:
            recorded = set(self._series.keys())
        names = {n for n in (set(live.keys()) | recorded) if n}
        if monitored:
            names.add(monitored)

        items = [
            {
                "name": "all",
                "label": "All interfaces",
                "kind": "aggregate",
                "is_aggregate": True,
                "is_physical": False,
                "state": "up",
                "up": True,
                "monitored": True,
                "rates": rate_alias(snap.get("rates", {})),
            }
        ]
        for name in sorted(names):
            entry = live.get(name, {})
            is_phys = bool(entry.get("is_physical", is_physical(name)))
            if monitored:
                if name != monitored:
                    continue
            elif not is_phys:
                continue
            kind = entry.get("kind")
            if kind is None and network_metrics is not None:
                kind = network_metrics.interface_kind(name)
            items.append(
                {
                    "name": name,
                    "label": name,
                    "kind": kind or ("ethernet" if is_phys else "other"),
                    "is_aggregate": False,
                    "is_physical": is_phys,
                    "state": entry.get("state", "unknown"),
                    "up": entry.get("state") == "up",
                    "monitored": True,
                    "ipv4": entry.get("ipv4"),
                    "ipv6": entry.get("ipv6"),
                    "received_bytes": entry.get("bytes_recv"),
                    "transmitted_bytes": entry.get("bytes_sent"),
                    "rates": rate_alias(entry.get("rates", {})),
                }
            )
        return items

    def status(self) -> dict:
        with self._lock:
            return {
                "available": True,
                "enabled": settings.NETWORK_USAGE_ENABLED,
                "running": self._task is not None and not self._task.done(),
                "timezone": self._tz_name,
                "monitored": settings.NETWORK_MONITORED_INTERFACE or "physical",
                "sample_seconds": settings.NETWORK_USAGE_SAMPLE_SECONDS,
                "flush_seconds": settings.NETWORK_USAGE_FLUSH_SECONDS,
                "retention_days": settings.NETWORK_USAGE_RETENTION_DAYS,
                "interfaces": list(self._series.keys()),
                "days_recorded": sum(len(v) for v in self._series.values()),
                "accounting_since": self._accounting_since_locked(),
                "samples": self._samples,
                "skipped_windows": self._skipped,
                "errors": self._errors,
                "last_flush": int(self._last_flush) if self._last_flush else None,
                "updated_at": self._updated_at,
                "created_at": self._created_at,
                "timestamp": int(time.time()),
            }

    def reset(self) -> None:
        """Clear in-memory state and remove the usage file (used by tests)."""
        with self._lock:
            self._series = {}
            self._prev = {}
            self._last_flush = 0.0
            self._samples = 0
            self._skipped = 0
            self._errors = 0
            try:
                USAGE_FILE.unlink(missing_ok=True)
            except OSError:
                pass


usage = NetworkUsage()
