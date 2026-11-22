# MCP protocol negotiation Build Plan

## Summary

A client can initialize the packaged Java server and still miss the tool it needs because the client and server negotiated an older protocol. This build gives Java the two protocol paths used by Python at `fcc5322a6672b0275dcc6e0c299e631df8e8de19`. Operators select the Python-compatible contract explicitly. Existing installations retain the session and tool calls supported by the starting Java jar at `d544829804891dda1f4e5d5e57f07146eed51342`. Build from the verified stdio-launch merge `ea95f7c853feba21615b2d3bc8a579b00a71ab85`. [G01 contract](../parity-grounding.md) · [Python wire tests](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_mcp_protocol.py)

## Skills To Use

Apply the scoped `qodo-get-rules` contracts for schema consumers, package boundaries, and migration. Use `codebase-design` for shared tool ownership, `tdd-bdd` and `failure-path-testing` for both transports, and `workflow-invariants` for stdio protocol selection. Qodo Local Review is eligible on this connected repository with a pushed main base. Apply `qodo-review` and `qodo-review-resolver` to each complete review unit.

## Scope

**In scope.** A typed startup setting selects the Java or grounded contract. Negotiate the supported versions, dispatch the existing search and preflight tools, report the installed Java version, preserve existing clients and transport settings, and stop cleanly on EOF or shutdown. Exercise the existing bearer gate for each HTTP dispatch path.

**Deferred.** G02–G04 and subsequent feature builds own complete tool discovery, new tools, canonical search/preflight schemas, domain error recovery, and data-backed citations. This build checks the protocol envelope and calls the two tools with their current Java contracts. A result from noop storage can establish a readiness refusal. A successful data-backed call needs a real supported store.

## Package Layout / File-to-Task Mapping

Paths below the Java source root start at `src/main/java/io/policynim/`.

- `pom.xml`, `config/PolicyNimProperties.java`, `config/PolicyNimMcpEnvironmentPostProcessor.java`, and `mcp/transport/` own dependency resolution, typed contract selection, effective transport selection, SDK registration, and shutdown. Keep ingest command mode independent.
- `mcp/tool/PolicyMcpToolSpecificationConfiguration.java` owns SDK-facing definitions. `PolicyMcpTools.java` owns invocation, readiness, and telemetry. Route both SDK protocol APIs to the same two tool operations and their argument and response mapping.
- `mcp/auth/SecurityConfiguration.java` and the hosted startup guard continue to protect the effective servlet path. Preserve the operator's configured endpoint and bind settings.
- Extend `src/test/java/io/policynim/packaging/PackagedStdioIT.java`. Add the HTTP process cases beside it, JDBC success checks to the integration lane, and a small shared set of independent protocol expectations to test resources. Keep the cross-language client harness outside the production jar.

## Dependencies And Settings

The starting jar packages Java SDK core and Spring MVC transport 0.17.0 through Spring AI 1.1.4. The application runs Java 21, Boot 3.5.6, Tomcat 10.1.46, Reactor 3.7.11, and Jackson 2.19.2. The installed jar manifest reports `Implementation-Version: 0.0.1-SNAPSHOT`. Read that installed version for both server-info responses. Keep the configured server name, which defaults to `PolicyNIM`.

The official Java SDK 2.2.0 ships separate modern and legacy server APIs. An isolated probe ran their stdio and Jakarta servlet transports separately with `mcp-core` and `mcp-json-jackson2` 2.2.0 on the current Boot runtime libraries. The probed legacy HTTP transport used sessions. The probe didn't run the dual-mode dispatcher, stateless legacy HTTP adapter, real tool definitions, or packaged Spring lifecycle. The SDK core POM declares Reactor 3.9.0-RC1 and Jakarta Servlet 6.1.0. Boot's Tomcat provides Servlet 6.0. Treat any method-linkage failure on a required path as a failed compatibility check. Keep one servlet API, one MCP core, and one JSON mapper implementation. Avoid introducing the old `javax.servlet` namespace into Boot's Jakarta runtime. [SDK core POM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-core/2.2.0/mcp-core-2.2.0.pom) · [SDK source archive](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-core/2.2.0/mcp-core-2.2.0-sources.jar)

