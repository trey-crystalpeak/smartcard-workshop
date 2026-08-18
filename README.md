# lrcmon

Pokémon that live on Java Cards. Your card hatches a team of 6 and fights
bring-6/pick-4 Gen 9 doubles on a local Showdown server; every move is decided
on-card by a strategy applet you write.

## Setup (once per machine)

    ./setup.sh

Needs `bash curl git make tar xz python3-venv`. Real cards also need a PC/SC
reader with `pcscd` running (Linux/WSL: `libpcsclite-dev swig pcscd`; WSL also
needs usbipd-win to pass the reader through).

`java -jar tools/gp.jar` needs Java 11+; if your system lacks it, use the one
`setup.sh` vendored at `tools/<platform>/jdk11/bin/java`.

## Exercise 0 — Hello, world (smoke-test the toolchain)

    make hello
    java -jar tools/gp.jar --install examples/build/hello.cap
    java -jar tools/gp.jar --applet 48656C6C6F01 --apdu 8000000000

Expect `48656C6C6F2C20776F726C6421 9000`. Same thing over pyscard:

    .venv-*/bin/python examples/hello/hello.py         # -> Hello, world! 9000

## Exercise 1 — Hatch a team and battle

    make install
    ./lrc setup
    make server                                        # other terminal; http://localhost:8000
    ./lrc battle --hatch                               # no hardware: two sims
    ./lrc battle --p1 reader:0 --p2 reader:1           # two real cards

## Exercise 2 — Write your own strategy

    $EDITOR card/strat/src/lrc/strat/MonStrat.java
    make strat                                         # reflash just your brain; team survives
    ./lrc battle --p1 reader:0 --p2 reader:1
