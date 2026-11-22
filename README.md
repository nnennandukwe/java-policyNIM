# Java PolicyNIM

Java PolicyNIM is a Java-first hosted MCP server for grounded policy retrieval and preflight guidance.

The current server exposes:

- `policy_search` for grounded policy search over the local corpus
- `policy_preflight` for task-specific policy guidance with citation validation
- `/mcp` as the hosted streamable HTTP MCP endpoint
- `/livez` as a public liveness endpoint
- `/healthz` as a public readiness endpoint with diagnostic storage details

HTTP clients can use the default Java session handshake or opt into sessionless legacy and modern MCP with `POLICYNIM_MCP_CONTRACT=grounded-v1`. Both profiles expose search and preflight. [HTTP client setup](docs/hosted-runbook.md#connect-a-grounded-http-client)

Project docs:

- [Contributor workflow](docs/contributor-workflow.md)
- [Architecture](docs/architecture.md)
- [Hosted runbook](docs/hosted-runbook.md)
- [Java and Python parity matrix](docs/parity-matrix.md) and [Software Build Plan](docs/parity-build-plan.md)

## Local Verification

```bash
./mvnw -q verify
```

The default runtime is offline from live providers: NVIDIA-backed embedding, rerank, and preflight generation are disabled unless explicitly enabled.

## Policy Ingest

Run `java -jar target/policynim.jar policies list --source` to see the 26 policies shipped in the jar. Use `POLICYNIM_CORPUS_DIR` or pass `--corpus-root=/absolute/path/to/policies` to inspect your own directory. The commands run without JDBC or a provider key. [List policies and inspect their revisions](docs/hosted-runbook.md#inspect-a-local-policy-directory)

Build the application jar, configure JDBC storage, then populate the policy chunk table:

```bash
export POLICYNIM_STORAGE_MODE=jdbc
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/policynim
export SPRING_DATASOURCE_USERNAME=policynim
export SPRING_DATASOURCE_PASSWORD=policynim

java -jar <path-to-policynim.jar> ingest --corpus-root=/absolute/path/to/policies
```

The ingest command exits after loading the corpus and doesn't start the hosted MCP web server.
Keep datasource passwords in environment variables or a secret manager rather than inline command arguments.

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

Bearer auth for the hosted MCP endpoint is opt-in while local bootstrap work is still in progress. Set `policynim.mcp.auth.enabled=true` and provide `POLICYNIM_MCP_BEARER_TOKEN` to require `Authorization: Bearer <token>` on `/mcp`. Grounded HTTP also requires the public service origin in `POLICYNIM_MCP_PUBLIC_BASE_URL`. The `/livez` and `/healthz` probes stay public.
