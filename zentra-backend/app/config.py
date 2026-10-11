import os
import secrets
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"

load_dotenv(BASE_DIR / ".env")


def _get_str(name: str, default: str = "") -> str:
    value = os.getenv(name)
    # Backward compatibility: existing installs used the OMNIS_ prefix.
    # ZENTRA_ wins when both are present.
    if value is None and name.startswith("ZENTRA_"):
        value = os.getenv("OMNIS_" + name[len("ZENTRA_"):])
    if value is None:
        return default
    return value.strip()


def _get_int(name: str, default: int) -> int:
    try:
        return int(_get_str(name, str(default)))
    except (TypeError, ValueError):
        return default


def _get_float(name: str, default: float) -> float:
    try:
        return float(_get_str(name, str(default)))
    except (TypeError, ValueError):
        return default


def _get_bool(name: str, default: bool) -> bool:
    raw = _get_str(name, "true" if default else "false").lower()
    return raw in ("1", "true", "yes", "on")


def _get_csv(name: str) -> list:
    raw = _get_str(name, "")
    return [item.strip() for item in raw.split(",") if item.strip()]


class Settings:
    APP_NAME = "zentra"
    VERSION = _get_str("ZENTRA_VERSION", "1.0.0")

    HOST = _get_str("ZENTRA_HOST", "0.0.0.0")
    PORT = _get_int("ZENTRA_PORT", 8765)

    JWT_SECRET = _get_str("ZENTRA_JWT_SECRET", "")
    JWT_SECRET_FALLBACK = False
    if not JWT_SECRET:
        JWT_SECRET = secrets.token_hex(32)
        JWT_SECRET_FALLBACK = True
    JWT_ALGORITHM = "HS256"
    JWT_EXPIRE_MINUTES = _get_int("ZENTRA_JWT_EXPIRE_MINUTES", 720)

    ADMIN_USERNAME = _get_str("ZENTRA_ADMIN_USERNAME", "admin")
    INITIAL_PASSWORD = _get_str("ZENTRA_INITIAL_PASSWORD", "")
    # When false, no account is created at startup; the first registered
    # user becomes admin (fresh installs set this in /opt/zentra/.env).
    AUTO_ADMIN = _get_bool("ZENTRA_AUTO_ADMIN", True)

    REQUIRE_AUTH_FOR_READS = _get_bool("ZENTRA_REQUIRE_AUTH_FOR_READS", False)

    CPU_ALERT_PERCENT = _get_float("CPU_ALERT_PERCENT", 90.0)
    MEMORY_ALERT_PERCENT = _get_float("MEMORY_ALERT_PERCENT", 90.0)
    DISK_ALERT_PERCENT = _get_float("DISK_ALERT_PERCENT", 85.0)
    TEMPERATURE_ALERT_C = _get_float("TEMPERATURE_ALERT_C", 80.0)

    ALERT_INTERVAL_SECONDS = _get_float("ALERT_INTERVAL_SECONDS", 10.0)
    ALERT_COOLDOWN_SECONDS = _get_float("ALERT_COOLDOWN_SECONDS", 300.0)
    ALERT_HISTORY_LIMIT = _get_int("ALERT_HISTORY_LIMIT", 500)

    WS_INTERVAL_SECONDS = _get_float("ZENTRA_WS_INTERVAL_SECONDS", 1.5)
    DOCKER_STATS_TTL_SECONDS = _get_float("ZENTRA_DOCKER_STATS_TTL_SECONDS", 5.0)

    # Shared metrics collector / hub. A single background task collects the
    # snapshot once per interval; websockets and the alert engine consume it.
    METRICS_HUB_ENABLED = _get_bool("ZENTRA_METRICS_HUB_ENABLED", True)
    # Per-collector wall-clock budget; a slow collector cannot stall the rest.
    METRICS_COLLECTOR_TIMEOUT_SECONDS = _get_float(
        "ZENTRA_COLLECTOR_TIMEOUT_SECONDS", 8.0
    )
    # Docker stats are heavier, so they get their own (larger) budget and are
    # refreshed on their own cadence. Between refreshes the cached section is
    # reused, so a slow Docker daemon does not slow the whole snapshot cadence.
    METRICS_DOCKER_TIMEOUT_SECONDS = _get_float("ZENTRA_DOCKER_TIMEOUT_SECONDS", 10.0)
    METRICS_DOCKER_REFRESH_SECONDS = _get_float("ZENTRA_DOCKER_REFRESH_SECONDS", 5.0)
    # Snapshot older than this is reported as stale rather than fresh.
    METRICS_STALE_SECONDS = _get_float("ZENTRA_METRICS_STALE_SECONDS", 10.0)
    # Timeout for a single websocket frame send.
    WS_SEND_TIMEOUT_SECONDS = _get_float("ZENTRA_WS_SEND_TIMEOUT_SECONDS", 5.0)
    # Overall budget for the per-container stats sweep in one collection pass.
    DOCKER_STATS_BUDGET_SECONDS = _get_float("ZENTRA_DOCKER_STATS_BUDGET_SECONDS", 5.0)

    # Network diagnostics. Restart is opt-in and refuses unless a scoped
    # privilege mechanism is actually available (see network_diagnostics.py).
    NETWORK_DIAGNOSTICS_ENABLED = _get_bool("ZENTRA_NETWORK_DIAGNOSTICS_ENABLED", True)
    ALLOW_NETWORK_RESTART = _get_bool("ZENTRA_ALLOW_NETWORK_RESTART", False)
    COMMAND_TIMEOUT_SECONDS = _get_float("ZENTRA_COMMAND_TIMEOUT_SECONDS", 5.0)

    # Network throughput. The top-level "rates" describe an aggregate that must
    # not double-count traffic. "physical" sums real NICs only (excludes
    # loopback, bridges, veth, VPNs); "primary" uses the default-route NIC;
    # "all" reproduces the legacy behaviour (counts every interface).
    NETWORK_AGGREGATE = _get_str("ZENTRA_NETWORK_AGGREGATE", "physical").lower()
    # Explicit interface allow-list for the aggregate (overrides NETWORK_AGGREGATE).
    NETWORK_INCLUDE_INTERFACES = _get_csv("ZENTRA_NETWORK_INCLUDE_INTERFACES")
    # Interfaces always excluded from the aggregate.
    NETWORK_EXCLUDE_INTERFACES = _get_csv("ZENTRA_NETWORK_EXCLUDE_INTERFACES")
    # Minimum seconds between counter samples; faster reads reuse the cached
    # rates so a REST call cannot perturb the shared WebSocket baseline.
    NETWORK_MIN_SAMPLE_INTERVAL = _get_float(
        "ZENTRA_NETWORK_MIN_SAMPLE_INTERVAL", 1.0
    )

    # Persistent, date-wise network usage accounting.
    NETWORK_USAGE_ENABLED = _get_bool("ZENTRA_NETWORK_USAGE_ENABLED", True)
    # IANA timezone used to decide day boundaries for daily usage records.
    NETWORK_USAGE_TZ = _get_str("ZENTRA_NETWORK_USAGE_TZ", "UTC")
    # How often cumulative counters are sampled for accounting.
    NETWORK_USAGE_SAMPLE_SECONDS = _get_float(
        "ZENTRA_NETWORK_USAGE_SAMPLE_SECONDS", 5.0
    )
    # How often accumulated totals are flushed to disk (batched, not per tick).
    NETWORK_USAGE_FLUSH_SECONDS = _get_float(
        "ZENTRA_NETWORK_USAGE_FLUSH_SECONDS", 60.0
    )
    # Retention: daily records older than this many days are pruned.
    NETWORK_USAGE_RETENTION_DAYS = _get_int("ZENTRA_NETWORK_USAGE_RETENTION_DAYS", 400)
    # Maximum length of a single usage query window.
    NETWORK_USAGE_MAX_RANGE_DAYS = _get_int("ZENTRA_NETWORK_USAGE_MAX_RANGE_DAYS", 400)
    # Interface whose usage is recorded. Empty = all physical interfaces.
    NETWORK_MONITORED_INTERFACE = _get_str("ZENTRA_NETWORK_MONITORED_INTERFACE", "")

    PROCESS_DEFAULT_LIMIT = _get_int("ZENTRA_PROCESS_DEFAULT_LIMIT", 30)
    PROCESS_MAX_LIMIT = _get_int("ZENTRA_PROCESS_MAX_LIMIT", 200)

    LOGS_DEFAULT_TAIL = _get_int("ZENTRA_LOGS_DEFAULT_TAIL", 200)
    LOGS_MAX_TAIL = _get_int("ZENTRA_LOGS_MAX_TAIL", 5000)

    cors_origins = [
        origin.strip()
        for origin in _get_str("ZENTRA_CORS_ORIGINS", "").split(",")
        if origin.strip()
    ]

    LOG_LEVEL = _get_str("ZENTRA_LOG_LEVEL", "INFO").upper()


settings = Settings()
