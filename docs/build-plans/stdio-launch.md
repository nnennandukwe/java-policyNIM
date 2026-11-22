# Packaged stdio launch Build Plan

## Summary

A client that launches the Java jar over standard input and output needs a clean JSON-RPC stream and a server that exits when the client closes stdin. The Java baseline wrote startup text to stdout, opened a TCP listener, and was still running five seconds after EOF. This PR covers that launch behavior within G01. The source pins are Java `d544829804891dda1f4e5d5e57f07146eed51342` and Python `fcc5322a6672b0275dcc6e0c299e631df8e8de19`. [Baseline measurements](../parity-matrix.md) · [Program order](../parity-build-plan.md)

## Skills To Use

| Stage | Skill | Evidence |
| --- | --- | --- |
| Settings and startup | `qodo-get-rules` | Apply the program's scoped rules. Refresh them if this build exposes uncovered ownership or precedence. |
| Behavior and recovery | `tdd-bdd`, `failure-path-testing` | Capture a failing jar session, cover stdio/HTTP selection, and prove recovery on a new launch. |
| Delivery | `qodo-review`, `qodo-review-resolver` | Review the complete diff locally and verify structured findings at the pushed head. |

The repository has a pushed base and qualifies for Qodo Local Review. Use the pinned Spring Boot/Spring AI behavior for startup decisions. This slice needs no persistent state transition or Java-specific skill.

## Scope

**In scope.** Packaged stdio framing, diagnostics, listener ownership, EOF, and explicit Spring configuration precedence. Verify existing HTTP bearer protection and ingest command behavior.

**Out of scope.** The next G01 build owns modern negotiation and server version reporting. Tool schemas, tool counts and argument rejection belong to later matrix rows. [Grounded MCP contracts](../parity-grounding.md)

## Package Layout / File-to-Task Mapping

- `config/PolicyNimMcpEnvironmentPostProcessor.java` owns derived Spring settings. Check `PolicyNimApplication.java` for the existing ingest-only startup branch. Paths here start at `src/main/java/io/policynim/`.
- `src/test/java/io/policynim/config/PolicyNimMcpEnvironmentPostProcessorTests.java` covers precedence. `PolicyNimApplicationCommandModeTests` and `BearerTokenSecurityTests` cover ingest and HTTP regressions.
- New `src/test/java/io/policynim/packaging/PackagedStdioIT.java` launches the executable jar. Extend `.github/workflows/ci.yml` in the package job and describe the verified command in `docs/hosted-runbook.md`.

## Dependencies And Settings

Use Java 21, Boot 3.5.6, Spring AI 1.1.4, existing JUnit/Jackson and Maven Failsafe. Use the current transport property and Spring overrides. The build needs no database migration, provider key, new dependency or new deployment setting.

## Canonical Contracts

From outside the checkout, run `POLICYNIM_MCP_TRANSPORT=stdio java -jar /absolute/path/to/policynim.jar`. Under normal stdio defaults, stdout contains only newline-delimited JSON-RPC messages. Diagnostics go to stderr. The server doesn't need a TCP port. A legacy client can initialize, send `notifications/initialized`, list the existing tools and close stdin. Require normal exit within five seconds of EOF as a test bound.

Explicit Spring transport, bind, endpoint and application settings retain their documented priority. Derive stdio defaults from the effective transport. An explicit Spring switch selecting HTTP must still allow HTTP startup. Default HTTP and explicit ingest remain independent launch paths.

## TDD And BDD Implementation Strategy

1. Start the baseline jar with the supported stdio setting. Parse every stdout line, including startup and shutdown. First require a valid `initialize` response and a successful `tools/list` for the existing protocol. Fix routing of output at startup.
2. Keep a loopback port occupied by the test and pass that port as the child's server port. Require the same handshake while the port stays occupied. Close stdin and observe normal exit. Fix effective non-web startup and lifetime as needed.
3. Exercise explicit Spring settings in both transport directions. Run the existing authenticated HTTP and ingest-help paths. Keep each test with the smallest change that makes it pass.

## Component Design

Resolve the effective transport after Spring reads its config sources. Supply stdio-specific defaults early enough for Boot's banner, logging and application type selection. Preserve higher-priority property sources. Keep Spring AI responsible for framing and reading stdin. Check its EOF handling in the non-web process before adding lifecycle code. Keep the existing ingest command branch and manual tool registration.

## Failure And Recovery Rules

Drain stdout and stderr separately throughout the run. Fail on any blank line, non-frame text, malformed JSON, wrong response ID, missed deadline or nonzero exit. On a timeout, terminate and reap only the test-owned process. Retain stage, protocol, elapsed time, exit status and a sanitized diagnostic, then launch a fresh jar to verify recovery. An unavailable port in HTTP mode must still report startup failure.

## Commit Plan

1. Pair the strict stdio regression with the launch fix and explicit-override tests.
2. Add package-lane execution and the operator command with its evidence.

## Branch And PR Flow

Use `parity/stdio-launch` from verified main `31ee38335ea6dd228ddf8f99fd420a1620477704`. The documentation baseline passed its five post-merge checks. [Planning PR](https://github.com/nnennandukwe/java-policyNIM/pull/11) · [CI](https://github.com/nnennandukwe/java-policyNIM/actions/runs/44971456551) Pin the actual pushed base for Qodo. At a substantive milestone run a fast local review. After native checks, run auto-depth review of all intended changes with recent work, G01 authority and evidence. Reproduce findings, repair supported defects, rerun affected and full checks and re-review changed heads. Open one PR linked to G01. Require the completed hosted review at its exact head plus all required CI jobs. Guard the merge with that SHA, compare trees, and inspect post-merge CI. Use automatic finding re-attribution. A stored dismissal or mark-implemented write requires separate authorization.

## Test Plan

Run from the Java worktree with the provider disabled.

```bash
export POLICYNIM_PROVIDER_NVIDIA_ENABLED=false
./mvnw -q -Dtest=PolicyNimMcpEnvironmentPostProcessorTests,PolicyNimApplicationCommandModeTests,BearerTokenSecurityTests test
./mvnw -B --no-transfer-progress -DskipTests package
jar tf target/policynim.jar
./mvnw -B --no-transfer-progress -Dit.test=PackagedStdioIT failsafe:integration-test failsafe:verify
./mvnw -q verify
git diff --check
```

Run the jar test after packaging in the package job. JDBC and Cucumber jobs invoke filtered Failsafe from `test-compile` and retain their current filters. Compare the pinned SDK's legacy result against the Python launcher and the final Java jar, record the jar SHA-256, and repeat the Java package checks on the merged tree.

## Definition Of Done

A strict client lists tools with zero invalid stdout lines. The packaged process completes that exchange while the nominated port's occupied and exits after EOF. Explicit Spring overrides, HTTP bearer rejection and ingest help pass. Exact-head review, CI and the post-merge run cover the shipped revision. Update only the G01 launch subcase.

## Assumptions And Defaults

Keep the Java baseline's negotiated legacy version `2024-11-05` for this test. Use a bounded startup wait independent of the five-second EOF check. The evidence concerns a local offline jar running with its stdio defaults.
