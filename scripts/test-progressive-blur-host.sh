#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command -v kotlinc >/dev/null 2>&1 || { echo 'Install a Kotlin CLI compiler to run host tests.' >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo 'Install a JDK to run host tests.' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
kotlinc \
  "$ROOT/android/src/main/java/com/edgefade/BlurLabGeometry.kt" \
  "$ROOT/android/src/main/java/com/edgefade/BlurLabShaders.kt" \
  "$ROOT/tests/native/BlurLabHostTest.kt" \
  -include-runtime -d "$TMP/tests.jar"
java -jar "$TMP/tests.jar"
