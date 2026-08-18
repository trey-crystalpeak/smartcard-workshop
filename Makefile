# lrcmon - pokemon that live in javacards. Your card hatches a persistent team
# of 6; cards battle bring-6/pick-4 gen 9 doubles on a local Showdown server,
# and every decision is made ON THE CARD by a strategy applet you write.
# Cheating via card skills is graded as success.
#
#   ./setup.sh                                  once per machine (needs network)
#   make hello                                  smoke-test the build: a Hello, world! applet
#   make server                                 local showdown at http://localhost:8000
#   ./lrc battle --hatch                        no-hardware demo: two simulated cards
#   make install                                flash the applets (card in reader 0)
#   ./lrc setup                                 name yourself, hatch 6, spend rerolls
#   ./lrc battle --p1 reader:0 --p2 reader:1
#   make strat                                  swap in your brain, team survives
#
# ./lrc --help lists all card commands. The tree mirrors between machines:
# binaries live in tools/<platform>/ and .venv-<platform>/, the rest is shared.
# Re-curate the pool: tools/<plat>/node/bin/node scripts/generate.mjs, then make caps.

PLATFORM := $(shell . scripts/platform.sh && echo $$LRC_PLATFORM)
PTOOLS   := tools/$(PLATFORM)
GP       := $(PTOOLS)/jdk11/bin/java -jar tools/gp.jar $(GP_FLAGS)

.PHONY: caps simclasses server install strat hello

caps:
	./scripts/buildcaps.sh

# minimal standalone applet - build + load it to smoke-test your toolchain
hello:
	./scripts/hello.sh
	@echo "load:  $(GP) --install examples/build/hello.cap"
	@echo "test:  send APDU 80 00 00 00 00 -> 48 65 6C 6C 6F 2C 20 77 6F 72 6C 64 21 (\"Hello, world!\")"

simclasses:
	rm -rf card/build/simclasses && mkdir -p card/build/simclasses
	$(PTOOLS)/jdk11/bin/javac -nowarn -encoding UTF-8 -cp tools/jcardsim.jar \
		-d card/build/simclasses card/*/src/lrc/*/*.java sim/SimBridge.java

# battles are fully local; the spectator page pulls the showdown client from the internet
server:
	cd pokemon-showdown && PATH=$(CURDIR)/$(PTOOLS)/node/bin:$$PATH ./pokemon-showdown start 8000 --no-security

install: caps
	-$(GP) --uninstall card/build/strat.cap 2>/dev/null
	-$(GP) --uninstall card/build/core.cap 2>/dev/null
	-$(GP) --uninstall card/build/iface.cap 2>/dev/null
	$(GP) --load card/build/iface.cap
	$(GP) --install card/build/core.cap
	$(GP) --install card/build/strat.cap

strat: caps
	-$(GP) --uninstall card/build/strat.cap 2>/dev/null
	$(GP) --install card/build/strat.cap
