#!/usr/bin/env bash
#
# Run buff-fastjson benchmarks and generate reports.
#
# Usage:
#   ./run-benchmarks.sh                          # run all benchmarks (default: -wi 3 -i 5 -f 2)
#   ./run-benchmarks.sh -wi 3 -i 5 -f 2         # pass custom JMH options
#
# Benchmark subsets (pass as regex filter):
#   ./run-benchmarks.sh "SimpleMessage"          # 6-field scalar message
#   ./run-benchmarks.sh "ComplexMessage"         # nested/maps/repeated/oneofs/bytes/timestamps
#   ./run-benchmarks.sh "AllScalars"             # all 15 proto3 scalar types + enum
#   ./run-benchmarks.sh "WktBenchmark"           # Timestamp, Duration, Wrappers, Struct
#   ./run-benchmarks.sh "WktBenchmark.timestamp" # Timestamp only
#   ./run-benchmarks.sh "WktBenchmark.duration"  # Duration only
#   ./run-benchmarks.sh "WktBenchmark.wrappers"  # all 9 wrapper types
#   ./run-benchmarks.sh "WktBenchmark.struct"    # Struct/Value/ListValue
#   ./run-benchmarks.sh "AnyBenchmark"           # Any with scalar message + Any with Timestamp
#   ./run-benchmarks.sh "RepeatedAndMap"         # 100+ repeated elements, 50+ map entries
#   ./run-benchmarks.sh "DeepNesting"            # 5-level recursive nesting + string/bytes stress
#
# Each benchmark has constant + random data variants and 3 encoders (codegen, generic, JsonFormat).
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

# Always clean-rebuild to pick up code changes and regenerate JMH BenchmarkList.
echo "Building benchmarks..."
mvn package -pl buff-fastjson-benchmarks -am -q
if ! mvn package -pl buff-fastjson-benchmarks -am -DskipTests; then
    echo "ERROR: Maven build failed. Fix compilation errors and retry."
    exit 1
fi

mkdir -p "$REPORTS_DIR"

