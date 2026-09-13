#!/usr/bin/env bash
#
# Runs LoggingDecoratorTest (org.acme) against every stable Quarkus release in the
# regression range and appends the classified result to results/version-matrix.txt.
#
# Usage:
#   ./run-matrix.sh                  # default range, appends to results/version-matrix.txt
#   ./run-matrix.sh 3.35.0 3.39.3    # explicit range (inclusive)
#
# Classification:
#   PASS              test exits 0
#   FAIL-StackOverflow test fails and the log contains StackOverflowError
#   FAIL-other        any other failure
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OUT_FILE="results/version-matrix.txt"
TEST_CLASS="LoggingDecoratorTest"
LOG_DIR="$(mktemp -d)"

ALL_VERSIONS=(
  3.33.0 3.33.1 3.33.2 3.33.3
  3.34.0 3.34.1 3.34.2 3.34.3 3.34.4 3.34.5 3.34.6 3.34.7
  3.35.0 3.35.1 3.35.2 3.35.3 3.35.4
  3.36.0 3.36.1 3.36.2 3.36.3
  3.37.0 3.37.1 3.37.2 3.37.3 3.37.4
  3.38.0 3.38.1 3.38.2 3.38.3
  3.39.0 3.39.1 3.39.2 3.39.3
)

if [[ $# -eq 0 ]]; then
  VERSIONS=("${ALL_VERSIONS[@]}")
elif [[ $# -eq 2 ]]; then
  VERSIONS=()
  for v in "${ALL_VERSIONS[@]}"; do
    if [[ "$(printf '%s\n' "$1" "$v" | sort -V | head -1)" == "$1" ]] \
       && [[ "$(printf '%s\n' "$2" "$v" | sort -V | tail -1)" == "$2" ]]; then
      VERSIONS+=("$v")
    fi
  done
else
  echo "usage: $0 [from-version to-version]" >&2
  exit 2
fi

mkdir -p "$(dirname "$OUT_FILE")"
: > "$OUT_FILE"

for V in "${VERSIONS[@]}"; do
  LOG="$LOG_DIR/$V.log"
  mvn clean -Dquarkus.platform.version="$V" -Dtest="$TEST_CLASS" test >"$LOG" 2>&1
  CODE=$?
  SO=$(grep -c "StackOverflowError" "$LOG")
  UOE=$(grep -c "UnsupportedOperationException" "$LOG")

  if [[ $CODE -eq 0 ]]; then
    R="PASS"
  elif [[ $SO -gt 0 ]]; then
    R="FAIL-StackOverflow"
  else
    R="FAIL-other"
  fi

  printf '%s %s (exit=%s so=%s uoe=%s)\n' "$V" "$R" "$CODE" "$SO" "$UOE" | tee -a "$OUT_FILE"
done

echo
echo "matrix written to $OUT_FILE"
echo "raw logs in $LOG_DIR"
