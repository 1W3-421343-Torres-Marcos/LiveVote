#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env.prod}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Error: env file '$ENV_FILE' not found." >&2
  exit 1
fi

# Normalize potential CRLF when loading variables on Windows/WSL/Git Bash
set -a
# shellcheck disable=SC1090
source <(tr -d '\r' < "$ENV_FILE")
set +a

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR"

COMPOSE_ARGS=(-f docker-compose.yml -f docker-compose.prod.yml --env-file "$ENV_FILE")

backup_db() {
  local service="$1"
  local user="$2"
  local db="$3"
  local label="$4"
  local out_file="${BACKUP_DIR}/${label}_${TIMESTAMP}.dump"

  echo "[backup] Dumping ${service} (${db}) -> ${out_file}"
  MSYS_NO_PATHCONV=1 docker compose "${COMPOSE_ARGS[@]}" exec -T "$service" \
    pg_dump -U "$user" -d "$db" -Fc > "$out_file"

  if [[ ! -s "$out_file" ]]; then
    echo "Error: dump file '${out_file}' is empty." >&2
    rm -f "$out_file"
    exit 1
  fi

  MSYS_NO_PATHCONV=1 docker compose "${COMPOSE_ARGS[@]}" exec -T "$service" \
    pg_restore --list < "$out_file" >/dev/null
  echo "[backup] Verified archive integrity: ${out_file} ($(wc -c < "$out_file") bytes)"
}

backup_db "postgres-votes" "${POSTGRES_VOTES_USER:?POSTGRES_VOTES_USER required}" "${POSTGRES_VOTES_DB:?POSTGRES_VOTES_DB required}" "votes"
backup_db "postgres-results" "${POSTGRES_RESULTS_USER:?POSTGRES_RESULTS_USER required}" "${POSTGRES_RESULTS_DB:?POSTGRES_RESULTS_DB required}" "results"

find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' -mtime +"$RETENTION_DAYS" -delete
echo "[backup] Completed successfully (retention: ${RETENTION_DAYS} days)."