# Default JMH args if none provided
if [ $# -eq 0 ]; then
    JMH_ARGS=(-wi 3 -i 5 -f 2)
else
    JMH_ARGS=("$@")
fi

echo "Running benchmarks with args: ${JMH_ARGS[*]}"
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
        if "Codegen" in name or name.startswith("buffJsonCodegen"):
            return "codegen"
        if "Generic" in name or name == "buffJson" or name == "buffJsonRandom":
            return "generic"
        if "JsonFormat" in name or name.startswith("protoJsonFormat"):
            return "jsonformat"
        return None

    # Per-class analysis
    for class_name in sorted(groups.keys()):
        methods = groups[class_name]
        f.write(f"## {class_name}\n\n")

        # Check if this is a POJO-only benchmark (no codegen/generic/jsonformat methods)
        is_pojo = all(classify_encoder(m["method"]) is None for m in methods)

        if is_pojo:
            f.write("| Benchmark | ops/s |\n")
            f.write("|---|---:|\n")
            for m in sorted(methods, key=lambda x: x["method"]):
                f.write(f"| {m['method']} | {fmt(m)} |\n")
            f.write("\n")
            continue

        # Group methods into comparable triples
        comparisons = defaultdict(dict)
        for m in methods:
            name = m["method"]
            is_random = name.endswith("Random")
            data_type = "random" if is_random else "constant"

            encoder = classify_encoder(name)
            if encoder is None:
                encoder = name

            # Extract the message/benchmark prefix
            prefix = name
            for suffix in ["CodegenRandom", "Codegen", "GenericRandom", "Generic",
                           "JsonFormatRandom", "JsonFormat", "Random"]:
                if prefix.endswith(suffix):
                    prefix = prefix[:-len(suffix)]
                    break
            if prefix in ("buffJson", "protoJson", "proto", "buffJsonCodegen", "protoJsonFormat"):
                prefix = "message"
            if not prefix:
                prefix = "message"

            key = f"{prefix}_{data_type}"
            comparisons[key][encoder] = m

        f.write("| Scenario | Codegen | Generic | JsonFormat | Codegen/Generic | Codegen/JsonFormat |\n")
        f.write("|---|---:|---:|---:|:---:|:---:|\n")

        for key in sorted(comparisons.keys()):
            scores = comparisons[key]
            cg = scores.get("codegen")
            gn = scores.get("generic")
            jf = scores.get("jsonformat")

            cg_vs_gn = ""
            if cg and gn and gn["score"] > 0:
                cg_vs_gn = f"**{cg['score'] / gn['score']:.1f}x**"

            cg_vs_jf = ""
            if cg and jf and jf["score"] > 0:
                cg_vs_jf = f"**{cg['score'] / jf['score']:.1f}x**"

            label = key.replace("_", " ")
            f.write(f"| {label} | {fmt(cg)} | {fmt(gn)} | {fmt(jf)} | {cg_vs_gn} | {cg_vs_jf} |\n")

        f.write("\n")

    # Overall highlights
    f.write("## Key Takeaways\n\n")

    all_ratios = []
    for class_name in groups:
        methods = groups[class_name]
        codegen_const = {m["method"]: m["score"] for m in methods
                         if ("Codegen" in m["method"] or m["method"] == "buffJsonCodegen")
                         and not m["method"].endswith("Random")}
        generic_const = {m["method"]: m["score"] for m in methods
                         if ("Generic" in m["method"] or m["method"] == "buffJson")
                         and not m["method"].endswith("Random")}
        jf_const = {m["method"]: m["score"] for m in methods
                    if ("JsonFormat" in m["method"] or m["method"] == "protoJsonFormat")
                    and not m["method"].endswith("Random")}

        for cg_name, cg_score in codegen_const.items():
            prefix_cg = cg_name.replace("Codegen", "").replace("buffJson", "")
            entry = {"class": class_name, "method": cg_name, "score": cg_score}

            for gn_name, gn_score in generic_const.items():
                prefix_gn = gn_name.replace("Generic", "").replace("buffJson", "")
                if prefix_cg == prefix_gn and gn_score > 0:
                    entry["codegen_vs_generic"] = cg_score / gn_score

            for jf_name, jf_score in jf_const.items():
                prefix_jf = jf_name.replace("JsonFormat", "").replace("protoJson", "").replace("Format", "")
                if prefix_cg == prefix_jf and jf_score > 0:
                    entry["codegen_vs_jsonformat"] = cg_score / jf_score

            if "codegen_vs_generic" in entry:
                all_ratios.append(entry)

    if all_ratios:
        best_cg = max(all_ratios, key=lambda r: r.get("codegen_vs_generic", 0))
        worst_cg = min(all_ratios, key=lambda r: r.get("codegen_vs_generic", float("inf")))
        best_jf = max(all_ratios, key=lambda r: r.get("codegen_vs_jsonformat", 0))
        worst_jf = min(all_ratios, key=lambda r: r.get("codegen_vs_jsonformat", float("inf")))

        f.write(f"- **Best codegen vs generic:** {best_cg['codegen_vs_generic']:.1f}x "
                f"({best_cg['class']}.{best_cg['method']})\n")
        f.write(f"- **Smallest codegen vs generic:** {worst_cg['codegen_vs_generic']:.1f}x "
                f"({worst_cg['class']}.{worst_cg['method']})\n")
        if "codegen_vs_jsonformat" in best_jf:
            f.write(f"- **Best codegen vs JsonFormat:** {best_jf['codegen_vs_jsonformat']:.1f}x "
                    f"({best_jf['class']}.{best_jf['method']})\n")
        if "codegen_vs_jsonformat" in worst_jf:
            f.write(f"- **Smallest codegen vs JsonFormat:** {worst_jf['codegen_vs_jsonformat']:.1f}x "
                    f"({worst_jf['class']}.{worst_jf['method']})\n")
        f.write("\n")

    f.write(f"\n---\n*Generated from `{os.path.basename(json_file)}`*\n")

print(f"Report written to {report_file}")
PYTHON_EOF

echo ""
echo "Done!"
echo "  Raw output:  ${RAW_FILE}"
echo "  JSON data:   ${JSON_FILE}"
echo "  Report:      ${REPORT_FILE}"
