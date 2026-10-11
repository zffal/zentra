#!/usr/bin/env bash
# zentra update — update the backend while keeping accounts and settings.
#
#   sudo zentra update                                  # from installed package
#   sudo zentra update /path/to/zentra-server.tar.gz     # from a new package
#   sudo zentra update /path/to/unpacked-folder
#
set -euo pipefail

ZENTRA_HOME="/opt/zentra"
SERVICE="zentra"
SRC="${1:-$ZENTRA_HOME/dist}"

die() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "  ✓ $*"; }

[ "$(id -u)" -eq 0 ] || die "Run with sudo: sudo zentra update"

# ---- resolve source (folder or tarball) -----------------------------------
TMPDIR_CREATED=""
cleanup() {
    if [ -n "$TMPDIR_CREATED" ] && [ -d "$TMPDIR_CREATED" ]; then
        rm -rf "$TMPDIR_CREATED"
    fi
}
trap cleanup EXIT

if [ -f "$SRC" ]; then
    case "$SRC" in
        *.tar.gz|*.tgz)
            TMPDIR_CREATED="$(mktemp -d)"
            tar -xzf "$SRC" -C "$TMPDIR_CREATED" || die "Could not extract $SRC"
            MAIN="$(find "$TMPDIR_CREATED" -maxdepth 3 -path '*/app/main.py' 2>/dev/null | head -n 1)"
            [ -n "$MAIN" ] || die "Package does not contain app/main.py"
            SRC="$(cd "$(dirname "$MAIN")/.." && pwd)"
            ;;
        *)
            die "Unsupported source file: $SRC (use a folder or .tar.gz)"
            ;;
    esac
fi
[ -f "$SRC/app/main.py" ] || die "Source not found: $SRC/app/main.py"
[ -f "$SRC/requirements.txt" ] || die "Source is missing requirements.txt"

echo "Zentra update"
echo "============"

# ---- backup configuration, accounts and current backend -------------------
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$ZENTRA_HOME/backups/$STAMP"
mkdir -p "$BACKUP"
cp -a "$ZENTRA_HOME/.env" "$BACKUP/.env" 2>/dev/null || true
cp -a "$ZENTRA_HOME/data" "$BACKUP/data" 2>/dev/null || true
cp -a "$ZENTRA_HOME/app" "$BACKUP/app" 2>/dev/null || true
cp -a "$ZENTRA_HOME/requirements.txt" "$BACKUP/requirements.txt" 2>/dev/null || true
ok "Backed up configuration and accounts → $BACKUP"

# ---- install new backend ---------------------------------------------------
shopt -s nullglob
for f in "$SRC"/app/*.py; do
    install -m 0644 -o zentra -g zentra "$f" "$ZENTRA_HOME/app/"
done
shopt -u nullglob
install -m 0644 -o root -g root "$SRC/requirements.txt" "$ZENTRA_HOME/requirements.txt"
if [ -f "$SRC/README.md" ]; then
    install -m 0644 -o root -g root "$SRC/README.md" "$ZENTRA_HOME/README.md"
fi
rm -rf "$ZENTRA_HOME/app/__pycache__"
ok "Backend files updated"

"$ZENTRA_HOME/venv/bin/python" -m pip install --quiet --disable-pip-version-check \
    -r "$ZENTRA_HOME/requirements.txt"
ok "Dependencies up to date"

# ---- restart + health check (rollback on failure) --------------------------
wait_health() {
    python3 - "$1" <<'PY'
import json, sys, time, urllib.request

port = int(sys.argv[1])
deadline = time.time() + 60
while time.time() < deadline:
    try:
        with urllib.request.urlopen(
            "http://127.0.0.1:%d/api/health" % port, timeout=3
        ) as response:
            if json.load(response).get("status") == "ok":
                sys.exit(0)
    except Exception:
        pass
    time.sleep(1)
sys.exit(1)
PY
}

systemctl restart "$SERVICE"
ok "Service restarted"

PORT="$(sed -n 's/^ZENTRA_PORT=//p' "$ZENTRA_HOME/.env" | tail -n 1)"
PORT="${PORT:-8765}"
if wait_health "$PORT"; then
    VERSION="$(python3 - "$PORT" <<'PY'
import json, sys, urllib.request
with urllib.request.urlopen(
    "http://127.0.0.1:%s/api/health" % sys.argv[1], timeout=3
) as response:
    print(json.load(response).get("version", "?"))
PY
)"
    echo ""
    echo "✓ Update complete — health OK (version $VERSION)"
    echo "  Accounts and settings unchanged."
else
    echo ""
    echo "✗ Health check failed — rolling back to the previous version"
    if [ -d "$BACKUP/app" ]; then
        for f in "$BACKUP"/app/*.py; do
            install -m 0644 -o zentra -g zentra "$f" "$ZENTRA_HOME/app/"
        done
    fi
    if [ -f "$BACKUP/.env" ]; then
        cp -a "$BACKUP/.env" "$ZENTRA_HOME/.env"
        chown zentra:zentra "$ZENTRA_HOME/.env"
        chmod 600 "$ZENTRA_HOME/.env"
    fi
    systemctl restart "$SERVICE" || true
    if wait_health "$PORT"; then
        echo "  Rolled back — previous version is healthy again."
    else
        echo "  Rolled back, but health is still failing. Check: zentra logs"
    fi
    echo "  Backup kept at: $BACKUP"
    echo "  Check logs: zentra logs"
    exit 1
fi
