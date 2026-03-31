#!/usr/bin/env bash
#
# Run buff-fastjson benchmarks and generate reports.
#
# Usage:
#   ./run-benchmarks.sh                          # run regression suite (default)
#   ./run-benchmarks.sh --full                   # run ALL benchmarks
#   ./run-benchmarks.sh --full -wi 3 -i 5 -f 2  # full suite with custom JMH options
#   ./run-benchmarks.sh -wi 3 -i 5 -f 2         # regression suite with custom JMH options
#
# Regression suite (default) — focused set for catching regressions fast:
#   SimpleMessageBenchmark     — flat 6-field message
#   ComplexMessageBenchmark    — nested/maps/repeated/oneofs/bytes/timestamps
#   WktBenchmark               — Timestamp + Struct
#   RepeatedAndMapBenchmark    — 100+ repeated, 50+ map entries
#   CeilingBenchmark           — fastjson2 POJO ceiling vs BuffJSON
#
# Full suite — adds these on top of regression:
#   AllScalarsBenchmark        — all 15 proto3 scalar types + enum
#   AnyBenchmark               — Any with TypeRegistry
#   DeepNestingAndStringBenchmark — recursive nesting + string/bytes stress
#
# Benchmark subsets (pass as regex filter after flags):
#   ./run-benchmarks.sh "SimpleMessage"
#   ./run-benchmarks.sh "WktBenchmark.timestamp"
#   ./run-benchmarks.sh "CeilingBenchmark"
#
# Output:
#   benchmark-reports/<timestamp>-raw.txt      — full JMH console output
#   benchmark-reports/<timestamp>-report.md    — human-readable markdown report
#   benchmark-reports/<timestamp>-results.json — machine-readable JSON results

set -euo pipefail
cd "$(dirname "$0")"

BENCHMARKS_JAR="buff-fastjson-benchmarks/target/benchmarks.jar"
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

# Regression suite filter (default unless --full)
if [ "$FULL_MODE" = false ]; then
    # Check if user already specified a regex filter (non-flag argument)
    HAS_FILTER=false
    for arg in "${JMH_ARGS[@]}"; do
        if [[ ! "$arg" =~ ^- ]] && [[ ! "$arg" =~ ^[0-9]+$ ]]; then
            HAS_FILTER=true
            break
        fi
    done
    if [ "$HAS_FILTER" = false ]; then
        JMH_ARGS=("(SimpleMessage|ComplexMessage|Ceiling|Wkt|RepeatedAndMap)Benchmark" "${JMH_ARGS[@]}")
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
import json, sys, os
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

