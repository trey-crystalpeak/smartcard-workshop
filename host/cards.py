import json
import platform as _platform
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CLA = 0x80

INS_STATUS = 0x01
INS_HATCH = 0x02
INS_REROLL = 0x03
INS_GET_TEAM = 0x04
INS_SET_ITEMS = 0x05
INS_APPLY_UNLOCK = 0x06
INS_SET_NAME = 0x07
INS_BATTLE_START = 0x10
INS_TEAM_PREVIEW = 0x11
INS_CHOOSE_TURN = 0x12
INS_FORCED_SWITCH = 0x13
INS_SIGN_LOSS = 0x20
INS_CLAIM_WIN = 0x21

CORE_AID = "F04C5243010101"

UNLOCK_EV, UNLOCK_MOVE, UNLOCK_SHINY = 1, 2, 3
UNLOCK_NAMES = {0: "item", 1: "ev", 2: "move", 3: "shiny"}

ACT_MOVE, ACT_SWITCH = 1, 2

MON_SIZE = 11
TEAM_SIZE = 6
NAME_MAX = 12
EV_LABELS = ["HP", "Atk", "Def", "SpA", "SpD", "Spe"]
STAT_NAMES = [s.lower() for s in EV_LABELS]

_pool = None


def host_platform():
    mach = _platform.machine().lower()
    return f"{_platform.system().lower()}-{'arm64' if mach == 'aarch64' else mach}"


def pool():
    global _pool
    if _pool is None:
        _pool = json.loads((ROOT / "data/pool.json").read_text())
    return _pool


def to_id(name):
    return "".join(c for c in name.lower() if c.isalnum())


def species_index():
    return {to_id(sp["name"]): i for i, sp in enumerate(pool()["species"])}


def parse_mon(b):
    return {
        "species": b[0],
        "nature": b[1],
        "ability": b[2],
        "tera": b[3],
        "item": None if b[4] == 0xFF else b[4],
        "ev_mask": b[5],
        "shiny": bool(b[6] & 1),
        "moves": list(b[7:11]),
    }


def parse_team(b):
    return [parse_mon(b[i * MON_SIZE : (i + 1) * MON_SIZE]) for i in range(TEAM_SIZE)]


def parse_status(b):
    return {
        "hatched": bool(b[0]),
        "rerolls": b[1],
        "wins": b[2],
        "unlocks": {"ev": b[3], "move": b[4], "shiny": b[5]},
        "item_mask": int.from_bytes(b[6:8], "big"),
        "card_id": b[8:12],
        "avatar": b[12],
        "name": b[14 : 14 + b[13]].decode("ascii", "replace"),
    }


def parse_actions(b):
    out = []
    for i in range(2):
        a = b[i * 4 : (i + 1) * 4]
        target = a[2] - 256 if a[2] > 127 else a[2]
        out.append(
            {"action": a[0], "index": a[1], "target": target, "tera": bool(a[3])}
        )
    return out


def describe_mon(mon):
    p = pool()
    sp = p["species"][mon["species"]]
    moves = ", ".join(sp["moves"][m]["name"] for m in mon["moves"])
    item = p["items"][mon["item"]] if mon["item"] is not None else "no item"
    evs = (
        "/".join(STAT_NAMES[i] for i in range(6) if mon["ev_mask"] & (1 << i)) or "none"
    )
    shiny = " [shiny]" if mon["shiny"] else ""
    return (
        f"{sp['name']}{shiny} (L{sp['level']}, {p['natures'][mon['nature']]}, "
        f"{sp['abilities'][mon['ability']]}, tera {p['types'][mon['tera']]}, {item}, "
        f"maxed EVs: {evs})\n    {moves}"
    )


