"""Tests for disk I/O throughput, listening ports, and firewall detection.

These cover the three reported problems: disk read/write activity, listening
sockets, and firewall status. They exercise empty data, unavailable commands,
permission errors, missing fields, counter resets, and populated responses.
"""

import os
import sys
import unittest
from unittest import mock

APP_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app"))
sys.path.insert(0, APP_ROOT)


def _io(read, write, rcount=0, wcount=0):
    return mock.Mock(
        read_bytes=read,
        write_bytes=write,
        read_count=rcount,
        write_count=wcount,
        read_time=0,
        write_time=0,
    )


class DiskIoTests(unittest.TestCase):
    def setUp(self):
        import storage_metrics

        storage_metrics._io_prev = {}
        storage_metrics._io_prev_total = None
        storage_metrics._io_at = None

    def _call(self, perdisk, total, monotonic):
        import storage_metrics

        perdisk_map = perdisk
        with mock.patch(
            "storage_metrics.psutil.disk_io_counters",
            side_effect=lambda perdisk=False: (perdisk_map if perdisk else total),
        ), mock.patch("storage_metrics.time.monotonic", return_value=monotonic):
            return storage_metrics.disk_io()

    def test_first_sample_rates_null(self):
        data = self._call({"sda": _io(1000, 500)}, _io(1000, 500), 1000.0)
        self.assertTrue(data["available"])
        self.assertFalse(data["measured"])
        self.assertIsNone(data["total"]["read_bytes_per_sec"])
        self.assertIsNone(data["total"]["write_bytes_per_sec"])
        self.assertEqual(data["unit"], "bytes_per_sec")

    def test_second_sample_computes_rate(self):
        self._call({"sda": _io(1000, 500)}, _io(1000, 500), 1000.0)
        data = self._call({"sda": _io(3000, 1500)}, _io(3000, 1500), 1002.0)
        self.assertTrue(data["measured"])
        self.assertAlmostEqual(data["total"]["read_bytes_per_sec"], 1000.0)
        self.assertAlmostEqual(data["total"]["write_bytes_per_sec"], 500.0)
        self.assertAlmostEqual(data["disks"]["sda"]["read_bytes_per_sec"], 1000.0)
        self.assertEqual(data["elapsed_seconds"], 2.0)

    def test_idle_returns_zero_not_invented(self):
        self._call({"sda": _io(1000, 500)}, _io(1000, 500), 1000.0)
        data = self._call({"sda": _io(1000, 500)}, _io(1000, 500), 1002.0)
        self.assertTrue(data["measured"])
        self.assertEqual(data["total"]["read_bytes_per_sec"], 0.0)
        self.assertEqual(data["total"]["write_bytes_per_sec"], 0.0)
        self.assertEqual(data["disks"]["sda"]["read_bytes_per_sec"], 0.0)

    def test_counter_reset_yields_null(self):
        self._call({"sda": _io(5000, 5000)}, _io(5000, 5000), 1000.0)
        data = self._call({"sda": _io(10, 10)}, _io(10, 10), 1001.0)
        self.assertIsNone(data["total"]["read_bytes_per_sec"])
        self.assertIsNone(data["disks"]["sda"]["read_bytes_per_sec"])

    def test_new_device_yields_null(self):
        self._call({"sda": _io(1000, 500)}, _io(1000, 500), 1000.0)
        data = self._call(
            {"sda": _io(2000, 1000), "sdb": _io(10, 10)}, _io(2000, 1000), 1001.0
        )
        self.assertAlmostEqual(data["disks"]["sda"]["read_bytes_per_sec"], 1000.0)
        self.assertIsNone(data["disks"]["sdb"]["read_bytes_per_sec"])

    def test_device_kind_and_no_double_counting(self):
        perdisk = {
            "sda": _io(100, 100),
            "sda1": _io(80, 80),
            "nvme0n1": _io(50, 50),
            "nvme0n1p1": _io(40, 40),
            "loop0": _io(1, 1),
            "dm-0": _io(2, 2),
        }
        data = self._call(perdisk, _io(999, 999), 1000.0)
        kinds = {k: v["kind"] for k, v in data["disks"].items()}
        self.assertEqual(kinds["sda"], "disk")
        self.assertEqual(kinds["sda1"], "partition")
        self.assertEqual(kinds["nvme0n1"], "disk")
        self.assertEqual(kinds["nvme0n1p1"], "partition")
        self.assertEqual(kinds["loop0"], "virtual")
        self.assertEqual(kinds["dm-0"], "virtual")
        # physical aggregate counts whole disks only (sda + nvme0n1)
        self.assertEqual(data["physical"]["read_bytes"], 150)
        self.assertEqual(data["physical"]["write_bytes"], 150)

    def test_unavailable_when_psutil_raises(self):
        import storage_metrics

        with mock.patch(
            "storage_metrics.psutil.disk_io_counters", side_effect=OSError("boom")
        ):
            data = storage_metrics.disk_io()
        self.assertFalse(data["available"])
        self.assertIsNone(data["total"])
        self.assertEqual(data["disks"], {})

    def test_capacity_and_io_are_separate(self):
        import storage_metrics

        data = storage_metrics.storage_metrics()
        self.assertIn("filesystems", data)
        self.assertIn("io", data)
        self.assertNotIn("total", data)  # capacity is not merged into io


