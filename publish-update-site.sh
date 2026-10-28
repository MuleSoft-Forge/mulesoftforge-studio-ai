#!/usr/bin/env bash
# Build a release and add it to the update site served from the MuleSoftForge docs site.
#
#   ./publish-update-site.sh --notes "What changed"         # into ../website/docs/public/studio-ai/updates
#   ./publish-update-site.sh --site DIR --notes "…"         # into another checkout or folder
#   ./publish-update-site.sh --replace --notes "…"          # rebuild a version already published (avoid once users have it)
#
# Each release gets its own folder (0.1.0/, 0.2.0/, …). compositeContent.xml and compositeArtifacts.xml list them
# all, so one URL serves every version and users can roll back. latest.json names the newest for the chat view.
# Bump the version first with:
#   mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=0.2.0-SNAPSHOT
# Then commit the website change and run its "Deploy to Production" workflow.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

SITE="$ROOT/../website/docs/public/studio-ai/updates"
NOTES=""
REPLACE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --site) SITE="$2"; shift ;;
    --notes) NOTES="$2"; shift ;;
    --replace) REPLACE=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

VERSION="$(sed -n 's/^Bundle-Version: *\([0-9.]*\)\..*/\1/p' "$ROOT/com.mulesoftforge.studio.ai/META-INF/MANIFEST.MF")"
if [[ -z "$VERSION" ]]; then
  echo "Could not read Bundle-Version from MANIFEST.MF" >&2
  exit 1
fi
mkdir -p "$SITE"
SITE="$(cd "$SITE" && pwd)"
if [[ -e "$SITE/$VERSION" && "$REPLACE" -eq 0 ]]; then
  echo "$SITE/$VERSION already exists. Bump the version, or pass --replace." >&2
  exit 1
fi

echo "Building ${VERSION}…"
(cd "$ROOT" && mvn -q -DskipTests clean package)
echo "Running offline checks…"
"$ROOT/harness/run-checks.sh"

rm -rf "$SITE/$VERSION"
cp -R "$ROOT/com.mulesoftforge.studio.ai.updatesite/target/repository" "$SITE/$VERSION"

# Every published version, oldest first.
VERSIONS=()
while IFS= read -r v; do VERSIONS+=("$v"); done < <(
  find "$SITE" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -t. -k1,1n -k2,2n -k3,3n)
LATEST="${VERSIONS[${#VERSIONS[@]}-1]}"
STAMP="$(date +%s)000"

composite() {
  local kind="$1" type="$2" file="$3"
  {
    echo "<?xml version='1.0' encoding='UTF-8'?>"
    echo "<?$kind version='1.0.0'?>"
    echo "<repository name='MuleSoftForge Studio AI' type='$type' version='1.0.0'>"
    echo "  <properties size='2'>"
    echo "    <property name='p2.timestamp' value='$STAMP'/>"
    echo "    <property name='p2.atomic.composite.loading' value='true'/>"
    echo "  </properties>"
    echo "  <children size='${#VERSIONS[@]}'>"
    for v in "${VERSIONS[@]}"; do
      echo "    <child location='$v'/>"
    done
    echo "  </children>"
    echo "</repository>"
  } > "$SITE/$file"
}
composite compositeMetadataRepository \
  org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository compositeContent.xml
composite compositeArtifactRepository \
  org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository compositeArtifacts.xml

cat > "$SITE/p2.index" <<'EOF'
version=1
metadata.repository.factory.order=compositeContent.xml,\!
artifact.repository.factory.order=compositeArtifacts.xml,\!
EOF

# Notes for latest.json: keep the existing ones when this run is not the newest version.
if [[ "$VERSION" == "$LATEST" ]]; then
  ESCAPED="$(printf '%s' "$NOTES" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' ')"
  printf '{\n  "version": "%s",\n  "notes": "%s"\n}\n' "$VERSION" "$ESCAPED" > "$SITE/latest.json"
fi

cat > "$SITE/index.html" <<EOF
<!doctype html>
<meta charset="utf-8">
<title>MuleSoftForge Studio AI update site</title>
<p>This is an Eclipse update site for MuleSoftForge Studio AI, not a web page.</p>
<p>In Anypoint Studio choose Help &gt; Install New Software…, click Add…, and paste this page's address.</p>
<p>Versions: ${VERSIONS[*]}. Latest: $LATEST. <a href="https://docs.mulesoftforge.com/studio-ai/">Install guide</a></p>
EOF

echo "Published $VERSION to $SITE (versions: ${VERSIONS[*]}; latest $LATEST)."
echo "Next: commit the website change and run its Deploy to Production workflow."
