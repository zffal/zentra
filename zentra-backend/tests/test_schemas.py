"""Schema compatibility tests for Zentra backend responses.

Covers the crash-investigation compatibility contract:
- cpu.per_core is a list of objects (core + usage_percent); cpu.per_core_usage
  is a numeric alias array (backward/forward compatible).
- Consistent epoch-second `timestamp` semantics across REST and WebSocket payloads.
- Temperature exposes package_temp and a `cpu` alias; unavailable readings are
  null, never zero.
"""

import os
import sys
import time
import unittest
from unittest import mock

APP_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app"))
sys.path.insert(0, APP_ROOT)


class CpuSchemaTests(unittest.TestCase):
    def _cpu(self, per_core_values):
        import system_metrics

        with mock.patch.object(
            system_metrics.psutil, "cpu_percent", return_value=list(per_core_values)
        ), mock.patch.object(
            system_metrics.psutil, "cpu_freq", return_value=None
        ), mock.patch("system_metrics.os.getloadavg", return_value=(1.0, 1.0, 1.0)):
            return system_metrics.cpu_metrics(interval=0)

    def test_per_core_is_objects(self):
        data = self._cpu([10.0, 20.0, 30.0, 40.0])
        self.assertIsInstance(data["per_core"], list)
        self.assertEqual(len(data["per_core"]), 4)
        for i, entry in enumerate(data["per_core"]):
            self.assertIsInstance(entry, dict)
            self.assertEqual(entry["core"], i)
            self.assertEqual(entry["core_id"], i)
            self.assertAlmostEqual(entry["usage_percent"], (i + 1) * 10.0)

    def test_per_core_usage_numeric_alias(self):
        data = self._cpu([10.0, 20.0, 30.0])
        self.assertEqual(data["per_core_usage"], [10.0, 20.0, 30.0])
        self.assertEqual(len(data["per_core_usage"]), len(data["per_core"]))

    def test_empty_per_core(self):
        data = self._cpu([])
        self.assertEqual(data["per_core"], [])
        self.assertEqual(data["per_core_usage"], [])
        self.assertEqual(data["usage_percent"], 0.0)

    def test_timestamp_int(self):
        before = int(time.time())
        data = self._cpu([1.0, 2.0])
        after = int(time.time())
        self.assertIsInstance(data["timestamp"], int)
        self.assertGreaterEqual(data["timestamp"], before)
        self.assertLessEqual(data["timestamp"], after)


class MemorySchemaTests(unittest.TestCase):
    def test_timestamp_int(self):
        import system_metrics

        with mock.patch("system_metrics.psutil.virtual_memory") as vm, mock.patch(
            "system_metrics.psutil.swap_memory"
        ) as sw:
            vm.return_value = mock.Mock(
                total=0, used=0, available=0, free=0, cached=0, buffers=0, percent=0.0
            )
            sw.return_value = mock.Mock(total=0, used=0, free=0, percent=0.0)
            data = system_metrics.memory_metrics()
        self.assertIsInstance(data["timestamp"], int)


class SummarySchemaTests(unittest.TestCase):
    def test_timestamp_consistency_across_sections(self):
        import system_metrics

        now = int(time.time())
        data = system_metrics.summary()
        self.assertIsInstance(data["timestamp"], int)
        for key in ("system", "cpu", "memory"):
            self.assertIsInstance(data[key]["timestamp"], int)
            self.assertLessEqual(abs(data[key]["timestamp"] - now), 60)

    def test_cpu_schema_in_summary(self):
        import system_metrics

        data = system_metrics.summary()
        self.assertTrue(all(isinstance(e, dict) for e in data["cpu"]["per_core"]))
        self.assertIsInstance(data["cpu"]["per_core_usage"], list)


