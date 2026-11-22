# Bundled Source Catalog Build Plan

## Summary

An operator who installs the Java jar or Linux image can list, validate and show the 26 default policies without a checkout, database, provider key or corpus directory. An explicit local source still takes precedence. This Software Build Plan covers one PR for the bundled-source portion of G09 and O04. [Parity matrix](../parity-operations.md) · [Explicit pack predecessor](explicit-policy-pack.md)

Java starts at `6792430e42eeab654bd4c9053d346298ac2668e9`, tree `bc1a14524fbbe71099e17bd41ec7f784db8b66dc`. All seven main CI jobs passed. A jar rebuilt from that base (SHA-256 `ff6302d04a1e3de01465e77bf0a499b0568d19e245b1b2f6ce6477a99f5dc736`) returned usage status 2 for list, validate and show with either no selector or a relative `POLICYNIM_CORPUS_DIR`. On the same inputs, the installed wheel from Python `fcc5322a6672b0275dcc6e0c299e631df8e8de19` returned the 26-policy bundle or the selected local policy. The noneditable wheel ran under CPython 3.12.10 in a new environment with 80 locked runtime packages including PolicyNIM. Its 59 data files matched the pinned Git blobs. [Linux CI](https://github.com/nnennandukwe/java-policyNIM/actions/runs/44989595509) · [macOS and Windows](https://github.com/nnennandukwe/java-policyNIM/actions/runs/44989595445) · [Java PR #17](https://github.com/nnennandukwe/java-policyNIM/pull/17) · [Python packaging](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/pyproject.toml#L72-L80)

## Skills To Use

- Apply the existing package and general `qodo-get-rules` results. Rules 1, 1060888, 1060853 and 2119936 govern pins, documented settings, existing client fields and valid/invalid settings tests. Use rule 2117707 at the resource-read boundary and rule 784431 for every Python harness function. Preserve upstream notices, Unicode and original source bytes as evidence.
- Use `codebase-design` and `policynim-runtime-path-safety` to keep source selection at the CLI and classpath lookup behind the catalog. Use `tdd-bdd` and `failure-path-testing` for first launch, override, refusal and corrected retry. Use `policynim-offline-ci-hardening` for the actual jar/image tests with providers disabled. This read-only operation has no publication transaction.
- Apply `voice-canon` and its language verifier to authored help, docs and reports. Use `qodo-review` and `qodo-review-resolver` for the local and hosted gates below. The repository is eligible for Qodo Local Review. Java 21 and the pinned Boot 3.5.6 documentation cover classloader behavior.

## Scope

Ship the 55 files under Python’s `policies/` and the four under `evals/`, with the upstream MIT license. The 55 comprise 26 selected Markdown files, one template, eight manifests, one profile and 19 notices, including the notices for nine CC BY-SA adaptations. Give the three existing `--source` commands a bundled default, an environment override and support for a corpus named in an explicit settings file. Prove the four evaluation assets can be read from the artifact. [Policy sources and notices](https://github.com/nnennandukwe/policyNIM/tree/fcc5322a6672b0275dcc6e0c299e631df8e8de19/policies) · [Evaluation assets](https://github.com/nnennandukwe/policyNIM/tree/fcc5322a6672b0275dcc6e0c299e631df8e8de19/evals)

O01 owns config-file discovery, init, version and installed user paths. O02 owns quickstart/client setup and support bundles. O05 owns profile resolution and selected pack pins. G10–G12/O06 own bare-command selection of active or retained generations, invalid pointers, capture, bootstrap and publication. Evaluation runtime and O25 capacity/recovery each need their own evidence. Record only the relevant G09/O04 and resource-availability progress. Existing explicit ingest, JDBC, provider and MCP launch paths keep their current owners. [Catalog selection](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/services/catalog.py#L74-L103)

## Package Layout / File-to-Task Mapping

| Path | Responsibility |
| --- | --- |
| `src/main/resources/io/policynim/bundled/{policies,evals}/` (new), `src/main/resources/META-INF/licenses/` | Copy pinned blobs with their logical relative names. Include the pinned Python root license alongside the distribution notices. |
| `cli/SourceCatalogCommand.java` | Parse explicit source mode and flags, use `CatalogSourceSettings` and `SourceConfigFile` to resolve the winning input, and retain current help, text, JSON and exit behavior. Existing `PolicyNimApplication.main` dispatches this command before Spring starts. |
| `policy/catalog/SourceCatalog.java`, `PackSourceFiles.java`, a package-private bundled reader if needed | Read declared classpath resources through the existing manifest, member-path, strict Markdown, provenance and catalog assembly code. Keep filesystem capture in the existing directory reader. |
| `src/test/java/io/policynim/packaging/PackagedSourceCatalogIT.java`, `src/test/parity/`, `.github/workflows/ci.yml` | Add first-install cases and compare pinned Git files, the installed wheel, the packaged jar and Linux image. Extend the existing Python-reference and package jobs. |
| `README.md`, `docs/hosted-runbook.md`, parity matrices | Document installed and overridden commands, recovery and revision-bound proof. |

## Dependencies And Settings

Use Java 21 class-relative resource streams and the existing Boot Maven repackager. The Dockerfile copies `src` into the build and installs the jar in its nonroot runtime. Keep one copy of the assets in that jar. Standard resources under this prefix pass through Maven unfiltered. Verify the resulting bytes, including notices and line endings. [JDK resource API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Class.html#getResourceAsStream(java.lang.String)) · [Boot executable jar layout](https://docs.spring.io/spring-boot/3.5/specification/executable-jar/nested-jars.html)

For `--source`, a valid explicit `--corpus-root` wins over `POLICYNIM_CORPUS_DIR`, including an invalid environment path. Otherwise, trim the environment setting using the reference whitespace rules. A nonblank setting selects the external directory. Resolve a relative override against the calling process’s working directory. A whitespace-only environment setting selects bundled data. An absent environment setting uses the explicit settings file when it names a corpus, then falls back to the bundle. Boot’s typed configuration currently has no corpus property. Keep this early CLI selection independent of Spring application startup. [Python path resolution](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/runtime_paths.py#L64-L83)

## Canonical Contracts

| Invocation or input | Observable result |
| --- | --- |
| `policies list --source --format json`, `policies validate --source --format json` | 26 authored/effective policies, source scope, null generation ID and indexed count, empty pack pins and unlisted Markdown. Catalog digest `sha256:04a26d71f3b54eb63e41e779585177ce397767cdf89da9368de3e92d61667a26` in the existing command-specific field. |
| `policies show SEC-AUTH-001 --source --format json` | Relative path `security/auth-sensitive-code-review-standard.md`, original content digest `sha256:afe79fb014bd7ed70e15df2e7f211a879c1f3fee7abf92c3f1f17b079cf54a36`, target `sha256:1fe921854a2a747fb1f21e12072fae8dc312f5781b41561f348a467722277fc7`. Compare the complete entry and body. |
| Explicit flag or environment directory | Inspect the selected plain or manifested directory. An invalid winning selector yields an error and recovery guidance. A missing flag value or unsupported command remains a usage error. |
| Explicit source with invalid generation setting or damaged active pointer | Inspect the selected source without touching generation state. The wheel does this for `--source`. Positive and negative bare-command generation cases remain required under O06. |

A settings file named by `POLICYNIM_CONFIG_FILE` can select the corpus. In paired tests, rejecting files that appeared to contain a source selector also rejected valid multiline, repeated and empty settings. Read the explicit file’s assignments, including quoted values, interpolation and the setup-generated literal header. Overlay process settings after reading the file. A process corpus containing only whitespace clears an earlier file selection. Refuse an unresolved profile and name the option that selects a directory. Help and explicit Java root commands work regardless of the configured file. O01 still owns implicit file discovery, setup, and selection for other commands. [Python settings](https://github.com/nnennandukwe/policyNIM/blob/fcc5322a6672b0275dcc6e0c299e631df8e8de19/src/policynim/settings.py#L239-L318)

## TDD And BDD Implementation Strategy

1. From an unrelated directory with a decoy `policies/`, run the three installed Java commands against the merged base. Capture the missing-default failure. Introduce the actual assets and make one packaged validation pass against the independently recorded digest.
2. Compare every member, text/JSON view, original revision and target. Add omitted, blank, valid relative and invalid overrides. Show that correcting a failing override recovers, and that an explicit CLI root wins even when the environment path doesn't exist.
3. Run the same assertions on the image built by the repository Dockerfile. Add missing-resource, malformed-member and help-before-lookup cases before running the full regression suite.

## Component Design

One source selector chooses either an explicit directory or the bundled manifest before I/O. For the bundle, load the manifest by its fixed absolute classpath name and each selected resource by its validated relative member name under the same prefix. Scope and close each stream with try-with-resources. Supply `policies/<relative-path>` for parser context and the original relative path for public identity. Reuse the existing builder for UTF-8 decoding, ID checks, canonical entries and digests. The frozen aggregate has zero unlisted Markdown files. Verify this fact from the complete immutable jar inventory against Git. The directory reader continues to discover unlisted files on mutable inputs. Resource reads need no temporary directory, open jar filesystem or native extension.

## Failure And Recovery Rules

Source failures produce exit 1, a single existing JSON source-error envelope on stdout in JSON mode, or actionable text on stderr in text mode. A bad override must name the selected source and its correction. If a bundled resource is missing, incomplete or invalid, report the logical resource and advise reinstalling the artifact or selecting a valid local corpus. Check that failure returns no partial catalog and leaves the working directory, home and runtime data untouched. Help and usage errors precede any source lookup. A corrected invocation resolves the source afresh.

A malformed named source can change which policies an operator sees. With two or four trailing backslashes before a quote and a subsequent assignment using that quote, the pinned wheel warned about the invalid statement on stderr and served the bundled policies. Java refuses a malformed source selector when the catalog depends on that declaration. Its diagnostic names the setting and omits the value. A corrected declaration or a higher-priority selection restores inspection. Ordinary quoted Windows paths and valid trailing backslashes retain the reference interpretation.

## Commit Plan

1. Add the source files, notices, classpath reader and default validation with independent byte and digest checks.
2. Add override precedence, failure/retry coverage and operator examples.
3. Add wheel/jar/image comparison, retained packaging checks and evidence links. Keep one review unit.

## Branch And PR Flow

Start a clean isolated branch from the verified PR #17 merge on `origin/main`. Link G09/O04 and this plan. Commit task-owned changes before local review. Use Qodo fast checkpoints for substantive changes and an auto review of the complete committed diff against the pushed base. Include the source pins, settings table, receipts and `recent_work`. Reproduce each finding and repair supported issues. Rerun affected and full checks, then review the new head. Open the draft PR and use `qodo-review-resolver` to confirm completed structured hosted review and required CI at that exact head. Verify the reviewed SHA immediately before merge, compare the resulting tree, and await main CI. Record revisions and artifact digests in the matrices. [Contributor workflow](../contributor-workflow.md)

## Test Plan

- Compare names and bytes for all 59 resources in Git trees `3a02777cf73a3b80b6ac0f1c2c2ef99857d6a68f` (policies) and `2134393a9833dd4d43bff524d12df51c51ff047a` (evals). Hash the Git blobs directly and compare with the noneditable wheel and jar entries under `BOOT-INF/classes`. The current Java and Python root MIT license files have identical bytes. Include the license once in the distribution with its source attribution and verify its SHA-256, `8a60c102e091e477f194fa90d76bcfc4a0e76263bfd9e540f3f05133e7a1816b`. Open the four eval assets from the artifact and count the cases in each. The default suite has 6, the grounded suite 10, the engineering source-review fixture 64 and the security fixture 34. Independently compare all 26 catalog entries against `catalog.json` inside the reference archive with SHA-256 `376ba2727eb73e56696bce91eaf68ac2b6eae20b315a99b87e405cc56a380dce`. Use the archive only in verification. [Archive oracle](https://github.com/nnennandukwe/policyNIM/tree/fcc5322a6672b0275dcc6e0c299e631df8e8de19/artifacts/public-generation)
- Launch the moved jar with private homes, cwd, temporary directory and eval workspace, an occupied port, providers disabled and no usable JDBC connection. Compare all three source commands against the installed Python CLI, including status and both streams. Reuse the existing cross-platform package test lane. Assert decoy files aren't read and no extracted corpus appears after success, errors or repeat runs. Test corrupted/missing resources with an isolated reader or disposable broken jar.
- Build and run the actual Dockerfile locally when the daemon is available, and on a Linux GitHub-hosted runner. Record the image ID, jar hash, default nonroot UID and runtime results. Use `docker create` and `docker cp` to verify the bytes of the final image’s jar, then remove the test container. Run the default and a read-only bind-mounted override with networking disabled. Retain any local Docker limit and require a completed Linux image check.

```sh
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -q verify
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress -DskipTests package
jar tf target/policynim.jar
POLICYNIM_PROVIDER_NVIDIA_ENABLED=false ./mvnw -B --no-transfer-progress -Dit.test=PackagedSourceCatalogIT,PackagedExplicitPackIT,PackagedBundledAssetsIT failsafe:integration-test failsafe:verify
docker build -t policynim-bundled-source:verify .
docker run --rm --read-only --network none -e POLICYNIM_PROVIDER_NVIDIA_ENABLED=false policynim-bundled-source:verify policies validate --source --format json
git diff --check
```

Extend the installed-wheel comparison and Linux package job to run the new cases. Keep the dedicated JDBC, acceptance, architecture and transport checks at the reviewed head. Build dependencies may use established CI caches and registries. Product tests run offline.

## Definition Of Done

The installed jar and actual Linux image return the expected source views from a clean directory and recover after an invalid override. All resources, notices, canonical digests and selected source bytes agree with independent pins. Merge evidence ties the reviewed head, merged tree, artifact hashes and successful main checks together. Publish only the G09/O04 evidence earned by this unit.

## Assumptions And Defaults

The reference wheel is a behavioral oracle and Git supplies original asset bytes. Its observed local wheel hash was `fa7c1345e9e9fbdbd54ab3fcab1de0a9a3c8712b93066eb82edf592400930c6d`. Different wheel builds can have different container bytes. The catalog, file inventory and per-file checks remain fixed at the Python revision.
