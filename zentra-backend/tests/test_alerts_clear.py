"""Tests for the alert lifecycle and the single bulk "Clear All" action.

The product exposes one destructive bulk action -- Clear All -- which must
permanently delete every stored alert (active, warning, critical and
resolved). The separate "Clear Resolved" action has been removed.

Coverage:
- emitted alerts carry a status defaulting to "active"
- resolve_one flips a single active alert to "resolved" (kept, idempotent)
- clear_all deletes every record, mixed active + resolved included
- clear_all on an empty store succeeds and reports zero deleted
- clear_all is idempotent: repeating it reports zero deleted
- the emptied store is persisted immediately and survives an engine reload
- new alerts keep being generated (as active) after clearing
- the HTTP handler returns the documented response schema
- the clear-all route is authenticated (401 without / with a bad token)
- the removed Clear Resolved route is no longer registered
"""

import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

APP_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app"))
sys.path.insert(0, APP_ROOT)

import alerts as alerts_module
import auth as auth_module

TMPDIR = tempfile.mkdtemp(prefix="zentra-alert-tests-")


def _fake_context(history=None):
    """Return (alerts_path, data_dir_mock) where persistence writes go."""
    file_path = Path(TMPDIR) / "alerts.json"
    if history is not None:
        with file_path.open("w", encoding="utf-8") as handle:
            json.dump(history, handle)
    else:
        try:
            file_path.unlink()
        except FileNotFoundError:
            pass
    data_dir = mock.MagicMock()
    data_dir.mkdir.return_value = None
    return file_path, data_dir


def _isolated_engine(history=None):
    """Instantiate an AlertEngine pointed at a temp file, isolated from /opt/zentra."""
    file_path, data_dir = _fake_context(history)
    with mock.patch.object(alerts_module, "ALERTS_FILE", file_path), mock.patch.object(
        alerts_module, "DATA_DIR", data_dir
    ):
        return alerts_module.AlertEngine()


def _emit(engine, alert_type="CPU_HIGH", source="system", value=91.0):
    return engine.emit(
        alert_type,
        "resource above threshold",
        source,
        severity="critical",
        value=value,
        threshold=90.0,
    )


