import logging
import os
import re
import shutil
import socket
import subprocess
import time
from typing import Dict, List, Optional

from config import settings

logger = logging.getLogger("zentra.network_diagnostics")

_SS_PROCESS_RE = re.compile(r'users:\(\("([^"]+)",pid=(\d+)')
_UFW_POLICY_RE = re.compile(r"(DEFAULT_\w+_POLICY)\s*=\s*\"([^\"]*)\"")

_MANAGER_UNITS = {
    "NetworkManager": "NetworkManager.service",
    "systemd-networkd": "systemd-networkd.service",
}


def _base() -> dict:
    return {
        "timestamp": int(time.time()),
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime()),
    }


def _run(args: List[str], timeout: Optional[float] = None) -> dict:
    """Run a fixed, non-shell command. Never pass user-controlled input."""
    timeout = timeout or settings.COMMAND_TIMEOUT_SECONDS
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return {
            "rc": proc.returncode,
            "stdout": proc.stdout or "",
            "stderr": proc.stderr or "",
            "error": None,
        }
    except FileNotFoundError:
        return {"rc": None, "stdout": "", "stderr": "", "error": "not_found"}
    except subprocess.TimeoutExpired:
        return {"rc": None, "stdout": "", "stderr": "", "error": "timeout"}
    except Exception as exc:  # pragma: no cover - defensive
        return {"rc": None, "stdout": "", "stderr": "", "error": str(exc)[:200]}


def _read_file(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


# --------------------------------------------------------------------------- #
# Network manager detection
# --------------------------------------------------------------------------- #
def _unit_active(unit: str) -> Optional[str]:
    if not shutil.which("systemctl"):
        return None
    result = _run(["systemctl", "is-active", unit], timeout=settings.COMMAND_TIMEOUT_SECONDS)
    if result["error"]:
        return None
    state = result["stdout"].strip()
    return state or None


def _detected_managers() -> Dict[str, bool]:
    netplan_dir = "/etc/netplan"
    try:
        netplan_present = os.path.isdir(netplan_dir) and bool(os.listdir(netplan_dir))
    except OSError:
        netplan_present = False
    return {
        "NetworkManager": bool(shutil.which("nmcli"))
        or os.path.isdir("/etc/NetworkManager")
        or os.path.exists("/lib/systemd/system/NetworkManager.service")
        or os.path.exists("/usr/lib/systemd/system/NetworkManager.service"),
        "systemd-networkd": bool(shutil.which("networkctl"))
        or os.path.exists("/lib/systemd/system/systemd-networkd.service")
        or os.path.exists("/usr/lib/systemd/system/systemd-networkd.service"),
        "netplan": netplan_present,
    }


def detect_network_manager() -> dict:
    detected = _detected_managers()
    if detected["NetworkManager"]:
        name = "NetworkManager"
    elif detected["systemd-networkd"]:
        name = "systemd-networkd"
    elif detected["netplan"]:
        name = "netplan"
    else:
        name = "unknown"

    active = None
    unit = _MANAGER_UNITS.get(name)
    if unit:
        active = _unit_active(unit)

    return {
        "name": name,
        "active": active,
        "detected": detected,
        "restart_supported": name == "NetworkManager",
        "restart_enabled": settings.ALLOW_NETWORK_RESTART,
    }


# --------------------------------------------------------------------------- #
# Status / interfaces
# --------------------------------------------------------------------------- #
def _hex_to_ip(value: str) -> Optional[str]:
    try:
        raw = bytes.fromhex(value)
        return ".".join(str(b) for b in reversed(raw))
    except (ValueError, TypeError):
        return None


def _default_route() -> dict:
    text = _read_file("/proc/net/route")
    if not text:
        return {}
    lines = text.splitlines()
    for line in lines[1:]:
        parts = line.split()
        if len(parts) < 4:
            continue
        iface, dest, gateway, flags = parts[0], parts[1], parts[2], parts[3]
        try:
            flag_bits = int(flags, 16)
            metric = int(parts[6]) if len(parts) > 6 else 0
        except ValueError:
            continue
        if dest == "00000000" and (flag_bits & 0x2):
            return {
                "interface": iface,
                "gateway": _hex_to_ip(gateway),
                "metric": metric,
                "source": "proc",
            }
    return {}


def _dns_config() -> dict:
    servers: List[str] = []
    search: List[str] = []
    text = _read_file("/etc/resolv.conf")
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] == "nameserver" and len(parts) >= 2:
            servers.append(parts[1])
        elif parts[0] == "search" and len(parts) >= 2:
            search.extend(parts[1:])
    return {"servers": servers, "search": search}


