# Contributor Workflow

This repository is being built as a Qodo-reviewed PR train.

## Current PR

- Branch: `pr/07-release-hardening`
- Intent: harden packaging, offline CI checks, Docker runtime defaults, and hosted operator docs.

## Expected Commit Shape

1. `test: add packaging and deployment smoke coverage`
2. `chore: add github actions and docker packaging`
3. `docs: publish setup architecture and hosted runbook`

## Local Workflow

1. Run `qodo-get-rules` before implementation once the remote is configured.
2. Start with a failing test or feature.
3. Add only the code required to satisfy the current slice.
4. Push a draft PR and let Qodo review it before broadening scope.
5. Resolve Qodo findings with `qodo-pr-resolver` or `qodo-fix`.

## CI Shape

GitHub Actions is split into `unit`, `integration`, `acceptance`, `archunit`, and `package` jobs. Each job runs on `ubuntu-24.04`, pins third-party actions by commit SHA, and disables the live NVIDIA provider explicitly in Maven commands.
