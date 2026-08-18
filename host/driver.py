import logging
import time

import cards
from cards import ApduError
from poke_env.battle import Field
from poke_env.player import Player
from poke_env.player.battle_order import DoubleBattleOrder, PassBattleOrder

log = logging.getLogger("lrcmon")


class FormatStr(str):
    def __eq__(self, other):
        return str(other) in (str(self), str(self).split("@@@")[0])

    def __ne__(self, other):
        return not self.__eq__(other)

    def __hash__(self):
        return hash(str(self))


FORMAT = FormatStr("gen9doublescustomgame@@@Picked Team Size = 4")

AVATARS = [
    "red",
    "ethan",
    "lucas",
    "dawn",
    "hilbert",
    "hilda",
    "nate",
    "rosa",
    "calem",
    "serena",
    "elio",
    "selene",
    "victor",
    "gloria",
]
WEATHER_IDS = {
    "SUNNYDAY": 1,
    "DESOLATELAND": 1,
    "RAINDANCE": 2,
    "PRIMORDIALSEA": 2,
    "SANDSTORM": 3,
    "SNOW": 4,
    "SNOWSCAPE": 4,
    "HAIL": 4,
}
TERRAIN_IDS = {
    "ELECTRIC_TERRAIN": 1,
    "GRASSY_TERRAIN": 2,
    "MISTY_TERRAIN": 3,
    "PSYCHIC_TERRAIN": 4,
}
STATUS_IDS = {"BRN": 1, "PAR": 2, "PSN": 3, "TOX": 4, "SLP": 5, "FRZ": 6}
BOOST_KEYS = ["atk", "def", "spa", "spd", "spe", "accuracy"]
TYPE_IDS = {t.lower(): i for i, t in enumerate(cards.pool()["types"])}


def hp_pct(pkm):
    return max(0, min(100, round(pkm.current_hp_fraction * 100)))


