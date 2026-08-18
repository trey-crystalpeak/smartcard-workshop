#!/usr/bin/env python3
"""Everything you do with your card.

    lrc setup             first time: name yourself, hatch, reroll
    lrc status            name, wins, items, unclaimed unlocks
    lrc team              show your team
    lrc pool              team with full move pools + item list
    lrc items "Leftovers,Life Orb,-,-,-,Focus Sash"
    lrc claim ev 0 spe    max out mon 0's speed
    lrc claim move 2 1 7  mon 2: replace move slot 1 with pool move 7
    lrc claim shiny 4     make mon 4 shiny
    lrc battle --p1 reader:0 --p2 reader:1

Card commands talk to reader 0; use --card reader:N or --card sim for others.
Battles need `make server` running - watch at http://localhost:8000.
Flash a card with `make install`; swap in your strategy applet with `make strat`.
Simulated cards refuse to sign losses, so wins against them grant no unlock.
"""

import argparse
import asyncio
import logging
import urllib.parse
import urllib.request

import cards
from cards import ApduError, MonCard, open_transport


def show_team(card):
    for i, mon in enumerate(card.team()):
        print(f"[{i}] {cards.describe_mon(mon)}")


def show_pool(card):
    p = cards.pool()
    s = card.status()
    print("items:")
    for i, name in enumerate(p["items"]):
        owned = "x" if s["item_mask"] & (1 << i) else " "
        print(f"  [{owned}] {i:2d} {name}")
    print()
    for i, mon in enumerate(card.team()):
        sp = p["species"][mon["species"]]
        print(f"[{i}] {sp['name']} - move pool:")
        for slot, mv in enumerate(sp["moves"]):
            mark = "*" if slot in mon["moves"] else " "
            print(
                f"  {mark} {slot} {mv['name']} ({p['types'][mv['type']]}, "
                f"{mv['cat']}{', ' + str(mv['bp']) + ' bp' if mv['bp'] else ''})"
            )


def show_status(card):
    p = cards.pool()
    s = card.status()
    print(
        f"trainer:  {s['name'] or '(unnamed)'}   card id: {s['card_id'].hex().upper()}"
    )
    print(
        f"hatched:  {s['hatched']}   rerolls left: {s['rerolls']}   wins: {s['wins']}"
    )
    u = s["unlocks"]
    print(f"unlocks:  ev x{u['ev']}, move x{u['move']}, shiny x{u['shiny']}")
    owned = [p["items"][i] for i in range(len(p["items"])) if s["item_mask"] & (1 << i)]
    print(f"items:    {', '.join(owned)}")


def paste_team(card):
    s = card.status()
    data = urllib.parse.urlencode(
        {
            "title": f"{s['name'] or 'lrcmon'}'s team",
            "author": s["name"] or "lrcmon",
            "paste": cards.team_export(card.team()),
        }
    ).encode()
    try:
        with urllib.request.urlopen("https://pokepast.es/create", data, timeout=5) as r:
            print(f"view your team: {r.geturl()}")
    except Exception as e:
        print(f"(pokepast.es upload failed: {e})")


def setup(card):
    s = card.status()
    if not s["name"]:
        while True:
            name = input(
                f"trainer name (up to {cards.NAME_MAX} printable ASCII characters, "
                "permanent): "
            ).strip()
            if not name or (
                len(name) <= cards.NAME_MAX and name.isascii() and name.isprintable()
            ):
                break
            print(f"use at most {cards.NAME_MAX} printable ASCII characters")
        if name:
            card.set_name(name)
    if not s["hatched"]:
        print("\nhatching your team...\n")
        card.hatch()
    show_team(card)
    paste_team(card)
    while left := card.status()["rerolls"]:
        ans = input(
            f"\nreroll a slot? {left} left, gone forever once used "
            f"(0-5, enter to keep team): "
        ).strip()
        if not ans:
            break
        if not ans.isdigit() or int(ans) > 5:
            continue
        print(f"[{ans}] is now: {cards.describe_mon(card.reroll(int(ans)))}")
        paste_team(card)
    else:
        print("\nno rerolls left - this is your team now")
    print("\nsetup complete - battle with: ./lrc battle")


def username(status, taken):
    name = "".join(c for c in status["name"] if c.isalnum())
    if not name:
        name = f"Card{status['card_id'].hex().upper()}"
    if name.lower() in taken:
        name += status["card_id"].hex().upper()[:4]
    taken.add(name.lower())
    return name


