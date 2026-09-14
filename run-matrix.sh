#!/usr/bin/env bash
#
# Runs LoggingDecoratorTest (face (a)) for both package variants -- org.acme and
# org.example -- against every stable Quarkus release in the regression range and
# writes a markdown data table to results/version-matrix.md.
#
# The two packages are the opposite sides of the known package-sensitivity flip:
# org.acme is in the "good on 3.34.7, bad on 3.35.0+" group, org.example is in the
# "bad on 3.34.7, good on 3.39.3" group. Running both per version makes the flip
# visible in a single table.
#
# Usage:
#   ./run-matrix.sh                  # default range, overwrites results/version-matrix.md
#   ./run-matrix.sh 3.35.0 3.39.3    # explicit range (inclusive)
#
# Classification (per package):
#   PASS              test exits 0
#   FAIL-StackOverflow test fails and the log contains StackOverflowError
#   FAIL-other        any other failure
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OUT_FILE="results/version-matrix.md"
LOG_DIR="$(mktemp -d)"

PACKAGES=(
  org.acme
  org.example
)
TEST_CLASS="LoggingDecoratorTest"

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

# classify_result <log-file> <exit-code> -> PASS | FAIL-StackOverflow | FAIL-other
classify_result() {
  local log="$1" code="$2"
  if [[ "$code" -eq 0 ]]; then
    echo "PASS"
  elif grep -q "StackOverflowError" "$log"; then
    echo "FAIL-StackOverflow"
  else
    echo "FAIL-other"
  fi
}

mkdir -p "$(dirname "$OUT_FILE")"

HEADER="| Quarkus Version"
SEPARATOR="| ---"
for PKG in "${PACKAGES[@]}"; do
  HEADER="$HEADER | $PKG"
  SEPARATOR="$SEPARATOR | ---"
done
HEADER="$HEADER |"
SEPARATOR="$SEPARATOR |"

{
  printf '%s\n' "$HEADER"
  printf '%s\n' "$SEPARATOR"
} > "$OUT_FILE"

for V in "${VERSIONS[@]}"; do
  ROW="| $V "
  for PKG in "${PACKAGES[@]}"; do
    LOG="$LOG_DIR/$V-$PKG.log"
    mvn clean -Dquarkus.platform.version="$V" -Dtest="$PKG.$TEST_CLASS" test >"$LOG" 2>&1
    CODE=$?
    R="$(classify_result "$LOG" "$CODE")"
    ROW="$ROW| $R "
    printf '%s %s %s (exit=%s)\n' "$V" "$PKG" "$R" "$CODE"
  done
  ROW="$ROW|"
  printf '%s\n' "$ROW" >> "$OUT_FILE"
done

echo
echo "matrix written to $OUT_FILE"
echo "raw logs in $LOG_DIR"
