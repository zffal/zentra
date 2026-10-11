"""Live schema verification against a running Zentra backend.

Checks the actual REST endpoints and the WebSocket stream for the
crash-investigation compatibility contract (per_core objects + numeric alias,
null-vs-zero temps, consistent epoch-second timestamps).

HTTP is done with stdlib urllib; WebSocket uses the 'websockets' package
(already present in the Zentra venv).

Run as the service user so .env/auth are readable:
    sudo -u zentra /opt/zentra/venv/bin/python tests/verify_live.py
Env override: ZENTRA_BASE (default http://127.0.0.1:8765)
Exits 0 when every assertion passes.
"""

import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("ZENTRA_BASE", "http://127.0.0.1:8765")


def http_get(path, token):
    req = urllib.request.Request(
        BASE + path, headers={"Authorization": "Bearer " + token}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read().decode()
    for token_bad in ("NaN", "Infinity"):
        assert token_bad not in body, "FAIL: non-finite token %s in %s" % (token_bad, path)
    return json.loads(body)


def http_post_status(path, token):
    """POST an action endpoint, returning its HTTP status (no body parse)."""
    req = urllib.request.Request(
        BASE + path,
        data=b"",
        headers={"Authorization": "Bearer " + token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code


def http_send(method, path, token=None, data=None):
    """Send a request, returning (status, parsed_json_or_None)."""
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = None
    if data is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(data).encode()
    req = urllib.request.Request(
        BASE + path, data=payload, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode()
            try:
                return resp.status, json.loads(body)
            except ValueError:
                return resp.status, body
    except urllib.error.HTTPError as exc:
        return exc.code, None


def main():
    sys.path.insert(0, os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app")))
    import auth

    token = auth.create_token({"username": "admin", "role": "admin"})["access_token"]
    now = int(time.time())
    checks = 0

    def ok(condition, message):
        nonlocal checks
        assert condition, "FAIL: " + message
        checks += 1
        print("ok -", message)

    # --- /api/system/cpu ---
    cpu = http_get("/api/system/cpu", token)
    ok(isinstance(cpu.get("timestamp"), int), "cpu.timestamp int")
    ok(abs(cpu["timestamp"] - now) <= 60, "cpu.timestamp recent")
    ok(
        all(
            isinstance(e, dict)
            and isinstance(e.get("core"), int)
            and isinstance(e.get("core_id"), int)
            and isinstance(e.get("usage_percent"), (int, float))
            for e in cpu["per_core"]
        ),
        "cpu.per_core is list of {core, core_id, usage_percent}",
    )
    ok(
        isinstance(cpu["per_core_usage"], list)
        and all(isinstance(v, (int, float)) for v in cpu["per_core_usage"]),
        "cpu.per_core_usage numeric array",
    )
    ok(len(cpu["per_core"]) == len(cpu["per_core_usage"]), "per_core/per_core_usage same length")

    # --- /api/system overview ---
    overview = http_get("/api/system", token)
    ok(isinstance(overview.get("timestamp"), int), "overview.timestamp int")
    ok(abs(overview["timestamp"] - now) <= 60, "overview.timestamp recent")
    for key in ("system", "cpu", "memory"):
        ok(isinstance(overview[key].get("timestamp"), int), "overview.%s.timestamp int" % key)
        ok(
            abs(overview[key]["timestamp"] - overview["timestamp"]) <= 120,
            "overview.%s.timestamp consistent w/ top-level" % key,
        )
    ok(all(isinstance(e, dict) for e in overview["cpu"]["per_core"]), "overview.cpu.per_core objects")
    ok(isinstance(overview["cpu"]["per_core_usage"], list), "overview.cpu.per_core_usage present")

    # --- /api/system/temperature ---
    temp = http_get("/api/system/temperature", token)
    ok(isinstance(temp.get("timestamp"), int), "temperature.timestamp int")
    ok("package_temp" in temp and "cpu" in temp, "temperature package_temp + cpu alias present")
    if temp["available"]:
        ok(temp["cpu"] == temp["package_temp"], "temperature cpu alias == package_temp")
        ok(
            isinstance(temp["max_c"], (int, float, type(None))) and temp["max_c"] is not None,
            "temperature max_c numeric when available",
        )
    else:
        ok(
            temp["package_temp"] is None and temp["cpu"] is None and temp["max_c"] is None,
            "temperature null (not zero) when unavailable",
        )

    # --- remaining REST collectors carry timestamp ---
    for path in (
        "/api/system/info",
        "/api/system/memory",
        "/api/system/storage",
        "/api/system/network",
        "/api/system/processes",
    ):
        data = http_get(path, token)
        ok(isinstance(data.get("timestamp"), int), "%s timestamp int" % path)

    # --- /api/system/network telemetry contract ---
    net = http_get("/api/system/network", token)
    rates = net["rates"]
    ok(rates.get("unit") == "bytes_per_sec", "network.rates.unit bytes_per_sec")
    ok("measured" in rates, "network.rates.measured present")
    ok(isinstance(rates.get("interfaces"), dict),
       "network.rates.interfaces is a per-interface Map (Android NetworkRates)")
    ok(isinstance(rates.get("included_interfaces"), list),
       "network.rates.included_interfaces is a list of names")
    if rates["interfaces"]:
        _sample_rate = next(iter(rates["interfaces"].values()))
        ok(set(_sample_rate)
           >= {"download_bytes_per_sec", "upload_bytes_per_sec", "is_up", "speed_mbps"},
           "rates.interfaces entries carry download/upload/is_up/speed_mbps")
    ok("aggregate" in net and "excluded" in net["aggregate"], "network.aggregate present")
    ok("primary" in net, "network.primary present")
    ok(isinstance(net.get("download_speed_bps"), int)
       and isinstance(net.get("upload_speed_bps"), int),
       "network.download_speed_bps/upload_speed_bps ints")
    ok("total_recv_bytes" in net and "total_sent_bytes" in net,
       "network.total_recv_bytes/total_sent_bytes present")
    virtual_prefixes = ("veth", "br-", "docker", "tailscale", "wg", "tun", "tap")
    ok(
        all(
            not (name == "lo" or name.startswith(virtual_prefixes))
            for name in net["aggregate"]["interfaces"]
        ),
        "aggregate excludes loopback/bridge/veth/vpn",
    )
    ok(
        rates["download_bytes_per_sec"]
        == net["aggregate"]["rates"]["download_bytes_per_sec"],
        "top-level rates == aggregate rates",
    )
    ok(
        all("kind" in i and "is_physical" in i and "rates" in i for i in net["interfaces"]),
        "interfaces carry kind/is_physical/rates",
    )
    ok(
        all("is_up" in i and "ip_address" in i
            and "download_bytes_per_sec" in i and "upload_bytes_per_sec" in i
            for i in net["interfaces"]),
        "interfaces carry is_up/ip_address/flat rates (Android NetworkInterface)",
    )

    # Android client POSTs to /api/network/restart; it must exist (not 404) and
    # be guarded. This host has no NetworkManager, so the guarded handler
    # refuses with 501 rather than restarting networking.
    restart_status = http_post_status("/api/network/restart", token)
    ok(restart_status not in (404, 405),
       "POST /api/network/restart route exists (Android alias)")
    ok(restart_status in (400, 403, 501),
       "POST /api/network/restart is guarded (refused, not executed)")
    if not rates["measured"]:
        ok(
            rates["download_bytes_per_sec"] is None
            and rates["upload_bytes_per_sec"] is None,
            "unmeasured rates are null (not fabricated zero)",
        )

    # --- usage accounting endpoints ---
    usage_status = http_get("/api/network/usage/status", token)
    ok("enabled" in usage_status and "timezone" in usage_status, "usage.status fields")
    daily = http_get("/api/network/usage/daily", token)
    ok(daily["unit"] == "bytes" and isinstance(daily["days"], list), "usage.daily shape")
    if daily["days"]:
        d0 = daily["days"][0]
        ok(
            "date" in d0 and "total_bytes" in d0 and "status" in d0,
            "usage.daily entry has date/total_bytes/status",
        )
    monthly = http_get("/api/network/usage/monthly", token)
    ok(isinstance(monthly.get("months"), list), "usage.monthly shape")
    top = http_get("/api/network/usage/top?limit=5", token)
    ok(isinstance(top.get("days"), list) and top.get("limit") == 5, "usage.top shape")

    # --- Android client contract: /api/network/usage + .../usage/interfaces ---
    # The client deserialises .../usage/interfaces as List<String> and
    # /api/network/usage as NetworkUsageResponse.
    ifc = http_get("/api/network/usage/interfaces", token)
    ok(isinstance(ifc, list) and all(isinstance(n, str) for n in ifc),
       "usage.interfaces is a JSON array of strings")
    ok("all" not in ifc, "usage.interfaces omits the 'all' pseudo-entry")

    catalog = http_get("/api/network/usage/interface-catalog", token)
    ok(isinstance(catalog.get("interfaces"), list)
       and catalog["interfaces"]
       and catalog["interfaces"][0].get("name") == "all"
       and catalog["interfaces"][0].get("is_aggregate") is True,
       "usage.interface-catalog aggregate 'all' first")
    ok(all("name" in i and "is_physical" in i for i in catalog["interfaces"]),
       "usage.interface-catalog entries carry name/is_physical")

    client = http_get("/api/network/usage?days=90&iface=all", token)
    ok(client.get("iface") == "all" and client.get("days_count") == 90,
       "usage?days=90&iface=all echoes params")
    ok(isinstance(client.get("days"), list) and len(client["days"]) == 90,
       "usage.days is the daily series spanning the requested window")
    ok(isinstance(client.get("interfaces"), list)
       and all(isinstance(n, str) for n in client["interfaces"]),
       "usage.interfaces is a list of strings")
    ok(isinstance(client.get("monthly"), list) and isinstance(client.get("top"), list),
       "usage includes monthly + top")
    ok("download_bytes_per_sec" in client and "upload_bytes_per_sec" in client,
       "usage exposes top-level live rates")
    ok(client["rates"].get("download") == client["rates"].get("download_bytes_per_sec"),
       "usage rates alias download == download_bytes_per_sec")
    ok(isinstance(client.get("peak"), dict) and "date" in client["peak"]
       and isinstance(client["peak"].get("total_bytes"), int),
       "usage.peak has date + total_bytes")
    ok(isinstance(client.get("tracking_since"), str)
       and isinstance(client.get("timezone"), str),
       "usage.tracking_since + timezone present")
    ok(
        all(k in client["totals"] for k in ("rx", "tx", "total"))
        and client["totals"]["total"] == client["totals"]["rx"] + client["totals"]["tx"],
        "usage.totals has rx/tx/total and total == rx + tx",
    )
    if client["days"]:
        day = client["days"][0]
        ok(all(k in day for k in ("date", "rx_bytes", "tx_bytes", "total_bytes")),
           "usage.days entry has date + rx/tx/total bytes")
        ok(all(isinstance(day[k], int) for k in ("rx_bytes", "tx_bytes", "total_bytes")),
           "usage.days byte fields are integers")

    # --- alerts: single bulk "Clear All" action ---
    alerts = http_get("/api/alerts", token)
    ok(isinstance(alerts.get("count"), int) and isinstance(alerts.get("alerts"), list),
       "alerts list has count + alerts")

    status, cleared = http_send("POST", "/api/alerts/clear-all", token, {})
    ok(status == 200, "clear-all returns 200")
    ok(isinstance(cleared, dict) and cleared.get("status") == "ok",
       "clear-all response status == ok")
    ok(isinstance(cleared.get("deleted"), int) and cleared["deleted"] >= 0,
       "clear-all response has integer 'deleted'")
    ok(cleared.get("remaining") == 0, "clear-all response 'remaining' == 0")
    ok(isinstance(cleared.get("timestamp"), int),
       "clear-all response timestamp is int")

    after = http_get("/api/alerts", token)
    ok(after.get("count") == 0 and after.get("alerts") == [],
       "clear-all permanently deleted every alert record")

    status2, again = http_send("POST", "/api/alerts/clear-all", token, {})
    ok(status2 == 200 and again.get("deleted") == 0,
       "repeated clear-all succeeds with 0 deleted")

    st_resolved, _ = http_send("DELETE", "/api/alerts/resolved", token)
    ok(st_resolved in (404, 405),
       "removed 'clear resolved' endpoint returns 404/405")

    st_resolve, _ = http_send("POST", "/api/alerts/does-not-exist/resolve", token, {})
    ok(st_resolve == 404, "per-alert resolve route still present (404 unknown id)")

    st_anon, _ = http_send("POST", "/api/alerts/clear-all", None, {})
    ok(st_anon in (401, 403), "clear-all requires authentication (401/403)")

    # --- health ---
    health = http_get("/api/health", token)
    ok(isinstance(health.get("timestamp"), int), "health.timestamp int")
    ok(isinstance(health.get("collector"), dict), "health.collector present")

    # --- WebSocket first frame ---
    async def ws_check():
        import websockets

        uri = BASE.replace("http", "ws") + "/ws/metrics?token=" + token
        async with websockets.connect(uri, max_size=2 ** 24, open_timeout=30) as ws:
            frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
        ok(frame.get("type") == "metrics", "ws.type == metrics")
        ok(isinstance(frame.get("timestamp"), int), "ws.timestamp int")
        ok(isinstance(frame.get("sequence"), int), "ws.sequence int")
        ok(isinstance(frame.get("collected_at"), int), "ws.collected_at int")
        ok(
            abs(frame["timestamp"] - frame["collected_at"]) <= 120,
            "ws.timestamp ~ ws.collected_at",
        )
        ok(
            all(isinstance(e, dict) for e in frame["cpu"]["per_core"]),
            "ws.cpu.per_core objects",
        )
        ok("per_core_usage" in frame["cpu"], "ws.cpu.per_core_usage present")
        ok(
            "package_temp" in frame["temperature"] and "cpu" in frame["temperature"],
            "ws.temperature package_temp + cpu alias",
        )
        ok(isinstance(frame["system"].get("timestamp"), int), "ws.system.timestamp int")
        ok(
            "rates" in frame["network"] and "aggregate" in frame["network"],
            "ws.network has rates + aggregate",
        )
        ok(
            "measured" in frame["network"]["rates"],
            "ws.network.rates has measured flag",
        )

    asyncio.run(ws_check())

    # --- concurrency: REST refreshes racing with live WS broadcasts ---
    async def concurrent_check():
        import websockets

        uri = BASE.replace("http", "ws") + "/ws/metrics?token=" + token
        async with websockets.connect(uri, max_size=2 ** 24, open_timeout=30) as ws:
            async def hammer():
                loop = asyncio.get_running_loop()
                for _ in range(5):
                    await loop.run_in_executor(None, http_get, "/api/system", token)
                    await loop.run_in_executor(None, http_get, "/api/system/cpu", token)

            frames = []

            async def read_frames():
                for _ in range(3):
                    frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
                    frames.append(frame)

            await asyncio.gather(hammer(), read_frames())
        ok(len(frames) == 3, "ws delivered frames during concurrent REST load")
        seqs = [f["sequence"] for f in frames]
        ok(seqs == sorted(seqs), "ws sequences monotonic under concurrent load")
        ok(all(f["cpu"]["per_core"] and isinstance(f["cpu"]["per_core"][0], dict) for f in frames),
           "ws per_core objects intact under concurrent load")

    asyncio.run(concurrent_check())

    print("ALL CHECKS PASSED (%d)" % checks)


if __name__ == "__main__":
    main()