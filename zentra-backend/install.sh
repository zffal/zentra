#!/usr/bin/env bash
#
# Zentra Server Installer
# One-command installation for Ubuntu Server.
#
#   sudo bash install.sh
#
# Piped installs (after this script is published) need the package URL:
#   curl -fsSL https://YOUR-ZENTRA-DOMAIN/install.sh | sudo bash
#   (or set ZENTRA_SOURCE_URL to the zentra-server.tar.gz URL)
#
# Safe to re-run: an existing installation, its configuration and all
# user accounts are preserved.

set -euo pipefail

ZENTRA_HOME="/opt/zentra"
ZENTRA_USER="zentra"
SERVICE="zentra"
HEALTH_TIMEOUT=90

log()  { printf '%s\n' "$*"; }
ok()   { printf '  \xe2\x9c\x93 %s\n' "$*"; }
note() { printf '  ! %s\n' "$*"; }
die()  { printf '  \xe2\x9c\x97 %s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------------------
# Locate the Zentra package (this script's folder must contain app/main.py)
# --------------------------------------------------------------------------
resolve_payload() {
    local here candidate tmp main
    here="$(cd "$(dirname "${BASH_SOURCE[0]:-install.sh}")" 2>/dev/null && pwd || pwd)"
    for candidate in "$here" "$PWD"; do
        if [ -f "$candidate/app/main.py" ]; then
            PAYLOAD="$candidate"
            return 0
        fi
    done
    if [ -n "${ZENTRA_SOURCE_URL:-}" ]; then
        tmp="$(mktemp -d)"
        log "  · Downloading Zentra package..."
        curl -fsSL "$ZENTRA_SOURCE_URL" -o "$tmp/package.tar.gz" \
            || die "Could not download ZENTRA_SOURCE_URL"
        tar -xzf "$tmp/package.tar.gz" -C "$tmp" \
            || die "Could not extract the Zentra package"
        main="$(find "$tmp" -maxdepth 3 -path '*/app/main.py' 2>/dev/null | head -n1)"
        [ -n "$main" ] || die "Package does not contain app/main.py"
        PAYLOAD="$(cd "$(dirname "$main")/.." && pwd)"
        return 0
    fi
    die "Zentra files not found next to this script.

Download zentra-server.tar.gz, extract it, then run:
  sudo bash install.sh

For unattended installs set ZENTRA_SOURCE_URL to the package URL."
}

# Wait for the health endpoint (uses python3 only, no curl required)
wait_for_health() {
    python3 - "$1" "$2" <<'PY'
import json, sys, time, urllib.request

port, timeout = int(sys.argv[1]), int(sys.argv[2])
url = "http://127.0.0.1:%d/api/health" % port
deadline = time.time() + timeout
last = "no attempt made"
while time.time() < deadline:
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            data = json.loads(response.read().decode("utf-8"))
        if data.get("status") == "ok":
            print(json.dumps(data))
            sys.exit(0)
        last = "unexpected payload: %r" % data
    except Exception as exc:
        last = str(exc)
    time.sleep(1)
print("health check failed after %ds: %s" % (timeout, last), file=sys.stderr)
sys.exit(1)
PY
}

# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------
log ""
log "Zentra Server Installer"
log "======================="
log ""

[ "$(id -u)" -eq 0 ] || die "Root is required. Run: sudo bash install.sh"

OS_ID="$(. /etc/os-release 2>/dev/null && printf '%s' "${ID:-unknown}" || echo unknown)"
OS_VERSION="$(. /etc/os-release 2>/dev/null && printf '%s' "${VERSION_ID:-}" || echo "")"
if [ "$OS_ID" = "ubuntu" ]; then
    ok "Ubuntu detected${OS_VERSION:+ ($OS_VERSION)}"
elif [ "${ZENTRA_FORCE_OS:-}" = "1" ]; then
    note "Non-Ubuntu system forced: $OS_ID"
else
    die "Ubuntu is required (detected: $OS_ID).

Zentra supports Ubuntu Server on x86_64 and ARM64.
To try anyway: ZENTRA_FORCE_OS=1 sudo -E bash install.sh"
fi

ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)  ok "Architecture x86_64" ;;
    aarch64|arm64) ok "Architecture ARM64" ;;
    *) die "Unsupported architecture: $ARCH

