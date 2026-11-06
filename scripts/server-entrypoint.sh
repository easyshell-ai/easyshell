#!/bin/sh
# Server entrypoint: picks up the generated secrets, then starts the application.
#
# Values that are already present in the environment (set by the operator through .env or
# `docker run -e`) always win; only empty ones are filled from the secrets volume.
set -eu

SECRETS_ENV="${EASYSHELL_SECRETS_FILE:-/secrets/server.env}"

if [ -r "$SECRETS_ENV" ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      ''|\#*) continue ;;
    esac
    eval "current=\${$key:-}"
    if [ -z "$current" ]; then
      export "$key=$value"
      echo "entrypoint: $key loaded from the generated secrets"
    fi
  done < "$SECRETS_ENV"
fi

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /opt/easyshell/app.jar
