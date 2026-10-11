"""Live network metrics.

Two properties matter for correctness:

* Throughput must be derived from cumulative counter **deltas over actual
  elapsed time** (monotonic clock), never from a single reading.
* The aggregate offered to clients must **not double-count traffic**. On a
  Docker host the same bytes traverse a ``veth`` pair, a bridge and the
  physical NIC, so summing every interface inflates the value several times
  over. The default aggregate therefore sums physical NICs only.

Unmeasurable values are reported as ``null`` (never a fabricated ``0.0``): the
first sample, a counter reset and a vanished interface all yield ``null``.
"""

import logging
import socket
import threading
import time
from typing import Dict, List, Optional, Tuple

import psutil

from config import settings

logger = logging.getLogger("zentra.network")

_PHYSICAL_KINDS = ("ethernet", "wifi")
_BRIDGE_PREFIXES = ("docker", "br-", "veth", "virbr", "vmnet", "vnet")
_VPN_PREFIXES = ("tailscale", "wg", "tun", "tap", "ppp")


def interface_kind(name: str) -> str:
    """Classify an interface name (kernel naming convention based)."""
    if name == "lo":
        return "loopback"
    if name.startswith(_BRIDGE_PREFIXES):
        return "bridge"
    if name.startswith(_VPN_PREFIXES):
        return "vpn"
    if name.startswith(("en", "eth")):
        return "ethernet"
    if name.startswith("wl"):
        return "wifi"
    if name.startswith("bond"):
        return "bond"
    return "other"


def is_physical(name: str) -> bool:
    return interface_kind(name) in _PHYSICAL_KINDS


# --------------------------------------------------------------------------- #
# Counter sampling
# --------------------------------------------------------------------------- #
_rate_lock = threading.Lock()
# name -> (bytes_recv, bytes_sent) at the previous sample
_prev_counters: Dict[str, Tuple[int, int]] = {}
_prev_at: Optional[float] = None
# Last computed per-interface rates + metadata, reused inside the min interval.
_cache: Optional[dict] = None


def _read_counters():
    try:
        return psutil.net_io_counters(pernic=True)
    except Exception as exc:  # pragma: no cover - defensive
        logger.error("event=net_io_failed error=%s", exc)
        return None


def _sample_counters() -> dict:
    """Return cached per-interface rates (see module docstring)."""
    global _prev_counters, _prev_at, _cache
    now = time.monotonic()
    with _rate_lock:
        min_interval = max(0.0, settings.NETWORK_MIN_SAMPLE_INTERVAL)
        if (
            _cache is not None
            and _prev_at is not None
            and (now - _prev_at) < min_interval
        ):
            return _cache

        io = _read_counters()
        if io is None:
            _cache = {"interfaces": {}, "elapsed_seconds": None, "available": False}
            return _cache

        elapsed = None if _prev_at is None else (now - _prev_at)
        if elapsed is not None and elapsed <= 0:
            elapsed = None

        per: Dict[str, dict] = {}
        for name, cur in io.items():
            recv = int(cur.bytes_recv)
            sent = int(cur.bytes_sent)
            entry = {
                "name": name,
                "kind": interface_kind(name),
                "bytes_recv": recv,
                "bytes_sent": sent,
                "download_bytes_per_sec": None,
                "upload_bytes_per_sec": None,
                "null_reason": None,
            }
            prev = _prev_counters.get(name)
            if prev is None:
                entry["null_reason"] = "first_sample"
            elif elapsed is None:
                entry["null_reason"] = "no_elapsed"
            else:
                d_recv = recv - prev[0]
                d_sent = sent - prev[1]
                if d_recv < 0 or d_sent < 0:
                    # Counter reset (reboot / interface re-created): skip this
                    # window rather than inventing a negative or zero rate.
                    entry["null_reason"] = "counter_reset"
                else:
                    entry["download_bytes_per_sec"] = round(d_recv / elapsed, 1)
                    entry["upload_bytes_per_sec"] = round(d_sent / elapsed, 1)

            per[name] = entry

        _prev_counters = {
            n: (int(c.bytes_recv), int(c.bytes_sent)) for n, c in io.items()
        }
        _prev_at = now
        _cache = {
            "interfaces": per,
            "elapsed_seconds": round(elapsed, 3) if elapsed is not None else None,
            "available": True,
        }
        return _cache


