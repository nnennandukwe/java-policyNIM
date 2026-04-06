# Contributor Workflow

This repository is being built as a Qodo-reviewed PR train.

## Current PR

- Branch: `pr/01-bootstrap-mcp-skeleton`
- Intent: establish the Java 21 / Maven / Spring Boot skeleton, package seams, no-op MCP bootstrap, and baseline tests.

## Expected Commit Shape

1. `test: add bootstrap and architecture guard specs`
2. `feat: scaffold spring boot mcp server skeleton`
3. `docs: add contributor workflow and qodo review process`

## Local Workflow

1. Run `qodo-get-rules` before implementation once the remote is configured.
2. Start with a failing test or feature.
3. Add only the code required to satisfy the current slice.
4. Push a draft PR and let Qodo review it before broadening scope.
5. Resolve Qodo findings with `qodo-pr-resolver` or `qodo-fix`.
