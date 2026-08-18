#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
. scripts/platform.sh

PS_COMMIT=84d7ceb4f009928221fce7a00e711bab263c5f4e
NODE_VERSION=v24.19.0
JCARDSIM_VERSION=3.0.6.0

LRC_TMP=$(mktemp -d "${TMPDIR:-/tmp}/lrcmon.XXXXXX")
trap 'rm -rf "$LRC_TMP"' EXIT
mkdir -p "$LRC_TOOLS"

if [ ! -x "$LRC_TOOLS/jdk11/bin/java" ]; then
  curl -sfL -o "$LRC_TMP/jdk11.tgz" \
    "https://api.adoptium.net/v3/binary/latest/11/ga/$JDK_OS/$JDK_ARCH/jdk/hotspot/normal/eclipse"
  mkdir "$LRC_TMP/jdk11"
  tar xzf "$LRC_TMP/jdk11.tgz" -C "$LRC_TMP/jdk11"
  rm -rf "$LRC_TOOLS/jdk11" "$LRC_TOOLS/jdk11.x"
  mv "$LRC_TMP/jdk11"/* "$LRC_TOOLS/jdk11.x"
  if [ -e "$LRC_TOOLS/jdk11.x/Contents/Home" ]; then
    mv "$LRC_TOOLS/jdk11.x/Contents/Home" "$LRC_TOOLS/jdk11"
  else
    mv "$LRC_TOOLS/jdk11.x" "$LRC_TOOLS/jdk11"
  fi
  rm -rf "$LRC_TOOLS/jdk11.x"
fi

if [ ! -x "$LRC_TOOLS/node/bin/node" ]; then
  curl -sfL -o "$LRC_TMP/node.txz" \
    "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-$NODE_PLAT.tar.xz"
  tar xf "$LRC_TMP/node.txz" -C "$LRC_TMP"
  rm -rf "$LRC_TOOLS/node"
  mv "$LRC_TMP/node-$NODE_VERSION-$NODE_PLAT" "$LRC_TOOLS/node"
fi

[ -e tools/gp.jar ] || curl -sfL -o tools/gp.jar \
  https://github.com/martinpaljak/GlobalPlatformPro/releases/latest/download/gp.jar
[ -e tools/jcardsim.jar ] || curl -sfL -o tools/jcardsim.jar \
  "https://repo1.maven.org/maven2/com/klinec/jcardsim/$JCARDSIM_VERSION/jcardsim-$JCARDSIM_VERSION.jar"
[ -e tools/oracle_javacard_sdks ] || git clone --depth 1 \
  https://github.com/martinpaljak/oracle_javacard_sdks.git tools/oracle_javacard_sdks

[ -e "$LRC_VENV/bin/python" ] || python3 -m venv "$LRC_VENV"
"$LRC_VENV/bin/python" -m pip install -q poke-env==0.15.0
"$LRC_VENV/bin/python" -m pip install -q pyscard 2>/dev/null || \
  echo "note: pyscard skipped; Linux/WSL needs libpcsclite-dev, swig, and pcscd"

if [ ! -e pokemon-showdown ]; then
  git clone https://github.com/smogon/pokemon-showdown.git
  git -C pokemon-showdown checkout -q "$PS_COMMIT"
fi
export PATH="$PWD/$LRC_TOOLS/node/bin:$PATH"
[ -e pokemon-showdown/dist/sim ] || (cd pokemon-showdown && npm ci --no-audit --no-fund && node build)
[ -e pokemon-showdown/config/config.js ] || cp pokemon-showdown/config/config-example.js pokemon-showdown/config/config.js
sed -i.bak 's/^exports.repl = true;/exports.repl = false;/' pokemon-showdown/config/config.js
rm -f pokemon-showdown/config/config.js.bak

if [ -f pokemon-showdown/node_modules/esbuild/package.json ]; then
  EV=$(node -p "require('$PWD/pokemon-showdown/node_modules/esbuild/package.json').version")
  [ -d "pokemon-showdown/node_modules/@esbuild/$NODE_PLAT" ] || \
    (cd pokemon-showdown && npm install --no-save --no-audit --no-fund "@esbuild/$NODE_PLAT@$EV")
fi

make caps simclasses
echo "done - smoke-test it: make hello"
