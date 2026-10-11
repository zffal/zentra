# Zentra

**Your infrastructure. One place.**

## 1. What is Zentra?

**Zentra is a self-hosted monitoring server.** It reads your server's health
(CPU, RAM, disk, network, temperature, processes, Docker containers) and
shows it live in the **Zentra Android app**.

- Runs on any Ubuntu Server (x86_64 or ARM64)
- One install command, no manual Python/systemd/Docker setup
- Works on your local network and over Tailscale
- No cloud account, no telemetry, your data stays on your server

---

## 2. Requirements

| Requirement | Details |
|---|---|
| OS | Ubuntu Server (22.04 or newer, x86_64 or ARM64) |
| Access | An account with `sudo` (root) |
| Network | The server and your phone on the same LAN or Tailscale |
| Optional | Docker — enables container monitoring/controls |

Nothing else is needed. The installer adds only what is missing
(`python3`, `python3-venv`).

---

## 3. Install

Download the release package, extract it, run one command:

```bash
tar -xzf zentra-server-1.0.0.tar.gz
cd zentra-server-1.0.0
sudo bash install.sh
```

The installer prints what it does:

```
Zentra Server Installer
=======================

  ✓ Ubuntu detected (24.04)
  ✓ Architecture x86_64
  ✓ Python detected (Python 3.12.3)
  ✓ Docker detected
  ✓ Creating Zentra user
  ✓ Installing Zentra
  ✓ Installing Python dependencies
  ✓ Generating secure configuration
  ✓ Configuring authentication (create your account in the app)
  ✓ Configuring Docker access (local socket only)
  ✓ Creating system service
  ✓ Starting Zentra
  ✓ Health check OK (version 1.0.0)
  ✓ Port 8765 listening
```

**It is safe to run twice.** A re-run keeps your configuration and all
accounts, and changes nothing if nothing changed.

After the release is hosted, the one-liner form will be:

```bash
curl -fsSL https://YOUR-ZENTRA-DOMAIN/install.sh | sudo bash
```

**Security notes:** the installer never opens ports to the public internet,
never disables your firewall, never touches SSH, and never exposes the
Docker socket over TCP.

### Migrating an existing Omnis install

If this server already runs the old **Omnis** backend, you do **not** need a
fresh install. Run the migration instead:

```bash
tar -xzf zentra-server-1.0.0.tar.gz
cd zentra-server-1.0.0
sudo bash scripts/migrate-from-omnis.sh
```

It installs Zentra to `/opt/zentra`, keeps your accounts, `.env` (JWT
secret), thresholds and alert history, stops the old `omnis` service and
starts `zentra` on the same port. The old `/opt/omnis` install is left
untouched and an archive is written to `/root`, so you can always roll back:

```bash
sudo systemctl disable --now zentra
sudo systemctl enable  --now omnis
```

---

## 4. Connect the Android app

The installer ends with your server address, for example:

```
Server address:

http://192.168.1.100:8765
```

1. Open the **Zentra** Android app
2. Enter that address (include `http://` and the port)
3. If you use Tailscale, the app also works with the Tailscale address,
   e.g. `http://100.64.0.1:8765`

Can't reach it? Both devices must be on the same network (or both
connected to the same Tailscale network).

---

## 5. Create account

Tap **Create account** in the app and choose your username and password.

- There is **no default account** — the first account you create becomes
  the admin
- Passwords are stored only as salted PBKDF2-SHA256 hashes (never in
  plain text)
- Anyone who can reach the server address can register, so keep the
  server on private networks only

---

## 6. Docker setup

If Docker is installed, the installer automatically:

- creates the dedicated, non-login system user `zentra`
- grants it access to the **local** Docker socket (`/var/run/docker.sock`)
  via the `docker` group

That lets the app show every container (CPU, memory, network, health,
logs) and start/stop/restart them — nothing more. There is no shell/exec
endpoint, and the Docker TCP port (2375) is never opened.

Without Docker, everything else still works; container panels simply
report "Docker not installed".

---

## 7. Wake-on-LAN

Zentra does not send magic packets from the server. The **Android app
sends the Wake-on-LAN packet itself** directly on your network — which
is the reliable way to do it.

Configure in the app:

