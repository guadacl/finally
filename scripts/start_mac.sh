#!/usr/bin/env bash
# Start Tradex (app + postgres + camunda) with Docker Compose. Idempotent.
#   ./scripts/start_mac.sh            start (builds the image the first time)
#   ./scripts/start_mac.sh --build    force a rebuild
#   ./scripts/start_mac.sh --no-open  don't open the browser
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BUILD=""
OPEN=1
for arg in "$@"; do
  case "$arg" in
    --build) BUILD="--build" ;;
    --no-open) OPEN=0 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is not installed or not on PATH." >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "WARNING: .env was missing - created it from .env.example."
  echo "         Set OPENROUTER_API_KEY in .env for AI chat (or LLM_MOCK=true)."
fi

APP_PORT="$(grep -E '^[[:space:]]*APP_PORT=' .env | tail -n1 | cut -d= -f2- | tr -d '[:space:]"'"'" || true)"
APP_PORT="${APP_PORT:-8000}"
URL="http://localhost:${APP_PORT}"

# Build only when the image doesn't exist yet (or --build was given).
if [ -z "$BUILD" ] && ! docker image inspect finally-app:latest >/dev/null 2>&1; then
  BUILD="--build"
fi

echo "Starting Tradex..."
docker compose up -d $BUILD

echo -n "Waiting for ${URL}/api/health "
for _ in $(seq 1 90); do
  if curl -fsS "${URL}/api/health" >/dev/null 2>&1; then
    echo " ok"
    echo ""
    echo "Tradex is running:  ${URL}"
    echo "Camunda Operate:     http://localhost:8081  (demo / demo)"
    if [ "$OPEN" = "1" ]; then
      if command -v open >/dev/null 2>&1; then open "$URL";
      elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1 || true; fi
    fi
    exit 0
  fi
  echo -n "."
  sleep 2
done

echo ""
echo "Tradex did not become healthy in time. Check the logs with: docker compose logs app" >&2
exit 1
