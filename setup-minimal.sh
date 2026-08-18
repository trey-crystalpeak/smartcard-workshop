#!/usr/bin/env bash
# Provision a machine for the smartcard workshop.
#
#   ./setup.sh              minimal: build applets, run the local sim, flash and
#                           talk to a real card (JDK, JavaCard SDK, gp, pyscard)
#   ./setup.sh --showdown   also install the battle stack (Node + poke-env +
#                           pokemon-showdown) needed for `make server` / ./lrc battle
#
# Paranoid: refuses to start without the host tools it shells out to, checks
# every download by running it, and is safe to re-run (skips what is present).
set -euo pipefail
cd "$(dirname "$0")"
. scripts/platform.sh

PS_COMMIT=84d7ceb4f009928221fce7a00e711bab263c5f4e
NODE_VERSION=v24.19.0
JCARDSIM_VERSION=3.0.6.0

SHOWDOWN=0
for arg in "$@"; do
  case "$arg" in
    --showdown) SHOWDOWN=1 ;;
    *) echo "usage: ./setup.sh [--showdown]" >&2; exit 2 ;;
  esac
done

die() { echo "setup: $*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
for t in curl git tar make python3; do have "$t" || die "missing required tool '$t'"; done
[ "$SHOWDOWN" = 1 ] && { have xz || die "missing required tool 'xz' (Node tarball is .tar.xz)"; }
get() { curl -fsSL --retry 3 -o "$1" "$2" || die "download failed: $2"; [ -s "$1" ] || die "empty download: $2"; }

LRC_TMP=$(mktemp -d "${TMPDIR:-/tmp}/lrcmon.XXXXXX")
trap 'rm -rf "$LRC_TMP"' EXIT
mkdir -p "$LRC_TOOLS"

# --- JDK 11: builds CAPs and runs gp.jar ---
if [ ! -x "$LRC_TOOLS/jdk11/bin/java" ]; then
  get "$LRC_TMP/jdk11.tgz" \
    "https://api.adoptium.net/v3/binary/latest/11/ga/$JDK_OS/$JDK_ARCH/jdk/hotspot/normal/eclipse"
  mkdir "$LRC_TMP/jdk11"; tar xzf "$LRC_TMP/jdk11.tgz" -C "$LRC_TMP/jdk11"
  rm -rf "$LRC_TOOLS/jdk11.x"; mv "$LRC_TMP/jdk11"/* "$LRC_TOOLS/jdk11.x"
  if [ -e "$LRC_TOOLS/jdk11.x/Contents/Home" ]; then
    mv "$LRC_TOOLS/jdk11.x/Contents/Home" "$LRC_TOOLS/jdk11"
  else
    mv "$LRC_TOOLS/jdk11.x" "$LRC_TOOLS/jdk11"
  fi
  rm -rf "$LRC_TOOLS/jdk11.x"
fi
"$LRC_TOOLS/jdk11/bin/java" -version >/dev/null 2>&1 || die "vendored JDK is broken"

# --- card toolchain: gp (load), jcardsim (sim), JavaCard SDK (converter) ---
[ -s tools/gp.jar ] || get tools/gp.jar \
  https://github.com/martinpaljak/GlobalPlatformPro/releases/latest/download/gp.jar
[ -s tools/jcardsim.jar ] || get tools/jcardsim.jar \
  "https://repo1.maven.org/maven2/com/klinec/jcardsim/$JCARDSIM_VERSION/jcardsim-$JCARDSIM_VERSION.jar"
[ -e tools/oracle_javacard_sdks ] || git clone --depth 1 \
  https://github.com/martinpaljak/oracle_javacard_sdks.git tools/oracle_javacard_sdks
[ -d tools/oracle_javacard_sdks/jc320v26.0_kit ] || die "JavaCard SDK kit missing"

# --- real-card comms ---
[ -e "$LRC_VENV/bin/python" ] || python3 -m venv "$LRC_VENV"
"$LRC_VENV/bin/python" -m pip install -q pyscard 2>/dev/null || \
  echo "warn: pyscard not installed - real cards need PC/SC (Linux/WSL: libpcsclite-dev swig pcscd)" >&2

# --- battle stack (optional) ---
if [ "$SHOWDOWN" = 1 ]; then
  if [ ! -x "$LRC_TOOLS/node/bin/node" ]; then
    get "$LRC_TMP/node.txz" "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-$NODE_PLAT.tar.xz"
    tar xf "$LRC_TMP/node.txz" -C "$LRC_TMP"
    rm -rf "$LRC_TOOLS/node"; mv "$LRC_TMP/node-$NODE_VERSION-$NODE_PLAT" "$LRC_TOOLS/node"
  fi
  "$LRC_TOOLS/node/bin/node" --version >/dev/null 2>&1 || die "vendored Node is broken"
  export PATH="$PWD/$LRC_TOOLS/node/bin:$PATH"

  "$LRC_VENV/bin/python" -m pip install -q poke-env==0.15.0

  if [ ! -e pokemon-showdown ]; then
    git clone https://github.com/smogon/pokemon-showdown.git
    git -C pokemon-showdown checkout -q "$PS_COMMIT"
  fi
  [ -e pokemon-showdown/dist/sim ] || (cd pokemon-showdown && npm ci --no-audit --no-fund && node build)
  [ -e pokemon-showdown/config/config.js ] || \
    cp pokemon-showdown/config/config-example.js pokemon-showdown/config/config.js
  sed -i.bak 's/^exports.repl = true;/exports.repl = false;/' pokemon-showdown/config/config.js
  rm -f pokemon-showdown/config/config.js.bak
  if [ -f pokemon-showdown/node_modules/esbuild/package.json ]; then
    EV=$(node -p "require('$PWD/pokemon-showdown/node_modules/esbuild/package.json').version")
    [ -d "pokemon-showdown/node_modules/@esbuild/$NODE_PLAT" ] || \
      (cd pokemon-showdown && npm install --no-save --no-audit --no-fund "@esbuild/$NODE_PLAT@$EV")
  fi
fi

make caps simclasses
[ -s card/build/core.cap ] || die "CAP build produced nothing"
echo "done - smoke-test it: make hello"
[ "$SHOWDOWN" = 1 ] || echo "(battles need Showdown: rerun ./setup.sh --showdown)"