def network_status() -> dict:
    import network_metrics

    route = _default_route()
    iface = network_metrics.interfaces()
    result = _base()
    result.update(
        {
            "hostname": socket.gethostname(),
            "manager": detect_network_manager(),
            "primary_interface": route.get("interface"),
            "gateway": route.get("gateway"),
            "default_route": route,
            "addresses": network_metrics._hostname_ips(),
            "dns": _dns_config(),
            "interfaces_total": len(iface),
            "interfaces_up": sum(1 for i in iface if i.get("state") == "up"),
        }
    )
    return result


def _classify(name: str) -> str:
    import network_metrics

    return network_metrics.interface_kind(name)


def network_interfaces() -> dict:
    import network_metrics

    items = []
    for iface in network_metrics.interfaces():
        item = dict(iface)
        item["kind"] = _classify(iface.get("name", ""))
        item["is_physical"] = item["kind"] in ("ethernet", "wifi")
        items.append(item)

    result = _base()
    result.update(
        {
            "available": True,
            "count": len(items),
            "up": sum(1 for i in items if i.get("state") == "up"),
            "interfaces": items,
        }
    )
    return result


# --------------------------------------------------------------------------- #
# Listening ports
# --------------------------------------------------------------------------- #
def _split_addr(value: str):
    if value.startswith("["):
        host, _, port = value.rpartition(":")
        return host.strip("[]"), _int_or_none(port)
    if ":" in value:
        host, _, port = value.rpartition(":")
        return host, _int_or_none(port)
    return value, None


def _int_or_none(value) -> Optional[int]:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _service_name(port: Optional[int], protocol: str) -> Optional[str]:
    if port is None:
        return None
    try:
        return socket.getservbyport(port, protocol)
    except OSError:
        return None


def _exposure(address: Optional[str]) -> str:
    """Where a listening socket is bound (not whether it is reachable)."""
    if not address:
        return "unknown"
    if address in ("127.0.0.1", "::1") or address.startswith("127."):
        return "loopback"
    if address in ("0.0.0.0", "::", "*"):
        return "all-interfaces"
    return "specific-address"


def _enrich(entry: dict) -> dict:
    entry["address_family"] = entry.get("ip_version")
    entry["exposure"] = _exposure(entry.get("address"))
    entry["service"] = _service_name(entry.get("port"), entry.get("protocol", "tcp"))
    return entry


def _parse_ss(text: str):
    entries = []
    restricted = False
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 5)
        if len(parts) < 5:
            continue
        netid = parts[0].lower()
        state = parts[1]
        host, port = _split_addr(parts[4])
        process = parts[5] if len(parts) > 5 else ""
        match = _SS_PROCESS_RE.search(process)
        if match:
            pinfo = {"name": match.group(1), "pid": int(match.group(2))}
        else:
            pinfo = None
            restricted = True
        entries.append(
            {
                "protocol": "tcp" if netid.startswith("tcp") else "udp",
                "state": state,
                "address": host,
                "port": port,
                "ip_version": "ipv6" if ":" in host else "ipv4",
                "process": pinfo,
            }
        )
    return entries, restricted


def _ports_via_ss():
    for args in (
        ["ss", "-H", "-l", "-t", "-u", "-n", "-p"],
        ["ss", "-l", "-t", "-u", "-n", "-p"],
    ):
        result = _run(args, timeout=settings.COMMAND_TIMEOUT_SECONDS)
        if result["error"] == "not_found":
            return None, "not_found"
        if result["rc"] == 0:
            return result["stdout"], None
    return None, "failed"


