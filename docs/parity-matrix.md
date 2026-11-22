# Java and Python PolicyNIM parity matrix

An agent that uses PolicyNIM needs to discover the available tools, follow a citation to the policy used for its task and recognize when the service can't answer. Operators also need to load, inspect, recover and serve the same policies. This matrix tracks those workflows from Python's shipped interface to Java's public process and packaged application. The [Software Build Plan](parity-build-plan.md) orders the work.

## Source revisions

| Repository | Pinned main commit | Role |
| --- | --- | --- |
| Python PolicyNIM | [fcc5322a6672b0275dcc6e0c299e631df8e8de19](https://github.com/nnennandukwe/policyNIM/tree/fcc5322a6672b0275dcc6e0c299e631df8e8de19) | Shipped source, accepted plans, tests and package manifests. |
| Java PolicyNIM | [d544829804891dda1f4e5d5e57f07146eed51342](https://github.com/nnennandukwe/java-policyNIM/tree/d544829804891dda1f4e5d5e57f07146eed51342) | Starting behavior, supported clients, build and package structure. |

These pins matched both remote main branches on November 21, 2026. To revise the Python baseline, record the replacement commit, the changed rows and their new acceptance cases in a reviewed change. Historical release, deployment and client receipts apply to the revisions they name. [Python acceptance record](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/docs/evidence/stable-baseline-acceptance.md)

## Capability rows

| Open the matrix for | What to look up |
| --- | --- |
| [Grounded policy guidance](parity-grounding.md) | MCP negotiation and discovery, search, immutable sources, compiled constraints, preflight and evidence links. |
| [Hosted authorization and evaluations](parity-hosted.md) | Identity, scope, public readiness, admission, evaluation jobs, resource ownership and recovery. |
| [Operator and client workflows](parity-operations.md) | Commands, policies and profiles, providers, evaluation, local runtime, browser setup, hosted account and agent setup, packaging and release evidence. |

The matrix contains 76 required rows and one optional proposal. All 76 were open at the starting Java commit. Paired HTTP discovery identified G23, the shipped modern notification subscription, after the first inventory. Each row identifies the Python authority, the behavior to preserve, the Java owner, an acceptance case and the state of its evidence. Source inspection can establish a gap. A passing Java unit test can establish one behavior. Mark a required row verified only after its named public or packaged check passes and its review unit passes post-merge checks. For results spanning rows, link a single receipt from each affected row.

## What the starting packages returned

One Python MCP SDK 2.2.0 client launched each stdio server with an empty home, no provider key, and a 10-second read timeout. The Python 3.12.10 environment used the pinned source archive and its lockfile. The Java 21.0.10 jar had SHA-256 `fa25e58b7f59e657c3d46bfd30fa1190aa620a68d8b7a27f4aa18a011b5f92a8`.

| Observation | Python command `policynim mcp --transport stdio` | Java command `java -jar policynim.jar` with `POLICYNIM_MCP_TRANSPORT=stdio` |
| --- | --- | --- |
| SDK auto / legacy negotiation | `2026-07-28` / `2025-11-25` | `2024-11-05` / `2024-11-05` |
| Tool discovery | Four guidance tools, with annotations and output schemas | Search and preflight, both without annotations or output schemas |
| Search with `top_k=0` | Input validation rejected the argument | The noop storage readiness check returned bootstrap instructions |
| Protocol stream | The SDK read protocol frames without parse errors | The SDK recovered from repeated JSON-RPC parse failures caused by startup output |

In a separate jar run, Java stdio opened a TCP listener and remained alive five seconds after stdin closed. Explicit non-web mode, a disabled banner and stderr logging allowed a clean stream and exit. PR #12 corrected the packaged launch defaults at `ea95f7c853feba21615b2d3bc8a579b00a71ab85`. PR #13 brought the legacy stdio handshake to `2025-11-25` and verified Java HTTP retrieval through the installed jar with PostgreSQL. PR #14 added sessionless grounded HTTP. PR #15 added grounded stdio and verified both protocols against the same stored policy in Linux CI. G01 remains open for canonical results and numeric IDs beyond the current SDK range. [Java SDK migration](parity-grounding.md#legacy-client-migration) · [Grounded HTTP receipt](parity-grounding.md#grounded-http-negotiation)

At the Python pin, 315 offline tests covering protocol, grounded MCP and policy generations passed. [More transport observations](parity-grounding.md#baseline-observations)

## Evidence to record for a completed row

Record the row ID, Python pin, reviewed Java head, PR, merge SHA, post-merge run, fixture version and package digest. Name the commands and client versions, expected and observed results, provider mode and any limit of the run. For refusals and recovery, record which state survived and the result of the next allowed operation. Keep bearer values, connection secrets and per-user payloads out of published receipts.

A matching response requires the independently authored expected result to pass against each process. Two services returning the same wrong value would fail this check. Compare canonical bytes for hashes, exact source text, line coordinates, structured fields and error classes. For allocated IDs, compare their format, ownership, references and reuse within a run. Record the reason for any other normalization beside the case.

## Architecture and acceptance limits

| Choice | Public behavior that still needs proof |
| --- | --- |
| Java 21, Spring MVC, manual MCP registration | Negotiate each supported protocol and list only capabilities the caller can use. Exercise typed results and compatible text with installed clients. |
| JDBC, PostgreSQL and pgvector | Prove atomic publication, snapshot identity, historical links, pins, isolation and recovery. For shared sealed bundles, retain the artifact digest even when the runtime indexes a different physical representation. |
| Executable jar and container | Launch from outside a checkout, verify bundled policy and browser resources, run operator commands and negotiate both transports. Preserve existing Java configuration and ingest syntax. |
| Opt-in provider integration | Test the packaged runtime with local provider doubles. Track any live provider, managed capacity, actual third-party account or production claim separately with its own authority and evidence. |

The optional native MCP Tasks extension has no shipped Python adapter at this pin. Durable application evaluation jobs are required. The public Python acceptance page identifies the provider, capacity, client and release evidence separately. [Tasks issue](https://github.com/nnennandukwe/policyNIM/issues/102)