| Setting | Typical value |
|---|---|
| MAC address | MAC of the machine to wake (e.g. `aa:bb:cc:dd:ee:ff`) |
| Broadcast address | `255.255.255.255` or your subnet, e.g. `192.168.1.255` |
| UDP port | `9` (default) |

Wake-on-LAN also has to be enabled in that machine's BIOS/UEFI and its
network card settings. If the machine or network doesn't support WOL,
no software can wake it.

---

## 8. Updates

```bash
sudo zentra update                                  # update from the installed package
sudo zentra update /path/to/zentra-server.tar.gz     # update from a new release
```

Every update automatically:

1. backs up configuration, accounts and the current backend
   to `/opt/zentra/backups/<timestamp>/`
2. installs the new backend files
3. restarts the service
4. verifies the health endpoint
5. **rolls back automatically** if the health check fails

User accounts and settings are never deleted by an update.

---

## 9. Uninstall

```bash
sudo zentra uninstall
```

It asks for confirmation and removes only the service. Your accounts,
`.env` settings and backups stay in `/opt/zentra`.

To delete everything (an archive is saved to `/root` first):

```bash
sudo zentra uninstall --purge
```

---

## 10. Troubleshooting

| Symptom | Fix |
|---|---|
| App can't connect | `zentra status` — check Service, Port and Health lines |
| "unreachable" health | `zentra logs` (or `sudo zentra logs -f`) to see errors |
| Service not running | `sudo zentra restart` then `zentra status` |
| Forgot what address to use | `zentra status` shows the LAN/Tailscale URLs |
| Wrong port changed by hand | Port lives in `/opt/zentra/.env` (`ZENTRA_PORT`), then `sudo zentra restart` |
| Docker panels empty | User must be in the `docker` group — re-run `sudo bash install.sh` |
| App shows 0/empty after connect | Log out/in once; if it persists, check `zentra status` health values |

Check everything at once:

```bash
zentra status
```

```
Service:   active (enabled)
Port:      8765 (listening)
Health:    ok (version 1.0.0, up 2d 3h 12m)
Docker:    available (21 containers running)
LAN:       http://192.168.1.100:8765
Tailscale: http://100.64.0.1:8765
```

---

---

## Appendix: Advanced Linux information

**Layout**

```
/opt/zentra/
├── app/               backend source (FastAPI + metric collectors)
├── venv/              Python virtual environment (root-owned)
├── .env               configuration, 600 zentra:zentra (JWT secret inside)
├── data/
│   ├── users.json          accounts: PBKDF2-SHA256 hashes only
│   ├── alerts.json         alert history (retention-capped)
│   └── network_usage.json  daily per-interface network usage history
├── backups/           automatic update backups
├── dist/              copy of the package used to install/update
├── scripts/           zentra helper scripts (update, uninstall)
├── requirements.txt
└── README.md
```

**Service**

- Unit: `/etc/systemd/system/zentra.service`
- Runs as system user `zentra` (no login shell), never as root
- `Restart=on-failure`, enabled at boot
- Listens on `0.0.0.0:8765` (single instance for LAN + Tailscale)

**Commands**

```bash
sudo systemctl status zentra       # service state
sudo journalctl -u zentra -f       # raw logs
sudo -u zentra ss -ltnp | grep 8765
```

**API**

- Interactive docs: `http://<server>:8765/docs`
- REST base: `/api/...` (health, system, docker, alerts, auth)
- Live telemetry: WebSocket `ws://<server>:8765/ws/metrics`
  (optional `?token=<JWT>`)
- Read endpoints are open on private networks by default; container
  start/stop/restart, auth and network-restart endpoints always require a
  Bearer token. Set `ZENTRA_REQUIRE_AUTH_FOR_READS=true` in
  `/opt/zentra/.env` to require tokens for everything (restart afterwards).

**Alert management**

| Endpoint | Purpose |
|---|---|
| `GET /api/alerts` | Alert history (newest first) with thresholds and counts |
| `GET /api/alerts/recent` | Most recent alerts only |
| `POST /api/alerts/{id}/resolve` | Mark a single alert resolved (kept) |
| `POST /api/alerts/clear-all` | **Clear All** — permanently delete every alert |

Alerts use a single destructive bulk action: **Clear All**
(`POST /api/alerts/clear-all`, Bearer token required). It permanently deletes
every stored alert record — active, warning, critical and resolved alike — and
returns the number deleted:

