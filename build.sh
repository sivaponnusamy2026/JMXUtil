#!/usr/bin/env bash
# Build jmxdiag with the plain JDK (no Maven required).
set -euo pipefail

SRC="src/main/java/com/example/jmxutil/JmxDiagnostic.java"
OUT="out"

mkdir -p "$OUT"

echo "Compiling..."
javac -d "$OUT" "$SRC"

echo "Packaging jmxdiag.jar..."
jar --create --file jmxdiag.jar \
    --main-class com.example.jmxutil.JmxDiagnostic \
    -C "$OUT" .

echo "Done. Run:  java -jar jmxdiag.jar --host <host> --port <port>"
