#!/usr/bin/env bash
# Compile and convert the three packages into CAP files with Oracle's own
# converter - no Ant. The 3.2 SDK converter runs on a modern JDK and takes
# -target 3.0.5, emitting the CAP format the J3R180 loads. iface is a shared
# library, so it is converted first and core/strat import its export file.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/platform.sh

JDK="$PWD/$LRC_TOOLS/jdk11"
JC="$PWD/tools/oracle_javacard_sdks/jc320v26.0_kit"
CP=$(printf '%s:' "$JC"/lib/*.jar)
OUT=card/build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/tree"

"$JDK/bin/javac" --release 7 -cp "$JC/lib/api_classic-3.0.5.jar" \
  -d "$OUT/classes" card/*/src/lrc/*/*.java

convert() {  # name package aid [appletAid appletClass]
  JAVA_HOME="$JDK" "$JDK/bin/java" -cp "$CP" com.sun.javacard.converter.Main \
    -target 3.0.5 -i -nobanner -classdir "$OUT/classes" -exportpath "$OUT/tree" \
    ${4:+-applet "$4" "$5"} -d "$OUT/tree" -out CAP EXP "$2" "$3" 1.0
  cp "$OUT/tree/${2//.//}/javacard/$1.cap" "$OUT/$1.cap"
}

convert iface lrc.iface 0xF0:0x4C:0x52:0x43:0x00:0x01
convert core  lrc.core  0xF0:0x4C:0x52:0x43:0x01:0x01 0xF0:0x4C:0x52:0x43:0x01:0x01:0x01 lrc.core.MonCore
convert strat lrc.strat 0xF0:0x4C:0x52:0x43:0x02:0x01 0xF0:0x4C:0x52:0x43:0x02:0x01:0x01 lrc.strat.MonStrat
echo "built $OUT/{iface,core,strat}.cap"
