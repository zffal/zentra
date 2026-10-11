import asyncio
import json
import logging
import os
import threading
import time
from collections import deque
from typing import Dict, List, Optional

from config import DATA_DIR, settings
from metrics_hub import hub

logger = logging.getLogger("zentra.alerts")

ALERTS_FILE = DATA_DIR / "alerts.json"

CPU_HIGH = "CPU_HIGH"
MEMORY_HIGH = "MEMORY_HIGH"
DISK_HIGH = "DISK_HIGH"
TEMPERATURE_HIGH = "TEMPERATURE_HIGH"
CONTAINER_STOPPED = "CONTAINER_STOPPED"
CONTAINER_RESTARTED = "CONTAINER_RESTARTED"

SEVERITY_WARNING = "warning"
SEVERITY_CRITICAL = "critical"

STATUS_ACTIVE = "active"
STATUS_RESOLVED = "resolved"


class Notifier:
    """Base class for notification providers.

    The alert engine produces events; notifiers consume them. New providers
    (Android push, browser webhook, Telegram, Discord, email, ...) only need
    to implement `send` and be registered on the registry.
    """

    name = "base"
    enabled = False

    async def send(self, alert: dict) -> None:
        raise NotImplementedError


class LogNotifier(Notifier):
    name = "log"
    enabled = True

    async def send(self, alert: dict) -> None:
        logger.info(
            "event=alert_dispatched type=%s severity=%s source=%s message=%s",
            alert.get("type"),
            alert.get("severity"),
            alert.get("source"),
            alert.get("message"),
        )


class WebhookNotifier(Notifier):
    """Generic HTTP webhook sink (browser / Android push relays)."""

    name = "webhook"

    def __init__(self, url: str):
        self.url = url
        self.enabled = True

    async def send(self, alert: dict) -> None:
        import requests

        def _post() -> None:
            try:
                requests.post(self.url, json=alert, timeout=5)
            except Exception as exc:
                logger.warning("event=webhook_failed error=%s", exc)

        await asyncio.to_thread(_post)


class DiscordNotifier(Notifier):
    name = "discord"

    def __init__(self, webhook_url: str):
        self.webhook_url = webhook_url
        self.enabled = True

    async def send(self, alert: dict) -> None:
        import requests

        content = (
            f"**Zentra alert: {alert.get('type')}**\n"
            f"{alert.get('message')}\n"
            f"source=`{alert.get('source')}` severity=`{alert.get('severity')}`"
        )

        def _post() -> None:
            try:
                requests.post(
                    self.webhook_url, json={"content": content}, timeout=5
                )
            except Exception as exc:
                logger.warning("event=discord_notify_failed error=%s", exc)

        await asyncio.to_thread(_post)


class TelegramNotifier(Notifier):
    name = "telegram"

    def __init__(self, bot_token: str, chat_id: str):
        self.bot_token = bot_token
        self.chat_id = chat_id
        self.enabled = True

    async def send(self, alert: dict) -> None:
        import requests

        text = (
            f"Zentra alert: {alert.get('type')}\n"
            f"{alert.get('message')}\n"
            f"source={alert.get('source')} severity={alert.get('severity')}"
        )
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"

        def _post() -> None:
            try:
                requests.post(
                    url, data={"chat_id": self.chat_id, "text": text}, timeout=5
                )
            except Exception as exc:
                logger.warning("event=telegram_notify_failed error=%s", exc)

        await asyncio.to_thread(_post)


class EmailNotifier(Notifier):
    name = "email"

    def __init__(self):
        self.host = os.getenv("SMTP_HOST", "")
        self.port = int(os.getenv("SMTP_PORT", "587"))
        self.username = os.getenv("SMTP_USERNAME", "")
        self.password = os.getenv("SMTP_PASSWORD", "")
        self.sender = os.getenv("SMTP_FROM", "zentra@localhost")
        self.recipient = os.getenv("SMTP_TO", "")
        self.enabled = bool(self.host and self.recipient)

    async def send(self, alert: dict) -> None:
        if not self.enabled:
            return
        import smtplib
        from email.mime.text import MIMEText

        subject = f"[Zentra] {alert.get('type')} on {alert.get('source')}"
        body = json.dumps(alert, indent=2)
        message = MIMEText(body)
        message["Subject"] = subject
        message["From"] = self.sender
        message["To"] = self.recipient

        def _send() -> None:
            try:
                with smtplib.SMTP(self.host, self.port, timeout=10) as server:
                    server.starttls()
                    if self.username:
                        server.login(self.username, self.password)
                    server.sendmail(self.sender, [self.recipient], message.as_string())
            except Exception as exc:
                logger.warning("event=email_notify_failed error=%s", exc)

        await asyncio.to_thread(_send)