with open(report_file, "w") as f:
    f.write("# Benchmark Report\n\n")
    f.write(f"**Date:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    if jvm_info:
        f.write(f"**{jvm_info}**\n")
    f.write(f"**Benchmarks:** {len(results)} methods across {len(groups)} classes\n\n")

    def fmt(m):
        if m is None:
            return "-"
        score_str = f"{m['score']:,.0f}"
        err = m['error']
        if isinstance(err, (int, float)) and err == err:  # not NaN
            return f"{score_str} \u00b1{err:,.0f}"
        return score_str

    def classify_encoder(name):
        if "Compiled" in name or name.startswith("buffJsonCompiled"):
            return "compiled"
        if "Runtime" in name or name.startswith("buffJsonRuntime"):
            return "runtime"
        if "JsonFormat" in name or name.endswith("JsonFormat"):
            return "jsonformat"
        if name.startswith("jacksonProtobuf"):
            return "jackson"
        if name.endswith("JacksonHubspot"):
            return "jackson_hubspot"
        if "fastjson2" in name or name.startswith("fastjson2"):
            return "fastjson2"
        return None

    def is_ceiling_class(class_name):
        return class_name == "CeilingBenchmark"


    # Per-class analysis
    for class_name in sorted(groups.keys()):
        methods = groups[class_name]
        f.write(f"## {class_name}\n\n")

        if is_ceiling_class(class_name):
            # Ceiling benchmark: compare like-for-like (compiled vs compiled, runtime vs runtime)
            fj2_runtime = None
            fj2_compiled = None
            bj_compiled = None
            bj_runtime = None
            for m in methods:
                name = m["method"]
                if name == "fastjson2Runtime":
                    fj2_runtime = m
                elif name == "fastjson2Compiled":
                    fj2_compiled = m
                elif name == "buffJsonCompiled":
                    bj_compiled = m
                elif name == "buffJsonRuntime":
                    bj_runtime = m

            f.write("| | Fastjson2 | BuffJSON | BuffJSON / Fastjson2 |\n")
            f.write("|---|---:|---:|:---:|\n")

            # Compiled row
            ratio_c = ""
            if bj_compiled and fj2_compiled and fj2_compiled["score"] > 0:
                ratio_c = f"**{bj_compiled['score'] / fj2_compiled['score']:.2f}x**"
            f.write(f"| compiled | {fmt(fj2_compiled)} | {fmt(bj_compiled)} | {ratio_c} |\n")

            # Runtime row
            ratio_r = ""
            if bj_runtime and fj2_runtime and fj2_runtime["score"] > 0:
                ratio_r = f"**{bj_runtime['score'] / fj2_runtime['score']:.2f}x**"
            f.write(f"| runtime | {fmt(fj2_runtime)} | {fmt(bj_runtime)} | {ratio_r} |\n")

            f.write("\n")
            continue

        # Standard benchmark classes: BuffJSON vs JsonFormat, row per mode
        comparisons = defaultdict(dict)
        for m in methods:
            name = m["method"]
            encoder = classify_encoder(name)
            if encoder is None:
                encoder = name

            # Extract the message/benchmark prefix
            prefix = name
            for suffix in ["Compiled", "Runtime", "JsonFormat", "Protobuf"]:
                if prefix.endswith(suffix):
                    prefix = prefix[:-len(suffix)]
                    break
            if prefix in ("buffJson", "protoJson", "proto", "protoJsonFormat", "jackson", "jacksonProtobuf"):
                prefix = "message"
            if not prefix:
                prefix = "message"

            comparisons[prefix][encoder] = m

        # Check if any method in this class has Jackson
        has_jackson = any(classify_encoder(m["method"]) == "jackson" for m in methods)

        if has_jackson:
            f.write("| | BuffJSON | JsonFormat | Jackson | BuffJSON / JsonFormat | BuffJSON / Jackson |\n")
            f.write("|---|---:|---:|---:|:---:|:---:|\n")
        else:
            f.write("| | BuffJSON | JsonFormat | BuffJSON / JsonFormat |\n")
            f.write("|---|---:|---:|:---:|\n")

        for key in sorted(comparisons.keys()):
            scores = comparisons[key]
            cp = scores.get("compiled")
            rt = scores.get("runtime")
            jf = scores.get("jsonformat")
            jk = scores.get("jackson")

            label = f"{key} " if key != "message" else ""

            ratio_c_jf = ""
            if cp and jf and jf["score"] > 0:
                ratio_c_jf = f"**{cp['score'] / jf['score']:.1f}x**"
            ratio_c_jk = ""
            if cp and jk and jk["score"] > 0:
                ratio_c_jk = f"**{cp['score'] / jk['score']:.1f}x**"

            if has_jackson:
                f.write(f"| {label}compiled | {fmt(cp)} | {fmt(jf)} | {fmt(jk)} | {ratio_c_jf} | {ratio_c_jk} |\n")
            else:
                f.write(f"| {label}compiled | {fmt(cp)} | {fmt(jf)} | {ratio_c_jf} |\n")

            ratio_r_jf = ""
            if rt and jf and jf["score"] > 0:
                ratio_r_jf = f"**{rt['score'] / jf['score']:.1f}x**"
            ratio_r_jk = ""
            if rt and jk and jk["score"] > 0:
                ratio_r_jk = f"**{rt['score'] / jk['score']:.1f}x**"

            if has_jackson:
                f.write(f"| {label}runtime | {fmt(rt)} | {fmt(jf)} | {fmt(jk)} | {ratio_r_jf} | {ratio_r_jk} |\n")
            else:
                f.write(f"| {label}runtime | {fmt(rt)} | {fmt(jf)} | {ratio_r_jf} |\n")

        f.write("\n")

    # Overall highlights
    f.write("## Key Takeaways\n\n")

    all_ratios = []
    for class_name in groups:
        if is_ceiling_class(class_name):
            continue
        methods = groups[class_name]
        compiled_methods = {m["method"]: m["score"] for m in methods
                           if "Compiled" in m["method"] or m["method"] == "buffJsonCompiled"}
        runtime_methods = {m["method"]: m["score"] for m in methods
                           if "Runtime" in m["method"] or m["method"] == "buffJsonRuntime"}
        jf_methods = {m["method"]: m["score"] for m in methods
                      if "JsonFormat" in m["method"] or m["method"] == "protoJsonFormat"}

        for cp_name, cp_score in compiled_methods.items():
            prefix_cp = cp_name.replace("Compiled", "")
            entry = {"class": class_name, "method": cp_name, "score": cp_score}

            for jf_name, jf_score in jf_methods.items():
                prefix_jf = jf_name.replace("JsonFormat", "")
                if prefix_cp == prefix_jf and jf_score > 0:
                    entry["compiled_vs_jsonformat"] = cp_score / jf_score

            for rt_name, rt_score in runtime_methods.items():
                prefix_rt = rt_name.replace("Runtime", "")
                for jf_name, jf_score in jf_methods.items():
                    prefix_jf = jf_name.replace("JsonFormat", "")
                    if prefix_rt == prefix_jf and jf_score > 0:
                        entry["runtime_vs_jsonformat"] = rt_score / jf_score

            if "compiled_vs_jsonformat" in entry:
                all_ratios.append(entry)

    if all_ratios:
        best_c = max(all_ratios, key=lambda r: r.get("compiled_vs_jsonformat", 0))
        worst_c = min(all_ratios, key=lambda r: r.get("compiled_vs_jsonformat", float("inf")))
        best_r = max(all_ratios, key=lambda r: r.get("runtime_vs_jsonformat", 0))
        worst_r = min(all_ratios, key=lambda r: r.get("runtime_vs_jsonformat", float("inf")))

        f.write(f"- **Best compiled vs JsonFormat:** {best_c['compiled_vs_jsonformat']:.1f}x "
                f"({best_c['class']}.{best_c['method']})\n")
        f.write(f"- **Smallest compiled vs JsonFormat:** {worst_c['compiled_vs_jsonformat']:.1f}x "
                f"({worst_c['class']}.{worst_c['method']})\n")
        if "runtime_vs_jsonformat" in best_r:
            f.write(f"- **Best runtime vs JsonFormat:** {best_r['runtime_vs_jsonformat']:.1f}x "
                    f"({best_r['class']}.{best_r['method'].replace('Compiled','Runtime')})\n")
        if "runtime_vs_jsonformat" in worst_r:
            f.write(f"- **Smallest runtime vs JsonFormat:** {worst_r['runtime_vs_jsonformat']:.1f}x "
                    f"({worst_r['class']}.{worst_r['method'].replace('Compiled','Runtime')})\n")

    # Ceiling summary
    ceiling_methods = groups.get("CeilingBenchmark", [])
    if ceiling_methods:
        fj2c = next((m["score"] for m in ceiling_methods if m["method"] == "fastjson2Compiled"), None)
        fj2r = next((m["score"] for m in ceiling_methods if m["method"] == "fastjson2Runtime"), None)
        bjc = next((m["score"] for m in ceiling_methods if m["method"] == "buffJsonCompiled"), None)
        bjr = next((m["score"] for m in ceiling_methods if m["method"] == "buffJsonRuntime"), None)
        if fj2c and bjc and fj2c > 0:
            f.write(f"- **Compiled: BuffJSON vs fastjson2:** {bjc / fj2c:.2f}x\n")
        if fj2r and bjr and fj2r > 0:
            f.write(f"- **Runtime: BuffJSON vs fastjson2:** {bjr / fj2r:.2f}x\n")

    f.write(f"\n\n---\n*Generated from `{os.path.basename(json_file)}`*\n")

print(f"Report written to {report_file}")
PYTHON_EOF

echo ""
echo "Done!"
echo "  Raw output:  ${RAW_FILE}"
echo "  JSON data:   ${JSON_FILE}"
echo "  Report:      ${REPORT_FILE}"
