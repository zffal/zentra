#!/usr/bin/env bash
# migrate-from-omnis.sh — rename an existing "Omnis" install to "Zentra".
#
#   sudo bash scripts/migrate-from-omnis.sh
#
# Safe by design:
#   * the old install (/opt/omnis, omnis.service, omnis user) is NEVER deleted
#   * a full archive of /opt/omnis is written to /root first
#   * accounts, .env (JWT secret), thresholds and alerts are preserved
#   * if the new service fails its health check, it rolls back to Omnis
#
# Rollback afterwards (any time):
#   sudo systemctl disable --now zentra
#   sudo systemctl enable  --now omnis
set -euo pipefail

OLD_HOME="/opt/omnis"
OLD_USER="omnis"
OLD_SERVICE="omnis"

NEW_HOME="/opt/zentra"
NEW_USER="zentra"
NEW_SERVICE="zentra"
PORT="8765"

PAYLOAD="$(cd "$(dirname "${BASH_SOURCE[0]:-migrate-from-omnis.sh}")/.." && pwd)"

die() { printf '  \xe2\x9c\x97 %s\n' "$*" >&2; exit 1; }
ok()  { printf '  \xe2\x9c\x93 %s\n' "$*"; }
log() { printf '%s\n' "$*"; }

log ""
log "Zentra migration (from Omnis)"
log "============================="
log ""

[ "$(id -u)" -eq 0 ] || die "Root is required. Run: sudo bash scripts/migrate-from-omnis.sh"

if [ ! -d "$OLD_HOME" ] || [ ! -f "/etc/systemd/system/$OLD_SERVICE.service" ]; then
    die "No existing Omnis install found ($OLD_HOME / $OLD_SERVICE.service).
Use install.sh for a fresh Zentra install instead."
fi
if [ -e "$NEW_HOME" ] || [ -f "/etc/systemd/system/$NEW_SERVICE.service" ]; then
    die "$NEW_HOME or $NEW_SERVICE.service already exists. Aborting to avoid overwriting."
fi
[ -f "$PAYLOAD/app/main.py" ] || die "Zentra package not found next to this script."

STAMP="$(date +%Y%m%d-%H%M%S)"

# ---- 1. back up the entire old install ------------------------------------
ARCHIVE="/root/omnis-pre-zentra-$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$(dirname "$OLD_HOME")" "$(basename "$OLD_HOME")"
ok "Backed up the old install → $ARCHIVE"

# ---- 2. read old configuration --------------------------------------------
[ -f "$OLD_HOME/.env" ] || die "$OLD_HOME/.env is missing; cannot migrate safely."
OLDPORT="$(sed -n 's/^OMNIS_PORT=//p' "$OLD_HOME/.env" | tail -n 1)"
[ -n "$OLDPORT" ] && PORT="$OLDPORT"

# ---- 3. create the Zentra user and layout ---------------------------------
if id "$NEW_USER" >/dev/null 2>&1; then
    ok "Zentra user already exists"
else
    useradd --system --home-dir "$NEW_HOME" --no-create-home \
        --shell /usr/sbin/nologin "$NEW_USER"
    ok "Created user $NEW_USER"
fi

mkdir -p "$NEW_HOME/app" "$NEW_HOME/data" "$NEW_HOME/scripts" \
         "$NEW_HOME/dist" "$NEW_HOME/backups"