class TemperatureSchemaTests(unittest.TestCase):
    def _temp(self, psutil_sensors, thermal_sensors):
        import temperature_metrics

        with mock.patch.object(
            temperature_metrics, "_psutil_sensors", return_value=psutil_sensors
        ), mock.patch.object(
            temperature_metrics, "_thermal_zone_sensors", return_value=thermal_sensors
        ):
            return temperature_metrics.temperature_metrics()

    def test_unavailable_uses_null_not_zero(self):
        data = self._temp([], [])
        self.assertFalse(data["available"])
        self.assertEqual(data["sensors"], [])
        self.assertIsNone(data["max_c"])
        self.assertIsNone(data["package_temp"])
        self.assertIsNone(data["cpu"])
        self.assertIsInstance(data["timestamp"], int)

    def test_package_temp_and_cpu_alias(self):
        sensors = [
            {
                "source": "psutil",
                "chip": "acpitz_0",
                "label": "acpitz_0",
                "current_c": 27.8,
                "high_c": None,
                "critical_c": None,
            },
            {
                "source": "psutil",
                "chip": "coretemp",
                "label": "Package id 0",
                "current_c": 64.0,
                "high_c": 85.0,
                "critical_c": 105.0,
            },
            {
                "source": "psutil",
                "chip": "coretemp",
                "label": "Core 0",
                "current_c": 64.0,
                "high_c": 85.0,
                "critical_c": 105.0,
            },
        ]
        data = self._temp(sensors, [])
        self.assertTrue(data["available"])
        self.assertEqual(data["package_temp"], 64.0)
        self.assertEqual(data["cpu"], data["package_temp"])
        self.assertEqual(data["max_c"], 64.0)

    def test_no_package_sensor_falls_back_to_max(self):
        sensors = [
            {
                "source": "psutil",
                "chip": "acpitz_0",
                "label": "acpitz_0",
                "current_c": 27.8,
                "high_c": None,
                "critical_c": None,
            },
            {
                "source": "psutil",
                "chip": "acpitz_1",
                "label": "acpitz_1",
                "current_c": 29.8,
                "high_c": None,
                "critical_c": None,
            },
        ]
        data = self._temp(sensors, [])
        self.assertTrue(data["available"])
        self.assertAlmostEqual(data["package_temp"], 29.8)
        self.assertEqual(data["cpu"], data["package_temp"])
        self.assertEqual(data["max_c"], 29.8)


class WebSocketPayloadSchemaTests(unittest.TestCase):
    def _assemble(self, cpu, temp):
        import metrics_hub
        import system_metrics

        now = int(time.time())
        parts = {
            "system": system_metrics.system_info(),
            "cpu": cpu,
            "memory": system_metrics.memory_metrics(),
            "storage": {"filesystems": [], "io": {}},
            "network": {"interfaces": [], "addresses": [], "io": {}, "active": False},
            "temperature": temp,
            "docker": {"containers": [], "available": False},
        }
        meta = {k: {"ok": True, "duration_ms": 1.0} for k in parts}
        payload = metrics_hub._assemble(parts, meta)
        payload["sequence"] = 1
        payload["collected_at"] = now
        payload["timestamp"] = now
        return payload

    def test_payload_schema_and_cpu_object_format(self):
        cpu = {
            "usage_percent": 50.0,
            "cores": 4,
            "logical_cores": 4,
            "per_core": [
                {"core": 0, "core_id": 0, "usage_percent": 10.0},
                {"core": 1, "core_id": 1, "usage_percent": 20.0},
            ],
            "per_core_usage": [10.0, 20.0],
            "per_core_frequency_mhz": None,
            "frequency": None,
            "load_average": [],
            "timestamp": int(time.time()),
        }
        temp = {
            "available": True,
            "sensors": [],
            "max_c": 64.0,
            "package_temp": 64.0,
            "cpu": 64.0,
            "timestamp": int(time.time()),
        }
        payload = self._assemble(cpu, temp)
        self.assertEqual(payload["type"], "metrics")
        self.assertIsInstance(payload["timestamp"], int)
        for key in ("system", "cpu", "memory", "temperature"):
            self.assertIn(key, payload)
        self.assertEqual(
            payload["cpu"]["per_core"][0], {"core": 0, "core_id": 0, "usage_percent": 10.0}
        )
        self.assertEqual(payload["cpu"]["per_core_usage"], [10.0, 20.0])
        self.assertEqual(payload["temperature"]["package_temp"], 64.0)
        self.assertEqual(payload["temperature"]["cpu"], 64.0)
        self.assertIsInstance(payload["system"]["timestamp"], int)

    def test_payload_temperature_null_when_unavailable(self):
        cpu = {
            "usage_percent": 0.0,
            "cores": 0,
            "logical_cores": 0,
            "per_core": [],
            "per_core_usage": [],
            "per_core_frequency_mhz": None,
            "frequency": None,
            "load_average": [],
            "timestamp": int(time.time()),
        }
        temp = {
            "available": False,
            "sensors": [],
            "max_c": None,
            "package_temp": None,
            "cpu": None,
            "timestamp": int(time.time()),
        }
        payload = self._assemble(cpu, temp)
        self.assertIsNone(payload["temperature"]["package_temp"])
        self.assertIsNone(payload["temperature"]["cpu"])
        self.assertIsNone(payload["temperature"]["max_c"])
        self.assertEqual(payload["cpu"]["per_core"], [])