# --------------------------------------------------------------------------- #
# Interface inventory
# --------------------------------------------------------------------------- #
def _address_family(family) -> str:
    try:
        name = family.name
    except Exception:
        name = str(family)
    if name in ("AF_INET", "AddressFamily.AF_INET"):
        return "ipv4"
    if name in ("AF_INET6", "AddressFamily.AF_INET6"):
        return "ipv6"
    return name.lower()


def interfaces() -> List[dict]:
    result: List[dict] = []
    try:
        addrs = psutil.net_if_addrs()
    except Exception as exc:
        logger.error("event=net_if_addrs_failed error=%s", exc)
        addrs = {}
    try:
        stats = psutil.net_if_stats()
    except Exception as exc:
        logger.error("event=net_if_stats_failed error=%s", exc)
        stats = {}
    try:
        io = psutil.net_io_counters(pernic=True) or {}
    except Exception as exc:
        logger.error("event=net_io_failed error=%s", exc)
        io = {}

    for name in sorted(set(list(addrs.keys()) + list(io.keys()))):
        entry_addrs = addrs.get(name, [])
        stat = stats.get(name)
        counters = io.get(name)
        addresses = []
        ipv4 = None
        ipv6 = None
        for addr in entry_addrs:
            family = _address_family(addr.family)
            item = {
                "family": family,
                "address": addr.address,
                "netmask": addr.netmask,
            }
            addresses.append(item)
            if family == "ipv4" and ipv4 is None:
                ipv4 = addr.address
            elif family == "ipv6" and ipv6 is None:
                ipv6 = addr.address

        entry = {
            "name": name,
            "kind": interface_kind(name),
            "is_physical": is_physical(name),
            "addresses": addresses,
            "ip_address": ipv4,
            "ipv4": ipv4,
            "ipv6": ipv6,
            "state": "up" if (stat and stat.isup) else "down",
            "is_up": bool(stat and stat.isup),
            "speed_mbps": int(stat.speed) if stat and stat.speed else None,
            "mtu": int(stat.mtu) if stat else None,
        }
        if counters:
            entry.update(
                {
                    "bytes_sent": counters.bytes_sent,
                    "bytes_recv": counters.bytes_recv,
                    "packets_sent": counters.packets_sent,
                    "packets_recv": counters.packets_recv,
                    "errin": counters.errin,
                    "errout": counters.errout,
                    "dropin": counters.dropin,
                    "dropout": counters.dropout,
                }
            )
        result.append(entry)
    return result


def totals() -> dict:
    """Legacy all-interface cumulative totals (kept for compatibility).

    This intentionally counts every interface (including loopback, bridges and
    veth pairs), matching the historical contract. Use the ``aggregate`` block
    for a value that does not double-count traffic.
    """
    try:
        counters = psutil.net_io_counters()
    except Exception as exc:
        logger.error("event=net_total_failed error=%s", exc)
        return {}
    if counters is None:
        return {}
    return {
        "bytes_sent": counters.bytes_sent,
        "bytes_recv": counters.bytes_recv,
        "packets_sent": counters.packets_sent,
        "packets_recv": counters.packets_recv,
        "errin": counters.errin,
        "errout": counters.errout,
        "dropin": counters.dropin,
        "dropout": counters.dropout,
    }