shopt -s nullglob
for f in "$PAYLOAD"/app/*.py; do
    install -m 0644 "$f" "$NEW_HOME/app/"
done
shopt -u nullglob
install -m 0644 "$PAYLOAD/requirements.txt" "$NEW_HOME/requirements.txt"
[ -f "$PAYLOAD/.env.example" ] && install -m 0644 "$PAYLOAD/.env.example" "$NEW_HOME/.env.example"
[ -f "$PAYLOAD/README.md" ] && install -m 0644 "$PAYLOAD/README.md" "$NEW_HOME/README.md"
shopt -s nullglob
for f in "$PAYLOAD"/scripts/*; do
    [ -f "$f" ] && install -m 0755 "$f" "$NEW_HOME/scripts/"
done
shopt -u nullglob

# keep a copy of the package for "zentra update"
if [ "$(readlink -f "$PAYLOAD")" != "$(readlink -f "$NEW_HOME/dist")" ]; then
    rm -rf "$NEW_HOME/dist.new"
    mkdir -p "$NEW_HOME/dist.new"
    cp -a "$PAYLOAD/." "$NEW_HOME/dist.new/"
    rm -rf "$NEW_HOME/dist"
    mv "$NEW_HOME/dist.new" "$NEW_HOME/dist"
fi
ok "Installed Zentra backend files"

# ---- 4. migrate data + settings (preserve everything) ---------------------
cp -a "$OLD_HOME/data/." "$NEW_HOME/data/" 2>/dev/null || true
ok "Copied accounts and alert history"

python3 - "$OLD_HOME/.env" "$NEW_HOME/.env" <<'PY'
import re, sys

src, dst = sys.argv[1], sys.argv[2]
out = []
for line in open(src, encoding="utf-8").read().splitlines():
    line = line.replace("# Omnis backend configuration", "# Zentra backend configuration")
    m = re.match(r"^(\s*)OMNIS_([A-Z0-9_]+)(=.*)$", line)
    if m:
        line = m.group(1) + "ZENTRA_" + m.group(2) + m.group(3)
    # cosmetic default sender only
    line = line.replace("SMTP_FROM=omnis@localhost", "SMTP_FROM=zentra@localhost")
    out.append(line)
if not any(l.startswith("ZENTRA_AUTO_ADMIN=") for l in out):
    out.append("ZENTRA_AUTO_ADMIN=false")
open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY
cp -a "$NEW_HOME/.env" "$NEW_HOME/.env.migrated-$STAMP"
ok "Migrated configuration (OMNIS_* → ZENTRA_*, values preserved)"

# ---- 5. Python environment ------------------------------------------------
if [ ! -x "$NEW_HOME/venv/bin/python" ]; then
    python3 -m venv "$NEW_HOME/venv"
fi
"$NEW_HOME/venv/bin/python" -m pip install --quiet --disable-pip-version-check \
    -r "$NEW_HOME/requirements.txt"
ok "Installed Python dependencies"

# ---- 6. ownership + permissions -------------------------------------------
chown -R "$NEW_USER:$NEW_USER" "$NEW_HOME/app" "$NEW_HOME/data"
chown "$NEW_USER:$NEW_USER" "$NEW_HOME/.env"
chmod 700 "$NEW_HOME/data"
chmod 600 "$NEW_HOME/.env"
chown root:root "$NEW_HOME/requirements.txt" "$NEW_HOME/scripts/"* 2>/dev/null || true
chmod 755 "$NEW_HOME/scripts/"* 2>/dev/null || true

# ---- 7. Docker access -----------------------------------------------------
if getent group docker >/dev/null 2>&1; then
    usermod -aG docker "$NEW_USER"
    AFTER_DOCKER=" docker.service"
    SUPP_GROUP="SupplementaryGroups=docker"
    ok "Granted local Docker socket access"
else
    AFTER_DOCKER=""
    SUPP_GROUP=""
fi

# ---- 8. systemd unit ------------------------------------------------------
cat > "/etc/systemd/system/$NEW_SERVICE.service" <<EOF
[Unit]
Description=Zentra monitoring backend server
Documentation=file://$NEW_HOME/README.md
After=network-online.target${AFTER_DOCKER}
Wants=network-online.target

[Service]
Type=simple
User=$NEW_USER
Group=$NEW_USER
${SUPP_GROUP}
WorkingDirectory=$NEW_HOME/app
EnvironmentFile=$NEW_HOME/.env
ExecStart=$NEW_HOME/venv/bin/uvicorn main:app --host \${ZENTRA_HOST} --port \${ZENTRA_PORT}
Restart=on-failure
RestartSec=3
TimeoutStopSec=15
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=$NEW_HOME

[Install]
WantedBy=multi-user.target
EOF
ln -sf "$NEW_HOME/scripts/zentra" /usr/local/bin/zentra
ok "Created $NEW_SERVICE.service and the zentra command"

# ---- 9. switch services (rollback on failure) -----------------------------
systemctl stop "$OLD_SERVICE" 2>/dev/null || true
systemctl disable "$OLD_SERVICE" 2>/dev/null || true
systemctl daemon-reload
systemctl enable "$NEW_SERVICE" >/dev/null 2>&1 || true
systemctl restart "$NEW_SERVICE"

if python3 - "$PORT" <<'PY'
import json, sys, time, urllib.request
port = int(sys.argv[1]); deadline = time.time() + 60
while time.time() < deadline:
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/health" % port, timeout=3) as r:
            if json.load(r).get("status") == "ok":
                sys.exit(0)
    except Exception:
        pass
    time.sleep(1)
sys.exit(1)
PY
then
    ok "Zentra is healthy on port $PORT"
else
    log ""
    log "✗ Zentra failed its health check — rolling back to Omnis"
    systemctl disable --now "$NEW_SERVICE" 2>/dev/null || true
    systemctl enable --now "$OLD_SERVICE" 2>/dev/null || true
    die "Migration rolled back. The old install is unchanged at $OLD_HOME."
fi

log ""
log "Zentra migration complete."
log ""
log "Preserved:  accounts ($NEW_HOME/data), settings/JWT ($NEW_HOME/.env)"
log "Kept old:   $OLD_HOME (untouched, service disabled) — archive: $ARCHIVE"
log ""
log "Rollback any time with:"
log "  sudo systemctl disable --now $NEW_SERVICE"
log "  sudo systemctl enable  --now $OLD_SERVICE"
log ""
