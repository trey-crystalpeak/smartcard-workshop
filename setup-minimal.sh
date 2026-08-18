#!/usr/bin/env bash
# Build applets, run the local sim, and talk to a real card.
set -euo pipefail
cd "$(dirname "$0")"
. scripts/platform.sh

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

[ -e tools/gp.jar ] || curl -sfL -o tools/gp.jar \
  https://github.com/martinpaljak/GlobalPlatformPro/releases/latest/download/gp.jar
[ -e tools/jcardsim.jar ] || curl -sfL -o tools/jcardsim.jar \
  "https://repo1.maven.org/maven2/com/klinec/jcardsim/$JCARDSIM_VERSION/jcardsim-$JCARDSIM_VERSION.jar"
[ -e tools/oracle_javacard_sdks ] || git clone --depth 1 \
  https://github.com/martinpaljak/oracle_javacard_sdks.git tools/oracle_javacard_sdks

[ -e "$LRC_VENV/bin/python" ] || python3 -m venv "$LRC_VENV"
"$LRC_VENV/bin/python" -m pip install -q pyscard 2>/dev/null || \
  echo "note: pyscard skipped; Linux/WSL needs libpcsclite-dev, swig, and pcscd"

make caps simclasses
echo "done - smoke-test it: make hello"
