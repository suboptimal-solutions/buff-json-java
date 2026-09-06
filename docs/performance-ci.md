# Performance validation in CI

Use ordinary JMH in a separate GitHub Actions workflow, with GitHub job summaries and commit checks as the primary reports. Keep allocation budgets as a required correctness-style guard. Throughput changes are review signals until measurements on a stable runner justify a reliable gate.

## What runs and where results live

|      Event      |                               Comparison                                |                                    Reports                                    |
|-----------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| PR into `main`  | Exact base SHA against PR head SHA                                      | Java 21/25 job summaries, raw artifacts, one updated PR comment, commit check |
| Push to `main`  | Push's previous SHA against new SHA                                     | Java 21/25 summaries and artifacts, commit check attached to the new SHA      |
| Manual dispatch | Selected baseline against checked-out revision; first parent by default | Summaries, artifacts, commit check                                            |

The repository's default branch is `main`. Each push is measured; a multi-commit push compares the complete pushed range. A PR compares its actual head, not GitHub's synthetic merge commit; the post-merge push measures the integrated result. New PR runs cancel obsolete runs. Default-branch runs are not cancelled by newer pushes.

- **Performance** runs 30 encoding cases on Corretto 21 and 25: simple, complex, map-heavy, Struct, and Timestamp × codegen/typed/reflection × UTF-16/UTF-8.
- Both revisions are built cleanly with the **same compiler/JDK**, on the **same runner**. Both builds finish before measurement. Benchmark sources and proto inputs come from the candidate for both builds; runtime, dependencies, and protoc generator come from their respective revisions. This also permits comparison with older commits that predate the matrix.
- Benchmark methods alternate base/candidate order, reversed for Java 25, to reduce ordering bias. Each method uses one thread, two forks, 3 × 1s warmup, 4 × 1s measurement, a fixed 256 MiB heap, and the GC profiler. Allow roughly 20–30 minutes per JVM, depending on downloads/builds; the two JVM jobs run on separate machines and are never compared against each other.
- Base/head/harness SHAs, jar digests, compiler/JVM/JMH versions, CPU, OS, runner image, raw samples, allocations, build logs, and report Markdown are retained in artifacts for 90 days. Commit checks link to the corresponding workflow. These artifacts are evidence, not the baseline: every comparison remeasures its own base.
- **CI / allocation-check** continues to enforce the 14 absolute allocation budgets. Its results and logs are now uploaded, and its table appears in the job summary. Missing/nonfinite allocation measurements fail instead of being skipped.

## Interpreting a report

Throughput is operations/second, so higher is better. A timing signal requires a change of at least 10% and non-overlapping JMH 99.9% confidence intervals; other results are marked **inconclusive**, not "no change." This is a conservative heuristic, not a paired statistical test. Both means and errors are shown.

Allocation growth is flagged when it exceeds both 5% and 16 B/op; from a zero baseline the absolute threshold applies. These relative flags are advisory, while the existing absolute budgets remain enforced. A red benchmark job or failed report means the measurements are invalid/incomplete, not that a timing regression has been statistically established.

Protocol validation rejects missing/duplicate benchmarks, missing GC data, incomplete forks, incompatible units, and changed JVM/JMH measurement settings. A benchmark that needs an API absent from the baseline will fail to build; select a compatible baseline or establish a new benchmark series rather than presenting incomparable data as an improvement.

The matrix currently measures **encoding**. Decoder, cold-start, virtual-thread, and other architecture performance need their own benchmark series. Hosted-runner ratios reduce machine-to-machine differences, but cannot eliminate scheduling noise or time-varying load. Absolute throughput from different CPUs/JDKs should not be treated as a continuous comparable series.

## Why this setup

|                   Option                    |                                         Fit here                                         |                                                               Tradeoff                                                                |
|---------------------------------------------|------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| GitHub Actions + stock JMH + native reports | Implemented; reuses the existing benchmark suite and needs no external service or secret | Shared-runner timing noise; artifacts have finite retention                                                                           |
| Dedicated ephemeral runner + stock JMH      | Best next step for a throughput merge gate                                               | Requires maintained, controlled hardware, serialized measurements, and calibration                                                    |
| CodSpeed                                    | Managed bare-metal measurements, profiling, history and PR integration                   | Java currently uses a JMH fork and walltime integration; enabling its service/runners is a separate dependency and organization setup |
| Bencher                                     | Managed history, alerts, PR/commit reporting, also self-hostable                         | Requires a service/project and API key; reporting alone does not stabilize the benchmark machine                                      |
| github-action-benchmark                     | Native JMH support and GitHub Pages history                                              | Historical comparisons across shared runners need care; Pages introduces another published state to maintain                          |

A dashboard does not improve measurement quality by itself. For this repository, start with reproducible same-run comparisons and native reports, then move the runner to controlled hardware if small throughput changes must block merges. Preserve separate series for each JDK/CPU/harness version. The raw JSON can later feed Bencher, CodSpeed, or a Pages dashboard without changing the runtime library.

Sources checked on 2026-09-06: [OpenJDK JMH](https://github.com/openjdk/jmh), [GitHub hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [CodSpeed Java/JMH integration](https://codspeed.io/docs/guides/how-to-benchmark-java-with-jmh), [CodSpeed macro runners](https://codspeed.io/docs/features/macro-runners), [Bencher GitHub Actions integration](https://bencher.dev/docs/how-to/github-actions/), [github-action-benchmark](https://github.com/benchmark-action/github-action-benchmark).

## Publishing and rollout

Benchmark execution has only read permissions and no repository secrets, including for fork PRs. A separate `workflow_run` publisher executes code from the trusted default branch. It downloads only bounded summary archives, reads JSON without extraction, validates the benchmark set/source identity/protocol, recomputes labels, and derives PR association from GitHub. It does not run code from an artifact or PR checkout with write credentials. Artifact redirects do not forward the API token to other hosts. The publisher skips stale PR heads/bases and updates a single comment instead of adding one per run.

The publisher must exist on the default branch before GitHub can trigger it. On the PR that introduces these files, the **Performance** job summaries and downloadable reports work immediately; automatic PR comments and the extra **Performance report** check start after merge. Subsequent PRs, including forks, use the same reporting path. No branch-protection rules or external accounts are changed by this implementation.

See [GitHub workflow_run behavior](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run) and [GitHub's secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use).

## Local use

Run from the repository root, with Java 21 or 25 selected in `JAVA_HOME` and Python 3.12+:

```bash
python3 .github/performance/compare.py \
  --base origin/main --candidate HEAD --java 21 \
  --output benchmark-reports/my-comparison
```

The output directory must be new. Revisions are exported to temporary directories, so the working tree is untouched. Maven caches are isolated per variant; CI retains third-party dependencies, while this project's SNAPSHOT artifacts are removed before each build. Normal library dependencies remain those declared by each revision; no JMH fork is installed.

Add `--smoke` for a short three-case end-to-end pipeline check. Smoke reports are explicitly labelled and cannot be published as full performance evidence. Test reporting/validation with:

```bash
python3 -m unittest discover -s .github/performance/tests -v
```

