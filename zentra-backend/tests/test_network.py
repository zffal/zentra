"""Tests for accurate network throughput and persistent usage accounting.

Coverage:
* throughput is derived from counter deltas over monotonic elapsed time;
* unmeasurable readings (first sample, counter reset, vanished interface)
  are reported as null, never as a fabricated zero;
* the aggregate excludes virtual/Docker/loopback interfaces so the same
  traffic is not counted more than once;
* persistent accounting accumulates per day, survives restarts, respects the
  configured timezone, prunes by retention, and distinguishes genuine zero
  from unavailable data;
* query/window validation.
"""

import asyncio
import os
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

APP_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app"))
sys.path.insert(0, APP_ROOT)

import network_metrics  # noqa: E402
import network_usage  # noqa: E402
from config import settings  # noqa: E402


def _nic(recv, sent):
    return mock.Mock(bytes_recv=recv, bytes_sent=sent)


def _iface(name, up=True, physical=True, recv=0, sent=0):
    return {
        "name": name,
        "state": "up" if up else "down",
        "is_physical": physical,
        "bytes_recv": recv,
        "bytes_sent": sent,
    }


class ThroughputTests(unittest.TestCase):
    def setUp(self):
        network_metrics.reset_rate_baseline()
        self._patches = [
            mock.patch.object(settings, "NETWORK_AGGREGATE", "physical"),
            mock.patch.object(settings, "NETWORK_INCLUDE_INTERFACES", []),
            mock.patch.object(settings, "NETWORK_EXCLUDE_INTERFACES", []),
            mock.patch.object(settings, "NETWORK_MIN_SAMPLE_INTERVAL", 1.0),
        ]
        for p in self._patches:
            p.start()
            self.addCleanup(p.stop)

    def _call(self, pernic, monotonic, ifaces, route=None):
        side = lambda pernic=False: (  # noqa: E731
            pernic_map[0] if pernic else {}
        )
        pernic_map = [pernic]
        with mock.patch(
            "network_metrics.psutil.net_io_counters", side_effect=side
        ), mock.patch(
            "network_metrics.time.monotonic", return_value=monotonic
        ), mock.patch(
            "network_metrics.interfaces", return_value=ifaces
        ), mock.patch(
            "network_metrics.totals", return_value={}
        ), mock.patch(
            "network_metrics._default_route_interface", return_value=route
        ):
            return network_metrics.network_metrics()

    def test_interface_kind(self):
        self.assertEqual(network_metrics.interface_kind("lo"), "loopback")
        self.assertEqual(network_metrics.interface_kind("eth0"), "ethernet")
        self.assertEqual(network_metrics.interface_kind("enx00e04c68191c"), "ethernet")
        self.assertEqual(network_metrics.interface_kind("wlan0"), "wifi")
        self.assertEqual(network_metrics.interface_kind("br-abc"), "bridge")
        self.assertEqual(network_metrics.interface_kind("veth123"), "bridge")
        self.assertEqual(network_metrics.interface_kind("tailscale0"), "vpn")
        self.assertEqual(network_metrics.interface_kind("docker0"), "bridge")
        self.assertTrue(network_metrics.is_physical("eth0"))
        self.assertFalse(network_metrics.is_physical("wg0"))

    def test_first_sample_rates_null(self):
        data = self._call({"eth0": _nic(1000, 500)}, 1000.0, [_iface("eth0")])
        self.assertIsNone(data["rates"]["download_bytes_per_sec"])
        self.assertIsNone(data["rates"]["upload_bytes_per_sec"])
        self.assertFalse(data["rates"]["measured"])
        self.assertEqual(data["rates"]["null_reason"], "first_sample")
        self.assertEqual(data["rates"]["unit"], "bytes_per_sec")

    def test_second_sample_computes_rates(self):
        ifaces = [_iface("eth0")]
        self._call({"eth0": _nic(1000, 500)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(3000, 1500)}, 1002.0, ifaces)
        self.assertTrue(data["rates"]["measured"])
        self.assertAlmostEqual(data["rates"]["download_bytes_per_sec"], 1000.0)
        self.assertAlmostEqual(data["rates"]["upload_bytes_per_sec"], 500.0)
        eth0 = next(i for i in data["interfaces"] if i["name"] == "eth0")
        self.assertAlmostEqual(eth0["rates"]["download_bytes_per_sec"], 1000.0)

    def test_rates_interfaces_is_per_interface_map(self):
        """Android NetworkRates.interfaces is a Map<String, InterfaceRateItem>."""
        ifaces = [_iface("eth0"), _iface("wlan0", up=False)]
        self._call(
            {"eth0": _nic(0, 0), "wlan0": _nic(0, 0)}, 1000.0, ifaces, route="eth0"
        )
        data = self._call(
            {"eth0": _nic(2000, 1000), "wlan0": _nic(0, 0)},
            1002.0,
            ifaces,
            route="eth0",
        )
        rates = data["rates"]
        self.assertIsInstance(rates["interfaces"], dict)
        self.assertIn("eth0", rates["interfaces"])
        eth0_rate = rates["interfaces"]["eth0"]
        self.assertEqual(
            set(eth0_rate),
            {"download_bytes_per_sec", "upload_bytes_per_sec", "is_up", "speed_mbps"},
        )
        self.assertAlmostEqual(eth0_rate["download_bytes_per_sec"], 1000.0)
        self.assertTrue(eth0_rate["is_up"])
        self.assertFalse(rates["interfaces"]["wlan0"]["is_up"])
        self.assertEqual(rates["included_interfaces"], ["eth0"])

    def test_interface_entries_carry_client_aliases(self):
        """Each NetworkInterface entry needs is_up/ip_address/flat rates for the client."""
        ifaces = [_iface("eth0")]
        self._call({"eth0": _nic(0, 0)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(2000, 1000)}, 1002.0, ifaces)
        eth0 = next(i for i in data["interfaces"] if i["name"] == "eth0")
        self.assertIn("is_up", eth0)
        self.assertTrue(eth0["is_up"])
        self.assertIn("ip_address", eth0)
        self.assertIn("download_bytes_per_sec", eth0)
        self.assertIn("upload_bytes_per_sec", eth0)
        self.assertAlmostEqual(eth0["download_bytes_per_sec"], 1000.0)

    def test_network_metrics_top_level_aliases(self):
        ifaces = [_iface("eth0")]
        self._call({"eth0": _nic(0, 0)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(2000, 1000)}, 1002.0, ifaces)
        self.assertEqual(data["download_speed_bps"], 1000)
        self.assertEqual(data["upload_speed_bps"], 500)

    def test_aggregate_excludes_virtual_interfaces(self):
        ifaces = [
            _iface("eth0", physical=True),
            _iface("br-abc", physical=False),
            _iface("veth1", physical=False),
            _iface("lo", physical=False),
        ]
        self._call(
            {
                "eth0": _nic(0, 0),
                "br-abc": _nic(0, 0),
                "veth1": _nic(0, 0),
                "lo": _nic(0, 0),
            },
            1000.0,
            ifaces,
        )
        data = self._call(
            {
                "eth0": _nic(2000, 1000),
                "br-abc": _nic(9000, 9000),
                "veth1": _nic(9000, 9000),
                "lo": _nic(500, 500),
            },
            1002.0,
            ifaces,
        )
        self.assertEqual(data["aggregate"]["interfaces"], ["eth0"])
        self.assertAlmostEqual(data["rates"]["download_bytes_per_sec"], 1000.0)
        self.assertAlmostEqual(data["rates"]["upload_bytes_per_sec"], 500.0)
        self.assertIn("lo", data["aggregate"]["excluded"])
        self.assertIn("veth1", data["aggregate"]["excluded"])

    def test_aggregate_all_mode_counts_everything(self):
        settings.NETWORK_AGGREGATE = "all"
        ifaces = [_iface("eth0", physical=True), _iface("veth1", physical=False)]
        self._call({"eth0": _nic(0, 0), "veth1": _nic(0, 0)}, 1000.0, ifaces)
        data = self._call(
            {"eth0": _nic(1000, 0), "veth1": _nic(1000, 0)}, 1001.0, ifaces
        )
        self.assertEqual(data["rates"]["mode"], "all")
        self.assertAlmostEqual(data["rates"]["download_bytes_per_sec"], 2000.0)

    def test_aggregate_primary_mode(self):
        settings.NETWORK_AGGREGATE = "primary"
        ifaces = [_iface("eth0", physical=True), _iface("wlan0", physical=True)]
        self._call(
            {"eth0": _nic(0, 0), "wlan0": _nic(0, 0)}, 1000.0, ifaces, route="wlan0"
        )
        data = self._call(
            {"eth0": _nic(1000, 0), "wlan0": _nic(8000, 0)},
            1001.0,
            ifaces,
            route="wlan0",
        )
        self.assertEqual(data["aggregate"]["interfaces"], ["wlan0"])
        self.assertEqual(data["primary"]["interface"], "wlan0")
        self.assertAlmostEqual(data["rates"]["download_bytes_per_sec"], 8000.0)

    def test_include_and_exclude_overrides(self):
        settings.NETWORK_INCLUDE_INTERFACES = ["eth0", "eth1"]
        settings.NETWORK_EXCLUDE_INTERFACES = ["eth1"]
        ifaces = [_iface("eth0", physical=True), _iface("eth1", physical=True)]
        self._call({"eth0": _nic(0, 0), "eth1": _nic(0, 0)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(1000, 0), "eth1": _nic(5000, 0)}, 1001.0, ifaces)
        self.assertEqual(data["rates"]["mode"], "custom")
        self.assertEqual(data["aggregate"]["interfaces"], ["eth0"])
        self.assertAlmostEqual(data["rates"]["download_bytes_per_sec"], 1000.0)

    def test_aggregate_null_when_a_member_is_unmeasured(self):
        ifaces = [_iface("eth0", physical=True), _iface("eth1", physical=True)]
        self._call({"eth0": _nic(0, 0), "eth1": _nic(1000, 0)}, 1000.0, ifaces)
        # eth1 resets this window -> aggregate cannot be summed reliably.
        data = self._call(
            {"eth0": _nic(1000, 0), "eth1": _nic(5, 0)}, 1001.0, ifaces
        )
        self.assertIsNone(data["rates"]["download_bytes_per_sec"])
        self.assertFalse(data["rates"]["measured"])
        self.assertEqual(data["rates"]["null_reason"], "counter_reset")

    def test_counter_reset_yields_null_not_negative(self):
        ifaces = [_iface("eth0")]
        self._call({"eth0": _nic(10000, 10000)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(10, 10)}, 1002.0, ifaces)
        self.assertIsNone(data["rates"]["download_bytes_per_sec"])
        self.assertEqual(data["rates"]["null_reason"], "counter_reset")
        self.assertGreaterEqual(data["rates"]["download_bytes_per_sec"] or 0, 0)

    def test_min_interval_reuses_cache(self):
        ifaces = [_iface("eth0")]
        self._call({"eth0": _nic(1000, 1000)}, 1000.0, ifaces)
        first = self._call({"eth0": _nic(2000, 2000)}, 1000.5, ifaces)
        self.assertIsNone(first["rates"]["download_bytes_per_sec"])  # cached baseline
        second = self._call({"eth0": _nic(4000, 4000)}, 1002.0, ifaces)
        self.assertAlmostEqual(second["rates"]["download_bytes_per_sec"], 1500.0)

    def test_down_interfaces_excluded_from_aggregate(self):
        ifaces = [_iface("eth0", up=True), _iface("eth1", up=False)]
        self._call({"eth0": _nic(0, 0), "eth1": _nic(0, 0)}, 1000.0, ifaces)
        data = self._call({"eth0": _nic(1000, 0), "eth1": _nic(1000, 0)}, 1001.0, ifaces)
        self.assertEqual(data["aggregate"]["interfaces"], ["eth0"])


class UsageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.uf = self.tmp / "network_usage.json"
        self._patches = [
            mock.patch.object(network_usage, "USAGE_FILE", self.uf),
            mock.patch.object(network_usage, "DATA_DIR", self.tmp),
            mock.patch.object(settings, "NETWORK_USAGE_RETENTION_DAYS", 100000),
            mock.patch.object(settings, "NETWORK_USAGE_TZ", "UTC"),
            mock.patch.object(settings, "NETWORK_MONITORED_INTERFACE", ""),
            mock.patch.object(settings, "NETWORK_USAGE_ENABLED", True),
            mock.patch.object(settings, "NETWORK_USAGE_FLUSH_SECONDS", 10 ** 9),
        ]
        for p in self._patches:
            p.start()
            self.addCleanup(p.stop)
        self.u = network_usage.NetworkUsage()

    def _sample(self, counters, now, u=None):
        u = u or self.u
        with mock.patch(
            "network_usage.psutil.net_io_counters", return_value=counters
        ):
            u.sample(now=now)

    def test_first_sample_not_counted(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(5000, 5000)}, t0)
        resp = self.u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertEqual(resp["days"][0]["status"], "no_data")
        self.assertIsNone(resp["days"][0]["rx_bytes"])

    def test_accumulates_and_daily(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(1000, 500)}, t0)
        self._sample({"eth0": _nic(3000, 1500)}, t0 + 10)
        resp = self.u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        day = resp["days"][0]
        self.assertEqual(day["status"], "ok")
        self.assertEqual(day["rx_bytes"], 2000)
        self.assertEqual(day["tx_bytes"], 1000)
        self.assertEqual(day["total_bytes"], 3000)
        self.assertEqual(day["first_sample_at"], int(t0 + 10))
        self.assertEqual(day["last_sample_at"], int(t0 + 10))
        self.assertEqual(resp["interfaces"], ["eth0"])

    def test_counter_reset_skipped(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(10000, 10000)}, t0)
        self._sample({"eth0": _nic(11000, 11000)}, t0 + 10)
        self._sample({"eth0": _nic(5, 5)}, t0 + 20)  # reset
        self._sample({"eth0": _nic(105, 105)}, t0 + 30)  # rebased, +100
        resp = self.u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertEqual(resp["days"][0]["rx_bytes"], 1100)
        self.assertEqual(self.u.status()["skipped_windows"], 1)

    def test_interface_disappearance_no_negative(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(1000, 1000), "eth1": _nic(1000, 1000)}, t0)
        self._sample({"eth0": _nic(2000, 2000), "eth1": _nic(2000, 2000)}, t0 + 10)
        self._sample({"eth0": _nic(3000, 3000)}, t0 + 20)  # eth1 vanished
        self._sample({"eth0": _nic(4000, 4000), "eth1": _nic(50, 50)}, t0 + 30)
        resp = self.u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertGreaterEqual(resp["days"][0]["rx_bytes"], 0)
        self.assertEqual(self.u.status()["skipped_windows"], 0)

    def test_zero_traffic_is_genuine_zero(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(1000, 1000)}, t0)
        self._sample({"eth0": _nic(1000, 1000)}, t0 + 10)  # no change
        resp = self.u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertEqual(resp["days"][0]["status"], "ok")
        self.assertEqual(resp["days"][0]["total_bytes"], 0)
        # A day with no samples at all is unavailable, not zero.
        missing = self.u.daily(
            network_usage.date(2023, 11, 13), network_usage.date(2023, 11, 13)
        )
        self.assertEqual(missing["days"][0]["status"], "no_data")
        self.assertIn("2023-11-13", missing["missing_days"])

    def test_persistence_across_restart(self):
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(1000, 500)}, t0)
        self._sample({"eth0": _nic(3000, 1500)}, t0 + 10)
        self.u.flush()
        self.assertTrue(self.uf.exists())
        restarted = network_usage.NetworkUsage()
        resp = restarted.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertEqual(resp["days"][0]["rx_bytes"], 2000)
        self.assertEqual(resp["accounting_since"], "2023-11-14")

    def test_retention_prunes_old_days(self):
        settings.NETWORK_USAGE_RETENTION_DAYS = 3
        base = time.time()

        def ts(days_ago):
            return base - days_ago * 86400

        self._sample({"eth0": _nic(0, 0)}, ts(6))
        self._sample({"eth0": _nic(100, 0)}, ts(5))
        self._sample({"eth0": _nic(300, 0)}, ts(2))
        self._sample({"eth0": _nic(400, 0)}, ts(1))
        self._sample({"eth0": _nic(500, 0)}, ts(0))
        self.u.flush()
        days = set(self.u._series.get("eth0", {}).keys())
        old = network_usage.datetime.fromtimestamp(base - 5 * 86400, tz=timezone.utc).date().isoformat()
        self.assertNotIn(old, days)
        self.assertLessEqual(len(days), 3)

    def test_timezone_day_boundary(self):
        settings.NETWORK_USAGE_TZ = "Etc/GMT-12"  # UTC+12
        u = network_usage.NetworkUsage()
        self.assertEqual(u.status()["timezone"], "Etc/GMT-12")
        base = datetime(2026, 2, 28, 19, 0, tzinfo=timezone.utc).timestamp()
        self._sample({"eth0": _nic(1000, 0)}, base, u=u)
        self._sample({"eth0": _nic(2000, 0)}, base + 3600, u=u)  # 20:00 UTC = Mar 1
        mar1 = u.daily(network_usage.date(2026, 3, 1), network_usage.date(2026, 3, 1))
        self.assertEqual(mar1["days"][0]["rx_bytes"], 1000)
        feb28 = u.daily(network_usage.date(2026, 2, 28), network_usage.date(2026, 2, 28))
        self.assertEqual(feb28["days"][0]["status"], "no_data")

    def test_monthly_and_top(self):
        t0 = 1_700_000_000.0  # 2023-11-14
        self._sample({"eth0": _nic(0, 0)}, t0)
        self._sample({"eth0": _nic(1000, 0)}, t0 + 86400)  # 2023-11-15, +1000
        self._sample({"eth0": _nic(4000, 0)}, t0 + 2 * 86400)  # 2023-11-16, +3000
        monthly = self.u.monthly(
            network_usage.date(2023, 11, 1), network_usage.date(2023, 11, 30)
        )
        self.assertEqual(monthly["months"][0]["total_bytes"], 4000)
        self.assertEqual(monthly["months"][0]["days_with_data"], 2)
        top = self.u.top(
            network_usage.date(2023, 11, 1), network_usage.date(2023, 11, 30), limit=1
        )
        self.assertEqual(top["days"][0]["date"], "2023-11-16")
        self.assertEqual(top["days"][0]["total_bytes"], 3000)

    def test_monitored_interface_selection(self):
        settings.NETWORK_MONITORED_INTERFACE = "eth1"
        u = network_usage.NetworkUsage()
        t0 = 1_700_000_000.0
        self._sample({"eth0": _nic(0, 0), "eth1": _nic(0, 0)}, t0, u=u)
        self._sample({"eth0": _nic(9000, 0), "eth1": _nic(50, 0)}, t0 + 10, u=u)
        resp = u.daily(
            network_usage.date(2023, 11, 14), network_usage.date(2023, 11, 14)
        )
        self.assertEqual(resp["interfaces"], ["eth1"])
        self.assertEqual(resp["days"][0]["rx_bytes"], 50)

    def test_parse_helpers(self):
        self.assertEqual(network_usage.parse_date("2026-01-31").day, 31)
        self.assertEqual(network_usage.parse_month("2026-07").month, 7)
        with self.assertRaises(ValueError):
            network_usage.parse_date("2026-13-01")
        with self.assertRaises(ValueError):
            network_usage.parse_date("nonsense")
        with self.assertRaises(ValueError):
            network_usage.parse_month("2026/07")

    def test_resolve_range_validation(self):
        start, end = network_usage.resolve_range(
            "2026-01-01", "2026-01-31", timezone.utc, max_days=400
        )
        self.assertEqual((end - start).days, 30)
        with self.assertRaises(ValueError):
            network_usage.resolve_range("2026-02-01", "2026-01-01", timezone.utc, 400)
        with self.assertRaises(ValueError):
            network_usage.resolve_range("2020-01-01", "2026-01-01", timezone.utc, 400)

    def test_resolve_month_range_validation(self):
        s, e = network_usage.resolve_month_range(
            "2026-01", "2026-03", timezone.utc, max_months=24
        )
        self.assertEqual((s.month, e.month), (1, 3))
        with self.assertRaises(ValueError):
            network_usage.resolve_month_range("2026-03", "2026-01", timezone.utc, 24)
        with self.assertRaises(ValueError):
            network_usage.resolve_month_range("2000-01", "2026-01", timezone.utc, 24)

    def test_status_reports_configuration(self):
        st = self.u.status()
        self.assertTrue(st["enabled"])
        self.assertEqual(st["timezone"], "UTC")
        self.assertEqual(st["monitored"], "physical")
        self.assertIn("accounting_since", st)


