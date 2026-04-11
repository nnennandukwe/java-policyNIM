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
- MCP path: `/mcp`
- Liveness path: `/livez`
- Readiness path: `/healthz`
- Storage mode: `noop`
- NVIDIA provider: disabled
- Hosted bearer auth: disabled

With `noop` storage, `/healthz` reports not-ready for retrieval-serving traffic. That is expected until JDBC storage is configured and policy chunks are ingested.

## Hosted Runtime Configuration

Set these values for a hosted JDBC-backed run:

```bash
export POLICYNIM_STORAGE_MODE=jdbc
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/policynim
export SPRING_DATASOURCE_USERNAME=policynim
export SPRING_DATASOURCE_PASSWORD=policynim
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
