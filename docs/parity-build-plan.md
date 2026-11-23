# Java PolicyNIM parity Build Plan

## Summary

PolicyNIM lets a coding agent find policy text, ask for guidance and inspect the sources behind the answer. A Java operator should also be able to select a corpus, recover a previous generation and account for work admitted by the hosted service. The [parity matrix](parity-matrix.md) defines the public behavior at Python `fcc5322a6672b0275dcc6e0c299e631df8e8de19` and compares it with Java `d544829804891dda1f4e5d5e57f07146eed51342`. The first PR settles the baseline and proof strategy. Each subsequent PR delivers one behavior that a caller can exercise.

## Skills To Use

| Stage | Skill or practice | Evidence |
| --- | --- | --- |
| Identify the contract | Software Build Plan and `qodo-get-rules` | Record row IDs, pinned source, affected callers, test seam, dependency and version choices before each build. Managed rules for the Java repository cover schema consumers, field migration and package boundaries. |
| Assign ownership | `codebase-design` | Identify the module that owns an operation, the data that crosses its interface and where the behavior can fail. |
| Drive the change | `tdd-bdd` and `failure-path-testing` | Run a failing public example, make it pass, then exercise refusal and recovery with the same client. |
| Protect durable state | `transaction-proof` and `workflow-invariants` | Check the old and new state from independent connections around cancellation, retries, competing owners and process exit. Apply to publications, sessions, keys, budgets and jobs. |
| Review and merge | `qodo-review` and `qodo-review-resolver` | Pin the pushed base, assess findings at the submitted head, and retain a completed local and hosted review of the final change. |

The Java worktree has a remote and a pushed review base. Use Qodo Local Review for the whole change. No Java/Spring-specific skill is installed. Resolve API choices against the effective Maven versions, versioned upstream sources and current Context7 documentation. Run the chosen path with the pinned implementation. Use the existing JUnit, Cucumber, ArchUnit, Failsafe and Testcontainers checks at their respective boundaries.

## Scope