class ClientContractTests(unittest.TestCase):
    """The Android client talks to /api/network/usage and .../usage/interfaces.

    These exercise the shared building blocks behind those routes: the rate
    field aliasing and the interface catalog used for the selector.
    """

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self._patches = [
            mock.patch.object(
                network_usage, "USAGE_FILE", self.tmp / "network_usage.json"
            ),
            mock.patch.object(network_usage, "DATA_DIR", self.tmp),
            mock.patch.object(settings, "NETWORK_MONITORED_INTERFACE", ""),
            mock.patch.object(settings, "NETWORK_USAGE_RETENTION_DAYS", 100000),
        ]
        for p in self._patches:
            p.start()
            self.addCleanup(p.stop)
        self.u = network_usage.NetworkUsage()

    def test_rate_alias_exposes_short_names(self):
        r = network_usage.rate_alias(
            {"download_bytes_per_sec": 100.0, "upload_bytes_per_sec": 50.0}
        )
        self.assertEqual(r["download_bytes_per_sec"], 100.0)
        self.assertEqual(r["upload_bytes_per_sec"], 50.0)
        self.assertEqual(r["download"], 100.0)
        self.assertEqual(r["upload"], 50.0)
        self.assertEqual(r["unit"], "bytes_per_sec")
        self.assertTrue(r["measured"])

    def test_rate_alias_unmeasured_stays_null(self):
        r = network_usage.rate_alias({})
        self.assertIsNone(r["download"])
        self.assertIsNone(r["upload"])
        self.assertIsNone(r["download_bytes_per_sec"])
        self.assertFalse(r["measured"])

    def test_interface_catalog_aggregate_first_and_physical_only(self):
        self.u._series["enx0"] = {
            "2026-01-01": {"rx": 1, "tx": 1, "samples": 1, "first_ts": 1, "last_ts": 1}
        }
        self.u._series["veth9"] = {
            "2026-01-01": {"rx": 2, "tx": 2, "samples": 1, "first_ts": 1, "last_ts": 1}
        }
        snap = {
            "rates": {
                "download_bytes_per_sec": 10.0,
                "upload_bytes_per_sec": 5.0,
                "available": True,
                "measured": True,
            },
            "interfaces": [
                {
                    "name": "enx0",
                    "is_physical": True,
                    "kind": "ethernet",
                    "state": "up",
                    "ipv4": "10.0.0.2",
                    "bytes_recv": 100,
                    "bytes_sent": 200,
                    "rates": {
                        "download_bytes_per_sec": 1.0,
                        "upload_bytes_per_sec": 2.0,
                    },
                },
                {
                    "name": "br-1",
                    "is_physical": False,
                    "kind": "bridge",
                    "state": "up",
                    "rates": {},
                },
            ],
        }
        with mock.patch("network_metrics.network_metrics", return_value=snap):
            items = self.u.interface_catalog()
        names = [i["name"] for i in items]
        self.assertEqual(names[0], "all")
        self.assertTrue(items[0]["is_aggregate"])
        self.assertIn("enx0", names)
        self.assertNotIn("br-1", names)
        self.assertNotIn("veth9", names)
        enx = next(i for i in items if i["name"] == "enx0")
        self.assertEqual(enx["rates"]["download"], 1.0)
        self.assertEqual(enx["received_bytes"], 100)
        self.assertEqual(enx["transmitted_bytes"], 200)

    def test_interface_catalog_monitored_override(self):
        settings.NETWORK_MONITORED_INTERFACE = "wg0"
        self.addCleanup(setattr, settings, "NETWORK_MONITORED_INTERFACE", "")
        snap = {
            "rates": {},
            "interfaces": [
                {"name": "enx0", "is_physical": True, "state": "up", "rates": {}}
            ],
        }
        with mock.patch("network_metrics.network_metrics", return_value=snap):
            items = self.u.interface_catalog()
        names = [i["name"] for i in items]
        self.assertEqual(names, ["all", "wg0"])


