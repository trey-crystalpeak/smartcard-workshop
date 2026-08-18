#!/usr/bin/env bash
# Build the standalone Hello, world! example into a CAP - the same javac +
# converter flow as scripts/buildcaps.sh, boiled down to one self-contained
# applet so it reads as a minimal template. Load it with:
#   java -jar tools/gp.jar --install examples/build/hello.cap
# then send APDU 80 00 00 00 00 and read back "Hello, world!" (48 65 6C ...).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/platform.sh

JDK="$PWD/$LRC_TOOLS/jdk11"
JC="$PWD/tools/oracle_javacard_sdks/jc320v26.0_kit"
CP=$(printf '%s:' "$JC"/lib/*.jar)
OUT=examples/build
rm -rf "$OUT"; mkdir -p "$OUT/tree"

"$JDK/bin/javac" --release 7 -cp "$JC/lib/api_classic-3.0.5.jar" \
  -d "$OUT/tree" examples/hello/HelloWorld.java

JAVA_HOME="$JDK" "$JDK/bin/java" -cp "$CP" com.sun.javacard.converter.Main \
  -target 3.0.5 -nobanner -classdir "$OUT/tree" -d "$OUT/tree" -out CAP \
  -applet 0x48:0x65:0x6C:0x6C:0x6F:0x01 hello.HelloWorld \
  hello 0x48:0x65:0x6C:0x6C:0x6F 1.0
cp "$OUT/tree/hello/javacard/hello.cap" "$OUT/hello.cap"
echo "built $OUT/hello.cap"
