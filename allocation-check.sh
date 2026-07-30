#!/usr/bin/env bash
#
# Allocation regression check.
#
# Runs JMH with -prof gc on a representative subset of benchmarks and asserts
# that gc.alloc.rate.norm (bytes allocated per @Benchmark invocation) stays
# within per-benchmark budgets. Catches regressions like a missed
# zero-allocation path, a forgotten try-with-resources, or a new String/byte[]
# allocation per call.
#
# Usage:
#   ./allocation-check.sh                  # default: runs full check
#   ./allocation-check.sh --quick          # fewer iterations (for local iteration)
#   JMH_FORK=2 ./allocation-check.sh       # override JMH fork count
#
# Exit codes:
#   0 — all benchmarks within budget
#   1 — at least one benchmark exceeded its budget
#   2 — environment / build failure
#
# Used in CI (.github/workflows/ci.yml).

set -euo pipefail
cd "$(dirname "$0")"

BENCHMARKS_JAR="buff-json-benchmarks/target/benchmarks.jar"
RESULTS_FILE="${RESULTS_FILE:-/tmp/buff-json-alloc-check.json}"

# Budgets in B/op (gc.alloc.rate.norm).
# Format: <fully-qualified benchmark>:<budget>
# Comments show the measurement that informed the budget — leave ~15-30%
# headroom over current readings to absorb minor JVM/JIT/codegen variance
# across runs and platforms. If you tighten or widen a budget, update the
# baseline comment.
BUDGETS=(
    # SimpleMessage (6 fields, ~80-byte JSON output) — the String/byte[] return is
    # now essentially the *only* allocation; the encoder itself is zero. Dropped
    # by 88 B/op when BuffJsonEncoder started sharing one JSONWriter.Context
    # instead of letting JSONWriter.of() allocate one per call.
    "io.suboptimal.buffjson.benchmarks.SimpleMessageBenchmark.compiledUtf16:290"   # baseline ~208 B/op
    "io.suboptimal.buffjson.benchmarks.SimpleMessageBenchmark.compiledUtf8:260"    # baseline ~184 B/op
    "io.suboptimal.buffjson.benchmarks.SimpleMessageBenchmark.runtimeUtf16:290"    # baseline ~208 B/op (typed-accessor)
    "io.suboptimal.buffjson.benchmarks.SimpleMessageBenchmark.runtimeUtf8:260"     # baseline ~184 B/op (typed-accessor)

    # ComplexMessage (nested + repeated + maps + oneof) — bigger output, more
    # internal collections.
    "io.suboptimal.buffjson.benchmarks.ComplexMessageBenchmark.buffJsonCompiled:2000"  # baseline ~1444 B/op
    "io.suboptimal.buffjson.benchmarks.ComplexMessageBenchmark.buffJsonRuntime:1900"   # baseline ~1308 B/op

    # DoubleHeavy (25 doubles, IoT/telemetry profile) — number formatting cost.
    "io.suboptimal.buffjson.benchmarks.DoubleHeavyBenchmark.compiledUtf16:2200"    # baseline ~1685 B/op
    "io.suboptimal.buffjson.benchmarks.DoubleHeavyBenchmark.compiledUtf8:2100"     # baseline ~1661 B/op
)

# Parse args
QUICK=false
for arg in "$@"; do
    case "$arg" in
        --quick) QUICK=true ;;
        -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# JMH parameters (override via env if needed)
WI="${JMH_WI:-2}"      # warmup iterations
I="${JMH_I:-3}"        # measurement iterations
F="${JMH_FORK:-1}"     # forks
W="${JMH_W:-1}"        # warmup time (seconds per iteration)
R="${JMH_R:-2}"        # measurement time (seconds per iteration)
if [ "$QUICK" = true ]; then
    WI=1; I=2; F=1; W=1; R=1
fi

# Build benchmark jar if missing or older than buff-json sources
if [ ! -f "$BENCHMARKS_JAR" ]; then
    echo "Benchmark jar not found — building..."
    mvn package -DskipTests -q || { echo "Build failed" >&2; exit 2; }
fi

# Compose the JMH benchmark filter from BUDGETS keys
PATTERN=$(printf "%s\n" "${BUDGETS[@]}" | cut -d: -f1 | paste -sd'|' -)

echo "Running JMH allocation check (-prof gc)"
echo "  warmup=${WI}x${W}s  measure=${I}x${R}s  forks=${F}"
echo ""

java -jar "$BENCHMARKS_JAR" \
    "$PATTERN" \
    -prof gc \
    -wi "$WI" -i "$I" -f "$F" -r "$R" -w "$W" \
    -rf json -rff "$RESULTS_FILE" \
    > /tmp/buff-json-alloc-check.log 2>&1 || {
        echo "JMH run failed — see /tmp/buff-json-alloc-check.log" >&2
        tail -40 /tmp/buff-json-alloc-check.log >&2
        exit 2
    }

# Parse JSON results and assert budgets
python3 - "$RESULTS_FILE" "${BUDGETS[@]}" << 'PYTHON_EOF'
import json, sys

results_file = sys.argv[1]
budgets = {}
for spec in sys.argv[2:]:
    name, budget = spec.rsplit(":", 1)
    budgets[name] = float(budget)

with open(results_file) as f:
    results = json.load(f)

print(f"{'Benchmark':<70} {'Measured':>14} {'Budget':>10} {'Status':>8}")
print("-" * 104)

failed = []
seen = set()
for r in results:
    name = r["benchmark"]
    if name not in budgets:
        continue
    seen.add(name)
    sm = r["secondaryMetrics"].get("gc.alloc.rate.norm")
    if sm is None:
        print(f"{name:<70} {'no data':>14} {budgets[name]:>10.0f} {'SKIP':>8}")
        continue
    score = sm["score"]
    budget = budgets[name]
    short = name.replace("io.suboptimal.buffjson.benchmarks.", "")
    status = "OK" if score <= budget else "FAIL"
    print(f"{short:<70} {score:>10.1f} B/op {budget:>5.0f} B/op {status:>8}")
    if score > budget:
        failed.append((name, score, budget))

# Flag missing benchmarks (budget defined but not reported)
missing = sorted(set(budgets.keys()) - seen)
for name in missing:
    short = name.replace("io.suboptimal.buffjson.benchmarks.", "")
    print(f"{short:<70} {'NOT RUN':>14} {budgets[name]:>10.0f} {'MISS':>8}")
    failed.append((name, None, budgets[name]))

print()
if failed:
    print(f"FAIL: {len(failed)} benchmark(s) outside allocation budget:")
    for name, score, budget in failed:
        short = name.replace("io.suboptimal.buffjson.benchmarks.", "")
        if score is None:
            print(f"  {short}: not reported in JMH results (budget {budget:.0f} B/op)")
        else:
            print(f"  {short}: {score:.1f} B/op > {budget:.0f} B/op (over by {score-budget:.0f})")
    sys.exit(1)

print(f"OK: all {len(seen)} benchmark(s) within allocation budgets.")
PYTHON_EOF
