import logging
import os
import time
from typing import List

logger = logging.getLogger("zentra.temperature")

THERMAL_ZONE_GLOB = "/sys/class/thermal/thermal_zone*/"
HWMON_GLOB = "/sys/class/hwmon/hwmon*/"


def _read_float(path: str) -> float:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return float(handle.read().strip())


def _psutil_sensors() -> List[dict]:
    sensors: List[dict] = []
    try:
        import psutil
    except ImportError:
        return sensors
    getter = getattr(psutil, "sensors_temperatures", None)
    if getter is None:
        return sensors
    try:
        groups = getter(fahrenheit=False) or {}
    except Exception as exc:
        logger.debug("event=sensors_temperatures_failed error=%s", exc)
        return sensors
    for chip, entries in groups.items():
        for entry in entries or []:
            sensors.append(
                {
                    "source": "psutil",
                    "chip": chip,
                    "label": entry.label or chip,
                    "current_c": round(entry.current, 1) if entry.current is not None else None,
                    "high_c": round(entry.high, 1) if entry.high else None,
                    "critical_c": round(entry.critical, 1) if entry.critical else None,
                }
            )
    return sensors


def _thermal_zone_sensors() -> List[dict]:
    sensors: List[dict] = []
    try:
        zones = sorted(os.listdir("/sys/class/thermal"))
    except OSError:
        return sensors
    for zone in zones:
        if not zone.startswith("thermal_zone"):
            continue
        temp_path = os.path.join("/sys/class/thermal", zone, "temp")
        type_path = os.path.join("/sys/class/thermal", zone, "type")
        try:
            millideg = _read_float(temp_path)
        except (OSError, ValueError):
            continue
        if millideg > 1000:
            celsius = millideg / 1000.0
        else:
            celsius = millideg
        label = zone
        try:
            with open(type_path, "r", encoding="utf-8", errors="replace") as handle:
                label = handle.read().strip() or zone
        except OSError:
            pass
        sensors.append(
            {
                "source": "thermal_zone",
                "chip": "thermal",
                "label": f"{label} ({zone})",
                "current_c": round(celsius, 1),
                "high_c": None,
                "critical_c": None,
            }
        )
    return sensors


def temperature_metrics() -> dict:
    sensors = _psutil_sensors()
    if not sensors:
        sensors = _thermal_zone_sensors()

    now = int(time.time())

    if not sensors:
        return {
            "available": False,
            "sensors": [],
            "max_c": None,
            "package_temp": None,
            "cpu": None,
            "timestamp": now,
        }

    values = [s["current_c"] for s in sensors if s.get("current_c") is not None]
    max_c = max(values) if values else None

    package_temp = None
    for s in sensors:
        if s.get("chip") == "coretemp" and (s.get("label") or "").startswith("Package"):
            package_temp = s.get("current_c")
            break
    if package_temp is None:
        package_temp = max_c

    return {
        "available": bool(values),
        "sensors": sensors,
        "max_c": max_c,
        "package_temp": package_temp,
        "cpu": package_temp,
        "timestamp": now,
    }
