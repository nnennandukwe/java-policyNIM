# Source catalog inspection Build Plan

## Summary

Operators will use the installed Java jar to inspect a directory of policy Markdown files and verify its revisions, before configuring a database or model provider. This is Stage 2's first review unit for G08, G09 and O04. The authority is Python `fcc5322a6672b0275dcc6e0c299e631df8e8de19`. The Java compatibility baseline is `d544829804891dda1f4e5d5e57f07146eed51342`. The implementation base is the verified stdio merge `bf4e32f8d22065fd391ba0c709b3b9b93fc68a9e`. [Parity matrix](../parity-matrix.md) · [Program Build Plan](../parity-build-plan.md) · [Python catalog commands](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/cli.py#L161-L297)

## Skills To Use

| Stage | Skill | Evidence |
|---|---|---|
| Before design | `qodo-get-rules` | Reuse the scoped corpus, structure and broader rule searches. Record applicable IDs and check how this PR applies each one. |
| Command and catalog design | `codebase-design`, `cli-interactive-parity` | One reader owns inspection for all three commands. Match help, examples, error recovery and machine-readable output to packaged executions. |
| Filesystem and behavior | `policynim-runtime-path-safety`, `tdd-bdd`, `failure-path-testing` | Test explicit root selection from an unrelated directory, byte identity, failure followed by correction, and absence of durable side effects. |
| Local and hosted review | `qodo-review`, `qodo-review-resolver` | Qodo Local Review is eligible on this connected Java repository with a pushed base. Require completed reviews of the submitted changes and the final PR head. |

**Scoped standards.** Apply Qodo warning 784431, “Require docstrings for all Python functions,” to every new or modified harness function, including nested functions. Check using the Python AST. Rule 1060877 (error) requires corresponding consumer-validation changes for a producer schema change. Here the paired checker must validate names, types, nullability and values of each new response. Rule 1060879 (error) requires updating known clients when endpoint parameters change. This unit adds CLI arguments and documents their callers. The MCP endpoint contracts retain their parameters. The API field and configuration migration rules (1060843, 1060851, 1060853, 1060863, 1060865 and 1060888, errors) apply if an existing public shape changes. Keep the legacy search and ingest shapes covered by regression tests. Test both valid and invalid forms of any new settings (2119936, warning). Pin any new dependency (1, warning). Use descriptive English names and comments (613754, 699152, 7205, 8184, 576985, warnings), preserving authored Unicode policy text in fixtures. The HTTP-router, database-repository, A2UI and Pydantic-specific rules concern other surfaces. Transaction-proof belongs to the later ingest/publication build. No Java-specific skill is available. Use the pinned JDK/Jackson APIs and official documentation if a library choice is needed.

## Scope

**In scope.** A directory without a root pack manifest, recursive source selection, validated UTF-8 Markdown, normalized metadata and body, plain-directory provenance, source and catalog identity, and read-only `policies validate`, `list` and `show` operations. Inspect one in-memory snapshot per invocation. Keep exact source line numbers available to the parser and its diagnostics.

**Deferred owners.** G09 and O04–O05 own pack membership, bundled default resources, notices, profiles and reviewed substitutions. O03 owns the separate inventory workflow. G08's chunk build owns section paths and chunk IDs. G10–G12 and the operator generation rows own indexing, durable publication, retained readers and active-generation defaults. Runtime-rule evaluation and compiled artifacts have their own stages. Parse and validate rule syntax that affects whether the reference accepts a source document. Keep the parity rows open for their remaining work. [Grounding rows](../parity-grounding.md) · [Operator rows](../parity-operations.md)

## Package Layout / File-to-Task Mapping