def _proc_table(path: str, protocol: str, listen_state: str):
    text = _read_file(path)
    entries = []
    if not text:
        return entries
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 4:
            continue
        try:
            local, state = parts[1], parts[3]
        except IndexError:
            continue
        if protocol == "tcp" and state.upper() != listen_state:
            continue
        hex_ip, _, hex_port = local.partition(":")
        if path.endswith("6"):
            ip = _format_ipv6(hex_ip)
        else:
            ip = _hex_to_ip(hex_ip)
        entries.append(
            {
                "protocol": protocol,
                "state": "LISTEN" if protocol == "tcp" else "UNCONN",
                "address": ip,
                "port": _int_or_none(int(hex_port, 16)) if hex_port else None,
                "ip_version": "ipv6" if path.endswith("6") else "ipv4",
                "process": None,
            }
        )
    return entries


def _format_ipv6(hex_ip: str) -> str:
    try:
        raw = bytes.fromhex(hex_ip)
        groups = [
            raw[i] | (raw[i + 1] << 8)
            for i in range(0, 16, 2)
        ]
        return ":".join(f"{g:x}" for g in groups)
    except (ValueError, TypeError):
        return hex_ip


def _ports_via_proc():
    entries = []
    entries += _proc_table("/proc/net/tcp", "tcp", "0A")
    entries += _proc_table("/proc/net/tcp6", "tcp", "0A")
    entries += _proc_table("/proc/net/udp", "udp", "07")
    entries += _proc_table("/proc/net/udp6", "udp", "07")
    return entries


def network_ports(limit: Optional[int] = None) -> dict:
    """Listening TCP/UDP sockets on this host.

    ``listening``/``ports`` list local LISTEN/UNCONN sockets with protocol,
    address, port, state, address family, bind exposure and owning process
    (when permitted). This describes what is *bound locally*; whether a port is
    actually reachable from another device or the internet also depends on the
    firewall and routing. Firewall-allowed ports are reported separately by
    ``GET /api/network/firewall``.
    """
    result = _base()
    tool = None
    entries: List[dict] = []
    restricted = False
    limitations: List[str] = []
    error: Optional[str] = None
    ss_error: Optional[str] = None

    if shutil.which("ss"):
        stdout, ss_error = _ports_via_ss()
        if stdout is not None:
            tool = "ss"
            entries, restricted = _parse_ss(stdout)
    if tool is None:
        tool = "proc"
        entries = _ports_via_proc()
        if ss_error == "not_found":
            limitations.append("ss is not installed; reading sockets from /proc.")
        elif ss_error:
            limitations.append(
                "ss failed (%s); reading sockets from /proc." % ss_error
            )
        limitations.append(
            "Process/service names are unavailable without elevated privileges "
            "when using /proc."
        )

    entries = [e for e in entries if e.get("port") is not None]
    seen = set()
    deduped = []
    for entry in entries:
        key = (entry["protocol"], entry["ip_version"], entry["address"], entry["port"])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(_enrich(entry))
    deduped.sort(key=lambda e: (e["port"], e["protocol"]))

    total = len(deduped)
    limited = deduped[:limit] if limit and limit > 0 else deduped

    if not deduped:
        if ss_error and ss_error != "not_found":
            error = "no sockets reported; ss error: %s" % ss_error
        else:
            limitations.append(
                "No listening sockets were reported. This can be legitimate on a "
                "quiet host, but may also indicate a permissions problem."
            )

    tcp = sum(1 for e in deduped if e["protocol"] == "tcp")
    udp = sum(1 for e in deduped if e["protocol"] == "udp")

    result.update(
        {
            "available": error is None,
            "error": error,
            "tool": tool,
            "counts": {"tcp": tcp, "udp": udp, "total": total},
            "total": total,
            "returned": len(limited),
            "limit": limit,
            "listening": limited,
            "ports": limited,
            "process_info": "restricted" if restricted or tool == "proc" else "available",
            "limitations": limitations,
        }
    )
    return result


