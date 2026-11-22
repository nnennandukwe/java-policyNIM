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
  -Dit.test=PackagedStdioIT,PackagedHttpIT,PackagedAlternateProtocolsIT,GroundedHttpIT,GroundedStdioIT failsafe:integration-test failsafe:verify
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

To use the grounded contract, set both environment values in the local client's server configuration.

```bash
POLICYNIM_MCP_TRANSPORT=stdio POLICYNIM_MCP_CONTRACT=grounded-v1 java -jar /absolute/path/to/policynim.jar
```

A legacy client starts with `initialize`. A modern client sends per-request protocol metadata and can start with `server/discover` or call a tool directly. Each stdio connection keeps the protocol selected by its first request. Use a fresh process to switch protocols. Send one UTF-8 JSON message per line, up to 4 MiB before the line ending. Keep stdout available for replies while closing stdin so a pending request can finish. [Protocol versions and compatibility](build-plans/mcp-negotiation.md)

## Connect an existing HTTP client

The default Streamable HTTP endpoint is `/mcp`. For a JSON-RPC POST, send `Content-Type: application/json` and `Accept: application/json, text/event-stream`. Initialize a session and send its `Mcp-Session-Id` header on subsequent requests. If bearer authentication is enabled, each request also needs its bearer token. A missing session ID returns 400. An unknown, deleted, or expired session ID returns 404. Initialize a fresh session to recover. Use `GET /livez` for liveness probes. An authorized HEAD request to the session endpoint returns 405 and leaves the client's event stream open.

By default, the maintenance timer checks idle sessions every 30 minutes. The servlet keeps active requests and attached streams. It pings idle listeners to detect abandoned connections. Set `spring.ai.mcp.server.streamable-http.session-idle-timeout` to a positive duration to change the interval. Clients can release sessions immediately with HTTP DELETE.

For clients that use the earlier SSE protocol, set `spring.ai.mcp.server.protocol=SSE`. The default events path is `/sse`. A bearer-authenticated client follows the endpoint event to `/mcp/message`. The explicit `STATELESS` Spring option preserves the previous Java server's empty tool registry.

By default, server discovery reports the version in the jar manifest. The existing `spring.ai.mcp.server.name` and `spring.ai.mcp.server.version` overrides select the identity a client sees.

## Connect a grounded HTTP client

Set `POLICYNIM_MCP_CONTRACT=grounded-v1` and launch the jar with Streamable HTTP. Connect to the same configured MCP path. The default is `/mcp`. Each request can run without a session ID. A current MCP client can discover the server with protocol `2026-07-28`. A legacy client can initialize with `2025-11-25`. Both can use the existing Java `policy_search` and `policy_preflight` arguments and results. [Tool contracts and remaining work](parity-grounding.md)

A legacy client can keep a GET open for server-sent events. The grounded server accepts up to 1,024 of these listeners at once. A full listener pool returns 429 with a `Retry-After` header. The server sends a heartbeat every 15 seconds. Two consecutive missed writes release a stalled listener's slot, even when the stream never starts. A successful write resets the count. A disconnected client can open a fresh GET. Modern requests use POST.

Leave the contract at its default `java-v1` for existing session-based clients, SSE, or the earlier Java stdio client. Setting `spring.ai.mcp.server.stdio` explicitly still takes precedence over `policynim.mcp.transport`.

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

## Inspect a local policy directory

To inspect the installed pack from any working directory, run the jar’s source commands. Each command reads the shipped files directly from the jar.

```bash
java -jar /app/policynim.jar policies list --source
java -jar /app/policynim.jar policies validate --source --format json
java -jar /app/policynim.jar policies show SEC-AUTH-001 --source --format json
```

The shipped pack contains 26 policies. To inspect your own directory, set `POLICYNIM_CORPUS_DIR` in the process environment or in a file named by `POLICYNIM_CONFIG_FILE`. The process setting takes precedence over the file. A relative path starts at your working directory. You can override the environment for one command with `--corpus-root`. Set `POLICYNIM_CORPUS_DIR` to an empty string to use the shipped pack even when the settings file names another corpus. If the jar reports a malformed source setting, correct the named setting or pass the directory with `--corpus-root`. If a bundled file can't be read, reinstall the jar or select a local directory. Source inspection requires `--source` until the jar supports active-generation selection. [Source selection Build Plan](build-plans/bundled-source-catalog.md)

Use the packaged jar to inspect editable policy Markdown before configuring storage or a model provider. Put `policies` immediately after the jar path. Select a directory containing policy Markdown. When a root `pack.toml` is present, the command reads the declared policy members. In a plain directory it reads nested Markdown and skips `TEMPLATE.md`. For a path relative to the current directory, use the same working directory for all three commands.

```bash
java -jar /app/policynim.jar policies validate --source --corpus-root=/work/policies --format json
java -jar /app/policynim.jar policies list --source --corpus-root=/work/policies
java -jar /app/policynim.jar policies show OPS-RECOVERY-001 --source --corpus-root=/work/policies --format json
```

