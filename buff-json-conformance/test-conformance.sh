#!/usr/bin/env bash
#
# Runs the official protobuf conformance suite against the buff-json testee.
#
# Prerequisites:
#   * A built `conformance_test_runner` binary (built from protobuf source — see
#     the `conformance` job in .github/workflows/ci.yml). Point CONF_TEST_PATH at it.
#   * The shaded testee jar:
#       mvn -pl buff-json-conformance -am package -DskipTests
#
# Environment variables:
#   CONF_TEST_PATH        Path to the conformance_test_runner binary (required).
#   ENFORCE_CONFORMANCE   1 => exit non-zero when the runner reports unexpected
#                         results. Default 0 => report results but always exit 0
#                         (so the build does not fail while the failure list is
#                         still being curated).
#   BUFFJSON_PATH         Which buff-json path the testee exercises:
#                         codegen (default) | runtime | reflection. Exported so the
#                         testee process (spawned by the runner) inherits it.
#
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runner="${CONF_TEST_PATH:-}"
failure_list="${script_dir}/failure_list.txt"
jar="${script_dir}/target/conformance-testee.jar"
enforce="${ENFORCE_CONFORMANCE:-0}"
export BUFFJSON_PATH="${BUFFJSON_PATH:-codegen}"

if [[ -z "${runner}" ]]; then
	echo "ERROR: set CONF_TEST_PATH to the conformance_test_runner binary" >&2
	exit 2
fi
if [[ ! -x "${runner}" ]]; then
	echo "ERROR: CONF_TEST_PATH is not an executable file: ${runner}" >&2
	exit 2
fi
if [[ ! -f "${jar}" ]]; then
	echo "ERROR: testee jar not found at ${jar}" >&2
	echo "       build it with: mvn -pl buff-json-conformance -am package -DskipTests" >&2
	exit 2
fi

# The runner exec()s the testee command with execv(), which does NOT search PATH,
# so the program must be an absolute path — a bare "java" fails with
# "No such file or directory" and every test reports "unexpected EOF".
java_bin="$(command -v java || true)"
if [[ -z "${java_bin}" ]]; then
	echo "ERROR: java not found on PATH" >&2
	exit 2
fi

report="${script_dir}/target/conformance-report-${BUFFJSON_PATH}.txt"
mkdir -p "$(dirname "${report}")"

echo "Conformance runner: ${runner}"
echo "Testee jar:         ${jar}"
echo "Failure list:       ${failure_list}"
echo "buff-json path:     ${BUFFJSON_PATH}"
echo "java:               ${java_bin}"
echo "Report:             ${report}"
echo

# All runner flags must precede the testee command; everything from the java
# binary onward is passed to the child process verbatim. The runner's full
# output (failing test names + summary = the failure list) is teed to a report
# file so CI can upload it as an artifact.
"${runner}" --failure_list "${failure_list}" "${java_bin}" -jar "${jar}" 2>&1 | tee "${report}"
rc=${PIPESTATUS[0]}

echo
if [[ "${enforce}" == "1" ]]; then
	echo "ENFORCE_CONFORMANCE=1 — propagating conformance_test_runner exit code (${rc})"
	exit "${rc}"
fi
echo "ENFORCE_CONFORMANCE=${enforce} — reporting only; not failing the build (runner exit code was ${rc})"
exit 0
