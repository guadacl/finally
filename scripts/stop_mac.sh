#!/usr/bin/env bash
# Stop FinAlly. Containers are removed; volumes (database, Camunda data) are kept. Idempotent.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is not installed or not on PATH." >&2
  exit 1
fi

docker compose down
echo "FinAlly stopped. Data volumes are preserved."
