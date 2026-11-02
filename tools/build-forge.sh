#!/usr/bin/env bash
# Builds Forge's game engine (forge-core, forge-game, forge-ai) at the commit pinned in forge.version,
# and packages the card data the app needs. Outputs (both gitignored):
#   android/app/libs/forge/*.jar                    engine jars
#   android/app/src/main/assets/forge/forge-res.zip card scripts + editions + tokens + AI profiles + English strings
# Needs: git, JDK 17, Maven, Python 3.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHA="$(tr -d '[:space:]' < "$ROOT/forge.version")"
SRC="${FORGE_SRC:-$ROOT/build/forge-src}"
LIBS="$ROOT/android/app/libs/forge"
ASSETS="$ROOT/android/app/src/main/assets/forge"

if [ ! -d "$SRC/.git" ] || [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null)" != "$SHA" ]; then
  rm -rf "$SRC"; mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin https://github.com/Card-Forge/forge.git
  git -C "$SRC" fetch -q --depth 1 origin "$SHA"
  git -C "$SRC" checkout -q FETCH_HEAD
fi

echo "Building forge-core, forge-game, forge-ai at $SHA"
(cd "$SRC" && mvn -B -q -pl forge-ai -am -DskipTests -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true -Dsource.skip=true package)

rm -rf "$LIBS"; mkdir -p "$LIBS"
for m in forge-core forge-game forge-ai; do
  jar="$(ls "$SRC/$m/target/"*.jar | grep -v -E -- '-(sources|tests|javadoc)\.jar$' | head -1)"
  cp "$jar" "$LIBS/$m.jar"
done
ls -la "$LIBS"

python3 "$ROOT/tools/package_forge_res.py" "$SRC/forge-gui/res" "$ASSETS/forge-res.zip" "$SHA"
ls -la "$ASSETS"
