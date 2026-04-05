#!/usr/bin/env bash
#
# Run buff-json benchmarks and generate reports.
#
# Usage:
#   ./run-benchmarks.sh                          # run regression suite (default)
#   ./run-benchmarks.sh --full                   # run full BuffJson-vs-JsonFormat suite
#   ./run-benchmarks.sh --full -wi 3 -i 5 -f 2  # full suite with custom JMH options
#   ./run-benchmarks.sh -wi 3 -i 5 -f 2         # regression suite with custom JMH options
#
# Regression suite (default) — core BuffJson vs JsonFormat:
#   SimpleMessageBenchmark     — flat 6-field message (encode + decode)
#   ComplexMessageBenchmark    — nested/maps/repeated/oneofs/bytes/timestamps (encode + decode)
#   WktBenchmark               — Timestamp + Struct
#   RepeatedAndMapBenchmark    — 100+ repeated, 50+ map entries
#
# Full suite — adds these on top of regression:
#   AllScalarsBenchmark        — all 15 proto3 scalar types + enum
#   AnyBenchmark               — Any with TypeRegistry
#   DeepNestingAndStringBenchmark — recursive nesting + string/bytes stress
#
# By-demand benchmarks (run explicitly by name):
#   CeilingBenchmark           — fastjson2 POJO ceiling vs BuffJson
#   JacksonBenchmark           — BuffJson vs Jackson (HubSpot + BuffJson-Jackson module)
#   ProtoBinaryBenchmark       — BuffJson JSON vs protobuf binary encoding
#
# Benchmark subsets (pass as regex filter after flags):
#   ./run-benchmarks.sh "SimpleMessage"
#   ./run-benchmarks.sh "WktBenchmark.timestamp"
#   ./run-benchmarks.sh "JacksonBenchmark"
#   ./run-benchmarks.sh "CeilingBenchmark"
#
# Output:
#   benchmark-reports/<timestamp>-raw.txt      — full JMH console output
#   benchmark-reports/<timestamp>-report.md    — human-readable markdown report
#   benchmark-reports/<timestamp>-results.json — machine-readable JSON results

set -euo pipefail
cd "$(dirname "$0")"

BENCHMARKS_JAR="buff-json-benchmarks/target/benchmarks.jar"
REPORTS_DIR="benchmark-reports"
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

RAW_FILE="${REPORTS_DIR}/${TIMESTAMP}-raw.txt"
JSON_FILE="${REPORTS_DIR}/${TIMESTAMP}-results.json"
REPORT_FILE="${REPORTS_DIR}/${TIMESTAMP}-report.md"

# Parse flags
FULL_MODE=false
JMH_ARGS=()
for arg in "$@"; do
    if [ "$arg" = "--full" ]; then
        FULL_MODE=true
    else
        JMH_ARGS+=("$arg")
    fi
done