def parse_command(parser, cmd, args):
    if cmd in ("setup", "status", "team", "pool", "battle"):
        if args:
            parser.error(f"{cmd} takes no arguments")
        return None
    if cmd == "items":
        if len(args) != 1:
            parser.error('items usage: items "ITEM,ITEM,-,-,-,-"')
        tokens = args[0].split(",")
        if len(tokens) > 6:
            parser.error("items accepts at most 6 entries")
        names = {name.lower(): i for i, name in enumerate(cards.pool()["items"])}
        assign = []
        for token in tokens:
            token = token.strip()
            if token in ("", "-"):
                assign.append(None)
                continue
            try:
                item = int(token) if token.isdecimal() else names[token.lower()]
            except (KeyError, ValueError):
                parser.error(f"unknown item {token!r}")
            if not 0 <= item < len(names):
                parser.error(f"item index out of range: {item}")
            assign.append(item)
        return assign + [None] * (6 - len(assign))
    if cmd == "claim":
        sizes = {"ev": 3, "move": 4, "shiny": 2}
        if not args or len(args) != sizes.get(args[0], -1):
            parser.error(
                "claim usage: claim ev MON STAT | claim move MON SLOT MOVE | "
                "claim shiny MON"
            )
        kind = args[0]
        try:
            mon = int(args[1])
            if kind == "ev":
                stat = args[2].lower()
                values = (
                    mon,
                    int(stat) if stat.isdecimal() else cards.STAT_NAMES.index(stat),
                )
            elif kind == "move":
                values = (mon, int(args[2]), int(args[3]))
            else:
                values = (mon,)
        except ValueError:
            parser.error("claim arguments must be valid indexes or stat names")
        limits = (6, 6) if kind == "ev" else (6, 4, 8) if kind == "move" else (6,)
        if any(not 0 <= value < limit for value, limit in zip(values, limits)):
            parser.error("claim argument out of range")
        return (kind, *values)
    parser.error(f"unknown command {cmd!r} (see --help)")


def battle(args):
    from driver import CardPlayer, run_battle
    from poke_env.ps_client import AccountConfiguration

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(name)s: %(message)s",
    )
    logging.getLogger("websockets").setLevel(logging.WARNING)

    transports = []
    players = []
    taken = set()
    try:
        for spec in (args.p1, args.p2):
            t = open_transport(spec)
            transports.append(t)
            card = MonCard(t)
            st = card.status()
            if not st["hatched"]:
                if not args.hatch:
                    raise SystemExit("card not hatched - run: ./lrc setup")
                card.hatch()
            players.append(
                CardPlayer(
                    card=card,
                    turn_delay=args.delay,
                    preview_delay=args.wait if not players else 0.0,
                    account_configuration=AccountConfiguration(
                        username(st, taken), None
                    ),
                    log_level=logging.DEBUG if args.verbose else logging.ERROR,
                )
            )
        print(
            f"\nwatch at http://localhost:8000 - the battle appears in the battle "
            f"list now and holds {args.wait:.0f}s before team preview\n"
        )
        asyncio.run(run_battle(players[0], players[1]))
    finally:
        for t in transports:
            t.close()


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--card", default="reader:0", help="reader:N or sim")
    ap.add_argument("--p1", default="sim")
    ap.add_argument("--p2", default="sim")
    ap.add_argument("--hatch", action="store_true", help="hatch unhatched cards first")
    ap.add_argument(
        "--delay",
        type=float,
        default=5.0,
        help="seconds per turn so spectators can follow (0 = instant)",
    )
    ap.add_argument(
        "--wait",
        type=float,
        default=10.0,
        help="seconds to hold before pick-4 so spectators can join",
    )
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("cmd", nargs="+")
    args = ap.parse_args()
    cmd, rest = args.cmd[0], args.cmd[1:]
    parsed = parse_command(ap, cmd, rest)

    if cmd == "battle":
        try:
            battle(args)
        except (OSError, RuntimeError) as e:
            raise SystemExit(e) from None
        return

    try:
        t = open_transport(args.card)
    except (OSError, RuntimeError) as e:
        raise SystemExit(e) from None
    card = MonCard(t)
    try:
        if cmd == "setup":
            setup(card)
        elif cmd == "status":
            show_status(card)
        elif cmd == "team":
            show_team(card)
        elif cmd == "pool":
            show_pool(card)
        elif cmd == "items":
            card.set_items(parsed)
            print("items assigned:\n")
            show_team(card)
        elif cmd == "claim":
            kind, *values = parsed
            if kind == "ev":
                card.apply_unlock_ev(*values)
            elif kind == "move":
                card.apply_unlock_move(*values)
            else:
                card.apply_unlock_shiny(*values)
            print("claimed!\n")
            show_team(card)
    except ApduError as e:
        raise SystemExit(
            f"the card said no: {e} "
            "(6985 = not allowed right now, 6A80 = bad arguments)"
        )
    except RuntimeError as e:
        raise SystemExit(e) from None
    finally:
        t.close()


if __name__ == "__main__":
    main()