Copy the ID from `list` exactly, including uppercase and lowercase letters. On Linux, use a UTF-8 locale when a policy ID or path contains non-ASCII characters. For example, prefix the command with `LC_ALL=C.UTF-8` on Ubuntu. The jar writes policy text and JSON in UTF-8.

The content revision hashes the original bytes, including line endings. The target digest also covers the normalized policy metadata, body and relative path. The catalog digest covers every selected entry in policy-ID order. A source inspection reports `scope=source`, `generation_id=null` and an unknown indexed count.

For an explicit pack, point `--corpus-root` at the directory containing `pack.toml`. Text `list` shows the pack ID and version beside each selected policy. JSON `show` includes its declared requirement level, applicability, grounding and source references. `validate --format json` lists unselected Markdown paths under `unlisted_markdown`. A Markdown file under a pack contributes to the catalog only when its manifest names it. Store provenance in the manifest and use the same policy ID in the member and its Markdown. Text output escapes control and formatting characters in paths. Use JSON when you need the exact filenames. [Pack fields and example](build-plans/explicit-policy-pack.md#canonical-contracts)

For example, if `ops/recovery.md` declares `policy_id: OPS-RECOVERY-001`, this `pack.toml` selects it for the three commands above.

```toml
schema_version = 1
pack_id = "team.operations"
version = "1.0.0"

[[policies]]
policy_id = "OPS-RECOVERY-001"
path = "ops/recovery.md"
requirement_level = "recommended"
```

If a file can't be read or two entries declare the same ID, fix the reported source and rerun `validate`. JSON mode returns one error object on stdout and exit 1 for an invalid source. Command usage errors go to stderr with exit 2. A file alias must resolve within the selected root. Inspect an operator-controlled directory while its contents are stable. The command reports a detected change during capture.

## Create and compare policy profiles

Create a profile when you want to pin the policy revisions used by a team. With no pack arguments, the jar pins its shipped 26-policy baseline and writes `builtin:baseline` to the profile. To select your own packs, pass each manifest directory with `--pack` in the order you want to review them. The jar resolves these arguments from your working directory and records their locations relative to the profile where the filesystem permits it.

```bash
java -jar /app/policynim.jar profile init /work/team.toml --id team --pack /work/security --pack /work/operations
java -jar /app/policynim.jar profile preview /work/team.toml
java -jar /app/policynim.jar profile diff /work/team.toml --format json
```

Init creates the profile at a new path. If the destination already exists, inspect it or choose another path. On Linux and macOS the destination filesystem must support hard links. On Windows the writer needs permission to rename the staged file. If init reports that the profile was created before cleanup failed or its directory moved, inspect that directory for the completed profile before retrying.

On Linux and macOS, init creates the profile with owner-only access. To use it from the container, create it as the service user in a writable mounted directory or grant the intended reader access to the profile and its parent directories. The service user also needs read access to each selected pack.

Diff reads the profile and its current sources. It lists added, changed and removed policy revisions, packs with changed pins and decisions that need another review. `current_policies` contains the current authored text and target digests before decisions are applied. If `validation_error` contains a message, use it to repair the profile or sources and rerun diff. A changed pack ID or an unreadable profile prevents a comparison and returns exit 1.

The review digest binds the original profile text to the proposed pins. A changed comment can change the digest even when the policies stay the same. To adopt a reviewed change with the Python CLI, use `policynim profile update /work/team.toml --expect-digest <review_digest>`. Rerun preview to inspect the result. [Profile initialization and diff Build Plan](build-plans/profile-init-diff.md)

## Inspect a policy profile

A profile is a TOML file that names policy packs, pins their revisions and records why a policy was excluded or replaced. `preview` shows every authored policy, including inactive entries and the recorded reason. `validate` checks the pins and the profile's decisions. Run these commands with the path to a local profile.

```bash
java -jar /app/policynim.jar profile preview /work/team.toml
java -jar /app/policynim.jar profile validate /work/team.toml --format json
```

A relative pack path starts at the profile's parent directory. A selector such as `builtin:security` reads that topic pack from the jar. The spelling and order of pack selectors contribute to the profile and catalog digests. Use the original selector spelling when comparing a reviewed revision. If a pin is stale, inspect the changed pack and review the profile before trying validation again.

To look up one policy in that profile, set `POLICYNIM_PROFILE` to its file and run the source command. Clear `POLICYNIM_CORPUS_DIR` when selecting a profile. The same two settings work in the file named by `POLICYNIM_CONFIG_FILE`. The process environment overrides each corresponding file value. The Java `--corpus-root` flag selects a directory for one source command.

```bash
POLICYNIM_PROFILE=/work/team.toml java -jar /app/policynim.jar policies list --source
POLICYNIM_PROFILE=/work/team.toml java -jar /app/policynim.jar policies show SEC-AUTH-001 --source --format json
```

Source inspection retains inactive members, their original bodies and their target digests. Use the effective count to see how many policies the profile selects. A failed profile check writes no changes to the profile or its packs. [Read-only profile Build Plan](build-plans/read-only-policy-profiles.md)

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
