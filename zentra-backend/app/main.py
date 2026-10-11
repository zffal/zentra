import logging
import sys
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Query, Request, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import docker_metrics
import network_diagnostics as netdiag
import network_metrics
import network_usage
import process_metrics
import storage_metrics
import system_metrics
import temperature_metrics
from alerts import engine
from metrics_hub import hub
from auth import (
    _rate_limited,
    authenticate,
    blacklist_token,
    change_password,
    create_token,
    decode_token,
    delete_user,
    ensure_initial_user,
    register_user,
    require_auth,
    require_read,
)
from config import settings
from json_utils import SafeJSONResponse
from websocket import metrics_websocket

logger = logging.getLogger("zentra")


def configure_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            fmt="%(asctime)s level=%(levelname)s logger=%(name)s %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S%z",
        )
    )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(settings.LOG_LEVEL)
    for noisy in ("uvicorn.access",):
        logging.getLogger(noisy).setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "event=startup service=zentra version=%s host=%s port=%s",
        settings.VERSION,
        settings.HOST,
        settings.PORT,
    )
    if settings.JWT_SECRET_FALLBACK:
        logger.warning(
            "event=jwt_secret_generated "
            "message=ZENTRA_JWT_SECRET missing from .env; using an ephemeral secret "
            "(sessions reset on restart)"
        )
    ensure_initial_user()
    await hub.start()
    await network_usage.usage.start()
    engine.start()
    yield
    engine.stop()
    await network_usage.usage.stop()
    await hub.stop()
    logger.info("event=shutdown service=zentra")


app = FastAPI(
    title="Zentra Backend",
    description="Monitoring backend for the Zentra Android application.",
    version=settings.VERSION,
    lifespan=lifespan,
    default_response_class=SafeJSONResponse,
)

if settings.cors_origins:
    allow_credentials = "*" not in settings.cors_origins
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=allow_credentials,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    logger.error(
        "event=api_error path=%s error=%s", request.url.path, exc, exc_info=True
    )
    return JSONResponse(status_code=500, content={"detail": "internal server error"})


class LoginRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=1, max_length=256)


class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=64)
    password: str = Field(..., min_length=6, max_length=256)


class ChangePasswordRequest(BaseModel):
    current_password: str = Field(..., min_length=1, max_length=256)
    new_password: str = Field(..., min_length=6, max_length=256)


@app.get("/api/health", tags=["health"])
async def health():
    collector = hub.status()
    return {
        "status": "ok" if not collector.get("stale", False) else "degraded",
        "service": settings.APP_NAME,
        "version": settings.VERSION,
        "hostname": system_metrics.system_info()["hostname"],
        "uptime": int(system_metrics.uptime_seconds()),
        "collector": collector,
        "metrics_stale": collector.get("stale", False),
        "timestamp": int(time.time()),
    }


@app.get("/api", tags=["health"])
async def index():
    return {
        "service": settings.APP_NAME,
        "version": settings.VERSION,
        "endpoints": {
            "health": "/api/health",
            "auth": [
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/logout",
                "/api/auth/me",
                "/api/auth/change-password",
            ],
            "system": [
                "/api/system",
                "/api/system/info",
                "/api/system/cpu",
                "/api/system/memory",
                "/api/system/storage",
                "/api/system/network",
                "/api/system/temperature",
                "/api/system/processes",
            ],
            "docker": [
                "/api/docker/containers",
                "/api/docker/containers/{id}",
                "/api/docker/containers/{id}/logs",
                "/api/docker/containers/{id}/start",
                "/api/docker/containers/{id}/stop",
                "/api/docker/containers/{id}/restart",
            ],
            "network": [
                "/api/network/status",
                "/api/network/interfaces",
                "/api/network/ports",
                "/api/network/firewall",
                "/api/network/restart-manager",
                "/api/network/usage",
                "/api/network/usage/interfaces",
                "/api/network/usage/status",
                "/api/network/usage/daily",
                "/api/network/usage/monthly",
                "/api/network/usage/top",
            ],
            "alerts": [
                "/api/alerts",
                "/api/alerts/recent",
                "/api/alerts/clear-all",
                "/api/alerts/{id}/resolve",
            ],
            "websocket": "/ws/metrics",
            "docs": "/docs",
        },
    }