class LifecycleTests(unittest.TestCase):
    def test_emit_sets_status_active(self):
        engine = _isolated_engine()
        alert = _emit(engine, source="system")
        self.assertIsNotNone(alert)
        self.assertEqual(alert["status"], "active")
        self.assertEqual(engine.active_count(), 1)
        self.assertEqual(engine.resolved_count(), 0)

    def test_resolve_one_flips_status(self):
        engine = _isolated_engine()
        a1 = _emit(engine, source="one")
        a2 = _emit(engine, source="two")
        result = engine.resolve_one(a1["id"])
        self.assertIsNotNone(result)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(engine.active_count(), 1)
        self.assertEqual(engine.resolved_count(), 1)
        by_id = {a["id"]: a for a in engine.all_alerts()}
        self.assertEqual(by_id[a2["id"]]["status"], "active")

    def test_resolve_one_idempotent(self):
        engine = _isolated_engine()
        a1 = _emit(engine, source="one")
        engine.resolve_one(a1["id"])
        again = engine.resolve_one(a1["id"])
        self.assertEqual(again["status"], "resolved")
        self.assertEqual(engine.active_count(), 0)
        self.assertEqual(engine.resolved_count(), 1)

    def test_resolve_one_unknown_id_returns_none(self):
        engine = _isolated_engine()
        self.assertIsNone(engine.resolve_one("does-not-exist"))

    def test_resolve_one_survives_clear_action(self):
        # Resolve stays available; clearing does not break it.
        engine = _isolated_engine()
        a1 = _emit(engine, source="one")
        engine.resolve_one(a1["id"])
        self.assertEqual(engine.resolved_count(), 1)
        self.assertTrue(engine.resolve_one(a1["id"])["status"] == "resolved")

    def test_clear_all_deletes_mixed_active_and_resolved(self):
        engine = _isolated_engine()
        a1 = _emit(engine, source="one", alert_type="CPU_HIGH")
        _emit(engine, source="two", alert_type="MEMORY_HIGH")
        _emit(engine, source="three", alert_type="DISK_HIGH")
        engine.resolve_one(a1["id"])  # a1 resolved, two+three active

        self.assertEqual(engine.active_count(), 2)
        self.assertEqual(engine.resolved_count(), 1)

        result = engine.clear_all()
        self.assertEqual(result["deleted"], 3)
        self.assertEqual(result["remaining"], 0)
        self.assertEqual(engine.all_alerts(), [])
        self.assertEqual(engine.active_count(), 0)
        self.assertEqual(engine.resolved_count(), 0)

    def test_clear_all_empty_store(self):
        engine = _isolated_engine()
        result = engine.clear_all()
        self.assertEqual(result["deleted"], 0)
        self.assertEqual(result["remaining"], 0)
        self.assertEqual(engine.all_alerts(), [])

    def test_clear_all_is_idempotent(self):
        engine = _isolated_engine()
        _emit(engine, source="one")
        _emit(engine, source="two")
        first = engine.clear_all()
        second = engine.clear_all()
        third = engine.clear_all()
        self.assertEqual(first["deleted"], 2)
        self.assertEqual(second["deleted"], 0)
        self.assertEqual(third["deleted"], 0)
        self.assertEqual(third["remaining"], 0)

    def test_clear_all_writes_empty_store_to_disk(self):
        file_path, data_dir = _fake_context()
        with mock.patch.object(alerts_module, "ALERTS_FILE", file_path), mock.patch.object(
            alerts_module, "DATA_DIR", data_dir
        ):
            engine = alerts_module.AlertEngine()
            _emit(engine, source="one")
            engine.clear_all()
            self.assertEqual(json.loads(file_path.read_text()), [])

    def test_clear_all_persists_across_restart(self):
        file_path, data_dir = _fake_context()
        with mock.patch.object(alerts_module, "ALERTS_FILE", file_path), mock.patch.object(
            alerts_module, "DATA_DIR", data_dir
        ):
            engine = alerts_module.AlertEngine()
            _emit(engine, source="one")
            _emit(engine, source="two")
            engine.resolve_one(engine.all_alerts()[0]["id"])
            engine.clear_all()
        # Simulate a service restart: a brand new engine reloads from disk.
        with mock.patch.object(alerts_module, "ALERTS_FILE", file_path), mock.patch.object(
            alerts_module, "DATA_DIR", data_dir
        ):
            engine2 = alerts_module.AlertEngine()
            self.assertEqual(engine2.all_alerts(), [])
            self.assertEqual(engine2.active_count(), 0)
            self.assertEqual(engine2.resolved_count(), 0)

    def test_legacy_alerts_backfilled_then_cleared(self):
        legacy = [
            {
                "id": "legacy-1",
                "type": "CPU_HIGH",
                "severity": "critical",
                "source": "old",
                "message": "old alert without status",
                "value": 95.0,
                "threshold": 90.0,
                "timestamp": 1,
            }
        ]
        engine = _isolated_engine(legacy)
        self.assertEqual(engine.active_count(), 1)
        self.assertEqual(engine.all_alerts()[0]["status"], "active")
        self.assertEqual(engine.clear_all()["deleted"], 1)
        self.assertEqual(engine.all_alerts(), [])

    def test_new_alerts_after_clear_are_active_again(self):
        engine = _isolated_engine()
        _emit(engine, source="one")
        engine.clear_all()
        self.assertEqual(engine.all_alerts(), [])
        # Alert generation is untouched: a new event is created normally.
        fresh = _emit(engine, source="two")
        self.assertIsNotNone(fresh)
        self.assertEqual(fresh["status"], "active")
        self.assertEqual(engine.active_count(), 1)

    def test_generation_not_disabled_after_clear(self):
        # The cooldown/active map is part of generation; it must still work.
        engine = _isolated_engine()
        _emit(engine, source="one")
        engine.clear_all()
        # A different key emits immediately and is stored.
        self.assertIsNotNone(_emit(engine, source="after-clear", alert_type="DISK_HIGH"))
        self.assertEqual(len(engine.all_alerts()), 1)


