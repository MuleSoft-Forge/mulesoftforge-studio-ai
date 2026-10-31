#!/usr/bin/env bash
# Run the LLM harness outside Anypoint Studio.
# Usage: ./run.sh test-connection [provider...]
#        ./run.sh list-models [provider...]
#        ./run.sh chat [--provider name] [--model id] [--file path] [--project dir [--apply]] "prompt"
#        ./run.sh check-tools
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN="$ROOT/com.mulesoftforge.studio.ai"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"

if [[ ! -d "$PLUGIN/lib" ]] || [[ ! -d "$PLUGIN/target/classes" ]]; then
  echo "Building plugin first (needs lib/ and target/classes)..."
  (cd "$ROOT" && JAVA_HOME="$JAVA_HOME" mvn -q -DskipTests package)
fi

mkdir -p "$ROOT/harness/out"
CP="$PLUGIN/target/classes"
for j in "$PLUGIN"/lib/*.jar; do
  CP="$CP:$j"
done
"$JAVAC" -d "$ROOT/harness/out" -cp "$CP" "$ROOT"/harness/src/*.java
cd "$ROOT/harness"
exec "$JAVA" -cp "out:$CP" Harness "$@"
