#!/usr/bin/env bash
# Build (optional) and copy the plugin jar into Anypoint Studio dropins.
# Usage:
#   ./install-dropins.sh           # package + copy
#   ./install-dropins.sh --copy    # copy only (jar must already exist)
#   ./install-dropins.sh --clean   # package + copy, then remind to restart with -clean
#   ./install-dropins.sh --skip-checks   # do not run the offline harness checks first
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

STUDIO_APP="${STUDIO_APP:-/Applications/AnypointStudio728.app}"
DROPINS="${DROPINS:-$STUDIO_APP/Contents/Eclipse/dropins}"
VERSION="$(sed -n 's/^Bundle-Version: *\([0-9.]*\)\..*/\1/p' "$ROOT/com.mulesoftforge.studio.ai/META-INF/MANIFEST.MF")"
JAR_SRC="$ROOT/com.mulesoftforge.studio.ai/target/com.mulesoftforge.studio.ai-$VERSION-SNAPSHOT.jar"
JAR_NAME="com.mulesoftforge.studio.ai.jar"

COPY_ONLY=0
REMIND_CLEAN=0
SKIP_CHECKS=0
for arg in "$@"; do
  case "$arg" in
    --copy) COPY_ONLY=1 ;;
    --clean) REMIND_CLEAN=1 ;;
    --skip-checks) SKIP_CHECKS=1 ;;
    -h|--help)
      sed -n '2,9p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

if [[ ! -d "$DROPINS" ]]; then
  echo "dropins not found: $DROPINS" >&2
  echo "Set STUDIO_APP or DROPINS if Studio is elsewhere." >&2
  exit 1
fi

if [[ "$COPY_ONLY" -eq 0 ]]; then
  echo "Building plugin…"
  (cd "$ROOT" && mvn -q -DskipTests package)
fi

if [[ "$SKIP_CHECKS" -eq 0 ]]; then
  echo "Running offline checks…"
  if ! "$ROOT/harness/run-checks.sh"; then
    echo "Not installing (use --skip-checks to override)." >&2
    exit 1
  fi
fi

if [[ ! -f "$JAR_SRC" ]]; then
  echo "Missing jar: $JAR_SRC" >&2
  echo "Run without --copy, or build first." >&2
  exit 1
fi

# Remove previous dropins copies of this plugin (any naming variant).
shopt -s nullglob
removed=0
for old in "$DROPINS"/com.mulesoftforge.studio.ai*.jar; do
  rm -f "$old"
  removed=1
done
shopt -u nullglob

cp "$JAR_SRC" "$DROPINS/$JAR_NAME"
echo "Installed $DROPINS/$JAR_NAME ($(du -h "$DROPINS/$JAR_NAME" | awk '{print $1}'))"
if [[ "$removed" -eq 1 ]]; then
  echo "Replaced previous com.mulesoftforge.studio.ai*.jar in dropins."
fi
echo "Restart Anypoint Studio to load it."
if [[ "$REMIND_CLEAN" -eq 1 ]]; then
  echo "If the view does not appear, start once with -clean:"
  echo "  open -a AnypointStudio728 --args -clean"
fi