Pin 2.2.0 for the SDK and Jackson 2 binding at the application-owned transport boundary. Replace the two earlier MCP starter registrations with explicit wiring to prevent competing transports. Confirm the resolved dependency tree and packaged libraries. Preserve Spring's explicit server enablement, stdio switch, HTTP protocol, endpoint, address, port, and the effective application type established by the previous build. Keep the servlet/MVC application and manual tool registration. [Java starting POM](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/pom.xml)

Add the typed setting `policynim.mcp.contract` (environment equivalent `POLICYNIM_MCP_CONTRACT`). Accepted values are `java-v1` and `grounded-v1`. Omission selects `java-v1`, and an unsupported value fails startup with the property's name. The selection belongs to the server process and applies at its existing configured endpoint for both transports. The same selection determines the tool/result schema when G02–G04 implement it, including calls that omit both `topK` and `top_k`.

## Canonical Contracts

The same pinned Python SDK 2.2.0 legacy client sent `initialize` proposing `2025-11-25` to both baseline processes. Java issued an MCP session ID. A follow-up without it returned HTTP 400, an unknown ID returned 404, and the issued ID was accepted. Python omitted the session header. These observations rule out choosing the public contract by the client's proposed revision. An input argument, credential, or request's body shape also can't select the configured contract.

| Caller | Observable contract |
| --- | --- |
| `grounded-v1`, modern client | `2026-07-28`. Auto mode starts with `server/discover`. A client that already knows the version can start with `tools/list` or `tools/call`. Each request carries version and client-capabilities metadata. HTTP checks method/version and the mirrored name for named calls. A completed modern result carries `resultType=complete`. |
| `grounded-v1`, legacy client | `initialize` offers `2025-11-25`, followed by `notifications/initialized` and a tool request. The pinned Python HTTP server returns JSON without a session ID in both modern and legacy mode. |
| `java-v1`, existing Java client | Preserve earlier stdio and HTTP negotiation, current tool arguments/results, and the stateful HTTP session handshake, including missing/unknown/issued ID behavior. The same result applies when the setting is absent. |
| Explicit Spring HTTP variants under `java-v1` | The original jar serves a bearer-authenticated legacy SSE stream at `/sse` when `spring.ai.mcp.server.protocol=SSE`. The endpoint event leads to `/mcp/message`, and initialization at `2024-11-05` exposes both Java tools. With `STATELESS`, the original HTTP endpoint initializes without a session ID and lists an empty tool registry. Preserve both overrides, their configured paths and their existing access rules. |
| Errors and identity | Correlate JSON-RPC errors with the request ID where available. A malformed protocol envelope yields a protocol error. An accepted call whose tool reports a readiness failure yields a tool result with `isError=true`. Both registries return the configured name and installed Java version. |

## TDD And BDD Implementation Strategy

1. Replay the known legacy handshake in the default Java configuration, explicit `java-v1`, and pinned Python. Capture version, session header, missing/unknown/issued session results, tool calls, EOF outcome, and package checksum. Establish failing tests for version reporting and for the absent canonical path.
2. Review the SDK migration with only the established Java behavior exposed. Keep both existing transports usable with two manually registered tools. Verify previously supported protocol offers and checked default session semantics before merging this prerequisite.
3. Add `grounded-v1` HTTP and stdio in successive review units. Start each with an actual client failure. Cover modern discovery, a direct call with no discovery, the canonical legacy flow, and invalid request recovery. Expose only a working contract/transport combination at each intermediate head. Record the outstanding transport in G01 until both pass.

## Component Design

