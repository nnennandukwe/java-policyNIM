# Hosted Runbook

## Prerequisites

- Java 21
- Docker, for container packaging
- Postgres with `pgvector`, for JDBC-backed retrieval
- An NVIDIA API key only when the live provider is enabled

## Local Checks

Run all local checks:

```bash
./mvnw -q verify
```

Run the same package smoke used by CI:

```bash
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress -DskipTests package
jar tf target/policynim.jar | grep -F BOOT-INF/classes/application.yml
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress \
  -Dit.test=PackagedStdioIT,PackagedHttpIT,PackagedAlternateProtocolsIT failsafe:integration-test failsafe:verify
```

## Container Build

```bash
docker build -t java-policynim:local .
```

The image listens on port `8080`, runs as the `policynim` user, and includes a `/livez` healthcheck. Runtime secrets are supplied through environment variables, not baked into the image.

## Offline Readiness Run

```bash
java -jar target/policynim.jar
```

Default behavior:

- MCP transport: streamable HTTP
- HTTP bind: `127.0.0.1:8080`
- MCP path: `/mcp`
- Liveness path: `/livez`
- Readiness path: `/healthz`
- Storage mode: `noop`
- NVIDIA provider: disabled
- Hosted bearer auth: disabled

With `noop` storage, `/healthz` reports not-ready for retrieval-serving traffic. Configure JDBC storage and ingest policy chunks to make retrieval available.

## Connect a local MCP client over stdio

Set the client's command to `java`, pass `-jar` and the absolute path to `policynim.jar` as separate arguments, and set the transport in the child environment.

```bash
POLICYNIM_MCP_TRANSPORT=stdio java -jar /absolute/path/to/policynim.jar
```

The client sends JSON-RPC on stdin and reads replies on stdout. By default the jar writes diagnostics to stderr, opens without a web listener and exits when the client closes stdin. If you configure file logging, read diagnostics at the configured location. Keep an explicit logging configuration or banner override from writing to stdout. The same JDBC and provider settings apply when a client needs policy retrieval.

Set `spring.ai.mcp.server.stdio=true` to select stdio or `false` to select HTTP. That explicit setting takes precedence over `policynim.mcp.transport`. The supported HTTP spelling is `false`, ignoring case. The effective HTTP endpoint and bearer configuration also apply when an explicit Spring setting selects HTTP.

## Connect an existing HTTP client

The default Streamable HTTP endpoint is `/mcp`. For a JSON-RPC POST, send `Content-Type: application/json` and `Accept: application/json, text/event-stream`. Initialize a session and send its `Mcp-Session-Id` header on subsequent requests. If bearer authentication is enabled, each request also needs its bearer token. A missing session ID returns 400. An unknown, deleted, or expired session ID returns 404. Initialize a fresh session to recover. Use `GET /livez` for liveness probes. An authorized HEAD request to the session endpoint returns 405 and leaves the client's event stream open.

By default, the maintenance timer checks idle sessions every 30 minutes. The servlet keeps active requests and attached streams. It pings idle listeners to detect abandoned connections. Set `spring.ai.mcp.server.streamable-http.session-idle-timeout` to a positive duration to change the interval. Clients can release sessions immediately with HTTP DELETE.

For clients that use the earlier SSE protocol, set `spring.ai.mcp.server.protocol=SSE`. The default events path is `/sse`. A bearer-authenticated client follows the endpoint event to `/mcp/message`. The explicit `STATELESS` Spring option preserves the previous Java server's empty tool registry.

By default, server discovery reports the version in the jar manifest. The existing `spring.ai.mcp.server.name` and `spring.ai.mcp.server.version` overrides select the identity a client sees.

## Connect a grounded HTTP client

Set `POLICYNIM_MCP_CONTRACT=grounded-v1` and launch the jar with Streamable HTTP. Connect to the same configured MCP path. The default is `/mcp`. Each request can run without a session ID. A current MCP client can discover the server with protocol `2026-07-28`. A legacy client can initialize with `2025-11-25`. Both can use the existing Java `policy_search` and `policy_preflight` arguments and results. [Tool contracts and remaining work](parity-grounding.md)

