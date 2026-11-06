#!/bin/sh
# Generates the deployment secrets on first boot and keeps them stable afterwards.
#
# Runs as a one-shot container before MySQL and the server start. Everything is written to
# the shared "easyshell_secrets" volume so restarts and upgrades keep the same values:
# rotating the JWT secret or the encryption key would invalidate every issued token and
# make stored credentials undecryptable.
#
# An operator can still pin a value: whatever is passed through the environment on the
# first run is written to the volume instead of a random one.
set -eu

SECRETS_DIR="${EASYSHELL_SECRETS_DIR:-/secrets}"

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

# 32 alphanumeric characters: enough for HS256 keys and AES-256 key material, and safe to
# use inside URLs, JDBC properties and YAML without quoting problems.
generate() {
  tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32
}

# seed <file> <env-var-with-optional-override>
seed() {
  file="$SECRETS_DIR/$1"
  override_var="$2"
  eval "override=\${$override_var:-}"

  if [ ! -s "$file" ]; then
    if [ -n "$override" ]; then
      printf '%s' "$override" > "$file"
      echo "init-secrets: $1 taken from the environment"
    else
      generate > "$file"
      echo "init-secrets: $1 generated"
    fi
  else
    echo "init-secrets: $1 already present, keeping it"
  fi
  chmod 600 "$file"
}

seed db_password            MYSQL_PASSWORD
seed jwt_secret             JWT_SECRET
seed encryption_key         ENCRYPTION_KEY
seed agent_register_secret  AGENT_REGISTER_SECRET

# server.env maps the files to the environment variables the server expects. The server
# entrypoint sources it and only fills variables that are not already set.
{
  printf 'MYSQL_PASSWORD=%s\n'          "$(cat "$SECRETS_DIR/db_password")"
  printf 'JWT_SECRET=%s\n'              "$(cat "$SECRETS_DIR/jwt_secret")"
  printf 'ENCRYPTION_KEY=%s\n'          "$(cat "$SECRETS_DIR/encryption_key")"
  printf 'AGENT_REGISTER_SECRET=%s\n'   "$(cat "$SECRETS_DIR/agent_register_secret")"
} > "$SECRETS_DIR/server.env"
chmod 600 "$SECRETS_DIR/server.env"

echo "init-secrets: ready"