class NotifierRegistry:
    def __init__(self):
        self._notifiers: List[Notifier] = []

    def register(self, notifier: Notifier) -> None:
        if notifier.enabled:
            self._notifiers.append(notifier)
            logger.info("event=notifier_registered notifier=%s", notifier.name)

    async def dispatch(self, alert: dict) -> None:
        for notifier in self._notifiers:
            try:
                await notifier.send(alert)
            except Exception as exc:
                logger.warning(
                    "event=notifier_error notifier=%s error=%s", notifier.name, exc
                )


def build_registry() -> NotifierRegistry:
    registry = NotifierRegistry()
    registry.register(LogNotifier())

    webhook_url = os.getenv(
        "ZENTRA_WEBHOOK_URL", os.getenv("OMNIS_WEBHOOK_URL", "")
    ).strip()
    if webhook_url:
        registry.register(WebhookNotifier(webhook_url))

    discord_url = os.getenv("DISCORD_WEBHOOK_URL", "").strip()
    if discord_url:
        registry.register(DiscordNotifier(discord_url))

    telegram_token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    telegram_chat = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    if telegram_token and telegram_chat:
        registry.register(TelegramNotifier(telegram_token, telegram_chat))

    email = EmailNotifier()
    if email.enabled:
        registry.register(email)

    return registry


