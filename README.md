# Java PolicyNIM

Java PolicyNIM is the Java-first companion to the existing Python PolicyNIM project.

This repository is being built as a Qodo-reviewed PR train, starting with a hosted MCP bootstrap focused on:

- Spring Boot application structure
- MCP transport bootstrap for `stdio` and streamable HTTP
- public readiness checks on `/healthz`
- test-first package boundaries and contributor workflow

Contributor workflow notes for the current bootstrap slice live in [`docs/contributor-workflow.md`](docs/contributor-workflow.md).