class JsonSafetyTests(unittest.TestCase):
    """Non-finite normalization: REST and WS must never emit NaN/Infinity."""

    def test_sanitize_json_replaces_non_finite(self):
        from json_utils import sanitize_json

        payload = {
            "nan": float("nan"),
            "pos_inf": float("inf"),
            "neg_inf": float("-inf"),
            "ok": 1.5,
            "nested": [1.0, float("nan"), {"deep": float("inf")}],
            "int": 7,
            "text": "x",
            "flag": True,
            "none": None,
        }
        clean = sanitize_json(payload)
        self.assertIsNone(clean["nan"])
        self.assertIsNone(clean["pos_inf"])
        self.assertIsNone(clean["neg_inf"])
        self.assertEqual(clean["ok"], 1.5)
        self.assertEqual(clean["nested"][0], 1.0)
        self.assertIsNone(clean["nested"][1])
        self.assertIsNone(clean["nested"][2]["deep"])
        self.assertEqual(clean["int"], 7)
        self.assertEqual(clean["text"], "x")
        self.assertTrue(clean["flag"])
        self.assertIsNone(clean["none"])

    def test_safe_json_response_is_valid_json(self):
        import json

        from json_utils import SafeJSONResponse

        body = SafeJSONResponse(content={"a": float("nan"), "b": [1, float("inf")]})
        rendered = body.body.decode()
        self.assertNotIn("NaN", rendered)
        self.assertNotIn("Infinity", rendered)
        parsed = json.loads(rendered, parse_constant=_reject_constant)
        self.assertIsNone(parsed["a"])
        self.assertIsNone(parsed["b"][1])

    def test_regression_per_core_payload_with_non_finite(self):
        # Reproduces a strict-client crash scenario: per_core objects plus a
        # non-finite numeric field must serialize to valid JSON (nulls).
        import json

        from json_utils import sanitize_json

        payload = {
            "type": "metrics",
            "cpu": {
                "usage_percent": float("nan"),
                "per_core": [
                    {"core": 0, "core_id": 0, "usage_percent": 12.5},
                    {"core": 1, "core_id": 1, "usage_percent": float("inf")},
                ],
                "per_core_usage": [12.5, float("inf")],
            },
            "temperature": {
                "available": False,
                "package_temp": None,
                "cpu": None,
                "max_c": None,
            },
        }
        rendered = json.dumps(sanitize_json(payload), allow_nan=False)
        parsed = json.loads(rendered, parse_constant=_reject_constant)
        self.assertIsNone(parsed["cpu"]["usage_percent"])
        self.assertEqual(parsed["cpu"]["per_core"][0]["usage_percent"], 12.5)
        self.assertIsNone(parsed["cpu"]["per_core"][1]["usage_percent"])
        self.assertEqual(parsed["cpu"]["per_core_usage"][0], 12.5)
        self.assertIsNone(parsed["cpu"]["per_core_usage"][1])
        self.assertIsNone(parsed["temperature"]["package_temp"])


def _reject_constant(value):
    raise AssertionError("non-finite token in JSON: %r" % value)


if __name__ == "__main__":
    unittest.main(verbosity=2)