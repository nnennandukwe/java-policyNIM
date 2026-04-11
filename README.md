# Java PolicyNIM

Java PolicyNIM is a Java-first hosted MCP server for grounded policy retrieval and preflight guidance.

The current server exposes:

- `policy_search` for grounded policy search over the local corpus
- `policy_preflight` for task-specific policy guidance with citation validation
- `/mcp` as the hosted streamable HTTP MCP endpoint
- `/livez` as a public liveness endpoint
- `/healthz` as a public readiness endpoint with diagnostic storage details

Project docs:

- [Contributor workflow](docs/contributor-workflow.md)
- [Architecture](docs/architecture.md)
- [Hosted runbook](docs/hosted-runbook.md)

## Local Verification

```bash
./mvnw -q verify
```

The default runtime is offline from live providers: NVIDIA-backed embedding, rerank, and preflight generation are disabled unless explicitly enabled.

## Packaging

Build the application jar:

```bash
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress -DskipTests package
```

Build the container image:

```bash
docker build -t java-policynim:local .
```

The Dockerfile uses pinned Eclipse Temurin base image digests, runs the service as a non-root user, and keeps bearer tokens and provider API keys outside the image.

## Hosted MCP Auth

Bearer auth for the hosted MCP endpoint is opt-in while local bootstrap work is still in progress. Set `policynim.mcp.auth.enabled=true` and provide `POLICYNIM_MCP_BEARER_TOKEN` to require `Authorization: Bearer <token>` on `/mcp`; `/livez` and `/healthz` stay public for probes.