class CardPlayer(Player):
    def __init__(self, card, turn_delay=0.0, preview_delay=0.0, **kwargs):
        self.card = card
        self.turn_delay = turn_delay
        self.preview_delay = preview_delay
        self.card_status = card.status()
        self.card_team = card.team()
        self.picks = [0, 1, 2, 3]
        self._species_idx = cards.species_index()
        self._slot_by_species = {
            cards.to_id(cards.pool()["species"][mon["species"]]["name"]): s
            for s, mon in enumerate(self.card_team)
        }
        kwargs.setdefault("avatar", AVATARS[self.card_status["avatar"] % len(AVATARS)])
        kwargs.setdefault("start_timer_on_battle_start", True)
        super().__init__(
            team=cards.team_export(self.card_team), battle_format=FORMAT, **kwargs
        )

    def _lookup(self, table, pkm):
        return table.get(
            cards.to_id(pkm.species), table.get(cards.to_id(pkm.base_species), 0)
        )

    def _my_pokemon(self, battle, card_slot):
        return next(
            (
                p
                for p in battle.team.values()
                if self._lookup(self._slot_by_species, p) == card_slot
            ),
            None,
        )

    def teampreview(self, battle):
        if self.preview_delay:
            time.sleep(self.preview_delay)
        summary = cards.opp_summary(
            [
                self._lookup(self._species_idx, p)
                for p in battle.teampreview_opponent_team
            ]
        )
        try:
            picks = self.card.team_preview(summary)
        except Exception as e:
            log.warning(
                "%s: team preview APDU failed (%s), bringing first 4", self.username, e
            )
            picks = [0, 1, 2, 3]
        if (
            len(picks) != 4
            or len(set(picks)) != 4
            or any(not 0 <= s < 6 for s in picks)
        ):
            log.warning(
                "%s: illegal preview picks %s, bringing first 4", self.username, picks
            )
            picks = [0, 1, 2, 3]
        self.picks = picks
        return "/team " + "".join(str(s + 1) for s in picks)

    def _active(self, pkm):
        if pkm is None or pkm.fainted:
            return bytes(12)
        types = [t for t in (pkm.type_1, pkm.type_2) if t is not None]
        b = [max(-6, min(6, pkm.boosts.get(k, 0))) + 6 for k in BOOST_KEYS]
        return bytes(
            [
                1,
                self._lookup(self._species_idx, pkm),
                TYPE_IDS.get(types[0].name.lower(), 0) if types else 0,
                TYPE_IDS.get(types[1].name.lower(), 0) if len(types) > 1 else 0xFF,
                hp_pct(pkm),
                STATUS_IDS.get(pkm.status.name, 0) if pkm.status else 0,
                (b[0] << 4) | b[1],
                (b[2] << 4) | b[3],
                (b[4] << 4) | b[5],
                1 if pkm.is_terastallized else 0,
                0,
                0,
            ]
        )

    def _my_moves(self, battle, i):
        pkm = battle.active_pokemon[i]
        if pkm is None or pkm.fainted:
            return bytes([0xFF, 0, 0, 0]) * 4
        mon = self.card_team[self._lookup(self._slot_by_species, pkm)]
        sp = cards.pool()["species"][mon["species"]]
        avail = (
            set()
            if any(battle.force_switch)
            else {m.id for m in battle.available_moves[i]}
        )
        out = bytearray()
        for slot in mon["moves"]:
            mv = sp["moves"][slot]
            out += bytes(
                [
                    slot,
                    mv["type"],
                    min(255, mv["bp"]),
                    (1 if cards.to_id(mv["name"]) in avail else 0)
                    | (2 if mv["targetClass"] == 1 else 0)
                    | (4 if mv["targetClass"] == 2 else 0)
                    | (8 if mv["cat"] != "Status" else 0),
                ]
            )
        return bytes(out)

    def _opp_moves(self, battle, j):
        pkm = battle.opponent_active_pokemon[j]
        out = bytearray()
        if pkm is not None and not pkm.fainted:
            for mv in list(pkm.moves.values())[:4]:
                out += bytes(
                    [
                        TYPE_IDS.get(mv.type.name.lower(), 0) if mv.type else 0,
                        min(255, int(mv.base_power)),
                        1 | (8 if mv.category.name != "STATUS" else 0),
                    ]
                )
        return bytes(out) + bytes(12 - len(out))

    def _bench(self, battle):
        avail = {p.name for lst in battle.available_switches for p in lst}
        out = bytearray()
        for card_slot in self.picks:
            pkm = self._my_pokemon(battle, card_slot)
            if pkm is None:
                out += bytes([0xFF, 0, 0, 0])
            else:
                out += bytes(
                    [
                        card_slot,
                        self._lookup(self._species_idx, pkm),
                        hp_pct(pkm),
                        1 if pkm.name in avail else 0,
                    ]
                )
        return bytes(out)

    def build_state(self, battle):
        out = bytearray()
        out.append(
            (1 if Field.TRICK_ROOM in battle.fields else 0)
            | (2 if any(battle.can_tera) else 0)
        )
        out.append(next((WEATHER_IDS.get(w.name, 0) for w in battle.weather), 0))
        out.append(
            next(
                (TERRAIN_IDS[f.name] for f in battle.fields if f.name in TERRAIN_IDS), 0
            )
        )
        out.append(min(255, battle.turn))
        for pkm in (*battle.active_pokemon, *battle.opponent_active_pokemon):
            out += self._active(pkm)
        out += self._my_moves(battle, 0) + self._my_moves(battle, 1)
        out += self._opp_moves(battle, 0) + self._opp_moves(battle, 1)
        out += self._bench(battle)
        out.append(max(0, 4 - sum(p.fainted for p in battle.opponent_team.values())))
        assert len(out) == 125
        return bytes(out)

    def _fallback(self, battle, i, first=None):
        for order in battle.valid_orders[i]:
            if first is None or DoubleBattleOrder.join_orders([first], [order]):
                return order
        return PassBattleOrder()

    def _move_order(self, battle, i, act):
        pkm = battle.active_pokemon[i]
        if pkm is None or pkm.fainted or not 0 <= act["index"] < 4:
            return None
        mon = self.card_team[self._lookup(self._slot_by_species, pkm)]
        mv = cards.pool()["species"][mon["species"]]["moves"][
            mon["moves"][act["index"]]
        ]
        mv_id = cards.to_id(mv["name"])
        move = next((m for m in battle.available_moves[i] if m.id == mv_id), None)
        if move is None:
            return None
        target = 0
        if mv["targetClass"] == 1:
            targets = [
                t for t in battle.get_possible_showdown_targets(move, pkm) if t > 0
            ]
            if not targets:
                return None
            target = act["target"] if act["target"] in targets else targets[0]
        elif mv["targetClass"] == 2:
            targets = [
                t for t in battle.get_possible_showdown_targets(move, pkm) if t < 0
            ]
            if not targets:
                return None
            target = act["target"] if act["target"] in targets else targets[0]
        return self.create_order(
            move,
            move_target=target,
            terastallize=act["tera"] and battle.can_tera[i],
        )

    def _switch_order(self, battle, i, act):
        if not 0 <= act["index"] < 4:
            return None
        pkm = self._my_pokemon(battle, self.picks[act["index"]])
        if pkm is None or pkm not in battle.available_switches[i]:
            return None
        return self.create_order(pkm)

    def choose_move(self, battle):
        if self.turn_delay:
            time.sleep(self.turn_delay)
        try:
            if any(battle.force_switch):
                return self._forced_switches(battle)
            actions = self.card.choose_turn(self.build_state(battle))
        except Exception as e:
            log.warning("%s: card turn failed (%s), using default", self.username, e)
            return self.choose_default_move()

        orders = []
        for i, act in enumerate(actions):
            if battle.active_pokemon[i] is None or battle.active_pokemon[i].fainted:
                orders.append(PassBattleOrder())
                continue
            order = None
            if act["action"] == cards.ACT_MOVE:
                order = self._move_order(battle, i, act)
            elif act["action"] == cards.ACT_SWITCH:
                order = self._switch_order(battle, i, act)
            if (
                order is not None
                and i
                and not DoubleBattleOrder.join_orders([orders[0]], [order])
            ):
                order = None
            if order is None:
                order = self._fallback(battle, i, orders[0] if i else None)
                log.warning(
                    "%s slot %d: card chose illegally (%s), using %r",
                    self.username,
                    i,
                    act,
                    order.message[8:],
                )
            orders.append(order)
        return DoubleBattleOrder(*orders)

    def _forced_switches(self, battle):
        mask = sum(1 << i for i, f in enumerate(battle.force_switch) if f)
        try:
            picks = self.card.forced_switch(mask, self.build_state(battle))
            if len(picks) != 2:
                raise ValueError(f"expected 2 picks, got {len(picks)}")
        except Exception as e:
            log.warning(
                "%s: forced switch APDU failed (%s), using default", self.username, e
            )
            return self.choose_default_move()
        orders = []
        used = set()
        for i, forced in enumerate(battle.force_switch):
            if not forced:
                orders.append(PassBattleOrder())
                continue
            order = None
            if picks[i] != 0xFF and 0 <= picks[i] < 4:
                pkm = self._my_pokemon(battle, self.picks[picks[i]])
                nick = pkm.name if pkm else None
                if (
                    nick
                    and nick not in used
                    and nick in {p.name for p in battle.available_switches[i]}
                ):
                    order = self.create_order(pkm)
                    used.add(nick)
            if order is None:
                avail = [p for p in battle.available_switches[i] if p.name not in used]
                if avail:
                    order = self.create_order(avail[0])
                    used.add(avail[0].name)
                    log.warning(
                        "%s slot %d: illegal forced-switch pick %s, using %r",
                        self.username,
                        i,
                        picks[i],
                        order.message[8:],
                    )
                else:
                    order = PassBattleOrder()
            orders.append(order)
        return DoubleBattleOrder(*orders)


