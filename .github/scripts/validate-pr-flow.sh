#!/usr/bin/env bash
set -euo pipefail

base_branch="${1:?base branch is required}"
head_branch="${2:?head branch is required}"

echo "PR branch flow: ${head_branch} -> ${base_branch}"

if [[ "${base_branch}" == "main" ]]; then
  if [[ "${head_branch}" != "develop" ]]; then
    echo "ERROR: main 대상 PR은 develop 브랜치에서만 생성할 수 있습니다."
    exit 1
  fi

  echo "OK: develop -> main release flow"
  exit 0
fi

if [[ "${base_branch}" == "develop" ]]; then
  if [[ "${head_branch}" == "main" ]]; then
    echo "OK: main -> develop post-release sync flow"
    exit 0
  fi

  if [[ "${head_branch}" =~ ^(feat|fix|refactor|chore|test|docs|setting|hotfix|perf)/[0-9]+/[a-z0-9][a-z0-9-]*$ ]]; then
    echo "OK: work branch -> develop flow"
    exit 0
  fi

  echo "ERROR: develop 대상 PR의 브랜치 이름이 허용 규칙과 다릅니다."
  echo "Allowed: <type>/<issue-number>/<keyword> or main -> develop sync"
  exit 1
fi

echo "ERROR: 지원하지 않는 PR base 브랜치입니다: ${base_branch}"
exit 1
