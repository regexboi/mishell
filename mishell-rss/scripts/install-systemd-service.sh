#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Install mishell-rss as a systemd service.

Run as root (or with sudo), ideally from the mishell-rss project directory.

Usage:
  sudo ./scripts/install-systemd-service.sh [options]

Options:
  --user <name>          Service user (default: SUDO_USER, else current user)
  --group <name>         Service group (default: same as --user)
  --project-dir <path>   Path to mishell-rss project (default: current directory)
  --env-file <path>      Path to .env file (default: <project-dir>/.env)
  --service-name <name>  systemd unit name without .service (default: mishell-rss)
  --host <host>          Bind host (default: 0.0.0.0)
  --port <port>          Bind port (default: 8000)
  --uv-bin <path>        Path to uv binary (default: auto-detect)
  --help                 Show this message

Example:
  sudo ./scripts/install-systemd-service.sh \
    --user x \
    --project-dir /home/x/scripts/mishell/mishell-rss
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

DEFAULT_USER="${SUDO_USER:-$(id -un)}"
SERVICE_USER="$DEFAULT_USER"
SERVICE_GROUP="$DEFAULT_USER"
PROJECT_DIR="$(pwd)"
SERVICE_NAME="mishell-rss"
HOST="0.0.0.0"
PORT="8000"
UV_BIN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)
      SERVICE_USER="$2"
      shift 2
      ;;
    --group)
      SERVICE_GROUP="$2"
      shift 2
      ;;
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --env-file)
      ENV_FILE="$2"
      shift 2
      ;;
    --service-name)
      SERVICE_NAME="$2"
      shift 2
      ;;
    --host)
      HOST="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --uv-bin)
      UV_BIN="$2"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

if [[ "$EUID" -ne 0 ]]; then
  echo "This script must run as root. Re-run with sudo." >&2
  exit 1
fi

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "Project directory not found: $PROJECT_DIR" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Env file not found: $ENV_FILE" >&2
  exit 1
fi

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "User does not exist: $SERVICE_USER" >&2
  exit 1
fi

if ! getent group "$SERVICE_GROUP" >/dev/null 2>&1; then
  echo "Group does not exist: $SERVICE_GROUP" >&2
  exit 1
fi

if [[ -z "$UV_BIN" ]]; then
  if [[ -x "/home/$SERVICE_USER/.local/bin/uv" ]]; then
    UV_BIN="/home/$SERVICE_USER/.local/bin/uv"
  elif command -v uv >/dev/null 2>&1; then
    UV_BIN="$(command -v uv)"
  else
    echo "Could not find uv binary. Install uv or pass --uv-bin." >&2
    exit 1
  fi
fi

cat >"$UNIT_FILE" <<EOF
[Unit]
Description=mishell-rss API service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_GROUP
WorkingDirectory=$PROJECT_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$UV_BIN run uvicorn mishell_rss.main:app --host $HOST --port $PORT
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now "${SERVICE_NAME}.service"
systemctl status "${SERVICE_NAME}.service" --no-pager -l

echo
echo "Installed and started: ${SERVICE_NAME}.service"
echo "Check logs: journalctl -u ${SERVICE_NAME}.service -f"