```json
{"status": "ok", "deleted": 12, "remaining": 0, "timestamp": 1791714025}
```

It is idempotent: clearing an already-empty store returns `200` with
`"deleted": 0`. Per-alert resolve is unaffected, alert generation keeps
running, and the emptied history is persisted immediately so the deletion
survives a restart. The separate "Clear Resolved" action (`DELETE
/api/alerts/resolved`) has been removed.

**Network diagnostics**

| Endpoint | Purpose |
|---|---|
| `GET /api/network/status` | Network manager, default route, gateway, DNS, addresses |
| `GET /api/network/interfaces` | Per-interface details, speed, MTU, classification |
| `GET /api/network/ports` | Listening TCP/UDP sockets (process/service names when permitted) |
| `GET /api/network/firewall` | Firewall backend, enabled state, default policy (rules if readable) |
| `GET /api/network/usage/status` | Usage-accounting status (timezone, retention, recorded interfaces) |
| `GET /api/network/usage/daily` | Per-day received/transmitted/total bytes for a date range |
| `GET /api/network/usage/monthly` | Per-month byte totals |
| `GET /api/network/usage/top` | Highest-usage days in a range |
| `GET /api/network/usage/interfaces` | Interface **names** for the client picker (bare JSON array of strings) |
| `GET /api/network/usage/interface-catalog` | Rich interface catalogue (aggregate `all` first, then physical) |
| `GET /api/network/usage` | Client summary: usage series + totals + live rates |
| `POST /api/network/restart-manager` | Restart the network manager (see restrictions below) |
| `POST /api/network/restart` | Alias of `restart-manager` (used by the Android client) |

`GET /api/network/usage` accepts `days` (1–`ZENTRA_NETWORK_USAGE_MAX_RANGE_DAYS`,
default 30; out-of-range → `422`) and `iface` (default `all` or a catalogue name).
It returns the Android client contract in the exact field names the app's Gson
models expect:

```json
{
  "days": [{"date": "2026-10-10", "rx_bytes": 0, "tx_bytes": 0, "total_bytes": 0}],
  "interfaces": ["enx00e04c68191c"],
  "peak": {"date": "2026-10-10", "total_bytes": 0},
  "timezone": "UTC",
  "totals": {"rx": 0, "tx": 0, "total": 0},
  "tracking_since": "2026-10-10"
}
```

