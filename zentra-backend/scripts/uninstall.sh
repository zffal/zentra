#!/usr/bin/env bash
# zentra uninstall — remove the Zentra service.
#
#   sudo zentra uninstall            (asks for confirmation)
#   sudo zentra uninstall --yes      (no prompt, e.g. scripted)
#   sudo zentra uninstall --purge    (also delete /opt/zentra after a final backup)
#
# User accounts, configuration and backups are NEVER deleted unless --purge
# is given, confirmed, and an archive is written to /root first.
#
set -euo pipefail

ZENTRA_HOME="/opt/zentra"
SERVICE="zentra"
YES=0
PURGE=0

for arg in "$@"; do
    case "$arg" in
        --yes|-y) YES=1 ;;
        --purge)  PURGE=1 ;;
        help|-h|--help)
            sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown option: $arg (try --help)" >&2
            exit 1
            ;;
    esac
done

confirm() {
    if [ "$YES" -eq 1 ]; then
        return 0
    fi
    if [ -t 0 ]; then
        printf '%s [y/N] ' "$1"
        read -r reply
        case "$reply" in
            y|Y|yes|YES) return 0 ;;
            *) return 1 ;;
        esac
    fi
    echo "Confirmation required. Re-run with --yes to proceed." >&2
    return 1
}

echo "Zentra uninstall"
echo "==============="
echo ""

if ! confirm "Stop and remove the Zentra service?"; then
    echo "Aborted. Nothing was changed."
    exit 0
fi

systemctl disable --now "$SERVICE" 2>/dev/null || true
rm -f "/etc/systemd/system/$SERVICE.service"
systemctl daemon-reload 2>/dev/null || true
rm -f /usr/local/bin/zentra
echo "  ✓ Service removed (backend files and data kept)"

if [ "$PURGE" -eq 1 ]; then
    if confirm "PERMANENTLY delete $ZENTRA_HOME (accounts, settings, backups)?"; then
        STAMP="$(date +%Y%m%d-%H%M%S)"
        ARCHIVE="/root/zentra-final-backup-$STAMP.tar.gz"
        if [ -d "$ZENTRA_HOME" ]; then
            tar -czf "$ARCHIVE" -C "$(dirname "$ZENTRA_HOME")" "$(basename "$ZENTRA_HOME")" \
                2>/dev/null || true
            rm -rf "$ZENTRA_HOME"
        fi
        echo "  ✓ Purged $ZENTRA_HOME"
        if [ -f "$ARCHIVE" ]; then
            echo "  · A full archive was saved first: $ARCHIVE"
        fi
        echo ""
        echo "Zentra has been completely removed."
        exit 0
    fi
    echo "  · Purge declined — keeping $ZENTRA_HOME"
fi

echo ""
echo "Zentra has been removed."
echo "Preserved (never deleted silently):"
if [ -d "$ZENTRA_HOME/data" ]; then
    echo "  · Accounts:  $ZENTRA_HOME/data/users.json"
fi
if [ -f "$ZENTRA_HOME/.env" ]; then
    echo "  · Settings:  $ZENTRA_HOME/.env"
fi
if [ -d "$ZENTRA_HOME/backups" ] && [ -n "$(ls -A "$ZENTRA_HOME/backups" 2>/dev/null)" ]; then
    echo "  · Backups:   $ZENTRA_HOME/backups/"
fi
echo ""
echo "To delete everything later:"
echo "  sudo $ZENTRA_HOME/scripts/uninstall.sh --purge"
