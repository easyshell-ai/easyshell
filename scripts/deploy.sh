#!/usr/bin/env bash
# EasyShell one-click deployment.
#
#   ./scripts/deploy.sh            # start everything (secrets are generated automatically)
#   ./scripts/deploy.sh --build    # build the images from this source tree first
#   ./scripts/deploy.sh --down     # stop everything (data volumes are kept)
#
# No .env file and no manual secret editing is required: on first boot a one-shot container
# generates the database password, JWT secret, encryption key and agent registration secret
# into the "easyshell_secrets" volume and every later start reuses them. Set a variable in
# .env (or with -e) only if you want to pin a specific value.
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

if [[ "$BUILD" == "1" ]]; then
  echo "==> building images from source"
  docker compose -f docker-compose.build.yml build
  COMPOSE_ARGS=(-f docker-compose.build.yml)
else
  COMPOSE_ARGS=(-f "$COMPOSE_FILE")
fi

echo "==> starting EasyShell (secrets are generated on first boot)"
docker compose "${COMPOSE_ARGS[@]}" up -d

echo "==> waiting for the server to become healthy"
status=""
for _ in $(seq 1 80); do
  status=$(docker inspect -f '{{.State.Health.Status}}' easyshell-server 2>/dev/null || echo "missing")
  [[ "$status" == "healthy" ]] && break
  sleep 3
done

if [[ "$status" != "healthy" ]]; then
  echo "server did not become healthy (last status: $status). Check: docker logs easyshell-server" >&2
  exit 1
fi

WEB_PORT=$(grep -E '^WEB_PORT=' .env 2>/dev/null | cut -d= -f2- || true)
WEB_PORT=${WEB_PORT:-18880}
SERVER_PORT=$(grep -E '^SERVER_PORT=' .env 2>/dev/null | cut -d= -f2- || true)
SERVER_PORT=${SERVER_PORT:-18080}

# The initial admin password: either the one pinned in .env, or the random one the server
# generated on first start (printed once in its logs).
if grep -qE '^EASYSHELL_ADMIN_PASSWORD=.+' .env 2>/dev/null; then
  PASSWORD_HINT="the EASYSHELL_ADMIN_PASSWORD value in .env"
elif docker logs easyshell-server 2>&1 | grep -q 'initial admin password'; then
  PASSWORD_HINT="$(docker logs easyshell-server 2>&1 | sed -n 's/.*initial admin password for user .easyshell.: //p' | tail -1)"
else
  PASSWORD_HINT="run: docker logs easyshell-server 2>&1 | grep 'initial admin password'"
fi

cat <<EOF

EasyShell is up.

  URL:            http://localhost:${WEB_PORT}
  Username:       easyshell
  Password:       ${PASSWORD_HINT}

  Add a host:     open Hosts -> Add and use one-click deployment. Agents register
                  themselves with the generated AGENT_REGISTER_SECRET.

  Notes:
    - Secrets live in the "easyshell_secrets" Docker volume and are reused on every start.
      To rotate them: docker compose down -v (this wipes the database too).
    - Agents on other machines need a reachable server URL. Set PROVISION_SERVER_URL
      in .env (or System config -> server.external-url in the UI), e.g.
      PROVISION_SERVER_URL=http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo your-server-ip):${SERVER_PORT}

  Stop:           ./scripts/deploy.sh --down
EOF
