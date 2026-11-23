# Profile Initialization and Source Diff Build Plan

## Summary

An operator needs to pin a set of local policy packs, then see which sources have changed before adopting new revisions. Add `profile init` and `profile diff` to the installed Java jar in one review unit for O05. A pin records the selected directory, pack ID, version, pack digest and individual policy revisions. Java base `c95ce30e85286852b0f733c616db21335a500957`, tree `d645f5282b946adde18a257d3ec88fa149a2b775`. On the merged jar, `profile init` and `profile diff` returned usage errors. The pinned wheel created a two-pack profile and produced the expected clean review token for that file. Python authority is `fcc5322a6672b0275dcc6e0c299e631df8e8de19`. [Parity plan](../parity-build-plan.md) · [Preceding build](read-only-policy-profiles.md) · [Operator contract](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/docs/policy-profiles.md#L16-L104)

## Skills To Use

| Stage | Skill and evidence |
| --- | --- |
| Contracts | Use `qodo-get-rules` and `codebase-design` to place pack capture, decision validation and digest construction behind the same catalog boundary that serves preview. |
| Behavior | Use `tdd-bdd` and `failure-path-testing` for installed command failures, successful retries and recovery after a stale decision. Use `policynim-runtime-path-safety` for the caller, profile parent and packaged resource locations. |
| Publication | Use `transaction-proof` for staging, flush, publication, cleanup and the first subsequent consumer read. Observe one complete winner under concurrent creation. |
| Delivery | Use `policynim-offline-ci-hardening` for the pinned wheel and native package lanes, `voice-canon` for operator text and the language check, and `qodo-review` and `qodo-review-resolver` at the exact heads described below. |

The scoped Qodo searches returned error rules 1060851, 1060853 and 1060863 on retaining fields and names, 1060879 on updating command consumers and 1060865 on exhaustive handling when adding values. Check the existing command forms, report schema, command help and rendering. Rule 1060843 concerns renamed event fields. This build adds CLI commands. The general search returned warnings 8184, 7205, 613754 and 699152 on English and meaningful identifiers, plus 2117707 on making system I/O testable. Exercise the filesystem provider through the installed jar and inject failures at the publication boundary. Keep the existing Python harness docstring rule 784431. The initial and broadened publication searches returned zero matches. The pinned Python service defines the public behavior. Java uses the filesystem operations described below to publish a complete profile.

## Scope

Initialize a new profile from the aggregate or an ordered list of local manifested packs. Diff its authored pins against the current versions of the selected packs. Preserve existing preview, validation, source inspection and ingest clients. A separate transaction build owns `profile update --expect-digest`, cooperating-writer locks, comment-preserving rewrites and postcommit update warnings. The generation stage owns activation, indexing and retained historical reads. [Update owner](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L468-L556)

## Package Layout / File-to-Task Mapping

- Extend `io.policynim.cli.ProfileCommand` for subcommand-specific parsing and output before Spring starts. Reuse the existing entry point in `PolicyNimApplication`. [Command owner](../../src/main/java/io/policynim/cli/ProfileCommand.java)
- In `policy/catalog`, let `ProfileToml` retain the original newline-decoded text alongside its validated `PolicyProfile`. Reuse `PackLocation`, `CapturedPack`, `ProfileResolver` and `CatalogJson`. Add a local profile writer and diff report in this package as needed. Keep presentation in the CLI. [Reader](../../src/main/java/io/policynim/policy/catalog/ProfileToml.java) · [Resolver](../../src/main/java/io/policynim/policy/catalog/ProfileResolver.java)
- Extend native CLI/package tests and the installed jar/wheel comparison under `src/test/parity`. Add a compact two-pack fixture under `src/test/resources/parity/catalog`. Connect the package checks to the existing Ubuntu, macOS and Windows jobs. Update the operator runbook and O05 evidence. [Existing paired check](../../src/test/parity/read_only_profiles.py)

## Dependencies And Settings

Use Java 21, the existing Tomlj reader, the existing canonical Jackson serializer and the shipped classpath policy resources. Keep execution offline from providers, storage and Spring. Default init explicitly selects the aggregate. On the pinned wheel, even a nonblank `POLICYNIM_CORPUS_DIR` didn't redirect it. Explicit pack arguments come from the caller's working directory. [Default lookup](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/runtime_paths.py#L64-L82)

## Canonical Contracts

```text
java -jar <jar> profile init PATH [--pack DIR]... [--id ID] [--format text|json]
java -jar <jar> profile diff PATH [--format text|json]
```

Init defaults to ID `local` and text output. JSON returns `status`, the caller's destination `path` and the complete `profile` model, including default empty decision arrays. Write editable UTF-8 TOML with safely quoted strings and keys, explicit pack tables and their revision maps. The default arrays can be omitted from TOML so operators can append decision tables. Relative local selectors come from the resolved destination parent. Across Windows drive roots, use the normalized absolute path. Keep repeated `--pack` order. The Python CLI treats `--pack builtin:baseline` as a directory argument. [CLI](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/profile_cli.py#L47-L78) · [Serialization](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L602-L604)

Diff JSON uses `profile_id, review_digest, added, changed, removed, changed_packs, invalidated_decisions, current_policies, validation_error, warnings`. Compare revisions by (pack ID, policy ID) and emit scalar-sorted ID lists. Determine changed packs from their full pins. Return every current entry in policy-ID order with its authored active state, standalone target digest and pack-prefixed path. When IDs tie across packs, preserve selection order. Keep `warnings: []` and a nullable `validation_error`. The token is `sha256:` plus lowercase SHA-256 hex over UTF-8 original TOML after universal newline decoding, one LF and the compact normalized current model JSON. A comment edit changes it. Converting LF to CRLF alone preserves it. The text form lists each change category and the review digest. A blocked diff directs the operator to current entries and target digests in JSON. Expose only available follow-up commands in Java's help and recovery text. [Report and hashing](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L407-L465) · [Text rendering](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/profile_cli.py#L116-L139)

## TDD And BDD Implementation Strategy

First record the merged jar's actual response to each missing command. Use independent synthetic bytes to build expectations. Compare installed processes at each step.

| Given | When | Required result |
| --- | --- | --- |
| A new output inside missing parent directories | Init two packs in argument order `team, base`, then reverse them in a second profile | Both products can validate each file. JSON echoes the supplied path. Each file preserves its own pack order, sorted policy revisions and profile ID. Invalid ID, repeated pack or unreadable member fails before publication. |
| The default aggregate in both distributions | Init without packs and preview each emitted file through both products | Both readers see 26 policies, pack `policynim.baseline` version `0.3.0`, digest `sha256:04a26d71f3b54eb63e41e779585177ce397767cdf89da9368de3e92d61667a26`. Check all revision pins. Accept the selector spelling defined below. |
| An existing file, a link to a file or a dangling link | Init at that destination, then choose an unused name | Exit 1 leaves the original entry and any target intact. The unused name succeeds. Exercise an aliased destination parent and inspect the resulting relative pin from both paths. |
| A selected parent moves while the operator is choosing sources | Finish or retry creation in a previously missing nested directory | The writer either reports the moved directory or publishes under the directory it originally opened. A new entry at the old path receives no profile. An invalid pack leaves the missing parent absent. |
| Two initializers select different packs for the same destination | Run both installed commands concurrently | One succeeds. One refuses. A fresh reader sees exactly the winner's complete document. Normal cleanup leaves no stage. |
| A clean two-pack fixture or an edited source | Diff the identical authored TOML with both programs | The clean token is `sha256:d2f5197954853f566ad4a404e88da76cb1e925f52cbceb38a545d6ccd000233d`. Editing A, dropping B and adding D yields `sha256:4db20130b673825dbe8d7cee34bbec7d5d9af9e9ede5469765dd8d869906a32e`. Check each full digest, every field and the complete current entries against the fixture. Moving B between packs lists B in both added and removed. A version-only change revises every member of that pack. |
| An exclusion whose target changed or disappeared, or a duplicate ID across packs | Diff, inspect the blocking reason, then restore the original inputs | Exit 0 carries the validation error, the current revisions and all authored entries. Preserve duplicate entries in the diagnostic report. Restoring both pack and profile yields the original token and clears the block. |
| Changed pack identity, malformed profile or malformed manifest | Diff, then restore the files | Exit 1 yields the profile error envelope. The corrected input recovers. A source diff preserves the profile bytes, including comments and formatting. |

Include Unicode and punctuation in policy IDs, paths containing spaces, and IDs needing TOML quoting. Round-trip output through both the Java reader and Python 3.12.10's TOML parser. [Pinned path and serialization tests](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_policy_profiles.py#L994-L1028) · [Unicode tests](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_policy_profiles.py#L1184-L1206)

## Component Design

Init opens the destination's deepest existing directory before it reads any policy pack. Existing directory aliases resolve during that selection. On POSIX, the writer opens the physical path's components from the filesystem root using directory-search access. On Windows, it checks the physical path returned for the open directory handle. After the packs and decisions pass validation, the writer creates or opens each missing child relative to its held parent. It keeps the final directory open, stages the full file and flushes its contents. On Linux and macOS, it stages inside a private directory and calls `linkat` with the open staging and destination directories. On Windows, it renames the staged file using its open handle and the destination directory handle. Both operations refuse to replace an existing name. Cleanup uses the staging handles. If the directory moves during publication, the writer reports where the profile was created or why it couldn't verify the location. The tests cover file contents and visibility to a new reader. Durability of the destination name after a power loss needs separate evidence. [Python publication](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L365-L404) · [POSIX linkat](https://pubs.opengroup.org/onlinepubs/9799919799/functions/linkat.html) · [Windows file rename](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information)

Diff captures the profile text and model from one read. Reconstruct current pins from the existing standalone pack captures. Check the selected pack identity while allowing its version, members and individual revisions to change. Retain the complete original entries for the report. Run the existing decision and runtime-rule checks on a copy so exclusion, replacement or validation failure can't rewrite `current_policies`. [Current pins](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L217-L257) · [Decision checks](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L260-L336)

## Failure And Recovery Rules

Keep argument errors on stderr with exit 2. Source or publication failures return exit 1 with `status, message` on JSON stdout or an actionable text error on stderr. A blocked diff exits 0 and identifies what the operator must review. Exercise write, flush and commit failures, then retry with corrected inputs or a supported filesystem. After the destination exists, inject a cleanup failure. A fresh reader must still see the complete published profile. A recovery message must identify that outcome so the operator can inspect the existing file before retrying. Never remove a competing destination during cleanup. [Profile error contract](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/profile_cli.py#L32-L45)

## Commit Plan

1. Add offline init for explicit packs and the aggregate, with safe TOML serialization and cross-reader checks.
2. Complete publication failure, contention and retry coverage at the real file boundary.
3. Add read-only diff, invalidated decisions, portable expected bytes and full paired package checks. Document recovery and update O05 evidence.

## Branch And PR Flow

Start proposed branch `parity/profile-init-diff` only after recording the preceding merge SHA, tree and passing postmerge checks. The repository's pushed base is eligible for Qodo Local Review. Use fast reviews for substantial checkpoints. Commit the complete task-owned changes and run final auto review with the exact pushed base, current context and recent work. Reproduce findings, repair supported issues and rerun affected and full checks and review on the changed head. Use one draft PR for O05 and require completed structured hosted review and passing required checks on the actual head. Guard that SHA at merge, compare the merged tree and verify postmerge CI. [Contributor workflow](../contributor-workflow.md)

## Test Plan

Run focused CLI, catalog, native filesystem and package tests. Use fresh working directories, HOME, explicit config and eval workspace for every reference process. Compare a pinned, noneditable wheel, Java jar and the packaged image, with live providers disabled. Keep existing JDBC, acceptance and architecture jobs. Exercise native publication on the established Ubuntu, macOS and Windows runners. Record OS, JDK, process results, source revisions and jar SHA-256.

```bash
export POLICYNIM_PROVIDER_NVIDIA_ENABLED=false
./mvnw -q -Dtest=ProfileCommandTests,ProfileResolverTests,ProfileTomlTests test
./mvnw -q verify
./mvnw -B --no-transfer-progress -DskipTests package
./mvnw -B --no-transfer-progress -Dit.test=PackagedReadOnlyProfilesIT failsafe:integration-test failsafe:verify
git diff --check
```

Extend the named package suite or add its new class to the same Failsafe command. Put both portable fixture tokens in the paired harness. Use the prior read-only fixture for comment, revision-key order and newline cases. Compare the full raw report for identical authored files. Include the exact tokens in the final receipt. [Package workflow](../../.github/workflows/ci.yml) · [Native workflow](../../.github/workflows/explicit-pack.yml)

## Definition Of Done

Java creates a file either installed product can read, preserves existing targets under contention, reports current source changes and blocked decisions through both formats, and recovers after inputs are repaired. The reviewed and merged revisions have installed-process receipts, platform checks, full required CI and postmerge verification. The matrix identifies the remaining profile update and generation work.

## Assumptions And Defaults

On POSIX, a directory name can begin or end with a space. The pinned wheel can create a profile whose reader trims that space and fails to find the pack. The jar adds a leading `./` or trailing `/.` to the selector when the path would otherwise lose that whitespace. Both readers can then open the named directory and compare the same profile. A local directory whose recorded name would select a different builtin pack is refused before publication. [Pinned profile path model](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L47-L85) · [Initializer cases](../../src/test/java/io/policynim/policy/catalog/ProfileInitializerTests.java)

On macOS, the pinned Python writer also refused a destination containing an existing directory alias followed by `..`. Its pack calculation follows the alias before applying `..`, while Python's temporary-file code collapses the path during staging. Using the physical directory allowed the wheel to create the file. The jar follows the selected directory when applying `..` and preserves the caller's spelling in its response. Both readers can open the resulting profile. [Pinned initializer](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L372-L404) · [Python 3.12.10 staging path](https://github.com/python/cpython/blob/v3.12.10/Lib/tempfile.py#L243-L248)

The Java default will write `builtin:baseline`, which both readers support. The Python wheel writes a filesystem selector relative to its own installed resources. Each authored file therefore has its own profile and review identity. Compare each file unchanged through both readers. The Java workflow requires no extracted runtime corpus, new configuration key or checkout path. [Builtin selection](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/profiles.py#L223-L239)