# --------------------------------------------------------------------------- #
# Firewall
# --------------------------------------------------------------------------- #
def _parse_ufw(text: str) -> List[dict]:
    rules = []
    started = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("To") and "Action" in stripped:
            started = True
            continue
        if not started:
            continue
        if stripped.replace(" ", "").replace("-", "") == "":
            continue
        parts = stripped.split(None, 2)
        if len(parts) == 3:
            source = parts[2]
            for direction in ("IN ", "OUT ", "FWD "):
                if source.startswith(direction):
                    source = source[len(direction):]
                    break
            rules.append(
                {"to": parts[0], "action": parts[1], "from": source.strip()}
            )
    return rules


def _fw_status(enabled: Optional[bool]) -> str:
    if enabled is True:
        return "active"
    if enabled is False:
        return "inactive"
    return "unknown"


def network_firewall() -> dict:
    """Firewall state for the backend(s) actually installed on this host.

    Reports, per detected system, whether it is installed and whether it is
    active. ``status`` is ``active``/``inactive``/``unknown`` - an undetermined
    check is never reported as ``inactive``. Rule enumeration is best-effort and
    often root-only; ``rules_available`` says whether rules could be read.
    """
    tools = {
        name: (shutil.which(name) or None)
        for name in ("ufw", "firewall-cmd", "nft", "iptables", "ip6tables")
    }
    result = _base()
    backend = None
    enabled: Optional[bool] = None
    policies: Dict[str, str] = {}
    rules: Optional[List] = None
    rules_available = False
    limitations: List[str] = []
    backends: Dict[str, dict] = {}

    if tools["ufw"]:
        backend = "ufw"
        conf = _read_file("/etc/ufw/ufw.conf")
        if conf is not None:
            for line in conf.splitlines():
                line = line.strip()
                if line.upper().startswith("ENABLED="):
                    enabled = line.split("=", 1)[1].strip().strip('"').lower() == "yes"
        default = _read_file("/etc/default/ufw")
        for match in _UFW_POLICY_RE.finditer(default or ""):
            policies[match.group(1)] = match.group(2)
        service_active = _unit_active("ufw")
        if enabled is None and service_active == "active":
            enabled = True
        run = _run(["ufw", "status"], timeout=settings.COMMAND_TIMEOUT_SECONDS)
        if run["rc"] == 0 and run["stdout"].strip():
            rules = _parse_ufw(run["stdout"])
            rules_available = True
        else:
            limitations.append(
                "Enumerating firewall rules requires elevated privileges "
                "(ufw status is root-only); enabled state came from /etc/ufw/ufw.conf "
                "and the ufw systemd service."
            )
        backends["ufw"] = {
            "installed": True,
            "active": enabled,
            "service_active": service_active,
            "rules_available": rules_available,
        }
    elif tools["firewall-cmd"]:
        backend = "firewalld"
        run = _run(["firewall-cmd", "--state"], timeout=settings.COMMAND_TIMEOUT_SECONDS)
        if run["rc"] == 0:
            enabled = run["stdout"].strip() == "running"
        service_active = _unit_active("firewalld")
        if enabled is None and service_active == "active":
            enabled = True
        limitations.append("firewalld rule enumeration is not implemented.")
        backends["firewalld"] = {
            "installed": True,
            "active": enabled,
            "service_active": service_active,
            "rules_available": False,
        }
    elif tools["nft"]:
        backend = "nftables"
        run = _run(["nft", "list", "ruleset"], timeout=settings.COMMAND_TIMEOUT_SECONDS)
        if run["rc"] == 0:
            rules = run["stdout"].splitlines()
            rules_available = True
            enabled = True
        else:
            limitations.append("Reading the nftables ruleset requires elevated privileges.")
        backends["nftables"] = {
            "installed": True,
            "active": enabled,
            "rules_available": rules_available,
        }
    elif tools["iptables"]:
        backend = "iptables"
        run = _run(["iptables", "-S"], timeout=settings.COMMAND_TIMEOUT_SECONDS)
        if run["rc"] == 0:
            rules = run["stdout"].splitlines()
            rules_available = True
            enabled = True
        else:
            limitations.append("Reading iptables rules requires elevated privileges.")
        backends["iptables"] = {
            "installed": True,
            "active": enabled,
            "rules_available": rules_available,
        }
    else:
        limitations.append("No supported firewall tool detected.")

    # Report every other installed system as well (unknown unless proven).
    for name, tool in (("nftables", "nft"), ("iptables", "iptables"), ("ufw", "ufw"), ("firewalld", "firewall-cmd")):
        if name not in backends and tools.get(tool):
            backends[name] = {"installed": True, "active": None, "rules_available": False}

    app_port = settings.PORT
    app_rules = None
    if rules_available and rules is not None:
        app_rules = [r for r in rules if str(app_port) in str(r)]

    result.update(
        {
            "available": backend is not None,
            "installed": backend is not None,
            "backend": backend,
            "enabled": enabled,
            "active": enabled,
            "running": enabled,
            "status": _fw_status(enabled),
            "default_policies": policies,
            "rules_available": rules_available,
            "rules_count": len(rules) if rules is not None else None,
            "rules": rules,
            "application_port": app_port,
            "application_port_rules": app_rules,
            "backends": backends,
            "limitations": limitations,
            "tools": tools,
        }
    )
    return result