# --------------------------------------------------------------------------- #
# Aggregate selection
# --------------------------------------------------------------------------- #
def _default_route_interface() -> Optional[str]:
    try:
        with open("/proc/net/route", "r", encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return None
    for line in lines[1:]:
        parts = line.split()
        if len(parts) < 4:
            continue
        iface, dest, _gw, flags = parts[0], parts[1], parts[2], parts[3]
        try:
            if dest == "00000000" and (int(flags, 16) & 0x2):
                return iface
        except ValueError:
            continue
    return None


def _member_names(ifaces: List[dict]) -> List[str]:
    include = set(settings.NETWORK_INCLUDE_INTERFACES)
    exclude = set(settings.NETWORK_EXCLUDE_INTERFACES)
    up = [i for i in ifaces if i.get("state") == "up"]
    if include:
        return [i["name"] for i in up if i["name"] in include and i["name"] not in exclude]

    mode = settings.NETWORK_AGGREGATE
    if mode == "all":
        return [i["name"] for i in up if i["name"] not in exclude]
    if mode == "primary":
        primary = _default_route_interface()
        if primary and primary in {i["name"] for i in up} and primary not in exclude:
            return [primary]
        physical = [i["name"] for i in up if i.get("is_physical") and i["name"] not in exclude]
        return physical[:1]
    # "physical" (default)
    return [
        i["name"]
        for i in up
        if i.get("is_physical") and i["name"] not in exclude
    ]


def _aggregate_rates(members: List[str], rates_by_name: Dict[str, dict]) -> dict:
    """Sum member rates; return null unless *every* member is measurable."""
    download = 0.0
    upload = 0.0
    reason = None
    if not members:
        reason = "no_interfaces"
    else:
        for name in members:
            entry = rates_by_name.get(name)
            if entry is None:
                reason = "unavailable"
                break
            d = entry.get("download_bytes_per_sec")
            u = entry.get("upload_bytes_per_sec")
            if d is None or u is None:
                reason = entry.get("null_reason") or "unavailable"
                break
            download += d
            upload += u
    if reason is None:
        return {
            "download_bytes_per_sec": round(download, 1),
            "upload_bytes_per_sec": round(upload, 1),
            "measured": True,
            "null_reason": None,
        }
    return {
        "download_bytes_per_sec": None,
        "upload_bytes_per_sec": None,
        "measured": False,
        "null_reason": reason,
    }


def _aggregate_totals(members: List[str], ifaces: List[dict]) -> dict:
    by_name = {i["name"]: i for i in ifaces}
    recv = 0
    sent = 0
    for name in members:
        entry = by_name.get(name)
        if not entry:
            continue
        recv += int(entry.get("bytes_recv", 0) or 0)
        sent += int(entry.get("bytes_sent", 0) or 0)
    return {"bytes_recv": recv, "bytes_sent": sent, "unit": "bytes"}


def _virtual_excluded(ifaces: List[dict]) -> List[str]:
    return [i["name"] for i in ifaces if not i.get("is_physical")]


def network_metrics() -> dict:
    sample = _sample_counters()
    rates_by_name = sample.get("interfaces", {})
    ifaces = interfaces()

    for entry in ifaces:
        r = rates_by_name.get(entry["name"], {})
        d = r.get("download_bytes_per_sec")
        u = r.get("upload_bytes_per_sec")
        entry["rates"] = {
            "download_bytes_per_sec": d,
            "upload_bytes_per_sec": u,
            "null_reason": r.get("null_reason"),
        }
        entry["download_bytes_per_sec"] = d
        entry["upload_bytes_per_sec"] = u
        entry.setdefault("is_up", entry.get("state") == "up")
        entry.setdefault("ip_address", entry.get("ipv4"))

    members = _member_names(ifaces)
    agg_rates = _aggregate_rates(members, rates_by_name)
    mode = "custom" if settings.NETWORK_INCLUDE_INTERFACES else settings.NETWORK_AGGREGATE
    ts = int(time.time())

    # The Android client models ``NetworkRates.interfaces`` as a
    # ``Map<String, InterfaceRateItem>`` (per-interface rates), not a list of
    # names. Members are moved to ``included_interfaces`` (which the client maps
    # to ``includedInterfaces``); a plain list under ``interfaces`` would make
    # Gson throw "Expected BEGIN_OBJECT but was BEGIN_ARRAY".
    per_interface_rates = {
        entry["name"]: {
            "download_bytes_per_sec": entry["rates"]["download_bytes_per_sec"],
            "upload_bytes_per_sec": entry["rates"]["upload_bytes_per_sec"],
            "is_up": entry.get("is_up", entry.get("state") == "up"),
            "speed_mbps": entry.get("speed_mbps"),
        }
        for entry in ifaces
    }

    rates = {
        "download_bytes_per_sec": agg_rates["download_bytes_per_sec"],
        "upload_bytes_per_sec": agg_rates["upload_bytes_per_sec"],
        "download_speed": agg_rates["download_bytes_per_sec"],
        "upload_speed": agg_rates["upload_bytes_per_sec"],
        "raw_download_bytes_per_sec": agg_rates["download_bytes_per_sec"],
        "raw_upload_bytes_per_sec": agg_rates["upload_bytes_per_sec"],
        "unit": "bytes_per_sec",
        "mode": mode,
        "interfaces": per_interface_rates,
        "included_interfaces": members,
        "available": bool(sample.get("available", False)),
        "measured": agg_rates["measured"],
        "null_reason": agg_rates["null_reason"],
        "elapsed_seconds": sample.get("elapsed_seconds"),
        "timestamp": ts,
    }

    primary_name = _default_route_interface()
    if primary_name is None:
        physical_up = [i["name"] for i in ifaces if i.get("is_physical") and i.get("state") == "up"]
        primary_name = physical_up[0] if physical_up else None
    primary_rates = rates_by_name.get(primary_name, {}) if primary_name else {}
    primary = {
        "interface": primary_name,
        "available": primary_name is not None and sample.get("available", False),
        "rates": {
            "download_bytes_per_sec": primary_rates.get("download_bytes_per_sec"),
            "upload_bytes_per_sec": primary_rates.get("upload_bytes_per_sec"),
            "null_reason": primary_rates.get("null_reason"),
            "unit": "bytes_per_sec",
        },
        "totals": _aggregate_totals([primary_name] if primary_name else [], ifaces),
    }

    total = totals()
    down_bps = agg_rates["download_bytes_per_sec"]
    up_bps = agg_rates["upload_bytes_per_sec"]

    return {
        "interfaces": ifaces,
        "total": total,
        "rates": rates,
        # Flat aliases consumed by the Android NetworkMetrics model.
        "download_speed_bps": int(down_bps) if down_bps is not None else 0,
        "upload_speed_bps": int(up_bps) if up_bps is not None else 0,
        "total_recv_bytes": int(total.get("bytes_recv", 0) or 0),
        "total_sent_bytes": int(total.get("bytes_sent", 0) or 0),
        "calculatedDownloadBps": int(down_bps) if down_bps is not None else None,
        "calculatedUploadBps": int(up_bps) if up_bps is not None else None,
        "aggregate": {
            "mode": mode,
            "interfaces": members,
            "excluded": _virtual_excluded(ifaces),
            "totals": _aggregate_totals(members, ifaces),
            "rates": dict(rates),
        },
        "primary": primary,
        "hostname_ips": _hostname_ips(),
        "timestamp": ts,
    }


def _hostname_ips() -> dict:
    lan: List[str] = []
    tailscale: List[str] = []
    for iface in interfaces():
        for addr in iface.get("addresses", []):
            if addr.get("family") != "ipv4":
                continue
            ip = addr.get("address", "")
            if ip.startswith("100.64.") or ip.startswith("100."):
                tailscale.append(ip)
            elif ip.startswith("192.168.") or ip.startswith("10.") or ip.startswith("172."):
                lan.append(ip)
    return {"lan": lan, "tailscale": tailscale}


def reset_rate_baseline() -> None:
    """Clear cached rates/baseline (used by tests)."""
    global _prev_counters, _prev_at, _cache
    with _rate_lock:
        _prev_counters = {}
        _prev_at = None
        _cache = None