Zentra supports x86_64 and ARM64 servers only."
    ;;
esac

resolve_payload

install_packages() {
    if [ "$#" -eq 0 ]; then
        return 0
    fi
    log "  · Installing system packages: $*"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq "$@" >/dev/null
}

if ! command -v python3 >/dev/null 2>&1; then
    install_packages python3 python3-venv
fi
command -v python3 >/dev/null 2>&1 || die "python3 is still missing after installation"
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null \
    || die "Python 3.10 or newer is required (found: $(python3 -V 2>&1))"
ok "Python detected ($(python3 -V 2>&1))"

DOCKER_DETECTED=0
if [ -S /var/run/docker.sock ] || command -v docker >/dev/null 2>&1; then
    DOCKER_DETECTED=1
    ok "Docker detected"
else
    note "Docker not detected (optional — Zentra works without it)"
fi

# --------------------------------------------------------------------------
# User and directories
# --------------------------------------------------------------------------
if id "$ZENTRA_USER" >/dev/null 2>&1; then
    ok "Zentra user already exists"
else
    useradd --system --home-dir "$ZENTRA_HOME" --no-create-home \
        --shell /usr/sbin/nologin "$ZENTRA_USER"
    ok "Creating Zentra user"
fi

mkdir -p "$ZENTRA_HOME/app" "$ZENTRA_HOME/data" "$ZENTRA_HOME/scripts" \
         "$ZENTRA_HOME/dist" "$ZENTRA_HOME/backups"

# --------------------------------------------------------------------------
# Install backend files (never touches data/ or an existing .env)
# --------------------------------------------------------------------------
APP_CHANGED=0
payload_count="$(find "$PAYLOAD/app" -maxdepth 1 -name '*.py' | wc -l)"
installed_count="$(find "$ZENTRA_HOME/app" -maxdepth 1 -name '*.py' 2>/dev/null | wc -l)"
if [ "$payload_count" -ne "$installed_count" ]; then
    APP_CHANGED=1