class ApiTests(unittest.TestCase):
    @staticmethod
    def _run(coro):
        return asyncio.new_event_loop().run_until_complete(coro)

    def test_clear_all_response_schema(self):
        import main as main_module

        engine = _isolated_engine()
        engine.emit("CPU_HIGH", "cpu at 95%", "system", value=95.0, threshold=90.0)
        engine.emit("MEMORY_HIGH", "mem at 95%", "system", value=95.0, threshold=90.0)
        engine.resolve_one(engine.all_alerts()[0]["id"])
        user = {"username": "tester", "role": "user", "created_at": 1}
        with mock.patch.object(main_module, "engine", engine):
            resp = self._run(main_module.alerts_clear_all(user=user))
        self.assertEqual(resp["status"], "ok")
        self.assertEqual(resp["deleted"], 2)
        self.assertEqual(resp["remaining"], 0)
        self.assertIsInstance(resp["timestamp"], int)
        self.assertEqual(engine.all_alerts(), [])

    def test_clear_all_response_schema_empty(self):
        import main as main_module

        engine = _isolated_engine()
        user = {"username": "tester", "role": "user", "created_at": 1}
        with mock.patch.object(main_module, "engine", engine):
            resp = self._run(main_module.alerts_clear_all(user=user))
        self.assertEqual(resp["status"], "ok")
        self.assertEqual(resp["deleted"], 0)
        self.assertEqual(resp["remaining"], 0)

    def test_clear_all_repeated_via_handler(self):
        import main as main_module

        engine = _isolated_engine()
        engine.emit("CPU_HIGH", "cpu at 95%", "system", value=95.0, threshold=90.0)
        user = {"username": "tester", "role": "user", "created_at": 1}
        with mock.patch.object(main_module, "engine", engine):
            first = self._run(main_module.alerts_clear_all(user=user))
            second = self._run(main_module.alerts_clear_all(user=user))
        self.assertEqual(first["deleted"], 1)
        self.assertEqual(second["deleted"], 0)

    def test_resolve_one_response_schema_and_404(self):
        from fastapi import HTTPException

        import main as main_module

        engine = _isolated_engine()
        alert = engine.emit("MEMORY_HIGH", "mem at 95%", "system", value=95.0, threshold=90.0)
        user = {"username": "tester", "role": "user", "created_at": 1}
        with mock.patch.object(main_module, "engine", engine):
            resp = self._run(main_module.alerts_resolve_one(alert["id"], user=user))
            self.assertEqual(resp["status"], "ok")
            self.assertEqual(resp["alert_id"], alert["id"])
            self.assertTrue(resp["resolved"])
            self.assertEqual(resp["alert"]["status"], "resolved")
            with self.assertRaises(HTTPException) as ctx:
                self._run(main_module.alerts_resolve_one("nope", user=user))
            self.assertEqual(ctx.exception.status_code, 404)

    def test_clear_all_route_method_and_path(self):
        import main as main_module

        methods = {}
        for route in main_module.app.routes:
            methods[getattr(route, "path", None)] = getattr(route, "methods", set())
        self.assertIn("/api/alerts/clear-all", methods)
        self.assertIn("POST", methods["/api/alerts/clear-all"])
        self.assertIn("/api/alerts/{alert_id}/resolve", methods)
        self.assertIn("POST", methods["/api/alerts/{alert_id}/resolve"])

    def test_clear_resolved_route_removed(self):
        import main as main_module

        paths = {getattr(route, "path", None) for route in main_module.app.routes}
        self.assertNotIn("/api/alerts/resolved", paths)


class AuthRouteTests(unittest.TestCase):
    def test_require_auth_rejects_missing_token(self):
        from fastapi import HTTPException

        class _FakeRequest:
            def __init__(self):
                self.query_params = mock.MagicMock()
                self.query_params.get.return_value = None

            @property
            def headers(self):
                return {}

        with mock.patch("auth.decode_token", return_value=None):
            with self.assertRaises(HTTPException) as ctx:
                auth_module.require_auth(_FakeRequest())
            self.assertEqual(ctx.exception.status_code, 401)

    def test_require_auth_rejects_invalid_token(self):
        from fastapi import HTTPException

        class _FakeRequest:
            def __init__(self):
                self.query_params = mock.MagicMock()
                self.query_params.get.return_value = None

            @property
            def headers(self):
                return {"Authorization": "bearer garbage"}

        with mock.patch("auth.decode_token", return_value=None):
            with self.assertRaises(HTTPException) as ctx:
                auth_module.require_auth(_FakeRequest())
            self.assertEqual(ctx.exception.status_code, 401)

    def test_require_auth_accepts_valid_token(self):
        payload = {"sub": "alice", "role": "user", "iat": 1}

        class _FakeRequest:
            def __init__(self):
                self.query_params = mock.MagicMock()
                self.query_params.get.return_value = None

            @property
            def headers(self):
                return {"Authorization": "bearer good-token"}

        with mock.patch("auth.decode_token", return_value=dict(payload)):
            result = auth_module.require_auth(_FakeRequest())
        self.assertEqual(result["username"], "alice")


if __name__ == "__main__":
    unittest.main(verbosity=2)
