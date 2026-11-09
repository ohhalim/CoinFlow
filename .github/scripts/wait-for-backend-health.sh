#!/usr/bin/env bash
set -euo pipefail

health_timeout_seconds="${BACKEND_HEALTH_TIMEOUT_SECONDS:-120}"

print_backend_diagnostics() {
  docker compose ps backend || true
  docker compose logs --tail=200 backend || true
}

if ! docker compose up -d --no-deps --wait \
  --wait-timeout "${health_timeout_seconds}" backend; then
  echo "ERROR: backend did not become healthy within ${health_timeout_seconds}s"
  print_backend_diagnostics
  exit 1
fi

backend_containers=()
while IFS= read -r backend_container; do
  if [[ -n "${backend_container}" ]]; then
    backend_containers+=("${backend_container}")
  fi
done < <(docker compose ps -q backend)

if [[ "${#backend_containers[@]}" -eq 0 ]]; then
  echo "ERROR: backend container was not created"
  print_backend_diagnostics
  exit 1
fi

for backend_container in "${backend_containers[@]}"; do
  backend_state="$(docker inspect --format \
    '{{.State.Status}} {{.State.Restarting}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
    "${backend_container}")"
  read -r runtime_status restarting health_status <<< "${backend_state}"

  if [[ "${runtime_status}" != "running" \
    || "${restarting}" != "false" \
    || "${health_status}" != "healthy" ]]; then
    echo "ERROR: backend ${backend_container} state=${runtime_status} restarting=${restarting} health=${health_status}"
    print_backend_diagnostics
    exit 1
  fi

  echo "Backend health gate passed: ${backend_container} state=${runtime_status} restarting=${restarting} health=${health_status}"
done