A legacy client can keep a GET open for server-sent events. The grounded server accepts up to 1,024 of these listeners at once. A full listener pool returns 429 with a `Retry-After` header. The server sends a heartbeat every 15 seconds. Two consecutive missed writes release a stalled listener's slot, even when the stream never starts. A successful write resets the count. A disconnected client can open a fresh GET. Modern requests use POST.

Leave the contract at its default `java-v1` for existing session-based clients, SSE, or stdio. Setting `spring.ai.mcp.server.stdio` explicitly still takes precedence over `policynim.mcp.transport`.

Bearer-authenticated grounded endpoints require an explicit public origin. Set `POLICYNIM_MCP_PUBLIC_BASE_URL` to the public origin, for example `https://policies.example`. If you run a proxy, forward the original Host. The grounded endpoint accepts loopback clients and the configured public authority. On anonymous servers it also accepts the specific interface and port selected for binding. Wildcard binds grant no additional host access. An untrusted Host returns 421 and an untrusted Origin returns 403 before authentication.

## Hosted Runtime Configuration

Set these values for a hosted JDBC-backed run:

```bash
export POLICYNIM_STORAGE_MODE=jdbc
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/policynim
export SPRING_DATASOURCE_USERNAME=policynim
export SPRING_DATASOURCE_PASSWORD=policynim
export POLICYNIM_MCP_HOST=0.0.0.0
export POLICYNIM_MCP_PORT=8080
export POLICYNIM_MCP_AUTH_ENABLED=true
export POLICYNIM_MCP_BEARER_TOKEN=replace-with-runtime-secret
```

Enable the NVIDIA provider only when live model calls are intended:

```bash
export POLICYNIM_PROVIDER_NVIDIA_ENABLED=true
export POLICYNIM_PROVIDER_NVIDIA_API_KEY=replace-with-runtime-secret
```

## Ingest Policies

After JDBC storage is configured, load the policy corpus before serving MCP traffic:

```bash
java -jar <path-to-policynim.jar> ingest --corpus-root=/absolute/path/to/policies
```

The command exits after writing policy chunks to the configured JDBC store. Replace `<path-to-policynim.jar>` with the deployed jar path, such as `/app/policynim.jar` in the container image, and use an absolute corpus path in hosted environments so the runtime path is independent of the launch directory.

## Container Run

```bash
docker run --rm \
  -p 8080:8080 \
  -e POLICYNIM_STORAGE_MODE=jdbc \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/policynim \
  -e SPRING_DATASOURCE_USERNAME=policynim \
  -e SPRING_DATASOURCE_PASSWORD=policynim \
  -e POLICYNIM_MCP_HOST=0.0.0.0 \
  -e POLICYNIM_MCP_PORT=8080 \
  -e POLICYNIM_MCP_AUTH_ENABLED=true \
  -e POLICYNIM_MCP_BEARER_TOKEN=replace-with-runtime-secret \
  java-policynim:local
```

Add `POLICYNIM_PROVIDER_NVIDIA_ENABLED=true` and `POLICYNIM_PROVIDER_NVIDIA_API_KEY` only for live provider runs.

## Runtime Checks

```bash
curl -s http://127.0.0.1:8080/livez
curl -s http://127.0.0.1:8080/healthz
```

Expected states:

- `ready=false` with `noop` storage
- `ready=true` after JDBC storage is configured and readable
- `ready=false` when JDBC storage or policy chunk readiness checks fail

## CI

The GitHub Actions workflow runs five jobs:

- `unit`: Maven unit tests
- `integration`: JDBC integration tests
- `acceptance`: Cucumber acceptance tests
- `archunit`: package boundary checks
- `package`: jar packaging smoke

All CI jobs pin actions by commit SHA, run on `ubuntu-24.04`, and set `POLICYNIM_PROVIDER_NVIDIA_ENABLED=false` directly in the Maven command line to keep live provider calls out of the default PR path.