class AppUsageEndpointContractTests(unittest.TestCase):
    """The Android app (com.example.serverdashboard) parses the raw JSON of
    ``GET /api/network/usage`` into ``NetworkUsageResponse`` and
    ``GET /api/network/usage/interfaces`` into a plain ``List<String>``.

    These call the FastAPI handlers directly (as the alerts tests do) and pin
    the exact field names/shapes the client's Gson models require. A previous
    revision returned ``days`` as an integer and ``interfaces`` as objects,
    which made the client throw and show "Failed to load usage data".
    """

    @staticmethod
    def _run(coro):
        return asyncio.new_event_loop().run_until_complete(coro)

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self._patches = [
            mock.patch.object(
                network_usage, "USAGE_FILE", self.tmp / "network_usage.json"
            ),
            mock.patch.object(network_usage, "DATA_DIR", self.tmp),
            mock.patch.object(settings, "NETWORK_MONITORED_INTERFACE", ""),
            mock.patch.object(settings, "NETWORK_USAGE_RETENTION_DAYS", 100000),
            mock.patch.object(settings, "NETWORK_USAGE_ENABLED", True),
        ]
        for p in self._patches:
            p.start()
            self.addCleanup(p.stop)

        self.u = network_usage.NetworkUsage()
        self.today = datetime.now(self.u.tzinfo).date()
        self.yesterday = self.today - timedelta(days=1)
        self.u._series["enx0"] = {
            self.yesterday.isoformat(): {
                "rx": 100,
                "tx": 50,
                "samples": 2,
                "first_ts": 1,
                "last_ts": 2,
            }
        }
        self._snap = {
            "rates": {
                "download_bytes_per_sec": 10.0,
                "upload_bytes_per_sec": 5.0,
                "available": True,
                "measured": True,
            },
            "interfaces": [
                {
                    "name": "enx0",
                    "is_physical": True,
                    "kind": "ethernet",
                    "state": "up",
                    "ipv4": "10.0.0.2",
                    "bytes_recv": 1,
                    "bytes_sent": 2,
                    "rates": {
                        "download_bytes_per_sec": 1.0,
                        "upload_bytes_per_sec": 2.0,
                    },
                }
            ],
        }

    def _query(self):
        import main as main_module

        with mock.patch.object(network_usage, "usage", self.u), mock.patch.object(
            main_module.network_metrics, "network_metrics", return_value=self._snap
        ):
            return self._run(
                main_module.network_usage_query(
                    days=7,
                    iface="all",
                    interface=None,
                    start=None,
                    end=None,
                    limit=10,
                    user={"username": "tester"},
                )
            )

    def test_days_is_a_list_of_daily_objects(self):
        resp = self._query()
        self.assertIsInstance(resp["days"], list)
        self.assertEqual(len(resp["days"]), 7)
        day = resp["days"][-2]  # yesterday
        self.assertEqual(day["date"], self.yesterday.isoformat())
        self.assertEqual(day["rx_bytes"], 100)
        self.assertEqual(day["tx_bytes"], 50)
        self.assertEqual(day["total_bytes"], 150)
        for item in resp["days"]:
            self.assertIsInstance(item["rx_bytes"], int)
            self.assertIsInstance(item["tx_bytes"], int)
            self.assertIsInstance(item["total_bytes"], int)

    def test_interfaces_is_a_list_of_strings(self):
        resp = self._query()
        self.assertIsInstance(resp["interfaces"], list)
        self.assertTrue(all(isinstance(n, str) for n in resp["interfaces"]))
        self.assertEqual(resp["interfaces"], ["enx0"])
        self.assertNotIn("all", resp["interfaces"])

    def test_totals_uses_rx_tx_total_keys(self):
        resp = self._query()
        self.assertEqual(resp["totals"]["rx"], 100)
        self.assertEqual(resp["totals"]["tx"], 50)
        self.assertEqual(resp["totals"]["total"], 150)

    def test_peak_and_tracking_since_present(self):
        resp = self._query()
        self.assertEqual(resp["peak"]["date"], self.yesterday.isoformat())
        self.assertEqual(resp["peak"]["total_bytes"], 150)
        self.assertIsInstance(resp["tracking_since"], str)
        self.assertEqual(resp["tracking_since"], self.yesterday.isoformat())
        self.assertIsInstance(resp["timezone"], str)

    def test_backward_compatible_fields_kept(self):
        resp = self._query()
        self.assertEqual(resp["days_count"], 7)
        self.assertEqual(resp["range_days"], 7)
        self.assertIn("interface_catalog", resp)
        self.assertIn("daily", resp)

    def test_interfaces_endpoint_returns_string_array(self):
        import main as main_module

        with mock.patch.object(network_usage, "usage", self.u), mock.patch.object(
            main_module.network_metrics, "network_metrics", return_value=self._snap
        ):
            resp = self._run(
                main_module.network_usage_interfaces(user={"username": "tester"})
            )
        self.assertEqual(resp, ["enx0"])


class NetworkRestartRouteTests(unittest.TestCase):
    """The Android client POSTs to /api/network/restart (not .../restart-manager)."""

    def _routes(self):
        import main as main_module

        return {(r.path, tuple(sorted(getattr(r, "methods", []) or []))): r.endpoint
                for r in main_module.app.routes if hasattr(r, "path")}

    def test_restart_alias_route_registered(self):
        routes = self._routes()
        key = ("/api/network/restart", ("POST",))
        self.assertIn(key, routes, "POST /api/network/restart route missing")
        self.assertIn(("/api/network/restart-manager", ("POST",)), routes)

    def test_restart_alias_same_endpoint_as_manager(self):
        import main as main_module

        routes = self._routes()
        self.assertIs(
            routes[("/api/network/restart", ("POST",))],
            routes[("/api/network/restart-manager", ("POST",))],
        )
        self.assertIs(
            routes[("/api/network/restart", ("POST",))],
            main_module.network_restart_manager,
        )


if __name__ == "__main__":
    unittest.main()