# Default JMH args if none provided
if [ ${#JMH_ARGS[@]} -eq 0 ]; then
    JMH_ARGS=(-wi 3 -i 5 -f 2)
fi

# Check if user already specified a regex filter (non-flag argument)
HAS_FILTER=false
for arg in "${JMH_ARGS[@]}"; do
    if [[ ! "$arg" =~ ^- ]] && [[ ! "$arg" =~ ^[0-9]+$ ]]; then
        HAS_FILTER=true
        break
    fi
done

# Apply suite filter only when no explicit filter was given
if [ "$HAS_FILTER" = false ]; then
    if [ "$FULL_MODE" = true ]; then
        # Full suite: all BuffJson-vs-JsonFormat benchmarks
        JMH_ARGS=("(SimpleMessage|ComplexMessage|Wkt|RepeatedAndMap|AllScalars|Any|DeepNestingAndString)Benchmark" "${JMH_ARGS[@]}")
    else
        # Regression suite: core BuffJson-vs-JsonFormat benchmarks
        JMH_ARGS=("(SimpleMessage|ComplexMessage|Wkt|RepeatedAndMap)Benchmark" "${JMH_ARGS[@]}")
    fi
fi

# Always clean-rebuild to pick up code changes and regenerate JMH BenchmarkList.
echo "Building benchmarks..."
mvn package -DskipTests -q

mkdir -p "$REPORTS_DIR"

if [ "$FULL_MODE" = true ]; then
    echo "Running FULL benchmark suite with args: ${JMH_ARGS[*]}"
else
    echo "Running REGRESSION suite with args: ${JMH_ARGS[*]}"
fi
echo "Raw output:  ${RAW_FILE}"
echo "JSON results: ${JSON_FILE}"
echo "Report:      ${REPORT_FILE}"
echo ""

# Run JMH — tee raw console output, also produce JSON results
java -jar "$BENCHMARKS_JAR" \
    "${JMH_ARGS[@]}" \
    -rf json -rff "$JSON_FILE" \
    2>&1 | tee "$RAW_FILE"

echo ""
echo "Benchmarks complete. Generating report..."

# Generate markdown report from JSON results
python3 - "$JSON_FILE" "$REPORT_FILE" << 'PYTHON_EOF'
import json, sys, os, re
from datetime import datetime
from collections import defaultdict

json_file = sys.argv[1]
report_file = sys.argv[2]

with open(json_file) as f:
    results = json.load(f)

# Group by benchmark class
groups = defaultdict(list)
for r in results:
    full_name = r["benchmark"]
    parts = full_name.rsplit(".", 1)
    class_name = parts[0].split(".")[-1]
    method_name = parts[1]
    score = r["primaryMetric"]["score"]
    raw_error = r["primaryMetric"]["scoreError"]
    error = float(raw_error) if isinstance(raw_error, (int, float)) and raw_error == raw_error else 0
    unit = r["primaryMetric"]["scoreUnit"]
    groups[class_name].append({
        "method": method_name,
        "score": score,
        "error": error,
        "unit": unit,
    })

# JVM info from first result
jvm_info = ""
if results:
    r0 = results[0]
    vm_name = r0.get("vmName", "")
    vm_version = r0.get("vmVersion", "")
    jdk_version = r0.get("jdkVersion", "")
    if vm_name:
        jvm_info = f"{vm_name} {vm_version}" if vm_version else vm_name
    if jdk_version:
        jvm_info += f" (JDK {jdk_version})" if jvm_info else f"JDK {jdk_version}"


# ---- Helpers ----

def fmt(m):
    if m is None:
        return "-"
    score_str = f"{m['score']:,.0f}"
    err = m['error']
    if isinstance(err, (int, float)) and err == err:  # not NaN
        return f"{score_str} \u00b1{err:,.0f}"
    return score_str

def camel_split(name):
    """Split camelCase: 'buffJsonCompiled' -> ['buff', 'Json', 'Compiled']"""
    return re.findall(r'[a-z0-9]+|[A-Z][a-z0-9]*', name)

def join_camel(parts):
    if not parts:
        return ""
    return parts[0] + "".join(p for p in parts[1:])

IMPL_KEYWORDS = {"json", "binary", "jackson", "buff", "fastjson", "proto", "gson", "hubspot"}

def keyword_score(col_names):
    """Score how many column names contain known implementation keywords."""
    return sum(10 for name in col_names
               if any(kw in name.lower() for kw in IMPL_KEYWORDS))

def is_core_class(methods):
    """Core benchmarks: have methods ending in Compiled + Runtime + JsonFormat."""
    has_compiled = any(m["method"].endswith("Compiled") or m["method"] == "buffJsonCompiled" for m in methods)
    has_jsonformat = any(m["method"].endswith("JsonFormat") or m["method"] == "protoJsonFormat" for m in methods)
    return has_compiled and has_jsonformat

def find_matrix(methods):
    """Auto-detect matrix structure from method names for comparison benchmarks.
    Returns (rows_dict, col_names) or None.
    rows_dict = {row_label: {col_label: method_data}}
    """
    names = [m["method"] for m in methods]
    by_name = {m["method"]: m for m in methods}
    n = len(names)

    if n < 2:
        return None

    segments = {name: camel_split(name) for name in names}
    max_segs = max(len(s) for s in segments.values())
    candidates = []

    def try_grouping(row_fn, col_fn):
        grps = defaultdict(dict)
        cols = set()
        for name in names:
            try:
                row = row_fn(name)
                col = col_fn(name)
            except (IndexError, ValueError):
                return None
            if not row or not col:
                return None
            if col in grps[row]:
                return None
            grps[row][col] = by_name[name]
            cols.add(col)
        total = sum(len(v) for v in grps.values())
        expected = len(grps) * len(cols)
        if total == expected == n and len(cols) >= 2 and len(grps) >= 1:
            return dict(grps), sorted(cols)
        return None

    # Strategy 1: last N camelCase segments as column
    for cw in range(1, min(4, max_segs)):
        result = try_grouping(
            lambda nm, w=cw: join_camel(segments[nm][:-w]) if len(segments[nm]) > w else None,
            lambda nm, w=cw: join_camel(segments[nm][-w:]) if len(segments[nm]) > w else None,
        )
        if result:
            candidates.append(result)

    # Strategy 2: first N camelCase segments as row
    for rw in range(1, min(4, max_segs)):
        result = try_grouping(
            lambda nm, w=rw: join_camel(segments[nm][:w]) if len(segments[nm]) > w else None,
            lambda nm, w=rw: join_camel(segments[nm][w:]) if len(segments[nm]) > w else None,
        )
        if result:
            candidates.append(result)

    # Strategy 3: middle segment extraction (segment at position pos = column)
    for pos in range(1, max_segs - 1):
        result = try_grouping(
            lambda nm, p=pos: join_camel(segments[nm][:p] + segments[nm][p+1:]) if len(segments[nm]) > p + 1 else None,
            lambda nm, p=pos: segments[nm][p] if len(segments[nm]) > p + 1 else None,
        )
        if result:
            candidates.append(result)

    if not candidates:
        return None

    # Score each candidate + its transpose, pick best.
    # Scoring: prefer columns with implementation keywords, more rows, fewer columns.
    def matrix_score(cols, n_rows):
        return keyword_score(cols) - len(cols) * 12 + n_rows * 3

    best_score = -999
    best_result = None

    for rows_dict, col_names in candidates:
        # Original orientation
        score = matrix_score(col_names, len(rows_dict))
        if score > best_score:
            best_score = score
            best_result = (rows_dict, col_names)

        # Transposed orientation
        transposed = defaultdict(dict)
        for row, cols in rows_dict.items():
            for col, m in cols.items():
                transposed[col][row] = m
        t_col_names = sorted(rows_dict.keys())
        t_rows_dict = dict(transposed)
        score_t = matrix_score(t_col_names, len(t_rows_dict))
        if score_t > best_score:
            best_score = score_t
            best_result = (t_rows_dict, t_col_names)

    return best_result

def find_baseline_col(col_names):
    """Find the column representing BuffJson (baseline for ratios)."""
    for col in col_names:
        if "uffJson" in col and "Jackson" not in col:
            return col
    for col in col_names:
        if col == "Json":
            return col
    return col_names[0]


# ---- Report generation ----

with open(report_file, "w") as f:
    f.write("# Benchmark Report\n\n")
    f.write(f"**Date:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    if jvm_info:
        f.write(f"**{jvm_info}**\n")
    f.write(f"**Benchmarks:** {len(results)} methods across {len(groups)} classes\n\n")

    for class_name in sorted(groups.keys()):
        methods = groups[class_name]
        f.write(f"## {class_name}\n\n")

        if is_core_class(methods):
            # ---- Core benchmark: BuffJson compiled/runtime vs JsonFormat ----
            comparisons = defaultdict(dict)
            for m in methods:
                name = m["method"]
                mode = None
                variant = name
                for suffix, mode_name in [("Compiled", "compiled"), ("Runtime", "runtime"), ("JsonFormat", "jsonformat")]:
                    if name.endswith(suffix):
                        mode = mode_name
                        variant = name[:-len(suffix)]
                        break
                if mode is None:
                    continue
                if variant in ("buffJson", "protoJson", "proto", "protoJsonFormat", ""):
                    variant = "message"
                comparisons[variant][mode] = m

            f.write("| | BuffJson Compiled | BuffJson Runtime | JsonFormat | Compiled / JF | Runtime / JF |\n")
            f.write("|---|---:|---:|---:|:---:|:---:|\n")

            for variant in sorted(comparisons.keys()):
                data = comparisons[variant]
                cp = data.get("compiled")
                rt = data.get("runtime")
                jf = data.get("jsonformat")
                label = variant if variant != "message" else ""
                ratio_c = f"**{cp['score']/jf['score']:.1f}x**" if cp and jf and jf["score"] > 0 else ""
                ratio_r = f"**{rt['score']/jf['score']:.1f}x**" if rt and jf and jf["score"] > 0 else ""
                f.write(f"| {label} | {fmt(cp)} | {fmt(rt)} | {fmt(jf)} | {ratio_c} | {ratio_r} |\n")

            f.write("\n")

        else:
            # ---- Comparison benchmark: auto-detect matrix ----
            matrix = find_matrix(methods)

            if matrix:
                rows_dict, col_names = matrix
                baseline = find_baseline_col(col_names)
                non_baseline = [c for c in col_names if c != baseline]

                # Header
                header = "| |"
                sep = "|---|"
                for col in col_names:
                    header += f" {col} |"
                    sep += "---:|"
                for col in non_baseline:
                    header += f" {baseline}/{col} |"
                    sep += ":---:|"
                f.write(header + "\n")
                f.write(sep + "\n")

                for row in sorted(rows_dict.keys()):
                    cols_data = rows_dict[row]
                    line = f"| {row} |"
                    for col in col_names:
                        line += f" {fmt(cols_data.get(col))} |"
                    bl_m = cols_data.get(baseline)
                    for col in non_baseline:
                        other_m = cols_data.get(col)
                        if bl_m and other_m and other_m["score"] > 0:
                            line += f" **{bl_m['score']/other_m['score']:.2f}x** |"
                        else:
                            line += " |"
                    f.write(line + "\n")
            else:
                # Fallback: flat table
                f.write("| Method | ops/s |\n")
                f.write("|---|---:|\n")
                for m in sorted(methods, key=lambda x: x["method"]):
                    f.write(f"| {m['method']} | {fmt(m)} |\n")

            f.write("\n")

    # ---- Key Takeaways ----
    f.write("## Key Takeaways\n\n")

    # Core benchmark ratios (BuffJson vs JsonFormat)
    all_ratios = []
    for class_name in groups:
        if not is_core_class(groups[class_name]):
            continue
        methods = groups[class_name]
        compiled = {m["method"]: m["score"] for m in methods
                    if m["method"].endswith("Compiled") or m["method"] == "buffJsonCompiled"}
        runtime = {m["method"]: m["score"] for m in methods
                   if m["method"].endswith("Runtime") or m["method"] == "buffJsonRuntime"}
        jf = {m["method"]: m["score"] for m in methods
              if m["method"].endswith("JsonFormat") or m["method"] == "protoJsonFormat"}

        for cp_name, cp_score in compiled.items():
            prefix_cp = cp_name.replace("Compiled", "").replace("buffJson", "")
            entry = {"class": class_name, "method": cp_name, "score": cp_score}
            for jf_name, jf_score in jf.items():
                prefix_jf = jf_name.replace("JsonFormat", "").replace("protoJsonFormat", "").replace("proto", "")
                if prefix_cp == prefix_jf and jf_score > 0:
                    entry["compiled_vs_jf"] = cp_score / jf_score
            for rt_name, rt_score in runtime.items():
                prefix_rt = rt_name.replace("Runtime", "").replace("buffJson", "")
                for jf_name, jf_score in jf.items():
                    prefix_jf = jf_name.replace("JsonFormat", "").replace("protoJsonFormat", "").replace("proto", "")
                    if prefix_rt == prefix_jf and jf_score > 0:
                        entry["runtime_vs_jf"] = rt_score / jf_score
            if "compiled_vs_jf" in entry:
                all_ratios.append(entry)

    if all_ratios:
        best_c = max(all_ratios, key=lambda r: r.get("compiled_vs_jf", 0))
        worst_c = min(all_ratios, key=lambda r: r.get("compiled_vs_jf", float("inf")))
        best_r = max(all_ratios, key=lambda r: r.get("runtime_vs_jf", 0))
        worst_r = min(all_ratios, key=lambda r: r.get("runtime_vs_jf", float("inf")))
        f.write(f"- **Best compiled vs JsonFormat:** {best_c['compiled_vs_jf']:.1f}x "
                f"({best_c['class']}.{best_c['method']})\n")
        f.write(f"- **Smallest compiled vs JsonFormat:** {worst_c['compiled_vs_jf']:.1f}x "
                f"({worst_c['class']}.{worst_c['method']})\n")
        if "runtime_vs_jf" in best_r:
            f.write(f"- **Best runtime vs JsonFormat:** {best_r['runtime_vs_jf']:.1f}x "
                    f"({best_r['class']}.{best_r['method'].replace('Compiled','Runtime')})\n")
        if "runtime_vs_jf" in worst_r:
            f.write(f"- **Smallest runtime vs JsonFormat:** {worst_r['runtime_vs_jf']:.1f}x "
                    f"({worst_r['class']}.{worst_r['method'].replace('Compiled','Runtime')})\n")

    # Comparison benchmark summaries (auto-detected)
    for class_name in sorted(groups.keys()):
        if is_core_class(groups[class_name]):
            continue
        matrix = find_matrix(groups[class_name])
        if not matrix:
            continue
        rows_dict, col_names = matrix
        baseline = find_baseline_col(col_names)
        non_baseline = [c for c in col_names if c != baseline]
        for other in non_baseline:
            ratios = []
            for row, cols_data in rows_dict.items():
                bl = cols_data.get(baseline)
                ot = cols_data.get(other)
                if bl and ot and ot["score"] > 0:
                    ratios.append(bl["score"] / ot["score"])
            if ratios:
                avg = sum(ratios) / len(ratios)
                f.write(f"- **{class_name} \u2014 {baseline} vs {other}:** avg {avg:.2f}x\n")

    f.write(f"\n\n---\n*Generated from `{os.path.basename(json_file)}`*\n")

print(f"Report written to {report_file}")
PYTHON_EOF

echo ""
echo "Done!"
echo "  Raw output:  ${RAW_FILE}"
echo "  JSON data:   ${JSON_FILE}"
echo "  Report:      ${REPORT_FILE}"