class AlertEngine:
    def __init__(self):
        self._lock = threading.Lock()
        self._history: deque = deque(maxlen=settings.ALERT_HISTORY_LIMIT)
        self._active: Dict[str, float] = {}
        self._container_states: Dict[str, dict] = {}
        self._registry = build_registry()
        self._task: Optional[asyncio.Task] = None
        self._load()

    def _load(self) -> None:
        try:
            if ALERTS_FILE.exists():
                with ALERTS_FILE.open("r", encoding="utf-8") as handle:
                    data = json.load(handle)
                for item in data if isinstance(data, list) else []:
                    if isinstance(item, dict) and not item.get("status"):
                        item["status"] = STATUS_ACTIVE
                    self._history.append(item)
                logger.info(
                    "event=alert_history_loaded count=%d", len(self._history)
                )
        except Exception as exc:
            logger.warning("event=alert_history_load_failed error=%s", exc)

    def _persist(self) -> None:
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            tmp = ALERTS_FILE.with_suffix(".json.tmp")
            with tmp.open("w", encoding="utf-8") as handle:
                json.dump(list(self._history), handle, indent=2)
            tmp.replace(ALERTS_FILE)
        except Exception as exc:
            logger.warning("event=alert_persist_failed error=%s", exc)

    @property
    def thresholds(self) -> dict:
        return {
            "CPU_ALERT_PERCENT": settings.CPU_ALERT_PERCENT,
            "MEMORY_ALERT_PERCENT": settings.MEMORY_ALERT_PERCENT,
            "DISK_ALERT_PERCENT": settings.DISK_ALERT_PERCENT,
            "TEMPERATURE_ALERT_C": settings.TEMPERATURE_ALERT_C,
        }

    def emit(
        self,
        alert_type: str,
        message: str,
        source: str,
        severity: str = SEVERITY_WARNING,
        value=None,
        threshold=None,
    ) -> Optional[dict]:
        key = f"{alert_type}:{source}"
        now = time.time()
        with self._lock:
            last = self._active.get(key)
            if last is not None and now - last < settings.ALERT_COOLDOWN_SECONDS:
                return None
            self._active[key] = now
            alert = {
                "id": f"{int(now * 1000)}-{alert_type}-{source}"[:80],
                "type": alert_type,
                "severity": severity,
                "status": STATUS_ACTIVE,
                "source": source,
                "message": message,
                "value": value,
                "threshold": threshold,
                "timestamp": int(now),
            }
            self._history.append(alert)
            self._persist()

        logger.warning(
            "event=alert_raised type=%s severity=%s source=%s value=%s threshold=%s",
            alert_type,
            severity,
            source,
            value,
            threshold,
        )
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self._registry.dispatch(alert))
        except RuntimeError:
            logger.debug("event=alert_dispatch_deferred reason=no_running_loop")

        return alert

    def _clear_below(self, alert_type: str, source: str) -> None:
        with self._lock:
            self._active.pop(f"{alert_type}:{source}", None)

    def evaluate(self, metrics: dict) -> List[dict]:
        raised: List[dict] = []

        cpu = metrics.get("cpu") or {}
        cpu_usage = None if cpu.get("stale") else cpu.get("usage_percent")
        if cpu_usage is not None:
            if cpu_usage >= settings.CPU_ALERT_PERCENT:
                alert = self.emit(
                    CPU_HIGH,
                    f"CPU usage is {cpu_usage}% (threshold {settings.CPU_ALERT_PERCENT}%)",
                    "system",
                    SEVERITY_CRITICAL,
                    cpu_usage,
                    settings.CPU_ALERT_PERCENT,
                )
                if alert:
                    raised.append(alert)
            else:
                self._clear_below(CPU_HIGH, "system")

        memory_section = metrics.get("memory") or {}
        memory = memory_section.get("memory") or {}
        mem_percent = None if memory_section.get("stale") else memory.get("percent")
        if mem_percent is not None:
            if mem_percent >= settings.MEMORY_ALERT_PERCENT:
                alert = self.emit(
                    MEMORY_HIGH,
                    f"Memory usage is {mem_percent}% (threshold {settings.MEMORY_ALERT_PERCENT}%)",
                    "system",
                    SEVERITY_CRITICAL,
                    mem_percent,
                    settings.MEMORY_ALERT_PERCENT,
                )
                if alert:
                    raised.append(alert)
            else:
                self._clear_below(MEMORY_HIGH, "system")

        storage_section = metrics.get("storage") or {}
        filesystems = [] if storage_section.get("stale") else storage_section.get("filesystems") or []
        for fs in filesystems:
            mountpoint = fs.get("mountpoint", "unknown")
            percent = fs.get("percent", 0)
            if percent >= settings.DISK_ALERT_PERCENT:
                alert = self.emit(
                    DISK_HIGH,
                    f"Disk {mountpoint} is {percent}% full (threshold {settings.DISK_ALERT_PERCENT}%)",
                    mountpoint,
                    SEVERITY_CRITICAL,
                    percent,
                    settings.DISK_ALERT_PERCENT,
                )
                if alert:
                    raised.append(alert)
            else:
                self._clear_below(DISK_HIGH, mountpoint)

        temperature = metrics.get("temperature") or {}
        if temperature.get("available") and not temperature.get("stale"):
            for sensor in temperature.get("sensors") or []:
                current = sensor.get("current_c")
                label = sensor.get("label", "sensor")
                if current is None:
                    continue
                if current >= settings.TEMPERATURE_ALERT_C:
                    alert = self.emit(
                        TEMPERATURE_HIGH,
                        f"Temperature {label} is {current}C (threshold {settings.TEMPERATURE_ALERT_C}C)",
                        label,
                        SEVERITY_CRITICAL,
                        current,
                        settings.TEMPERATURE_ALERT_C,
                    )
                    if alert:
                        raised.append(alert)
                else:
                    self._clear_below(TEMPERATURE_HIGH, label)

        docker = metrics.get("docker") or {}
        if docker.get("available") and not docker.get("stale"):
            current_states = {
                c["name"]: c
                for c in docker.get("containers") or []
                if c.get("name")
            }
            previous = self._container_states
            if previous:
                for name, container in current_states.items():
                    old = previous.get(name)
                    if old is None:
                        continue
                    state = container.get("state")
                    if old.get("state") == "running" and state != "running":
                        alert = self.emit(
                            CONTAINER_STOPPED,
                            f"Container {name} changed state from running to {state}",
                            name,
                            SEVERITY_CRITICAL,
                            state,
                            "running",
                        )
                        if alert:
                            raised.append(alert)
                    old_restarts = old.get("restart_count") or 0
                    new_restarts = container.get("restart_count") or 0
                    if new_restarts > old_restarts:
                        alert = self.emit(
                            CONTAINER_RESTARTED,
                            f"Container {name} restarted ({old_restarts} -> {new_restarts})",
                            name,
                            SEVERITY_WARNING,
                            new_restarts,
                            old_restarts,
                        )
                        if alert:
                            raised.append(alert)
            self._container_states = current_states

        return raised

    def recent(self, limit: int = 50) -> List[dict]:
        with self._lock:
            items = list(self._history)
        items.sort(key=lambda a: a.get("timestamp", 0), reverse=True)
        return items[: max(1, min(limit, 500))]

    def all_alerts(self, limit: int = 200) -> List[dict]:
        with self._lock:
            items = list(self._history)
        items.sort(key=lambda a: a.get("timestamp", 0), reverse=True)
        return items[: max(1, min(limit, settings.ALERT_HISTORY_LIMIT))]

    def active_alerts(self, limit: int = 200) -> List[dict]:
        with self._lock:
            items = [
                a for a in self._history if a.get("status", STATUS_ACTIVE) == STATUS_ACTIVE
            ]
        items.sort(key=lambda a: a.get("timestamp", 0), reverse=True)
        return items[: max(1, min(limit, settings.ALERT_HISTORY_LIMIT))]

    def active_count(self) -> int:
        with self._lock:
            return sum(
                1 for a in self._history
                if a.get("status", STATUS_ACTIVE) == STATUS_ACTIVE
            )

    def resolved_count(self) -> int:
        with self._lock:
            return sum(
                1 for a in self._history
                if a.get("status", STATUS_ACTIVE) == STATUS_RESOLVED
            )

    def resolve_one(self, alert_id: str) -> Optional[dict]:
        with self._lock:
            for item in self._history:
                if item.get("id") == alert_id:
                    if item.get("status", STATUS_ACTIVE) != STATUS_RESOLVED:
                        item["status"] = STATUS_RESOLVED
                        self._persist()
                    return dict(item)
        return None

    def clear_all(self) -> dict:
        """Permanently delete every stored alert, regardless of status.

        This backs the single bulk "Clear All" action: active, warning,
        critical and already-resolved records are all removed from the store
        and the emptied history is persisted immediately, so the deletion
        survives a service restart.

        Idempotent: clearing an already-empty store succeeds and reports
        ``deleted`` of 0. Individual resolution and alert generation are
        untouched, so new alerts keep being created normally afterwards.
        """
        with self._lock:
            deleted = len(self._history)
            self._history.clear()
            self._persist()
            return {"deleted": deleted, "remaining": len(self._history)}

    async def collect_metrics(self) -> dict:
        import storage_metrics
        import system_metrics
        import temperature_metrics
        import docker_metrics

        cpu = await asyncio.to_thread(system_metrics.cpu_metrics, 0.1)
        memory = await asyncio.to_thread(system_metrics.memory_metrics)
        storage = await asyncio.to_thread(storage_metrics.storage_metrics)
        temperature = await asyncio.to_thread(temperature_metrics.temperature_metrics)
        docker = await asyncio.to_thread(docker_metrics.list_containers, True)
        return {
            "cpu": cpu,
            "memory": memory,
            "storage": storage,
            "temperature": temperature,
            "docker": docker,
        }

    async def run_loop(self) -> None:
        logger.info(
            "event=alert_engine_started interval=%ss cooldown=%ss",
            settings.ALERT_INTERVAL_SECONDS,
            settings.ALERT_COOLDOWN_SECONDS,
        )
        while True:
            try:
                # Prefer the shared snapshot so the alert engine does not run a
                # second full collection (including Docker stats) alongside it.
                metrics = self._snapshot_metrics()
                if metrics is None:
                    metrics = await self.collect_metrics()
                self.evaluate(metrics)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("event=alert_cycle_failed error=%s", exc)
            await asyncio.sleep(settings.ALERT_INTERVAL_SECONDS)

    def _snapshot_metrics(self) -> Optional[dict]:
        snapshot = hub.latest()
        if not snapshot:
            return None
        docker = snapshot.get("docker") or {}
        if not docker.get("available"):
            return None
        return {
            "cpu": snapshot.get("cpu"),
            "memory": snapshot.get("memory"),
            "storage": snapshot.get("storage"),
            "temperature": snapshot.get("temperature"),
            "docker": docker,
        }

    def start(self) -> None:
        if self._task is None:
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                logger.error("event=alert_engine_start_failed reason=no_running_loop")
                return
            self._task = loop.create_task(self.run_loop())

    def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            self._task = None


engine = AlertEngine()