async def run_battle(player_a, player_b):
    id_a = player_a.card_status["card_id"]
    id_b = player_b.card_status["card_id"]
    nonce_a = player_a.card.battle_start(id_b)
    nonce_b = player_b.card.battle_start(id_a)

    await player_a.battle_against(player_b, n_battles=1)

    battle = list(player_a.battles.values())[-1]
    if battle.won is None:
        print("\nThe battle ended in a tie. No unlock.")
        return

    winner, loser = (player_a, player_b) if battle.won else (player_b, player_a)
    winner_nonce = nonce_a if winner is player_a else nonce_b
    mac = None
    try:
        mac = loser.card.sign_loss(winner.card_status["card_id"], winner_nonce)
    except ApduError:
        pass
    try:
        unlock, item = winner.card.claim_win(loser.card_status["card_id"], mac)
    except ApduError:
        unlock, item = winner.card.claim_win(loser.card_status["card_id"])

    if unlock is None:
        print(f"\n{winner.username} wins! (loss unsigned - win recorded, no unlock)")
    else:
        got = cards.UNLOCK_NAMES[unlock]
        if item is not None:
            got += f" -> {cards.pool()['items'][item]}"
        print(f"\n{winner.username} wins and unlocks: {got}")
        print("(apply ev/move/shiny unlocks with: ./lrc claim)")
