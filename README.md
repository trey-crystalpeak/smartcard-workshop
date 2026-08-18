# Smartcard Workshop

## Setup

    ./setup.sh

Needs `bash curl git make tar xz python3-venv`
Linux/WSL: `libpcsclite-dev swig pcscd`
WSL also needs usbipd-win to pass the reader through (probably?)

`java -jar tools/gp.jar` needs Java 11+; if your system lacks it, use the one `setup.sh` provides at `tools/<platform>/jdk11/bin/java`

## Exercise 0 — Hello, world (smoke-test card + toolchain)

    make hello
    java -jar tools/gp.jar --install examples/build/hello.cap
    java -jar tools/gp.jar --applet 48656C6C6F01 --apdu 8000000000

Expect `48656C6C6F2C20776F726C6421 9000`. Same thing over pyscard:

    .venv-*/bin/python examples/hello/hello.py         # -> Hello, world! 9000

## Exercise 1 — Battle!

    make install                                       # flash the applets; card in reader 0
    ./lrc setup                                        # name, hatch 6, spend rerolls
    make server                                        # other terminal; http://localhost:8000
    ./lrc battle --p1 reader:0 --p2 reader:1           # pair up: two cards, two readers

## Exercise 2 — Write your own strategy

    $EDITOR card/strat/src/lrc/strat/MonStrat.java
    make strat                                         # reflash just your brain; team survives
    ./lrc battle --p1 reader:0 --p2 reader:1
