package lrc.iface;

import javacard.framework.Shareable;

// The contract between mon-core and your strategy applet. mon-core relays each
// battle APDU to request(); buf is a fresh global array per call
// (JCSystem.makeGlobalArray; object deletion is requested afterwards). The APDU
// buffer may not cross the applet firewall, and the JCRE forbids storing a
// global array reference in any field. The request occupies
// buf[inOff..inOff+inLen); write your answer at buf[outOff..] and return its
// length. outOff always equals inOff+inLen, so the answer never clobbers
// unread input. Two firewall rules that WILL bite you on the real card even
// though the simulator shrugs: your applet runs these calls while mon-core is
// the selected applet, so any CLEAR_ON_DESELECT transient you allocate throws
// SecurityException here: use CLEAR_ON_RESET; and you cannot touch the APDU
// buffer at all. Nothing is remembered for you between calls; persist fields
// in your applet if you want a memory that survives the reader.
//
// kind = KIND_TEAM_PREVIEW
//   in:  your own team, 6 mons x 11 bytes: species, nature, ability, teraType,
//        item (0xFF none), evMask (bit i = stat i maxed to 252; hp,atk,def,
//        spa,spd,spe), flags (bit0 shiny), 4 moves (slots in your species'
//        8-move pool), followed by the opponent summary, 6 x 4 bytes:
//        species, type1, type2 (0xFF none), baseStatTotal/4.
//   out: 4 bytes: the team slots (0-5) you bring, first two lead.
//
// kind = KIND_CHOOSE_TURN
//   in:  the ST_SIZE-byte state blob laid out by the ST_/AM_/MM_/BE_ offsets
//        below. Field flags: bit0 trick room up, bit1 you may still
//        terastallize. Weather: 0 none, 1 sun, 2 rain, 3 sand, 4 snow. Terrain:
//        0 none, 1 electric, 2 grassy, 3 misty, 4 psychic. Status: 0 none,
//        1 brn, 2 par, 3 psn, 4 tox, 5 slp, 6 frz. Actives in order: mine[0],
//        mine[1], theirs[0], theirs[1]; absent/fainted = all zero. Boosts are
//        six signed values (atk,def,spa,spd,spe,acc) stored as value+6 in
//        nibbles packed high-to-low into 3 bytes. HP is a 0-100 percent.
//        My moves are 2 mons x 4 x (poolSlot 0xFF=empty, type, basePower,
//        MMF_ flags); opp moves are revealed only, 2 x 4 x (type, basePower,
//        flags bit0 present / bit3 damaging); bench is 4 x (teamSlot
//        0xFF=empty, species, hpPct, canSwitchIn) in bring-list order.
//   out: 8 bytes: one ACT_ record per active slot: action, index (move slot
//        0-3 / bring-list index 0-3), target (foe 1|2, ally -1|-2, 0 none),
//        tera (1 = terastallize with this move).
//
// kind = KIND_FORCED_SWITCH
//   in:  needMask byte (bit i = active slot i must switch) + the state blob.
//   out: 2 bytes: bring-list index per slot, 0xFF where not asked.
//
// The host driver checks every answer and substitutes the first legal action
// (logging loudly) if yours is illegal, so a buggy brain never stalls a battle.
public interface LrcStrategy extends Shareable {

    byte KIND_TEAM_PREVIEW = 1;
    byte KIND_CHOOSE_TURN = 2;
    byte KIND_FORCED_SWITCH = 3;

    short ST_FIELD = 0;
    short ST_WEATHER = 1;
    short ST_TERRAIN = 2;
    short ST_TURN = 3;
    short ST_ACTIVE = 4;
    short ST_MY_MOVES = 52;
    short ST_OPP_MOVES = 84;
    short ST_BENCH = 108;
    short ST_OPP_LEFT = 124;
    short ST_SIZE = 125;

    short AM_PRESENT = 0;
    short AM_SPECIES = 1;
    short AM_TYPE1 = 2;
    short AM_TYPE2 = 3;
    short AM_HP_PCT = 4;
    short AM_STATUS = 5;
    short AM_BOOSTS = 6;
    short AM_TERAED = 9;
    short AM_SIZE = 12;

    short MM_POOL_SLOT = 0;
    short MM_TYPE = 1;
    short MM_BP = 2;
    short MM_FLAGS = 3;
    byte MMF_USABLE = 0x01;
    byte MMF_FOE_TARGET = 0x02;
    byte MMF_ALLY_TARGET = 0x04;
    byte MMF_DAMAGING = 0x08;

    short BE_TEAM_SLOT = 0;
    short BE_SPECIES = 1;
    short BE_HP_PCT = 2;
    short BE_CAN_SWITCH = 3;

    byte ACT_PASS = 0;
    byte ACT_MOVE = 1;
    byte ACT_SWITCH = 2;

    short request(byte kind, byte[] buf, short inOff, short inLen, short outOff);
}