class ListeningPortTests(unittest.TestCase):
    SS_SAMPLE = (
        "tcp   LISTEN 0      4096   0.0.0.0:22      0.0.0.0:*    "
        'users:(("sshd",pid=1234,fd=3))\n'
        "tcp   LISTEN 0      4096   0.0.0.0:443     0.0.0.0:*\n"
        "tcp   LISTEN 0      4096   [::]:22         [::]:*       "
        'users:(("sshd",pid=1234,fd=4))\n'
        "udp   UNCONN 0      0      127.0.0.1:323   0.0.0.0:*\n"
    )

    def test_parse_ss_marks_restricted_and_enriches(self):
        import network_diagnostics as nd

        entries, restricted = nd._parse_ss(self.SS_SAMPLE)
        self.assertTrue(restricted)
        self.assertEqual(len(entries), 4)
        sshd = [e for e in entries if e["port"] == 22 and e["ip_version"] == "ipv4"][0]
        self.assertEqual(sshd["protocol"], "tcp")
        self.assertEqual(sshd["state"], "LISTEN")
        self.assertEqual(sshd["process"], {"name": "sshd", "pid": 1234})
        enriched = nd._enrich(dict(sshd))
        self.assertEqual(enriched["address_family"], "ipv4")
        self.assertEqual(enriched["exposure"], "all-interfaces")
        self.assertEqual(enriched["service"], "ssh")

    def test_exposure_classification(self):
        import network_diagnostics as nd

        self.assertEqual(nd._exposure("127.0.0.1"), "loopback")
        self.assertEqual(nd._exposure("::1"), "loopback")
        self.assertEqual(nd._exposure("0.0.0.0"), "all-interfaces")
        self.assertEqual(nd._exposure("::"), "all-interfaces")
        self.assertEqual(nd._exposure("203.0.113.10"), "specific-address")
        self.assertEqual(nd._exposure(None), "unknown")

    def test_network_ports_has_ports_alias(self):
        import network_diagnostics as nd

        with mock.patch("network_diagnostics.shutil.which", return_value="/usr/bin/ss"), mock.patch(
            "network_diagnostics._ports_via_ss", return_value=(self.SS_SAMPLE, None)
        ):
            data = nd.network_ports()
        self.assertTrue(data["available"])
        self.assertIn("listening", data)
        self.assertIn("ports", data)
        self.assertEqual(data["ports"], data["listening"])
        self.assertEqual(data["counts"]["total"], data["total"])

    def test_network_ports_limit(self):
        import network_diagnostics as nd

        with mock.patch("network_diagnostics.shutil.which", return_value="/usr/bin/ss"), mock.patch(
            "network_diagnostics._ports_via_ss", return_value=(self.SS_SAMPLE, None)
        ):
            data = nd.network_ports(limit=1)
        self.assertEqual(len(data["ports"]), 1)
        self.assertEqual(data["returned"], 1)
        self.assertEqual(data["total"], data["counts"]["total"])

    def test_network_ports_error_not_silent_empty(self):
        import network_diagnostics as nd

        with mock.patch("network_diagnostics.shutil.which", return_value="/usr/bin/ss"), mock.patch(
            "network_diagnostics._ports_via_ss", return_value=(None, "failed")
        ), mock.patch("network_diagnostics._ports_via_proc", return_value=[]):
            data = nd.network_ports()
        self.assertFalse(data["available"])
        self.assertIsNotNone(data["error"])
        self.assertEqual(data["ports"], [])

    def test_network_ports_empty_is_available_when_no_tool_error(self):
        import network_diagnostics as nd

        with mock.patch("network_diagnostics.shutil.which", return_value=None), mock.patch(
            "network_diagnostics._ports_via_proc", return_value=[]
        ):
            data = nd.network_ports()
        self.assertTrue(data["available"])
        self.assertIsNone(data["error"])
        self.assertEqual(data["ports"], [])