- `src/main/java/io/policynim/PolicyNimApplication.java` owns top-level command selection. Recognize the entire `policies` command family, including malformed invocations, before booting the server. Existing server and ingest paths retain their entry points. [Original Java entry point](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/main/java/io/policynim/PolicyNimApplication.java) · [Java ingest argument parser](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/main/java/io/policynim/ingest/IngestCommandLine.java)
- A new source-catalog command adapter will parse arguments, call inspection, format text or JSON and set exit status. A new source-catalog module under `io.policynim.policy` will select files, parse sources and compute revisions. Reuse the current policy model and parsing logic where their contracts match. Keep the existing `policy/parse/MarkdownPolicyParser.java`, `ingest/FileSystemPolicyCorpus.java` and public MCP serialization compatible. [Legacy Java parsing](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/main/java/io/policynim/policy/parse/MarkdownPolicyParser.java) · [Legacy Java corpus selection](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/main/java/io/policynim/ingest/FileSystemPolicyCorpus.java)
- Add source fixtures under `src/test/resources/parity/`, package coverage in a new `PackagedSourceCatalogIT`, and a paired CLI check under `src/test/parity/`. Put the jar test in the existing package CI job. Update the hosted runbook, matrix and program plan with the reviewed artifact evidence.

## Dependencies And Settings

Use Java 21, the existing executable Boot jar and the project's pinned serialization stack. The source command runs before database, Flyway, provider or MCP initialization, even when a caller's normal server environment selects JDBC or stdio. Keep sources read-only and allocate working state in memory. Reuse Jackson for public JSON and test its exact compact encoding against the pinned Python model. Use the reference's CommonMark behavior for heading-derived titles, including setext headings. Use the BSD-2-Clause `org.commonmark:commonmark:0.32.0` parser for heading inference. A standalone comparison against the locked Python parser matched 322 heading events over 710 inputs, including 26 shipped policies. Keep the separate production chunk scanner with its own G08 proof. [Versioned parser source and license](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.32.0) Storage migrations belong to the later write path.