The three [matrix sections](parity-matrix.md#capability-rows) cover the shipped guidance, authorization, evaluation, client and operator workflows. Keep one review unit active. Split a row across multiple PRs when its tests need different runtime owners. Keep it open until the final dependent proof passes.

| Build order | What each review unit will deliver | Dependencies |
| --- | --- | --- |
| 0. Baseline | Review the matrix, the source pins, the build sequence and the starting process observations. | The two pinned trees. |
| 1. Public transport and compatibility | First repair the packaged stdio launch. Next prove version negotiation, discovery, Java field compatibility, typed errors, public probe privacy and trusted HTTP startup. Register additional tools as their implementations land. | Baseline. |
| 2. Sources and publication | Ship inventory/catalog and source packs, reviewed profiles, canonical parsing and immutable generation import/build/activate/rollback/retain. Migrate existing JDBC rows safely, hold a reader across publication, then prove external replica probe, sync, inspect and recovery. | Transport and independently authored corpus fixtures. |
| 3. Grounded work | Ship pinned retrieval and resource links, provider bounds, routing, extractive compilation, preflight and same-run evidence. Add review prompts, regeneration and conformance at the public seams. | Validated generations, source ownership and client shapes. |
| 4. Identity and admission | Enforce hosted principal/scopes, issuer verification, explicit legacy credentials, public safe status, scoped diagnostics and per-principal/global admission. Introduce durable accounting before any job worker is enabled. | Transport guards, provider lifecycle and durable store. |
| 5. Evaluation and execution | Deliver synchronous evaluation and report provenance, durable application jobs and report access, cancellation/restart, owner cleanup and accounting. Deliver local runtime decision, controlled execution and session evidence in separate builds. | Grounded workflows and evaluation fixtures. Hosted job releases also require identity and admission. |
| 6. Setup and packages | Expose prior builds through the complete operator CLI, local browser setup, hosted beta account and key flows, accessible agent instructions, optional provider modes, and installed-jar/container resources. Test each command alongside its owning build, then verify fresh installs and upgrade/recovery as a whole. | Required service and storage owners. |
| 7. Acceptance | Execute all matrix cases on the final Java package against the pinned Python process. Link reviewed changes, merge trees and post-merge runs to the rows. | All mandatory implementations and their per-unit checks. |

The stage owners below account for all mandatory rows. A row can rely on evidence from several PRs.

| Stage | Matrix rows owned |
| --- | --- |
| Public transport | G01–G04 and G23. Complete G23 with the modern SDK change feed after the corresponding tool and resource registries exist. H01 and H08 supply the later hosted protection checks. |
| Sources and publication | G05–G06, G08–G13, O03–O07 and O21. Include the maintained index, source licenses, profile isolation and verified import/export in fresh installs. |
| Grounded work | G07, G14–G22 and O09–O11. |
| Identity and admission | H01–H14, O20 and O25. |
| Evaluation and execution | J01–J14, O12–O16. |
| Setup and packages | O01–O02, O08, O17–O19 and O22–O24. |
| Acceptance | Recheck every required row at the final integrated Java revision. |

The optional native MCP Tasks extension stays outside the release baseline. Package publication, production deployment, paid workloads and real credential changes need separate authority. Track a required live claim as pending until its owner supplies that authority and the check runs. [Python release acceptance](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/docs/evidence/stable-baseline-acceptance.md)

Java can inspect plain directories, explicit pack manifests and the shipped corpus. Operators can also inspect a pinned profile’s decisions. The jar creates new profiles and compares their pins with current pack contents. The next unit applies an operator-reviewed change to the profile. [Creation and comparison evidence](parity-operations.md#profile-creation-and-source-comparison) · [Reviewed update Build Plan](build-plans/profile-reviewed-update.md)

## Package Layout / File-to-Task Mapping

Paths below `src/main/java/io/policynim/` name Java packages. Existing package boundaries and ArchUnit rules apply.

| Owner | Work |
| --- | --- |
| `PolicyNimApplication.java`, `config/`, `mcp/transport/`, `mcp/tool/` | Select one transport, preserve explicit Spring settings, negotiate protocol, validate requests and register only usable tools, resources and prompts. Keep stdout available for MCP frames in stdio mode. |
| `policy/`, `ingest/`, `persistence/jdbc/`, `src/main/resources/db/migration/` | Parse and validate source material, stage corpus revisions, publish and retain generations, bind readers to one revision, own all JDBC operations. |
| `retrieval/`, `preflight/`, `provider/` | Rank evidence and carry its source ownership into selection, compilation, guidance and traces. Put versioned MCP serialization in the transport layer. |
| `mcp/auth/`, `mcp/health/`, `mcp/telemetry/` | Verify identity, protect routes, present public probes and expose operator diagnostics with appropriate authorization. New evaluation/job and account modules will own durable lifecycle logic. |
| `src/test/`, `src/main/resources/`, `pom.xml`, `Dockerfile`, `.github/workflows/ci.yml` | Maintain the shared corpus, caller expectations, acceptance examples, package assets, JDBC and packaged process checks. Add a separate process-based parity runner with the first cross-language fixture that needs it. |

## Dependencies And Settings

Retain Java 21, Spring Boot 3.5.6, Spring AI 1.1.4, Maven wrapper and PostgreSQL/pgvector as the starting stack. Select an SDK upgrade or narrowly scoped transport adapter only against a failing protocol test. Verify the effective dependency tree, supported Spring combination and existing clients before accepting that change. The starting Python environment uses its committed `uv.lock` with Python 3.11 or 3.12.

Apply new Flyway migrations forward from existing databases. Capture baseline rows and test upgrades before activation. Plain Java chunk rows lack the sealed source bytes needed for historical identity. Require an owned source reingest or verified bundle before offering generation-backed proof. Retain the previous data until the replacement has passed validation.

Preserve explicit Spring transport, bind and endpoint configuration precedence. Preserve Java's existing ingest invocation. Keep offline CI deterministic, use isolated loopback provider/issuer fixtures, and keep NVIDIA disabled by default. A missing required provider should produce a typed failure or supported refusal at the owning interface.

## Canonical Contracts

1. **Client shape.** Keep the existing Java caller's `topK` request and camelCase search response available, including calls that omit the optional argument. In the Python-compatible contract, use `top_k`, strict schemas, compatible text JSON and `structuredContent` where the pinned tools supply it. Bind the contract to an explicit server launch profile or versioned endpoint before interpreting tool arguments. Test both clients with omitted arguments. Require deterministic rejection of contradictory aliases when both can be submitted. Keep authorization identical across contract profiles.
2. **Source identity.** A caller holding a resource URI for generation A can still read A after B becomes active, until retention legitimately removes A. Reject missing, invalid or expired links. Compare canonical bytes for sealed bundle identity, source/chunk keys and packet digests. A newly built native store must publish verifiable artifact identity and the original bytes needed for interoperability. Verify reference bundle import and export before closing the row.
3. **Grounding.** Search returns bounded evidence. Compilation returns complete normalized policy bodies in `required_steps`. Preflight repeats the selected bodies once in `plan_steps`. Empty categories, service-owned wording, citations and the valid empty refusal have exact fixture expectations. An inconsistent NVIDIA selection fails with `invalid_response`. The evidence tool returns one run and one packet. [Source-extractive contract](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/docs/source-extractive-guidance.md)
4. **Hosted work.** Scope, active principal and resource ownership govern dispatch. Separate free reads, charged work and occupied capacity. An authorized caller can reconnect after disconnection and inspect a durable job or retained result. An unrelated principal gets the documented unavailable response. Compare correlation, ownership, bounds and release of reservations after failure. [Hosted contracts](parity-hosted.md)
5. **Packaged workflows.** A jar or container installed outside the checkout can exercise each documented operator function with a selected source/profile. The hosted account shows a secret once on explicit issuance. Following instructions or viewing an account can't establish that a client connected. [Operator contracts](parity-operations.md)

## TDD And BDD Implementation Strategy

1. **First runnable build.** Start the packaged jar on stdio with the normal Java transport setting in an isolated process and use a client that parses every stdout line. Feed an initialization version the Java baseline supports. Require valid JSON-RPC output, a working tool listing and predictable close on stdin EOF. Cover explicit Spring settings, the HTTP route and the separate ingest path. Exercise newer protocol versions in their own review unit.
2. **One fixture, two independent calls.** Commit a small reviewed policy with a multiline body, known line offsets, an empty match and independently calculated canonical bytes. Extend it with a second generation and a conflicting or insufficient selection. Run the same requests through the Python process and the Java package against expected values authored from the policy text and public contract.
3. **One refusal, one recovery.** For each matrix row, exercise the normal operation, a rejected operation and a valid retry through the same client. Add a second reader, principal or process only when the contract requires it.
4. **Package before completion.** A unit can start with Spring/JUnit tests. It closes after the owning public route, command or packaged artifact demonstrates the stated outcome and the exact merged revision passes CI.

## Component Design

Keep the Java transport responsible for negotiation and serialization. The corpus owner validates an immutable generation and exposes a reader that holds that revision through retrieval, source selection, evidence serialization and cleanup. JDBC owns activation and retention transactions. Provider calls run outside publication transactions. Verify reference bundle bytes before adapting their contents for PostgreSQL. Protect the old active generation through staging failure or process loss.

Use the same held source set to build the compiler packet, the preflight output and the evidence trace. Providers select identifiers. The service resolves authoritative text locally, verifies ownership and computes packet identity. Reuse those modules for evaluations and local runtime decisions.

A hosted request obtains its verified principal once per applicable request. The admission module takes that principal and a bounded operation. Separate durable accounting from a worker's actual occupancy. Evaluate state transitions in the job owner and enforce ownership again in transactional writes. Keep CLI, MCP and portal handlers thin. A new abstraction must make an identified behavior testable or serve actual callers.

## Failure And Recovery Rules

| Failure | Observable result and recovery |
| --- | --- |
| Package launches in the wrong mode or writes non-protocol stdout | Fail the process test on the first unexpected frame. Capture redacted stderr and exit status. Reopen the client with the documented launch settings. |
| Interrupted publication, stale activation token or unknown historical key | Keep the last verified active pointer and held readers. Report a typed refusal. Inspect staged/retained inventory, then retry validation and activation with the expected state. |
| Missing ownership, altered body, invalid selector or provider deadline | Return the documented refusal/error for that path. Compare call counts and released resources. A subsequent valid request can acquire capacity and use the same generation. |
| Issuer unavailable, wrong scope, principal limit or stale job worker | Deny dispatch or publication as appropriate. Inspect redacted status and the durable ledger. Recover with the right owner, budget and verified credential. Reconcile interrupted work using the recorded evidence. |
| Replayed key form, aborted setup or failed package migration | Preserve the last active key/configuration unless an explicit rotation or completed save committed. Check key prefix, session, file bytes and installed resources after restart. |

## Commit Plan

Use one branch per independently testable behavior. Pair code with its fixture and proof. The initial docs commit adds this plan and the matrix. The first implementation commit connects the stdio regression to its launch fix. Follow each later build with the migration notes and exact-revision receipt for its rows. Record a commit's own merge evidence in the PR record or a later reconciliation commit to avoid a self-referential SHA.

## Branch And PR Flow

1. Base the documentation PR on `d544829804891dda1f4e5d5e57f07146eed51342` using `parity/baseline-plan`. For each build, branch from the last verified main. Keep the unrelated pending checkout out of each diff.
2. Run focused and full native checks. When local Docker can't supply PostgreSQL, retain that failure and require the established Ubuntu JDBC lane to pass before merge. Commit task-owned work, then run a fast Qodo checkpoint at substantive coding milestones and a final auto-depth review against the pushed merge base. Include row IDs, decisions, changes, pinned specifications, test results and limitations in the review context.
3. Reproduce findings at the reviewed revision. Fix supported issues, repeat affected and full checks, and re-review the revised head. Keep disagreement and service errors visible. Obtain specific authority before storing a dismissal or marking a finding implemented.
4. Open the PR, require a completed structured Qodo session at its exact head and inspect all five native CI jobs plus any additional required checks. Recheck the base and head, merge with the reviewed head SHA as a guard, compare the resulting tree and follow post-merge CI to completion.
5. Link the PR, merge SHA and CI run to the affected rows. A change to either runtime, test fixtures or acceptance criteria requires a new receipt at the affected revision.

## Test Plan

Run the following from the Java worktree. CI separates unit, JDBC, acceptance, architecture and packaging jobs. The JDBC test uses a real PostgreSQL 16 container with pgvector 0.8.2.

```bash
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -q verify
./mvnw -q -Dtest=PolicyMcpToolSpecificationConfigurationTests,PolicyMcpToolsTests test
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress \
  test-compile '-Dit.test=io.policynim.persistence.jdbc.*IT' failsafe:integration-test failsafe:verify
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress -DskipTests package
jar tf target/policynim.jar
git diff --check
```

Export the pinned Python commit into a disposable directory, install from its lock and run the documented offline lane there. Exclude live and Docker-live markers in the default lane. Use the same independent fixture with both products. Bind each package run to a SHA-256 digest, an isolated config and a named database. Verify fresh install, immutable resource reads across activation, malformed inputs, scope and cross-principal denial, cancellation and restart recovery. Exercise installed SDKs, actual coding clients, and browser/accessibility flows at their respective rows.

## Definition Of Done

A build closes when its public example passes, its failure and retry preserve the documented state, the supported Java caller still works and its exact head passes local and hosted review. The merged tree and post-merge CI must agree with the delivered revision.

The program closes when every mandatory matrix row links its final two-runtime or packaged proof, each supported architecture difference names the behavior tested, and the report identifies the final Java revision, pinned Python revision, artifacts, review, merge and CI evidence. Live and external claims need the evidence specified in their row.

## Assumptions And Defaults

The committed Python source, its matching tests and accepted plans define this baseline. Where an old guide disagrees with the shipped registry or measured behavior, record the discrepancy and resolve it against the implementation and acceptance tests. Reuse scoped Qodo rules for this migration, and retrieve fresh rules when the implementation concern changes. Get a product decision before removing an existing public Java contract.
