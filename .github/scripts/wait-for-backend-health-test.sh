#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture_directory="$(mktemp -d)"
trap 'rm -rf "${fixture_directory}"' EXIT

cat > "${fixture_directory}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  "compose up -d --no-deps --wait --wait-timeout "*" backend")
    exit "${FAKE_UP_EXIT:-0}"
    ;;
  "compose ps -q backend")
    printf '%s\n' "${FAKE_CONTAINER_ID:-backend-container}"
    ;;
  "inspect --format "*)
    printf '%s %s %s\n' \
      "${FAKE_STATUS:-running}" \
      "${FAKE_RESTARTING:-false}" \
      "${FAKE_HEALTH:-healthy}"
    ;;
  "compose ps backend")
    printf '%s\n' "backend diagnostic status"
    ;;
  "compose logs --tail=200 backend")
    printf '%s\n' "backend diagnostic logs"
    ;;
  *)
    printf 'unexpected docker arguments: %s\n' "$*" >&2
    exit 2
    ;;
esac
FAKE_DOCKER
chmod +x "${fixture_directory}/docker"

PATH="${fixture_directory}:${PATH}" \
  bash "${script_directory}/wait-for-backend-health.sh" >/dev/null

if failure_output="$(PATH="${fixture_directory}:${PATH}" FAKE_UP_EXIT=1 \
  bash "${script_directory}/wait-for-backend-health.sh" 2>&1)"; then
  echo "expected compose wait failure" >&2
  exit 1
fi
[[ "${failure_output}" == *"backend diagnostic status"* ]]
[[ "${failure_output}" == *"backend diagnostic logs"* ]]

if failure_output="$(PATH="${fixture_directory}:${PATH}" FAKE_HEALTH=unhealthy \
  bash "${script_directory}/wait-for-backend-health.sh" 2>&1)"; then
  echo "expected unhealthy inspection failure" >&2
  exit 1
fi
[[ "${failure_output}" == *"health=unhealthy"* ]]
[[ "${failure_output}" == *"backend diagnostic logs"* ]]

if failure_output="$(PATH="${fixture_directory}:${PATH}" FAKE_STATUS=exited \
  bash "${script_directory}/wait-for-backend-health.sh" 2>&1)"; then
  echo "expected exited container failure" >&2
  exit 1
fi
[[ "${failure_output}" == *"state=exited"* ]]
[[ "${failure_output}" == *"backend diagnostic logs"* ]]

if failure_output="$(PATH="${fixture_directory}:${PATH}" FAKE_RESTARTING=true \
  bash "${script_directory}/wait-for-backend-health.sh" 2>&1)"; then
  echo "expected restarting container failure" >&2
  exit 1
fi
[[ "${failure_output}" == *"restarting=true"* ]]
[[ "${failure_output}" == *"backend diagnostic logs"* ]]

echo "Backend health gate tests passed"