# --------------------------------------------------------------------------- #
# Restricted restart
# --------------------------------------------------------------------------- #
def restart_network_manager() -> dict:
    info = detect_network_manager()
    result = _base()

    if info["name"] != "NetworkManager":
        result.update(
            {
                "success": False,
                "supported": False,
                "manager": info,
                "message": (
                    "NetworkManager is not the network manager on this host; "
                    "refusing to restart arbitrary networking. Detected manager: "
                    f"{info['name']}."
                ),
            }
        )
        logger.warning(
            "event=network_restart_refused reason=manager_absent detected=%s",
            info["name"],
        )
        return result

    if not settings.ALLOW_NETWORK_RESTART:
        result.update(
            {
                "success": False,
                "supported": True,
                "enabled": False,
                "manager": info,
                "message": (
                    "Network restart is disabled. Set "
                    "ZENTRA_ALLOW_NETWORK_RESTART=true to opt in."
                ),
            }
        )
        return result

    systemctl = shutil.which("systemctl")
    if systemctl is None:
        result.update(
            {
                "success": False,
                "supported": True,
                "enabled": True,
                "manager": info,
                "message": "systemctl not found; cannot restart NetworkManager.",
            }
        )
        return result

    if os.geteuid() == 0:
        argv = [systemctl, "restart", "NetworkManager.service"]
    elif shutil.which("sudo"):
        # Narrowly scoped mechanism only: a passwordless sudo rule for exactly
        # this command. No shell, no free-form arguments.
        argv = ["sudo", "-n", systemctl, "restart", "NetworkManager.service"]
    else:
        result.update(
            {
                "success": False,
                "supported": True,
                "enabled": True,
                "manager": info,
                "message": (
                    "No permitted privilege mechanism is available to restart "
                    "NetworkManager from the service account; refusing."
                ),
            }
        )
        return result

    run = _run(argv, timeout=max(settings.COMMAND_TIMEOUT_SECONDS, 15.0))
    if run["rc"] == 0:
        logger.warning("event=network_manager_restart_requested result=ok")
        result.update(
            {
                "success": True,
                "supported": True,
                "enabled": True,
                "manager": info,
                "message": "NetworkManager restart accepted.",
            }
        )
    else:
        detail = (run.get("stderr") or run.get("error") or "").strip()[:200]
        logger.error("event=network_manager_restart_failed error=%s", detail)
        result.update(
            {
                "success": False,
                "supported": True,
                "enabled": True,
                "manager": info,
                "message": "Failed to restart NetworkManager.",
                "error": detail or None,
            }
        )
    return result
