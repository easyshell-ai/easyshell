#!/usr/bin/env bash
# EasyShell one-click deployment.
#
#   ./scripts/deploy.sh            # create .env (if missing) and start everything
#   ./scripts/deploy.sh --build    # build the images from source first
#   ./scripts/deploy.sh --down     # stop everything (data volumes are kept)
#
# What it does:
#   1. creates .env from .env.example with freshly generated secrets when it is missing
#   2. starts MySQL / Redis / server / web with docker compose
#   3. waits until the stack is healthy and prints how to sign in
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose.yml"
BUILD=0
DOWN=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --down) DOWN=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [[ "$DOWN" == "1" ]]; then
  docker compose -f "$COMPOSE_FILE" down
  echo "stopped (volumes kept). Use 'docker compose -f $COMPOSE_FILE down -v' to wipe data."
  exit 0
fi

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose (v2) is required" >&2; exit 1; }

gen() { openssl rand -base64 "$1" | tr -d '\n'; }

if [[ ! -f .env ]]; then
  echo "==> creating .env with generated secrets"
  cp .env.example .env
  # Replace the placeholder secrets with real random values (kept local, never committed).
  sed -i "s|^MYSQL_PASSWORD=.*|MYSQL_PASSWORD=$(gen 24)|" .env
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(gen 48)|" .env
  sed -i "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=$(gen 24)|" .env
  sed -i "s|^AGENT_REGISTER_SECRET=.*|AGENT_REGISTER_SECRET=$(gen 32)|" .env
  chmod 600 .env
  echo "    .env written (mode 600). Back it up: it holds the database password, JWT secret,"
  echo "    encryption key and the agent registration secret."
else
  # Make sure the variables this release needs are present.
  for key in MYSQL_PASSWORD JWT_SECRET ENCRYPTION_KEY AGENT_REGISTER_SECRET; do
    if ! grep -q "^${key}=" .env; then
      echo "==> adding missing ${key} to .env"
      case "$key" in
        JWT_SECRET) value=$(gen 48) ;;
        ENCRYPTION_KEY|MYSQL_PASSWORD) value=$(gen 24) ;;
        *) value=$(gen 32) ;;
      esac
      printf '%s=%s\n' "$key" "$value" >> .env
    fi
  done
  chmod 600 .env
fi

if [[ "$BUILD" == "1" ]]; then
  echo "==> building images from source"
  docker compose -f docker-compose.build.yml build
  COMPOSE_ARGS=(-f docker-compose.build.yml)
else
  COMPOSE_ARGS=(-f "$COMPOSE_FILE")
fi

echo "==> starting EasyShell"
docker compose "${COMPOSE_ARGS[@]}" up -d

echo "==> waiting for the server to become healthy"
for _ in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' easyshell-server 2>/dev/null || echo "missing")
  [[ "$status" == "healthy" ]] && break
  sleep 3
done

if [[ "$status" != "healthy" ]]; then
  echo "server did not become healthy (last status: $status). Check: docker logs easyshell-server" >&2
  exit 1
fi

WEB_PORT=$(grep -E '^WEB_PORT=' .env | cut -d= -f2- || true)
WEB_PORT=${WEB_PORT:-18880}

if grep -qE '^EASYSHELL_ADMIN_PASSWORD=.+' .env; then
  PASSWORD_HINT="the EASYSHELL_ADMIN_PASSWORD value in .env"
else
  PASSWORD_HINT="docker logs easyshell-server 2>&1 | grep 'initial admin password'"
fi

cat <<EOF

EasyShell is up.

  URL:            http://localhost:${WEB_PORT}
  Username:       easyshell
  Password:       ${PASSWORD_HINT}

  Add a host:     open Hosts -> Add and use one-click deployment; the installed agent
                  registers itself with AGENT_REGISTER_SECRET from .env.

  Stop:           ./scripts/deploy.sh --down
EOF