@app.post("/api/auth/register", tags=["auth"])
async def register(payload: RegisterRequest, request: Request):
    if _rate_limited(request):
        logger.warning(
            "event=register_rate_limited ip=%s", request.client.host if request.client else "?"
        )
        raise HTTPException(status_code=429, detail="too many registration attempts")

    try:
        user = register_user(payload.username, payload.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    token = create_token(user)
    logger.info(
        "event=register_success username=%s ip=%s",
        user["username"],
        request.client.host if request.client else "?",
    )
    return {
        "access_token": token["access_token"],
        "token_type": "bearer",
        "expires_at": token["expires_at"],
        "user": user,
    }


@app.post("/api/auth/login", tags=["auth"])
async def login(payload: LoginRequest, request: Request):
    if _rate_limited(request):
        logger.warning(
            "event=login_rate_limited ip=%s", request.client.host if request.client else "?"
        )
        raise HTTPException(status_code=429, detail="too many login attempts")

    user = authenticate(payload.username, payload.password)
    if user is None:
        logger.warning(
            "event=login_failed username=%s ip=%s",
            payload.username,
            request.client.host if request.client else "?",
        )
        raise HTTPException(status_code=401, detail="invalid credentials")

    token = create_token(user)
    logger.info(
        "event=login_success username=%s ip=%s",
        user["username"],
        request.client.host if request.client else "?",
    )
    return {
        "access_token": token["access_token"],
        "token_type": "bearer",
        "expires_at": token["expires_at"],
        "user": user,
    }


@app.post("/api/auth/logout", tags=["auth"])
async def logout(request: Request, user: dict = Depends(require_auth)):
    header = request.headers.get("Authorization", "")
    token = header[7:].strip() if header.lower().startswith("bearer ") else ""
    if not token:
        token = request.query_params.get("token", "")
    payload = decode_token(token) if token else None
    if payload:
        blacklist_token(payload)
    logger.info("event=logout username=%s", user.get("username"))
    return {"status": "ok"}


@app.get("/api/auth/me", tags=["auth"])
async def me(user: dict = Depends(require_auth)):
    return {
        "authenticated": True,
        "user": user,
        "username": user.get("username", ""),
        "role": user.get("role", "user"),
        "created_at": user.get("created_at"),
    }


@app.post("/api/auth/change-password", tags=["auth"])
async def change_password_endpoint(payload: ChangePasswordRequest, user: dict = Depends(require_auth)):
    try:
        change_password(user["username"], payload.current_password, payload.new_password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"status": "ok"}


@app.get("/api/system", tags=["system"])
async def system_summary(user: Optional[dict] = Depends(require_read)):
    return system_metrics.summary()


@app.get("/api/system/info", tags=["system"])
async def system_info_endpoint(user: Optional[dict] = Depends(require_read)):
    return system_metrics.system_info()


@app.get("/api/system/cpu", tags=["system"])
async def system_cpu(user: Optional[dict] = Depends(require_read)):
    return system_metrics.cpu_metrics()


@app.get("/api/system/memory", tags=["system"])
async def system_memory(user: Optional[dict] = Depends(require_read)):
    return system_metrics.memory_metrics()


@app.get("/api/system/storage", tags=["system"])
async def system_storage(user: Optional[dict] = Depends(require_read)):
    return storage_metrics.storage_metrics()


@app.get("/api/system/network", tags=["system"])
async def system_network(user: Optional[dict] = Depends(require_read)):
    return network_metrics.network_metrics()


@app.get("/api/system/temperature", tags=["system"])
async def system_temperature(user: Optional[dict] = Depends(require_read)):
    return temperature_metrics.temperature_metrics()


@app.get("/api/system/processes", tags=["system"])
async def system_processes(
    sort: Optional[str] = Query(None, pattern="^(cpu|memory|name)$"),
    sort_by: Optional[str] = Query(None, pattern="^(cpu|memory|name)$"),
    limit: int = Query(None, ge=1, le=settings.PROCESS_MAX_LIMIT),
    search: Optional[str] = Query(None, max_length=64),
    user: Optional[dict] = Depends(require_read),
):
    effective_sort = sort_by or sort or "cpu"
    return process_metrics.list_processes(sort=effective_sort, limit=limit, search=search)


def _require_diagnostics() -> None:
    if not settings.NETWORK_DIAGNOSTICS_ENABLED:
        raise HTTPException(status_code=404, detail="network diagnostics disabled")


@app.get("/api/network/status", tags=["network"])
async def network_status(user: Optional[dict] = Depends(require_read)):
    _require_diagnostics()
    return netdiag.network_status()


@app.get("/api/network/interfaces", tags=["network"])
async def network_interfaces(user: Optional[dict] = Depends(require_read)):
    _require_diagnostics()
    return netdiag.network_interfaces()


@app.get("/api/network/ports", tags=["network"])
async def network_ports(
    limit: int = Query(None, ge=1, le=2000),
    user: Optional[dict] = Depends(require_read),
):
    _require_diagnostics()
    return netdiag.network_ports(limit=limit)


@app.get("/api/network/firewall", tags=["network"])
async def network_firewall(user: Optional[dict] = Depends(require_read)):
    _require_diagnostics()
    return netdiag.network_firewall()


def _require_usage() -> None:
    if not settings.NETWORK_USAGE_ENABLED:
        raise HTTPException(status_code=404, detail="network usage accounting disabled")


def _parse_range(start: Optional[str], end: Optional[str]) -> tuple:
    try:
        return network_usage.resolve_range(
            start,
            end,
            network_usage.usage.tzinfo,
            settings.NETWORK_USAGE_MAX_RANGE_DAYS,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@app.get("/api/network/usage/status", tags=["network"])
async def network_usage_status(user: Optional[dict] = Depends(require_read)):
    return network_usage.usage.status()


@app.get("/api/network/usage/daily", tags=["network"])
async def network_usage_daily(
    start: Optional[str] = Query(None, max_length=10),
    end: Optional[str] = Query(None, max_length=10),
    interface: Optional[str] = Query(None, max_length=64),
    user: Optional[dict] = Depends(require_read),
):
    _require_usage()
    start_date, end_date = _parse_range(start, end)
    return network_usage.usage.daily(start_date, end_date, interface)


@app.get("/api/network/usage/monthly", tags=["network"])
async def network_usage_monthly(
    start: Optional[str] = Query(None, max_length=7),
    end: Optional[str] = Query(None, max_length=7),
    interface: Optional[str] = Query(None, max_length=64),
    user: Optional[dict] = Depends(require_read),
):
    _require_usage()
    try:
        start_month, end_month = network_usage.resolve_month_range(
            start,
            end,
            network_usage.usage.tzinfo,
            settings.NETWORK_USAGE_MAX_RANGE_DAYS // 28 + 1,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return network_usage.usage.monthly(start_month, end_month, interface)


@app.get("/api/network/usage/top", tags=["network"])
async def network_usage_top(
    start: Optional[str] = Query(None, max_length=10),
    end: Optional[str] = Query(None, max_length=10),
    interface: Optional[str] = Query(None, max_length=64),
    limit: int = Query(10, ge=1, le=100),
    user: Optional[dict] = Depends(require_read),
):
    _require_usage()
    start_date, end_date = _parse_range(start, end)
    return network_usage.usage.top(start_date, end_date, interface, limit)


def _rate_alias(rates: Optional[dict]) -> dict:
    """Delegate to :func:`network_usage.rate_alias` (kept for symmetry)."""
    return network_usage.rate_alias(rates)


def _usage_interface_catalog() -> list:
    """Delegate to :meth:`network_usage.NetworkUsage.interface_catalog`."""
    return network_usage.usage.interface_catalog()


@app.get("/api/network/usage/interfaces", tags=["network"])
async def network_usage_interfaces(user: Optional[dict] = Depends(require_read)):
    """Interface names for the Android usage selector.

    The client deserialises this as a plain ``List<String>`` and prepends its
    own ``all`` aggregate chip, so this endpoint returns a JSON array of raw
    interface names (never the ``all`` pseudo-entry).
    """
    _require_usage()
    items = _usage_interface_catalog()
    return [item["name"] for item in items if item.get("name") not in (None, "all")]


@app.get("/api/network/usage/interface-catalog", tags=["network"])
async def network_usage_interface_catalog(user: Optional[dict] = Depends(require_read)):
    """Rich interface catalog (labels, kind, live rates) for non-Android clients."""
    _require_usage()
    items = _usage_interface_catalog()
    return {
        "available": True,
        "unit": "bytes",
        "timezone": network_usage.usage.status().get("timezone", "UTC"),
        "count": len(items),
        "aggregate": "all",
        "default": "all",
        "selected": "all",
        "interfaces": items,
        "generated_at": int(time.time()),
    }


@app.get("/api/network/usage", tags=["network"])
async def network_usage_query(
    days: Optional[int] = Query(None, ge=1, le=settings.NETWORK_USAGE_MAX_RANGE_DAYS),
    iface: Optional[str] = Query(None, max_length=64),
    interface: Optional[str] = Query(None, max_length=64),
    start: Optional[str] = Query(None, max_length=10),
    end: Optional[str] = Query(None, max_length=10),
    limit: int = Query(10, ge=1, le=100),
    user: Optional[dict] = Depends(require_read),
):
    """Combined usage report for one interface (or the ``all`` aggregate).

    Serves the Android client's ``?days=N&iface=<name|all>`` request in one
    call: current live rates, per-day history, monthly totals and the
    highest-usage days, in a configurable timezone with byte units.
    """
    _require_usage()
    catalog = _usage_interface_catalog()
    known = {item["name"] for item in catalog}
    selected = (interface or iface or "all").strip() or "all"
    if selected not in known:
        raise HTTPException(status_code=400, detail=f"unknown interface {selected!r}")

    if start or end:
        start_date, end_date = _parse_range(start, end)
        window = (end_date - start_date).days + 1
    else:
        window = days if days is not None else 30
        end_date = datetime.now(network_usage.usage.tzinfo).date()
        start_date = end_date - timedelta(days=window - 1)

    daily = network_usage.usage.daily(start_date, end_date, selected)
    monthly = network_usage.usage.monthly(start_date, end_date, selected)
    top = network_usage.usage.top(start_date, end_date, selected, limit)

    try:
        snap = network_metrics.network_metrics()
    except Exception:
        snap = {}
    if selected in ("all", "aggregate", "physical", "default"):
        live_rates = snap.get("rates", {})
    else:
        entry = next(
            (e for e in snap.get("interfaces", []) if e.get("name") == selected), None
        )
        live_rates = dict(entry.get("rates", {})) if entry else {}
        live_rates.setdefault("available", entry is not None)
    rates = _rate_alias(live_rates)

    totals = daily["totals"]

    def _num(value):
        """Coerce a possibly-missing usage value to a number.

        The Android client models daily bytes as primitive ``long``s, which
        cannot represent a missing value, so a day without recorded samples is
        emitted as ``0`` rather than ``null`` (a null would also break Gson's
        reflective primitive assignment).
        """
        return value if isinstance(value, (int, float)) else 0

    daily_series = [
        {
            **d,
            "rx_bytes": _num(d.get("rx_bytes")),
            "tx_bytes": _num(d.get("tx_bytes")),
            "total_bytes": _num(d.get("total_bytes")),
            "download_bytes": _num(d.get("rx_bytes")),
            "upload_bytes": _num(d.get("tx_bytes")),
            "rx": _num(d.get("rx_bytes")),
            "tx": _num(d.get("tx_bytes")),
            "total": _num(d.get("total_bytes")),
        }
        for d in daily["days"]
    ]
    monthly_series = [
        {
            **m,
            "download_bytes": m["rx_bytes"],
            "upload_bytes": m["tx_bytes"],
        }
        for m in monthly["months"]
    ]

    # Interface names for the Android chip selector (List<String>).
    interface_names = [
        item["name"] for item in catalog if item.get("name") not in (None, "all")
    ]

    # Highest-usage day within the window (always present so the client's
    # unconditional PeakUsageDay dereference cannot NPE).
    peak = {"date": daily_series[-1]["date"] if daily_series else end_date.isoformat(),
            "total_bytes": 0}
    best_total = -1
    for day in daily_series:
        if day["total_bytes"] > best_total:
            best_total = day["total_bytes"]
            peak = {"date": day["date"], "total_bytes": day["total_bytes"]}

    tracking_since = daily["accounting_since"] or (
        daily_series[0]["date"] if daily_series else end_date.isoformat()
    )

    return {
        # --- Android client contract (NetworkUsageResponse) ---
        "days": daily_series,
        "interfaces": interface_names,
        "peak": peak,
        "timezone": daily["timezone"],
        "totals": {
            "rx": totals["rx_bytes"],
            "tx": totals["tx_bytes"],
            "total": totals["total_bytes"],
            "rx_bytes": totals["rx_bytes"],
            "tx_bytes": totals["tx_bytes"],
            "total_bytes": totals["total_bytes"],
            "download_bytes": totals["rx_bytes"],
            "upload_bytes": totals["tx_bytes"],
        },
        "tracking_since": tracking_since,

        # --- Backward-compatible rich payload ---
        "available": True,
        "enabled": True,
        "unit": "bytes",
        "iface": selected,
        "interface": selected,
        "days_count": window,
        "range_days": window,
        "start": daily["start"],
        "end": daily["end"],
        "range": {"start": daily["start"], "end": daily["end"]},
        "generated_at": int(time.time()),
        "accounting_since": daily["accounting_since"],
        "targets": daily["interfaces"],
        "interface_catalog": catalog,
        "rates": rates,
        "current": rates,
        "download_bytes_per_sec": rates["download_bytes_per_sec"],
        "upload_bytes_per_sec": rates["upload_bytes_per_sec"],
        "download_bytes": totals["rx_bytes"],
        "upload_bytes": totals["tx_bytes"],
        "total_bytes": totals["total_bytes"],
        "received_bytes": totals["rx_bytes"],
        "transmitted_bytes": totals["tx_bytes"],
        "daily": daily_series,
        "monthly": monthly_series,
        "top": top["days"],
    }


@app.post("/api/network/restart", tags=["network"])
@app.post("/api/network/restart-manager", tags=["network"])
async def network_restart_manager(user: dict = Depends(require_auth)):
    _require_diagnostics()
    result = netdiag.restart_network_manager()
    if not result.get("success"):
        if not result.get("supported"):
            status_code = 501
        elif result.get("enabled") is False:
            status_code = 403
        else:
            status_code = 400
        logger.warning(
            "event=network_restart_denied by=%s status=%s reason=%s",
            user.get("username"),
            status_code,
            result.get("message"),
        )
        raise HTTPException(status_code=status_code, detail=result)
    logger.info("event=network_restart_accepted by=%s", user.get("username"))
    return result


@app.get("/api/docker/containers", tags=["docker"])
async def docker_containers(
    stats: bool = Query(True),
    all: Optional[bool] = Query(None),
    user: Optional[dict] = Depends(require_read)
):
    return docker_metrics.list_containers(include_stats=stats)


@app.get("/api/docker/containers/{container_id}", tags=["docker"])
async def docker_container(container_id: str, user: Optional[dict] = Depends(require_read)):
    try:
        result = docker_metrics.get_container(container_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid container id")
    return result


@app.get("/api/docker/containers/{container_id}/logs", tags=["docker"])
async def docker_container_logs(
    container_id: str,
    tail: Optional[int] = Query(None, ge=1, le=settings.LOGS_MAX_TAIL),
    timestamps: bool = Query(True),
    user: Optional[dict] = Depends(require_read),
):
    try:
        result = docker_metrics.container_logs(container_id, tail=tail, timestamps=timestamps)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid container id")
    return result


@app.post("/api/docker/containers/{container_id}/start", tags=["docker"])
async def docker_container_start(container_id: str, user: dict = Depends(require_auth)):
    try:
        result = docker_metrics.start_container(container_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid container id")
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "action failed"))
    return result


@app.post("/api/docker/containers/{container_id}/stop", tags=["docker"])
async def docker_container_stop(container_id: str, user: dict = Depends(require_auth)):
    try:
        result = docker_metrics.stop_container(container_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid container id")
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "action failed"))
    logger.info(
        "event=container_stop_requested by=%s container=%s",
        user.get("username"),
        result.get("container"),
    )
    return result


@app.post("/api/docker/containers/{container_id}/restart", tags=["docker"])
async def docker_container_restart(container_id: str, user: dict = Depends(require_auth)):
    try:
        result = docker_metrics.restart_container(container_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid container id")
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "action failed"))
    logger.info(
        "event=container_restart_requested by=%s container=%s",
        user.get("username"),
        result.get("container"),
    )
    return result


@app.get("/api/alerts", tags=["alerts"])
async def alerts_list(
    limit: int = Query(100, ge=1, le=settings.ALERT_HISTORY_LIMIT),
    status_filter: Optional[str] = Query(None),
    user: Optional[dict] = Depends(require_read),
):
    return {
        "thresholds": engine.thresholds,
        "count": len(engine.all_alerts(limit)),
        "alerts": engine.all_alerts(limit),
        "timestamp": int(time.time()),
    }


@app.get("/api/alerts/recent", tags=["alerts"])
async def alerts_recent(
    limit: int = Query(20, ge=1, le=200), user: Optional[dict] = Depends(require_read)
):
    return {
        "count": len(engine.recent(limit)),
        "alerts": engine.recent(limit),
        "timestamp": int(time.time()),
    }


@app.post("/api/alerts/clear-all", tags=["alerts"])
async def alerts_clear_all(user: dict = Depends(require_auth)):
    """Clear All: permanently delete every stored alert.

    Single authenticated bulk-delete covering active, warning, critical and
    resolved records. Idempotent -- clearing an empty store succeeds with
    ``deleted`` of 0. Individual per-alert resolve is unaffected, and new
    alerts continue to be generated after clearing.
    """
    result = engine.clear_all()
    logger.info(
        "event=alerts_cleared_all by=%s deleted=%d",
        user.get("username"),
        result["deleted"],
    )
    return {
        "status": "ok",
        "deleted": result["deleted"],
        "remaining": result["remaining"],
        "timestamp": int(time.time()),
    }


@app.post("/api/alerts/{alert_id}/resolve", tags=["alerts"])
async def alerts_resolve_one(alert_id: str, user: dict = Depends(require_auth)):
    alert = engine.resolve_one(alert_id)
    if alert is None:
        raise HTTPException(status_code=404, detail="alert not found")
    logger.info(
        "event=alert_resolved by=%s alert=%s",
        user.get("username"),
        alert_id,
    )
    return {
        "status": "ok",
        "alert_id": alert_id,
        "resolved": alert.get("status") == "resolved",
        "alert": alert,
        "timestamp": int(time.time()),
    }


@app.websocket("/ws/metrics")
async def ws_metrics(websocket: WebSocket):
    await metrics_websocket(websocket)