For this unit the Java command requires an explicit, nonblank `--corpus-root` and `--source`. Resolve a relative root against the process working directory. Python selects the same directory through `POLICYNIM_CORPUS_DIR`. It also supports bundled resources, profiles, or a retained generation in other invocations. The source-only Java command should give a specific refusal when it encounters a root `pack.toml` before that format is supported. [Python root resolution](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/runtime_paths.py#L64-L81) · [Catalog selection](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/catalog.py#L73-L103)

## Canonical Contracts

The Python CLI accepts a positional ID for `show` and `--format text|json` for all three commands. Text is its default. Java will expose the same forms for explicit source inspection and add a root argument. Support the existing Java `--corpus-root=<path>` spelling. Examples use an independent caller-supplied directory.

```bash
java -jar "$jar" policies validate --source --corpus-root="$corpus" --format json
java -jar "$jar" policies list --source --corpus-root="$corpus" --format json
java -jar "$jar" policies show API-TASKS --source --corpus-root="$corpus" --format json
POLICYNIM_CORPUS_DIR="$corpus" policynim policies validate --source --format json
POLICYNIM_CORPUS_DIR="$corpus" policynim policies list --source --format json
POLICYNIM_CORPUS_DIR="$corpus" policynim policies show API-TASKS --source --format json
```

| Operation | Observable contract |
|---|---|
| Validate | Exit 0. Return `status=ok`, `scope=source`, `generation_id=null`, `catalog_digest`, equal authored/effective counts, `indexed_count=null` and `unlisted_markdown=[]` for the plain source directory. |
| List | Return schema version 1 and source scope, null generation/profile IDs, null profile digest, empty pack pins, digest, counts, unlisted Markdown and policy entries sorted by exact policy ID. Each entry includes `state=active`, target digest, null reason/replacement ID, relative POSIX path, policy metadata and body. |
| Show | Find the exact, case-sensitive policy ID. Return the same entry with `scope=source` and `generation_id=null`. Source lines stay in the parser and diagnostics. The pinned public JSON model carries `body` and `path`. |
| Failure | Invalid sources and unknown IDs exit 1. With `--format json`, emit one stdout object with `status=error`, `scope=source`, `generation_id=null` and an actionable `message`. A miss names the ID and the source list command. Invalid command syntax returns nonzero and usage. The help path exits successfully without opening the root. It documents actual invocation and format choices. |

Compare JSON field names, values, types, defaults and nulls. Use exact byte comparisons for hash inputs. Keep full policy text out of exception traces. Text mode should report the same counts, identities, source state, path, revision and body as the corresponding pinned command. [Canonical models](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/catalog.py#L19-L72) · [CLI success and failure](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/cli.py#L161-L297)

## TDD And BDD Implementation Strategy

1. From an unrelated working directory, invoke the installed pre-change jar with the first example. Bound startup and reap the owned process if it serves HTTP instead of recognizing `policies`. Record the exit, stdout and jar checksum. Add a failing package test for one successful validation before implementing command dispatch and one-file inspection.
2. Given a nested, unmanifested directory, require `list` and `show` to agree on membership, body, metadata and both digest levels. Add a second file in reverse policy-ID order, a depth-four file and a `TEMPLATE.md`. Write expected outputs directly from the documented fields. Add one failing parser case at a time for inputs in the Test Plan.
3. Given an invalid source or unknown ID, require the failure envelope and a successful retry after the operator corrects the input. Validate the complete new command through the packaged jar and the installed pinned Python CLI from their normal entry points.

## Component Design

Read each `.md` file once as bytes. Exclude the exact basename `TEMPLATE.md` at every depth. Retain the lexical name of a same-root Markdown file symlink. Its source bytes match the target, while its inferred ID and catalog path can differ. Skip directory-symlink traversal. Treat a directory or dangling link named with a `.md` suffix as a read failure for the whole catalog. Detect duplicate effective IDs across the whole selection. Use the root basename plus the relative POSIX path as parser input. Use the path relative to the selected root in the public catalog. Default metadata can depend on the root basename. For example, `policies/api/tasks.md` infers `API-TASKS` and domain `api`. Preserve Unicode in inferred IDs and declared list order. Match title precedence (declared title, first H1, first heading, humanized stem) and the pinned domain/ID inference even for roots named something other than `policies`. [Loader and provenance](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/loader.py#L15-L98) · [Metadata inference](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L204-L229)

Honor the reference's UTF-8 decoding, leading BOM removal, first-line frontmatter rule, accepted delimiters, escaped metadata lists and blank-body refusal. With frontmatter, the parser joins body lines with LF and preserves a trailing newline when the source ends in LF. Without frontmatter it preserves the decoded body after BOM removal, including its original line endings. Retain one-based body start and runtime-rule spans before trimming. Compare a leading-blank-line file in both Java contracts. The existing ingest parser accepts frontmatter after leading blank lines. [Python parse and split](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L55-L97) · [Frontmatter and line calculation](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L165-L202) · [Legacy Java line test](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/test/java/io/policynim/policy/parse/MarkdownPolicyParserTests.java#L104-L115)

Compute the three identities from the same captured bytes. Use SHA-256, lowercase hex and the `sha256:` prefix.

1. **Content revision.** Hash the original file bytes including BOM, CRLF and terminal LF. Plain-directory provenance includes null pack ID/version, requirement level `unknown`, applicability `[]`, grounding kind `legacy_unknown` and sources `[]`.
2. **Target digest.** Concatenate the compact pinned `PolicyMetadata` JSON, LF, normalized body, LF and relative catalog path. Hash the UTF-8 bytes. Match Pydantic's declared key order, string escaping, Unicode and included default/null fields. See the specific metadata and provenance declarations.
3. **Catalog digest.** Sort entries by policy ID with Python's Unicode string ordering. Join each compact `CatalogPolicy` JSON with a single LF between entries, then hash the UTF-8 bytes. Entry key order is `state, target_digest, reason, replacement_id, path, policy, body`. Pretty printing belongs to command display. [Digest algorithm](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/catalog.py#L105-L158) · [Metadata field order](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/types.py#L139-L163)

The pinned source format recognizes an inline list only when both brackets are present. It recognizes a quoted scalar only when its quotes match. In a quoted string, escaped quote marks lose their backslash. Other backslashes remain part of the authored text. For a runtime matcher, an unquoted decimal such as `1000` fails validation. The tokens `0x10` and `1_000` remain strings in this grammar. These choices affect the public source metadata and its hashes. [Source scalar rules](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L730-L823)

Quote an inline tag that contains an apostrophe, as in `tags: ["can't"]`. A rule’s `reason` accepts the text `123`, `true` or `null` with or without quotes. A duplicate rule key with a multiline value reports the last line of that value. Look above that line for the repeated key. Both installed commands agreed on these cases. [Rule field validation](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L424-L558) · [Inline list parsing](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/ingest/parser.py#L826-L875)

The text listing shows each policy's ID, state, domain, pack description and title. Use `show` for its source path, body and revision, or request JSON for the complete records. [Python catalog output](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/interfaces/cli.py#L161-L245)

## Failure And Recovery Rules

Reject missing, blank, unreadable, non-directory and empty selections, invalid UTF-8, duplicate IDs, malformed frontmatter and invalid declared runtime rules. Include a useful file/line or policy ID where the reference supplies one. Correct the file or ID and rerun the same command. For an unsupported manifest, name the format so the operator knows which source selection failed. Java will require every followed Markdown file link to resolve within the selected root. Verify the read uses that root even if a link changes. Pinned Python uses `Path.read_bytes()` for plain files and can read a sibling through a file link. Record both observed outcomes using disposable files. For a local operator's plain directory, resolve the selected root and file alias, open the regular target with no-follow semantics and check identities around capture. Refuse a detected change before returning the catalog. A path check before and after reading can't establish isolation from a hostile writer that changes an entry and puts it back between those checks. Protected pack loading and retained generation reads have separate ownership and validation requirements. Source catalogs never call the existing JDBC `replaceAll`. Verify source tree and database state are untouched by both success and failure. [Legacy JDBC replacement semantics](https://github.com/nnennandukwe/java-policyNIM/blob/d544829804891dda1f4e5d5e57f07146eed51342/src/main/java/io/policynim/persistence/jdbc/JdbcPolicyChunkStore.java) · [Pinned parser refusals](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_ingest.py#L287-L422)

## Commit Plan

1. `feat: validate a plain source catalog from the installed jar` with one independent digest fixture and package test.
2. `feat: list and show normalized source policies` with membership, parser, digest and CLI failure/recovery coverage.
3. `test: compare installed source catalog commands` with the paired harness, operator instructions, CI coverage and scoped parity evidence.

## Branch And PR Flow

Start one isolated branch, proposed `parity/source-catalog-inspection`, from the recorded and verified pushed main revision. Preserve the original checkout and the read-only Python reference. Link G08/G09/O04 and this plan in the PR. Commit only task-owned changes. Run focused checks and a fast Qodo checkpoint on a coherent milestone. Run full verification and auto Qodo Local Review of the complete intended diff against the pushed baseline, with exact head, contract links, current context and recent work. Reproduce findings, repair supported issues and repeat focused/full tests and review after edits. On the draft PR require a completed structured hosted review at its actual head and all five CI jobs. Recheck the SHA at merge, verify the merged tree and main CI, and rerun the installed comparison on the merged jar. Record unresolved disagreements explicitly. [Contributor workflow](../contributor-workflow.md)

## Test Plan

- **Independent bytes.** At `policies/api/tasks.md`, use `# Tasks` followed by a single LF (eight UTF-8 bytes total). The expected content SHA-256 is `8c78633697597050ade157306c7ffe98c2446b6dc1163ee569124ee0296e304c`, the target SHA-256 is `e2bcfe20a6a180b224c83d765883ca019d1a3d6f1a138395e95cb572198b7064` and the single-entry catalog SHA-256 is `8c0aed1bd0c8930d5c27140b32d67fe0dda219bbd37cba272fb9d99a13684572`. These are independently calculated fixture expectations. Confirm each against both installed products. Compare the exact intermediate compact byte sequences in Java parser/catalog tests. Exercise quote, backslash, non-ASCII, CRLF/BOM and source edits. Relocate the same root basename and bytes. Change one byte and recheck all affected identities.
- **Parser and operations.** Cover no frontmatter, blank lines preceding `---`, UTF-8 and CRLF with and without frontmatter, a fenced pseudoheading, setext title, repeated/nested headings, and inferred metadata below multiple directories. Add a same-root alias, a directory symlink, a directory named `folder.md`, a dangling Markdown link and a harmless outside-root fixture. The broken entry rejects the whole catalog with one error JSON value on stdout and exit 1. Repair it and repeat. Check internal body and rule line spans against hand-counted source lines without adding public fields. Run valid, invalid and corrected inputs through actual catalog commands. Compare full sorted output, including nulls and empty arrays, and validate the unknown-ID error's recovery hint. [Pinned ingest fixtures](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_ingest.py) · [Catalog tests](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/tests/test_policy_catalog.py)
- **Compatibility.** Run the jar from an unrelated temporary cwd and home, using only the supplied corpus. Verify it exits promptly with a protected test port already occupied and a deliberately unavailable JDBC target in the normal server configuration. Confirm no database connection/provider requests, no eval/config files in the worktrees and no source changes. Run historical `ingest --help`, missing-root and JDBC ingest/read package checks, plus default MCP transport tests. Compare installed Python from its locked reference archive with explicit temporary cwd, config and `POLICYNIM_EVAL_WORKSPACE_DIR`. Record both Git revisions, jar SHA-256, Python/SDK versions, fixture hashes, command exit codes and results. Keep Python expectations independent of Java. Run the pinned offline `tests/test_ingest.py` and `tests/test_policy_catalog.py` suites in the disposable reference archive. Add the paired CLI invocation to the same documented reproduction path as the existing stdio/HTTP parity harnesses.

Run the following from the isolated Java checkout. Add the new package test to the five-job workflow and use its existing JDBC integration and acceptance commands. Re-run affected and full checks after review repairs.

```bash
export POLICYNIM_PROVIDER_NVIDIA_ENABLED=false
./mvnw -q -Dtest=MarkdownPolicyParserTests,IngestServiceTests,IngestCommandRunnerTests,PolicyNimApplicationCommandModeTests test
./mvnw -q verify
./mvnw -B --no-transfer-progress -DskipTests package
jar tf target/policynim.jar
./mvnw -B --no-transfer-progress -Dit.test=PackagedSourceCatalogIT failsafe:integration-test failsafe:verify
git diff --check
```

### Run the installed comparison

Set `python` to the interpreter from a disposable install of Python PolicyNIM at `fcc5322a6672b0275dcc6e0c299e631df8e8de19`. Install its lockfile and keep its CLI next to that interpreter. Run the following from the Java checkout after packaging.

```bash
"$python" src/test/parity/source_catalog.py --jar target/policynim.jar
```

The JSON under `src/test/resources/parity/catalog/plain-source.json` holds non-English test data for exact UTF-8 round trips. Its Unicode policy ID, tags, title and body are the expected source bytes for both products. The paired check validates both command outputs against the committed fixture and records the jar and input checksums. It checks UTF-8 source text, error status and output channels, retries, and read-only behavior from isolated working directories.

## Definition Of Done

The installed Java and Python commands agree on the plain-directory JSON shapes, source selections, metadata, bodies and digest bytes for the committed fixtures. A rejected source returns a useful failure, leaves the source/store untouched and succeeds after correction. The packaged Java ingest and MCP regressions pass. Record the reviewed and merged revisions, hosted gates and independently exercised merge artifact against the named parts of G08/G09/O04. Leave pack/profile, chunk and retained-generation outcomes with their owning units.

## Assumptions And Defaults

Explicit source selection keeps this first command independent of the later generation catalog. Keep Java's existing default ingest behavior available, including its depth-three walk and handling of an empty external root. Its public path format and current clients have their own compatibility tests. No hosted credentials or live provider trial are needed for source inspection.