else
    for f in "$PAYLOAD"/app/*.py; do
        if ! cmp -s "$f" "$ZENTRA_HOME/app/$(basename "$f")"; then
            APP_CHANGED=1
            break
        fi
    done
fi

shopt -s nullglob
for f in "$PAYLOAD"/app/*.py; do
    install -m 0644 "$f" "$ZENTRA_HOME/app/"
done
shopt -u nullglob
install -m 0644 "$PAYLOAD/requirements.txt" "$ZENTRA_HOME/requirements.txt"
if [ -f "$PAYLOAD/.env.example" ]; then
    install -m 0644 "$PAYLOAD/.env.example" "$ZENTRA_HOME/.env.example"
fi
if [ -f "$PAYLOAD/README.md" ]; then
    install -m 0644 "$PAYLOAD/README.md" "$ZENTRA_HOME/README.md"
fi
shopt -s nullglob
for f in "$PAYLOAD"/scripts/*; do
    if [ -f "$f" ]; then
        install -m 0755 "$f" "$ZENTRA_HOME/scripts/"
    fi
done
shopt -u nullglob
ok "Installing Zentra"

# Keep a copy of this package for "zentra update" (never user data)
if [ "$(readlink -f "$PAYLOAD")" != "$(readlink -f "$ZENTRA_HOME/dist")" ]; then
    rm -rf "$ZENTRA_HOME/dist.new"
    mkdir -p "$ZENTRA_HOME/dist.new"
    cp -a "$PAYLOAD/." "$ZENTRA_HOME/dist.new/"
    rm -rf "$ZENTRA_HOME/dist"
    mv "$ZENTRA_HOME/dist.new" "$ZENTRA_HOME/dist"
fi

# --------------------------------------------------------------------------
# Python environment
# --------------------------------------------------------------------------
if [ ! -x "$ZENTRA_HOME/venv/bin/python" ] || [ ! -x "$ZENTRA_HOME/venv/bin/pip" ]; then
    rm -rf "$ZENTRA_HOME/venv"
    if ! python3 -m venv "$ZENTRA_HOME/venv" >/dev/null 2>&1 \
            || [ ! -x "$ZENTRA_HOME/venv/bin/pip" ]; then
        install_packages python3-venv
        rm -rf "$ZENTRA_HOME/venv"
        python3 -m venv "$ZENTRA_HOME/venv" \
            || die "Could not create the Python environment"
    fi
    ok "Creating Python environment"
fi
"$ZENTRA_HOME/venv/bin/python" -m pip install --quiet --disable-pip-version-check \
    -r "$ZENTRA_HOME/requirements.txt"
ok "Installing Python dependencies"

# --------------------------------------------------------------------------
# Configuration (create once, keep forever)
# --------------------------------------------------------------------------
ENV_FILE="$ZENTRA_HOME/.env"
ENV_CREATED=0
if [ -f "$ENV_FILE" ]; then
    ok "Configuration already present (kept unchanged)"
else
    [ -f "$ZENTRA_HOME/.env.example" ] || die ".env.example is missing from the package"
    JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
    cp "$ZENTRA_HOME/.env.example" "$ENV_FILE"
    python3 - "$ENV_FILE" "$JWT_SECRET" <<'PY'
import pathlib, re, sys

path, secret = sys.argv[1], sys.argv[2]
text = pathlib.Path(path).read_text()
text = re.sub(r"(?m)^ZENTRA_JWT_SECRET=.*$", "ZENTRA_JWT_SECRET=" + secret, text, count=1)
text = re.sub(r"(?m)^ZENTRA_AUTO_ADMIN=.*$", "ZENTRA_AUTO_ADMIN=false", text, count=1)
if not re.search(r"(?m)^ZENTRA_AUTO_ADMIN=", text):
    text += "\n# No default account: the first registered account becomes admin\nZENTRA_AUTO_ADMIN=false\n"
pathlib.Path(path).write_text(text)
PY
    unset JWT_SECRET
    chmod 600 "$ENV_FILE"
    ENV_CREATED=1
    ok "Generating secure configuration"
fi

# --------------------------------------------------------------------------
# Authentication data (preserved on every re-run/update)
# --------------------------------------------------------------------------
chown "$ZENTRA_USER:$ZENTRA_USER" "$ZENTRA_HOME/data"
chmod 700 "$ZENTRA_HOME/data"
if [ -f "$ZENTRA_HOME/data/users.json" ]; then
    ok "Existing accounts preserved"
else
    ok "Configuring authentication (create your account in the app)"
fi

chown -R "$ZENTRA_USER:$ZENTRA_USER" "$ZENTRA_HOME/app" "$ZENTRA_HOME/data"
chown "$ZENTRA_USER:$ZENTRA_USER" "$ENV_FILE"
chmod 600 "$ENV_FILE"
chown root:root "$ZENTRA_HOME/requirements.txt" "$ZENTRA_HOME/scripts/"* 2>/dev/null || true
chmod 755 "$ZENTRA_HOME/scripts/"* 2>/dev/null || true

# --------------------------------------------------------------------------
# Docker access (local unix socket only — never TCP)
# --------------------------------------------------------------------------
if [ "$DOCKER_DETECTED" -eq 1 ]; then
    getent group docker >/dev/null 2>&1 || groupadd --system docker
    usermod -aG docker "$ZENTRA_USER"
    ok "Configuring Docker access (local socket only)"
fi

# --------------------------------------------------------------------------
# systemd service
# --------------------------------------------------------------------------
UNIT_FILE="/etc/systemd/system/zentra.service"
UNIT_TMP="$(mktemp)"
if getent group docker >/dev/null 2>&1; then
    AFTER_DOCKER=" docker.service"
    SUPP_GROUP="SupplementaryGroups=docker"
else
    AFTER_DOCKER=""
    SUPP_GROUP=""
fi
cat > "$UNIT_TMP" <<EOF
[Unit]
Description=Zentra monitoring backend server
Documentation=file:///opt/zentra/README.md
After=network-online.target${AFTER_DOCKER}
Wants=network-online.target

[Service]
Type=simple
User=zentra
Group=zentra
${SUPP_GROUP}
WorkingDirectory=/opt/zentra/app
EnvironmentFile=/opt/zentra/.env
ExecStart=/opt/zentra/venv/bin/uvicorn main:app --host \${ZENTRA_HOST} --port \${ZENTRA_PORT}
Restart=on-failure
RestartSec=3
TimeoutStopSec=15
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=/opt/zentra

[Install]
WantedBy=multi-user.target
EOF
UNIT_CHANGED=1
if cmp -s "$UNIT_TMP" "$UNIT_FILE" 2>/dev/null; then
    UNIT_CHANGED=0
else
    install -m 0644 "$UNIT_TMP" "$UNIT_FILE"
fi
rm -f "$UNIT_TMP"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1 || true
if [ "$UNIT_CHANGED" -eq 1 ]; then
    ok "Creating system service"
else
    ok "System service already configured"
fi

SERVICE_ACTIVE=0
if systemctl is-active --quiet "$SERVICE"; then
    SERVICE_ACTIVE=1
fi
if [ "$APP_CHANGED" -eq 1 ] || [ "$UNIT_CHANGED" -eq 1 ] || [ "$ENV_CREATED" -eq 1 ] || [ "$SERVICE_ACTIVE" -eq 0 ]; then
    systemctl restart "$SERVICE"
    ok "Starting Zentra"
else
    ok "Zentra already running (no changes)"
fi

ln -sf "$ZENTRA_HOME/scripts/zentra" /usr/local/bin/zentra
ok "Installing zentra commands (status, logs, restart, update, uninstall)"

# --------------------------------------------------------------------------
# Firewall — only add private-network rules, never delete or disable
# --------------------------------------------------------------------------
if command -v ufw >/dev/null 2>&1 \
        && ufw status 2>/dev/null | head -n 1 | grep -qi "active"; then
    if ufw status 2>/dev/null | grep -q "8765"; then
        ok "Firewall: rules for port 8765 already exist (left untouched)"
    else
        ufw allow proto tcp from 10.0.0.0/8 to any port 8765 comment "Zentra private" >/dev/null
        ufw allow proto tcp from 172.16.0.0/12 to any port 8765 comment "Zentra private" >/dev/null
        ufw allow proto tcp from 192.168.0.0/16 to any port 8765 comment "Zentra LAN" >/dev/null
        ufw allow proto tcp from 100.64.0.0/10 to any port 8765 comment "Zentra Tailscale" >/dev/null
        ok "Firewall: allowed 8765/tcp from private networks only"
    fi
else
    note "Firewall: UFW not active — no firewall changes made"
fi

# --------------------------------------------------------------------------
# Verify
# --------------------------------------------------------------------------
PORT="$(sed -n 's/^ZENTRA_PORT=//p' "$ENV_FILE" | tail -n 1)"
PORT="${PORT:-8765}"

if HEALTH_JSON="$(wait_for_health "$PORT" "$HEALTH_TIMEOUT")"; then
    VERSION="$(printf '%s' "$HEALTH_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("version","?"))')"
    ok "Health check OK (version $VERSION)"
else
    die "Zentra did not answer on port $PORT.

Check the logs with:  zentra logs
Or:  journalctl -u zentra -n 50"
fi

if command -v ss >/dev/null 2>&1; then
    if ss -ltn | grep -q ":$PORT"; then
        ok "Port $PORT listening"
    else
        die "Port $PORT is not listening"
    fi
fi

# --------------------------------------------------------------------------
# Ready
# --------------------------------------------------------------------------
LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1)"
if [ -z "$LAN_IP" ]; then
    LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
LAN_IP="${LAN_IP:-SERVER-IP}"
TS_IP=""
if command -v tailscale >/dev/null 2>&1; then
    TS_IP="$(tailscale ip -4 2>/dev/null | head -n 1 || true)"
fi

log ""
log "Zentra is ready."
log ""
log "Server address:"
log ""
log "http://$LAN_IP:$PORT"
if [ -n "$TS_IP" ]; then
    log "Tailscale: http://$TS_IP:$PORT"
fi
log ""
log "Open the Zentra Android app and enter this address."
log "Then tap \"Create account\" to make your login."
log ""
log "Useful commands:  zentra status | zentra logs | zentra restart"
log ""