**Select the server contract at startup.** Register exactly one endpoint and one server lifecycle for the resolved transport. `java-v1` uses the stateful legacy servlet (`HttpServletStreamableServerTransportProvider`) and retains its existing stdio path. `grounded-v1` uses the modern and stateless legacy HTTP handlers or their stdio counterparts. The 2.2.0 stateful servlet creates a non-daemon scheduler for its idle sweep and leaves that scheduler running after the transport closes. Use one application-owned timer and one shared SDK session factory. Keep each issued ID with its servlet delegate so expiration can close the delegate and release its SDK session map. The previous Boot 3 MVC modules hit a removed SDK error constructor on missing or unknown sessions. [SDK servlet source at 2.2.0](https://github.com/modelcontextprotocol/java-sdk/blob/81aed210cea9b4d080d0300944b6ee128cf64424/mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletStreamableServerTransportProvider.java)

Both contracts share the existing bearer/authentication chain, configured servlet path, bind settings, and application services. Header values, dates, credentials and incoming schema fields never override the server setting. Bind failure or an unsupported transport combination must fail clearly before a listener starts. When the grounded HTTP profile ships, an explicit Spring `SSE` or `STATELESS` selection conflicts with its Streamable HTTP endpoint. Name both settings so the operator can select the intended client contract.

**HTTP within grounded-v1.** Place the canonical dispatcher behind the same Spring Security filters. Python SDK 2.2.0 uses `MCP-Protocol-Version` to select the wire protocol *within* the configured contract. Dispatch absent or known handshake revisions to `HttpServletStatelessServerTransport`. Any other present value goes to modern `HttpServletMcpTransport`, including unsupported versions that need a typed refusal. Reject duplicate/conflicting routing headers before dispatch. The modern handler validates the method, version, and applicable name headers against the actual body. Canonical legacy `initialize` and subsequent calls require no session ID. The server doesn't inspect an initialize request's proposed version to choose between Java and grounded behavior.

The stateless Java servlet requires both JSON and SSE in Accept, while the pinned Python server in JSON-response mode accepts JSON alone and matching wildcards. Cover these requests at the public boundary. Normalize an accepted JSON-response request for the internal delegate if needed and retain the public 406 for unacceptable representations. Modern `ToolsFeature.ofSync` and the legacy stateless/stateful specifications have different types. Map each to the same service operation with one telemetry record, equivalent structured content and text, and the existing safe failure payload.

**Stdio within grounded-v1.** Open stdin once. Select the protocol from the first JSON-RPC **request**. `initialize` chooses legacy. Any other request with the reserved `params._meta["io.modelcontextprotocol/protocolVersion"]` key chooses modern. A progress token alone doesn't select modern. Bound frame size, retain at most eight preceding non-request frames as Python does, replay only that retained prefix and the opening request, and preserve unread buffered bytes. Construct only the selected SDK transport. The Java legacy provider starts consuming its input when the server attaches its session factory. Use the shared output writer to keep error and result frames intact under concurrent calls.

Keep the selected protocol for the life of stdin. A subsequent modern envelope on a legacy connection yields `-32600`. A subsequent initialize on a modern connection yields `-32022` with supported/requested data where available. Java's modern SDK handles a plain legacy initialize but treats a modern-stamped initialize differently. A boundary guard should cover the missing case and leave normal protocol decoding and tool dispatch to the SDK. [Pinned Python SDK](https://pypi.org/project/mcp/2.2.0/) · [Python process checks](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_mcp_protocol.py)

Python MCP 2.2.0 checks the media range in `Accept` and discards its parameters. When JSON matches, both protocol modes return the JSON response even with `application/json;q=0` or `application/json;q=0, */*;q=1`. Preserve this observable choice in grounded HTTP. The HTTP specification permits a server to disregard an unsatisfied content negotiation field. [HTTP semantics, section 12.4.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-12.4.1)

The reference HTTP router advertises legacy DELETE on a HEAD refusal, although the stateless service rejects DELETE with 405. Java reports the available methods, `Allow: GET, POST`, for legacy 405 responses and `Allow: POST` for modern 405 responses. Java also bounds held legacy GET listeners at 1,024, using the locked SDK's default subscription capacity as its limit. A saturated listener pool returns 429. Disconnect, shutdown, or two consecutive missed heartbeat writes release a slot. A healthy idle stream can stay connected across heartbeat cycles. [HTTP Allow semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.1) · [SDK capacity and cleanup](https://github.com/modelcontextprotocol/python-sdk/blob/9972c21aa42054fb1450c5fc614761ed11847ec6/src/mcp/server/subscriptions.py)

Grounded requests use string or integer IDs. The pinned Python legacy decoder can interpret a request with a null or fractional ID as a notification and return HTTP 202. Java rejects these malformed legacy frames with HTTP 400 and `-32602`. Both modern paths return HTTP 400 and `-32600`. The Java error text stays bounded and excludes the request payload. Compare the status, code and correlation rules for each named refusal. [Locked protocol types](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/uv.lock)

A legacy client can POST a JSON-RPC response. On the pinned Python server, an object result with a string or integer ID receives HTTP 202. A null, array, or scalar result receives HTTP 400 and `-32602`. A well-formed error response can carry a null ID. The modern endpoint rejects posted responses with HTTP 400 and `-32600`. The paired HTTP check exercises these cases before the next valid request. [Python SDK wire types](https://github.com/modelcontextprotocol/python-sdk/blob/9972c21aa42054fb1450c5fc614761ed11847ec6/src/mcp-types/mcp_types/jsonrpc.py#L56-L126)

JSON-RPC defines success and failure as separate response shapes. A legacy POST to Python can receive HTTP 202 when it contains both `result` and `error`, or when its error code is a numeric string or a boolean. Java rejects those frames with HTTP 400 and `-32602`. Send exactly one response member. The request fields `method` and `params` belong to a request or notification. Java refuses a frame that combines either field with a response member. In paired checks, Python acknowledged a legacy `ping` with array params and a result. With the same result and ordinary params, Python ran the ping. A fresh, well-formed ping works after either case on both servers. An error code must be an integer number. Both services accept `1`, `1.0`, and `1e0`, and reject `1.25`. The paired check records each server's observed response and sends a valid request immediately afterward. [JSON-RPC response and error definitions](https://www.jsonrpc.org/specification#response_object) · [JSON Schema integers](https://json-schema.org/draft/2020-12/json-schema-validation#section-6.1.1)

Use unique member names within every JSON object. On exact request bytes, pinned Python acknowledged a repeated legacy result with HTTP 202 and returned a correlated tool list for a modern request with a repeated ID. The grounded Java parser refuses duplicate names throughout a message with HTTP 400, a null ID and `-32700`. The next request can proceed normally. [JSON object interoperability](https://www.rfc-editor.org/rfc/rfc8259.html#section-4)

For a known tool with a missing required field, both installed services returned HTTP 200 with a correlated `result.isError` and a text block naming the missing field. Neither sent a JSON-RPC error for this call. Python's legacy client adds `resultType=complete` to its decoded object. Check the original response body when comparing protocol fields. Explicit null optional fields still reach different validation paths at this stage. G03 owns the grounded nullable schema and the canonical `top_k` argument. [Stateless SDK tool validation](https://github.com/modelcontextprotocol/java-sdk/blob/81aed210cea9b4d080d0300944b6ee128cf64424/mcp-core/src/main/java/io/modelcontextprotocol/util/ToolInputValidator.java)

## Failure And Recovery Rules

Apply the same HTTP protection in both contract modes on the configured path and its accepted spellings. Version/header disagreement yields the applicable structured rejection. An unsupported modern version names the supported version. Rejected requests must leave the next valid call usable. Keep diagnostics on stderr and protect bearer values, request content, and upstream exception detail.

Own each SDK instance once. The modern HTTP servlet closes its server when handed the server directly. Modern stdio needs both transport and server cleanup. The legacy stdio provider has no public completion signal for its read loop. Observe EOF on the stream it owns and verify orderly close without racing a pending response. Release services on startup failure and during shutdown. Test EOF before the first request, with an idle initialized session, and during a bounded call. Reap test-owned children and verify a fresh launch works.

## Commit Plan

1. Upgrade the SDK with explicit dependency/wiring changes, shared tool operations, truthful version reporting, and end-to-end Java compatibility tests. Gate the commit set and its packaged jar in one PR.
2. Add typed contract selection with the default `java-v1` and opt-in `grounded-v1` HTTP implementation. Add sessionful and stateless paired fixtures to prove both behaviors with identical client requests. In this intermediate PR, reject an unavailable grounded stdio launch explicitly before startup.
3. Implement `grounded-v1` stdio behind the same setting. Add bounded framing, mixed-protocol guards, EOF coverage and the full combined matrix. Update operator instructions and record the exact package result each PR earned.

## Branch And PR Flow

Start the SDK branch from **ea95f7c853feba21615b2d3bc8a579b00a71ab85** once the stdio-launch merge passes its main run. Branch the canonical HTTP work from the verified SDK merge, and the canonical stdio work from the verified HTTP merge. Apply a local fast Qodo checkpoint during substantive changes, review the complete committed change with automatic scope and the pushed base, and repeat final review after supported repairs. Require completed structured hosted review and all five CI jobs at the actual PR head. Guard the reviewed SHA during merge, compare trees, and check the main run before opening the next unit.

## Test Plan

- In `grounded-v1`, exercise Python SDK 2.2.0 auto, forced legacy, and explicitly selected modern modes against the jar. Repeat the identical legacy handshake under omitted, explicit `java-v1`, and explicit `grounded-v1` settings. Verify the existing bearer gate separately in both modes. Assert server version from the jar manifest, request IDs, envelopes, both tool names and actual calls. Compare Python legacy and modern sessionlessness to the pinned Python process. Capture the Java client's initialization, missing/unknown/issued session ID outcomes, and session close. Omit both optional top-k fields to prove that contract selection persists for calls with identical bodies. Reject an unknown setting on startup and recover with a valid value.
- HTTP cases include named-header mismatch, missing capabilities, unsupported revision, unknown method, wrong/missing bearer, rejected Origin/Host, JSON-only Accept, custom endpoint, protected alternate path spelling, and a valid call after each rejection. Stdio cases include an early notification, pipelined first/second request, direct tools/list, wrong-era request, oversized frame, EOF and a fresh launch. Assert no TCP listener or nonprotocol stdout in stdio mode.
- Verify a success against the packaged jar with real PostgreSQL/pgvector and the existing offline Java path, plus a readiness refusal with noop storage. Keep provider doubles and absent live-provider verification explicit. Test fixtures mustn't change authentication or enable real NVIDIA work.
- Run the package-lane integration test against a freshly built jar, the repository's full Maven verify and CI lanes, `jar tf target/policynim.jar`, `./mvnw dependency:tree`, and `git diff --check`. Record effective SDK/Jackson/Reactor/container versions and the jar SHA-256. The Linux CI path owns JDBC evidence when local Docker isn't available.

### Reproduce the paired stdio check

Build the Java jar with the package command in the hosted runbook. From the Java checkout, extract the exact Python revision into a temporary directory and install its locked environment there. The offline command requires the dependencies in the local uv cache.

```bash
set -e -o pipefail
reference_dir="$(mktemp -d)"
git -C /path/to/policyNIM archive fcc5322a6672b0275dcc6e0c299e631df8e8de19 | tar -x -C "$reference_dir"
uv sync --frozen --offline --project "$reference_dir"
"$reference_dir/.venv/bin/python" src/test/parity/legacy_stdio.py --jar target/policynim.jar
```

The check creates a temporary home for each server, verifies its negotiated revision and installed version, calls search without a required query, and lists tools again on the same session. Java and Python each have their own expected tool names for this migration. The result includes the jar checksum. [Package checks](../hosted-runbook.md)

The JDBC test uses the same Markdown file a Python operator can select with `POLICYNIM_CORPUS_DIR`. The pinned Python CLI accepted it with `policynim policies validate --source --format json` and reported one effective policy. Its source catalog digest was `sha256:8235dea85dcda12002099e86802cb31ece64c5597ae9f1ca2dc736be3d46b749`. The Java check runs jar ingest in one process and verifies the returned policy ID, path, body, and lines in another. [Shared recovery policy](../../src/test/resources/parity/policies/recovery.md)

### Reproduce the paired HTTP check

Use the same locked Python environment and freshly packaged jar as the stdio check. Pass the extracted Python revision so the check can import its committed public generation.

```bash
"$reference_dir/.venv/bin/python" src/test/parity/grounded_http.py \
  --jar target/policynim.jar --reference-root "$reference_dir"
```

The check starts the installed Python command and the Java jar on loopback with temporary homes and a new test bearer token. It exercises discovery, calls before discovery, sessionless legacy requests, Host and Origin checks, protocol errors, and the next valid call. The Python reference serves its indexed generation. Java's noop store supplies its documented readiness error. The JDBC package test separately checks successful Java retrieval. Each implementation has its own expected tool list while the remaining capabilities are in development.

## Definition Of Done

In `grounded-v1`, both transports negotiate the two reference protocols, execute the existing tools, report the installed Java version, reject bad envelopes at the correct boundary, and release their runtime resources. With the setting omitted or set to `java-v1`, existing clients retain their verified stateful HTTP and stdio flows. Attach the separate exact-head local and hosted reviews, merge commits, post-merge runs, and package evidence for all three review units to G01. Keep G01 open for any successful canonical call or proof that depends on later tool or storage rows. Leave G02–G04 open until their feature proofs pass.

## Assumptions And Defaults

The default `java-v1` preserves the established Java API. Set `policynim.mcp.contract=grounded-v1` to serve the canonical client on the configured URL, including its sessionless legacy HTTP handshake. The same process-wide setting governs future tool/result shapes even if optional argument names give no signal. Providers, storage, transport selection, and authentication retain their own settings.
