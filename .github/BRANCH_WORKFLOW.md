# Branch workflow

## Feature and maintenance work

1. Create an issue.
2. Create `<type>/<issue-number>/<keyword>` from the latest `develop`.
3. Open a pull request from the work branch to `develop`.

Allowed types: `feat`, `fix`, `refactor`, `chore`, `test`, `docs`, `setting`, `hotfix`, `perf`.

## Release

1. Open a pull request directly from `develop` to `main`.
2. Do not create an intermediate `release/*` or `codex/*` branch.
3. After the release merge, open a `main` to `develop` synchronization pull request.
4. Confirm that the synchronization pull request has no file changes before merging.

Direct pushes to `develop` and `main` are prohibited.