def opp_summary(species_indices):
    p = pool()
    out = bytearray()
    for idx in species_indices[:6]:
        sp = p["species"][idx]
        t2 = sp["types"][1] if len(sp["types"]) > 1 else 0xFF
        out += bytes([idx, sp["types"][0], t2, min(255, sp["bst"] // 4)])
    return bytes(out) + bytes(24 - len(out))


def mon_export(mon):
    p = pool()
    sp = p["species"][mon["species"]]
    head = sp["name"]
    if mon["item"] is not None:
        head += f" @ {p['items'][mon['item']]}"
    lines = [
        head,
        f"Ability: {sp['abilities'][mon['ability']]}",
        f"Level: {sp['level']}",
        f"Tera Type: {p['types'][mon['tera']]}",
    ]
    if mon["shiny"]:
        lines.append("Shiny: Yes")
    evs = " / ".join(
        f"252 {EV_LABELS[i]}" for i in range(6) if mon["ev_mask"] & (1 << i)
    )
    if evs:
        lines.append(f"EVs: {evs}")
    lines.append(f"{p['natures'][mon['nature']]} Nature")
    lines += [f"- {sp['moves'][m]['name']}" for m in mon["moves"]]
    return "\n".join(lines)


def team_export(team):
    return "\n\n".join(mon_export(mon) for mon in team)


class ApduError(Exception):
    def __init__(self, sw, ins):
        self.sw = sw
        self.ins = ins
        super().__init__(f"INS {ins:02X} failed with SW {sw:04X}")


class SimCard:
    def __init__(self):
        self.proc = subprocess.Popen(
            [
                str(ROOT / "tools" / host_platform() / "jdk11/bin/java"),
                "-Dcom.licel.jcardsim.randomdata.secure=1",
                "-cp",
                f"{ROOT / 'tools/jcardsim.jar'}:{ROOT / 'card/build/simclasses'}",
                "SimBridge",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True,
        )

    def transmit(self, apdu):
        self.proc.stdin.write(apdu.hex() + "\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().strip()
        if not resp or resp.startswith("ERR"):
            raise RuntimeError(resp or "sim bridge died")
        raw = bytes.fromhex(resp)
        return raw[:-2], int.from_bytes(raw[-2:], "big")

    def close(self):
        self.proc.kill()
        self.proc.wait()


class PcscCard:
    def __init__(self, reader_index=0):
        try:
            from smartcard.System import readers
        except ImportError:
            raise RuntimeError(
                "pyscard is unavailable; install PC/SC dependencies and rerun "
                "./setup.sh"
            ) from None

        rs = readers()
        if len(rs) <= reader_index:
            raise RuntimeError(f"reader {reader_index} not found (have: {rs})")
        self.conn = rs[reader_index].createConnection()
        self.conn.connect()
        aid = bytes.fromhex(CORE_AID)
        _data, sw = self.transmit(bytes.fromhex("00A40400") + bytes([len(aid)]) + aid)
        if sw != 0x9000:
            self.conn.disconnect()
            raise RuntimeError(f"SELECT mon-core failed: {sw:04X}")

    def transmit(self, apdu):
        resp, sw1, sw2 = self.conn.transmit(list(apdu))
        return bytes(resp), (sw1 << 8) | sw2

    def close(self):
        self.conn.disconnect()


def open_transport(spec):
    if spec == "sim":
        return SimCard()
    if spec.startswith("reader:"):
        return PcscCard(int(spec.split(":", 1)[1]))
    raise SystemExit(f"bad card spec {spec!r} (want 'reader:N' or 'sim')")


class MonCard:
    def __init__(self, transport):
        self.t = transport

    def _apdu(self, ins, data=b"", p1=0, p2=0):
        cmd = bytes([CLA, ins, p1, p2])
        if data:
            cmd += bytes([len(data)]) + data
        resp, sw = self.t.transmit(cmd + b"\x00")
        if sw != 0x9000:
            raise ApduError(sw, ins)
        return resp

    def status(self):
        return parse_status(self._apdu(INS_STATUS))

    def hatch(self):
        return parse_team(self._apdu(INS_HATCH))

    def reroll(self, slot):
        return parse_mon(self._apdu(INS_REROLL, p1=slot))

    def team(self):
        return parse_team(self._apdu(INS_GET_TEAM))

    def set_name(self, name):
        self._apdu(INS_SET_NAME, name.encode("ascii"))

    def set_items(self, items):
        self._apdu(INS_SET_ITEMS, bytes(0xFF if i is None else i for i in items))

    def apply_unlock_ev(self, mon_slot, stat):
        self._apdu(INS_APPLY_UNLOCK, bytes([UNLOCK_EV, mon_slot, stat]))

    def apply_unlock_move(self, mon_slot, move_slot, pool_slot):
        self._apdu(
            INS_APPLY_UNLOCK, bytes([UNLOCK_MOVE, mon_slot, move_slot, pool_slot])
        )

    def apply_unlock_shiny(self, mon_slot):
        self._apdu(INS_APPLY_UNLOCK, bytes([UNLOCK_SHINY, mon_slot]))

    def battle_start(self, opp_id):
        return self._apdu(INS_BATTLE_START, opp_id)

    def team_preview(self, summary):
        return list(self._apdu(INS_TEAM_PREVIEW, summary))

    def choose_turn(self, state):
        return parse_actions(self._apdu(INS_CHOOSE_TURN, state))

    def forced_switch(self, need_mask, state):
        return list(self._apdu(INS_FORCED_SWITCH, bytes([need_mask]) + state))

    def sign_loss(self, winner_id, winner_nonce):
        return self._apdu(INS_SIGN_LOSS, winner_id + winner_nonce)

    def claim_win(self, loser_id, mac=None):
        r = self._apdu(INS_CLAIM_WIN, loser_id + (mac or b""))
        return (None if r[0] == 0xFF else r[0]), (None if r[1] == 0xFF else r[1])