class FirewallTests(unittest.TestCase):
    def _which(self, name):
        return {
            "ufw": "/usr/sbin/ufw",
            "nft": "/usr/sbin/nft",
            "iptables": "/usr/sbin/iptables",
        }.get(name)

    def test_ufw_active_from_conf_and_service(self):
        import network_diagnostics as nd

        def fake_read(path):
            if path == "/etc/ufw/ufw.conf":
                return "ENABLED=yes\n"
            if path == "/etc/default/ufw":
                return 'DEFAULT_INPUT_POLICY="DROP"\nDEFAULT_OUTPUT_POLICY="ACCEPT"\n'
            return None

        def fake_run(args, timeout=None):
            # ufw status is root-only -> fails
            return {"rc": 1, "stdout": "", "stderr": "permission denied", "error": None}

        with mock.patch("network_diagnostics.shutil.which", side_effect=self._which), mock.patch(
            "network_diagnostics._read_file", side_effect=fake_read
        ), mock.patch("network_diagnostics._run", side_effect=fake_run), mock.patch(
            "network_diagnostics._unit_active", return_value="active"
        ):
            data = nd.network_firewall()
        self.assertEqual(data["backend"], "ufw")
        self.assertTrue(data["enabled"])
        self.assertTrue(data["active"])
        self.assertTrue(data["running"])
        self.assertEqual(data["status"], "active")
        self.assertEqual(data["rules_count"], None)
        self.assertIn("ufw", data["backends"])
        self.assertEqual(data["backends"]["ufw"]["service_active"], "active")
        self.assertEqual(data["default_policies"]["DEFAULT_INPUT_POLICY"], "DROP")

    def test_ufw_unknown_is_not_inactive(self):
        import network_diagnostics as nd

        def fake_run(args, timeout=None):
            return {"rc": 1, "stdout": "", "stderr": "", "error": None}

        with mock.patch("network_diagnostics.shutil.which", side_effect=self._which), mock.patch(
            "network_diagnostics._read_file", return_value=None
        ), mock.patch("network_diagnostics._run", side_effect=fake_run), mock.patch(
            "network_diagnostics._unit_active", return_value=None
        ):
            data = nd.network_firewall()
        self.assertIsNone(data["enabled"])
        self.assertIsNone(data["active"])
        self.assertEqual(data["status"], "unknown")

    def test_multiple_backends_reported_separately(self):
        import network_diagnostics as nd

        def fake_read(path):
            return "ENABLED=yes\n" if path == "/etc/ufw/ufw.conf" else None

        def fake_run(args, timeout=None):
            return {"rc": 1, "stdout": "", "stderr": "", "error": None}

        with mock.patch("network_diagnostics.shutil.which", side_effect=self._which), mock.patch(
            "network_diagnostics._read_file", side_effect=fake_read
        ), mock.patch("network_diagnostics._run", side_effect=fake_run), mock.patch(
            "network_diagnostics._unit_active", return_value="active"
        ):
            data = nd.network_firewall()
        self.assertIn("ufw", data["backends"])
        self.assertIn("nftables", data["backends"])
        self.assertIn("iptables", data["backends"])
        # non-active systems are unknown, not reported inactive
        self.assertIsNone(data["backends"]["nftables"]["active"])


if __name__ == "__main__":
    unittest.main(verbosity=2)