Backward-compatible extras are kept for existing consumers: `days_count`,
`range_days`, `iface`, `interface_catalog`, `daily`/`monthly`/`top` series,
`rate_alias` rates, `current`, and the decimal-byte aliases
(`rx_bytes`/`tx_bytes`/`total_bytes`/`download_bytes`/`upload_bytes`) alongside
`totals.rx`/`tx`/`total`. Per-day `*_bytes` values are integers (no-data days are
`0`; the client's primitive `long` fields cannot hold `null`).

`GET /api/network/usage/interfaces` returns a bare JSON array of raw interface
names (e.g. `["enx00e04c68191c"]`) with no `all` pseudo-entry — the client
prepends its own `all` chip. `GET /api/network/usage/interface-catalog` returns
the richer objects (aggregate `all` first), each with `name`, `kind`,
`is_aggregate`, `is_physical`, `state`, `ipv4`/`ipv6` and cumulative counters.

`POST /api/network/restart-manager` only works when all of the following are
true — anything else is refused with a clear error:

1. the host actually runs **NetworkManager** (this server uses
   `systemd-networkd` + netplan, so it reports *unsupported* and never
   restarts networking);
2. `ZENTRA_ALLOW_NETWORK_RESTART=true` is set in `/opt/zentra/.env`;
3. a narrowly scoped privilege mechanism (e.g. a passwordless sudo rule for
   exactly that command, or running as root) is available to the service.

Diagnostics are read-only: they never modify ports, routes or firewall
rules. When a value requires elevated privileges it is reported as
*unknown/restricted* rather than guessed.

**Reliable metrics**

All websocket clients and the alert engine consume **one shared snapshot**
produced by a single background collector (`metrics_hub.py`). Each collector
has its own timeout, so a slow or failed collector (e.g. a stalled Docker
daemon) is isolated: the other sections keep updating, the failing section
is marked stale and recovery is automatic on the next cycle. Docker stats
are refreshed on their own cadence (default 5 s) so a heavy Docker sweep
does not slow the snapshot cadence. Every snapshot carries `sequence`,
`collected_at` and per-section `collector` timing metadata; a section is
never presented as fresh when it is stale or missing.

**Response schemas & timestamps**

Timestamps are **Unix epoch seconds** (integer, UTC) everywhere — REST and
WebSocket — so REST and live values share the same units. In the REST system
overview (`GET /api/system`) the top-level `timestamp` plus each embedded
section (`system`, `cpu`, `memory`) carry their own `timestamp`, all taken
within the same collection pass. WebSocket snapshots add two more integers:
`sequence` (monotonically increasing collector cycle) and `collected_at`
(the cycle's epoch seconds; `timestamp` matches it), which let a client detect
gaps or stale data.

CPU per-core data is exposed in two shapes for compatibility:

```json
"per_core": [{"core": 0, "core_id": 0, "usage_percent": 41.2}, ...],
"per_core_usage": [41.2, ...]
```

`per_core` is the object form (`core_id` and `usage_percent` per core);
`per_core_usage` is the flat numeric array. Clients that historically read a
numeric `per_core` array should read `per_core_usage`.

Temperature preserves `package_temp` and additionally exposes a `cpu` alias
with the same value; both are `null` (never `0`) when no sensor is available,
alongside `available: false`, `sensors: []` and `max_c: null`.

All JSON output is guaranteed finite: any non-finite float (NaN/Infinity) from
a collector is normalized to `null` before serialization, so responses are
always valid JSON and a single bad reading cannot turn into a 500 (REST) or an
invalid `NaN` frame (WebSocket).

**Disk I/O, ports and firewall schemas**

- `GET /api/system/storage` returns `filesystems` (capacity) plus `io`
  (activity). `io.disks.<name>` carries cumulative `read_bytes`/`write_bytes`
  and throughput `read_bytes_per_sec`/`write_bytes_per_sec` in bytes per
  second. Throughput is `null` on the first sample and after a counter reset,
  and `0.0` only when the device was genuinely idle — never an invented
  value. `io.physical` aggregates whole disks only (partitions are not counted
  twice); each device reports a `kind` of `disk`, `partition` or `virtual`.
- `GET /api/network/ports` returns listening sockets under both `ports` and
  `listening` (the same list) with `protocol`, `address`, `port`, `state`,
  `address_family`, `exposure` (`loopback` / `all-interfaces` /
  `specific-address`), `service`, and the owning process when permitted.
  Optional `?limit=N`. When a scan tool fails, the result is marked
  `available: false` with an `error` instead of a silent empty list.
- `GET /api/network/firewall` returns `status` (`active` / `inactive` /
  `unknown`) plus the boolean aliases `active`, `running`, `enabled` and
  `installed`, the detected `backend`, and a `backends` map with per-system
  `installed`/`active`. An undetermined check is reported as `unknown`, never
  as a false `inactive`.

**Network throughput & usage telemetry**

`GET /api/system/network` (and the `network` section of every WebSocket
snapshot) reports live throughput. The top-level `rates` object is the
aggregate clients should use:

```json
"rates": {
  "download_bytes_per_sec": 3775.4,
  "upload_bytes_per_sec": 16361.1,
  "download_speed": 3775.4,
  "upload_speed": 16361.1,
  "raw_download_bytes_per_sec": 3775.4,
  "raw_upload_bytes_per_sec": 16361.1,
  "unit": "bytes_per_sec",
  "mode": "physical",
  "included_interfaces": ["enx00e04c68191c"],
  "interfaces": {
    "enx00e04c68191c": {
      "download_bytes_per_sec": 3775.4,
      "upload_bytes_per_sec": 16361.1,
      "is_up": true,
      "speed_mbps": 1000
    }
  },
  "measured": true,
  "null_reason": null,
  "available": true,
  "elapsed_seconds": 1.503,
  "timestamp": 1791704182
}
```

`rates.interfaces` is a **map of per-interface rates** keyed by name (the Android
`NetworkRates.perInterfaceRates` model); the aggregate member names are listed
separately in `included_interfaces` (Android `includedInterfaces`). A plain list
under `interfaces` would make the client's Gson parser throw
*"Expected BEGIN_OBJECT but was BEGIN_ARRAY"*, which is why members were moved.
The top level also exposes flat aliases the Android `NetworkMetrics` model
reads: `download_speed_bps`, `upload_speed_bps`, `total_recv_bytes`,
`total_sent_bytes`, and `calculatedDownloadBps`/`calculatedUploadBps`.

What changed and why it is now correct:

- Throughput is computed from **cumulative counter deltas over the actual
  elapsed time** measured with a **monotonic clock** (not wall-clock), so a
  clock adjustment cannot distort the rate.
- The aggregate **never double-counts traffic**. On a Docker host the same
  bytes cross a `veth` pair, a bridge and the physical NIC; the legacy
  "sum every interface" total inflated the figure several times over. The
  default `mode` is `physical`, which sums real NICs only and lists the
  excluded `loopback`/`bridge`/`veth`/`vpn` interfaces under
  `aggregate.excluded`. Set `ZENTRA_NETWORK_AGGREGATE=primary` to expose only
  the default-route NIC, or `all` to restore the legacy behaviour.
- Values that cannot be measured are reported as **`null`, never a fabricated
  `0.0`**: first sample (`null_reason: "first_sample"`), counter reset such as
  a reboot (`"counter_reset"`) and no elapsed window (`"no_elapsed"`). A real
  `0.0` means the interface was genuinely idle for the window.
- Each entry in `interfaces` carries `kind` (`ethernet`/`wifi`/`bridge`/
  `vpn`/`loopback`), `is_physical`, `is_up`, `ip_address` and both a
  per-interface `rates` object and flat `download_bytes_per_sec`/
  `upload_bytes_per_sec` aliases, so a client can show per-NIC speed. `primary`
  reports the default-route NIC; `aggregate` describes the aggregate (`mode`,
  `interfaces`, `excluded`, `totals`, `rates`). The legacy `total` field is kept
  (all interfaces, cumulative) for backward compatibility.

**Network usage history**

A background sampler records received/transmitted bytes per interface into
daily buckets and persists them to `data/network_usage.json`. It runs
independently of whether the Android app is connected, samples every
`ZENTRA_NETWORK_USAGE_SAMPLE_SECONDS` (default 5 s) and **flushes to disk in
batches** every `ZENTRA_NETWORK_USAGE_FLUSH_SECONDS` (default 60 s), on day
rollover and on shutdown — never on every metrics tick.

Day boundaries use `ZENTRA_NETWORK_USAGE_TZ` (IANA name, default `UTC`). Totals
survive restarts; records older than `ZENTRA_NETWORK_USAGE_RETENTION_DAYS`
(default 400) are pruned. Counter resets/reboots skip that window, a vanished
interface's baseline is dropped, and a rename simply starts a new series.
Query endpoints (`daily`, `monthly`, `top`) take optional `start`, `end`
(`YYYY-MM-DD`; month form `YYYY-MM` for monthly) and `interface`, validate the
range (max `ZENTRA_NETWORK_USAGE_MAX_RANGE_DAYS`) and return `unit: "bytes"`
with an explicit `timezone`. A day is reported `status: "ok"` with
`total_bytes` when accounting confirms the period (a genuine zero is `0`), and
`status: "no_data"` with `null` bytes when no samples exist (e.g. the service
was down), so an unavailable period is never shown as zero usage.

Limitations (documented, not bugs): usage is only recorded while the backend
is running; the byte totals cover this host's interfaces, so they are **not** a
whole-network/router total — traffic between other LAN peers never reaches
this host.

**Authentication internals**

- Passwords: `pbkdf2_sha256` with 310,000 iterations and a per-user salt
- Sessions: JWT (HS256), 12 h default, server-side logout blacklist
- Login attempts: rate-limited (8/min/IP)
- `ZENTRA_AUTO_ADMIN=false` in `.env` guarantees no account exists before
  the first registration (first account = admin)

**Firewall**

The installer only ever *adds* UFW rules for port `8765/tcp` from private
ranges (`10/8`, `172.16/12`, `192.168/16`, `100.64/10`) and only when UFW
is already active. It never deletes rules, never disables UFW.

**Environment variables** — see `.env.example` for the full list
(thresholds, alerts, WebSocket interval, notification providers).
