package lrc.core;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Shareable;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.KeyBuilder;
import javacard.security.RandomData;
import javacard.security.Signature;
import lrc.iface.LrcStrategy;

public class MonCore extends Applet {

    static final byte CLA = (byte) 0x80;

    static final byte INS_STATUS = 0x01;
    static final byte INS_HATCH = 0x02;
    static final byte INS_REROLL = 0x03;
    static final byte INS_GET_TEAM = 0x04;
    static final byte INS_SET_ITEMS = 0x05;
    static final byte INS_APPLY_UNLOCK = 0x06;
    static final byte INS_SET_NAME = 0x07;
    static final byte INS_BATTLE_START = 0x10;
    static final byte INS_TEAM_PREVIEW = 0x11;
    static final byte INS_CHOOSE_TURN = 0x12;
    static final byte INS_FORCED_SWITCH = 0x13;
    static final byte INS_SIGN_LOSS = 0x20;
    static final byte INS_CLAIM_WIN = 0x21;

    static final short SW_NO_STRATEGY = (short) 0x6A82;

    static final byte AVATAR = 5;
    static final byte TEAM_SIZE = 6;
    static final byte MON_SIZE = 11;
    static final byte NAME_MAX = 12;

    static final byte M_SPECIES = 0;
    static final byte M_NATURE = 1;
    static final byte M_ABILITY = 2;
    static final byte M_TERA = 3;
    static final byte M_ITEM = 4;
    static final byte M_EVMASK = 5;
    static final byte M_FLAGS = 6;
    static final byte M_MOVES = 7;

    static final byte UNLOCK_ITEM = 0;
    static final byte UNLOCK_EV = 1;
    static final byte UNLOCK_MOVE = 2;
    static final byte UNLOCK_SHINY = 3;

    static final byte[] STRAT_AID = {(byte) 0xF0, 0x4C, 0x52, 0x43, 0x02, 0x01, 0x01};

    // Deliberately soft security: every card ships this same AES key in source.
    // A loss is signed as MAC(winnerId||loserId||winnerNonce) over one AES block;
    // the fresh nonce kills replays. Forging the MAC on a laptop is the intended
    // first cheat. Cards installed with param 0x01 are "simulated" and refuse to
    // sign, so farming a simulator earns wins but no unlocks.
    static final byte[] KEY = {0x4C, 0x52, 0x43, 0x6D, 0x6F, 0x6E, 0x21, 0x30,
                               0x77, 0x35, 0x64, 0x21, 0x70, 0x6B, 0x6D, 0x6E};

    private final byte[] team = new byte[TEAM_SIZE * MON_SIZE];
    private boolean hatched;
    private byte rerolls = 6;
    private byte wins;
    private byte pendingEv;
    private byte pendingMove;
    private byte pendingShiny;
    private short itemMask = 0x003F;
    private final byte[] cardId = new byte[4];
    private final byte[] name = new byte[NAME_MAX];
    private byte nameLen;
    private final boolean simulated;

    private boolean sessionOpen;
    private final byte[] oppId = new byte[4];
    private final byte[] nonce = new byte[8];

    private final RandomData rng;
    private final Signature macSig;
    private final AESKey macKey;
    private final byte[] scratch;
    private final boolean canDelete;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        byte aidLen = 0;
        boolean sim = false;
        if (bArray != null && bLength > 0) {
            aidLen = bArray[bOffset];
            short off = (short) (bOffset + 1 + aidLen);
            byte privLen = bArray[off];
            off = (short) (off + 1 + privLen);
            if (bArray[off] > 0 && bArray[(short) (off + 1)] == 1) {
                sim = true;
            }
        }
        MonCore app = new MonCore(sim);
        if (aidLen > 0) {
            app.register(bArray, (short) (bOffset + 1), aidLen);
        } else {
            app.register();
        }
    }

    private MonCore(boolean sim) {
        simulated = sim;
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        macKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        macKey.setKey(KEY, (short) 0);
        macSig = Signature.getInstance(Signature.ALG_AES_MAC_128_NOPAD, false);
        scratch = JCSystem.makeTransientByteArray((short) 32, JCSystem.CLEAR_ON_DESELECT);
        canDelete = JCSystem.isObjectDeletionSupported();
        rng.generateData(cardId, (short) 0, (short) 4);
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        byte[] buf = apdu.getBuffer();
        if (buf[ISO7816.OFFSET_CLA] != CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
        switch (buf[ISO7816.OFFSET_INS]) {
            case INS_STATUS:        status(apdu); break;
            case INS_HATCH:         hatch(apdu); break;
            case INS_REROLL:        reroll(apdu); break;
            case INS_GET_TEAM:      getTeam(apdu); break;
            case INS_SET_ITEMS:     setItems(apdu); break;
            case INS_APPLY_UNLOCK:  applyUnlock(apdu); break;
            case INS_SET_NAME:      setName(apdu); break;
            case INS_BATTLE_START:  battleStart(apdu); break;
            case INS_TEAM_PREVIEW:  teamPreview(apdu); break;
            case INS_CHOOSE_TURN:   strategyPassthrough(apdu, LrcStrategy.KIND_CHOOSE_TURN); break;
            case INS_FORCED_SWITCH: strategyPassthrough(apdu, LrcStrategy.KIND_FORCED_SWITCH); break;
            case INS_SIGN_LOSS:     signLoss(apdu); break;
            case INS_CLAIM_WIN:     claimWin(apdu); break;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    private void status(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        buf[0] = hatched ? (byte) 1 : (byte) 0;
        buf[1] = rerolls;
        buf[2] = wins;
        buf[3] = pendingEv;
        buf[4] = pendingMove;
        buf[5] = pendingShiny;
        Util.setShort(buf, (short) 6, itemMask);
        Util.arrayCopyNonAtomic(cardId, (short) 0, buf, (short) 8, (short) 4);
        buf[12] = AVATAR;
        buf[13] = nameLen;
        Util.arrayCopyNonAtomic(name, (short) 0, buf, (short) 14, nameLen);
        apdu.setOutgoingAndSend((short) 0, (short) (14 + nameLen));
    }

    private void setName(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (nameLen != 0) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (lc < 1 || lc > NAME_MAX) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        for (short i = 0; i < lc; i++) {
            byte c = buf[(short) (ISO7816.OFFSET_CDATA + i)];
            if (c < 0x20 || c > 0x7E) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
        }
        JCSystem.beginTransaction();
        Util.arrayCopy(buf, ISO7816.OFFSET_CDATA, name, (short) 0, lc);
        nameLen = (byte) lc;
        JCSystem.commitTransaction();
    }

    private void hatch(APDU apdu) {
        if (hatched) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        JCSystem.beginTransaction();
        for (byte slot = 0; slot < TEAM_SIZE; slot++) {
            team[(short) (slot * MON_SIZE + M_SPECIES)] = (byte) 0xFF;
        }
        for (byte slot = 0; slot < TEAM_SIZE; slot++) {
            rollMon(slot);
        }
        hatched = true;
        JCSystem.commitTransaction();
        sendTeam(apdu);
    }

    private void reroll(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte slot = buf[ISO7816.OFFSET_P1];
        if (slot < 0 || slot >= TEAM_SIZE) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        if (!hatched || rerolls == 0) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        JCSystem.beginTransaction();
        rollMon(slot);
        rerolls--;
        JCSystem.commitTransaction();
        Util.arrayCopyNonAtomic(team, (short) (slot * MON_SIZE), buf, (short) 0, MON_SIZE);
        apdu.setOutgoingAndSend((short) 0, MON_SIZE);
    }

    private void getTeam(APDU apdu) {
        if (!hatched) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        sendTeam(apdu);
    }

    private void sendTeam(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        Util.arrayCopyNonAtomic(team, (short) 0, buf, (short) 0, (short) (TEAM_SIZE * MON_SIZE));
        apdu.setOutgoingAndSend((short) 0, (short) (TEAM_SIZE * MON_SIZE));
    }

    private void rollMon(byte slot) {
        short off = (short) (slot * MON_SIZE);
        rng.generateData(scratch, (short) 0, (short) 11);
        byte species = (byte) (scratch[0] & 0x3F);
        while (speciesTaken(species, slot)) {
            species = (byte) ((species + 1) & 0x3F);
        }
        team[(short) (off + M_SPECIES)] = species;
        team[(short) (off + M_NATURE)] = (byte) mod(scratch[1], (short) 25);
        team[(short) (off + M_ABILITY)] = (byte) mod(scratch[2], PoolData.ABILITY_COUNT[species]);
        team[(short) (off + M_TERA)] = ((scratch[3] & 1) == 0)
                ? PoolData.TERA_A[species] : PoolData.TERA_B[species];
        team[(short) (off + M_ITEM)] = (byte) 0xFF;
        team[(short) (off + M_EVMASK)] = 0;
        team[(short) (off + M_FLAGS)] = 0;

        for (byte i = 0; i < 8; i++) {
            scratch[(short) (16 + i)] = i;
        }
        for (byte i = 0; i < 7; i++) {
            byte j = (byte) (i + mod(scratch[(short) (4 + i)], (short) (8 - i)));
            byte t = scratch[(short) (16 + i)];
            scratch[(short) (16 + i)] = scratch[(short) (16 + j)];
            scratch[(short) (16 + j)] = t;
        }
        byte dmg = PoolData.DMG_MASK[species];
        byte n = 0;
        for (byte i = 0; i < 8 && n < 2; i++) {
            byte mv = scratch[(short) (16 + i)];
            if ((dmg & bit(mv)) != 0) {
                team[(short) (off + M_MOVES + n)] = mv;
                scratch[(short) (16 + i)] = (byte) 0xFF;
                n++;
            }
        }
        for (byte i = 0; i < 8 && n < 4; i++) {
            byte mv = scratch[(short) (16 + i)];
            if (mv != (byte) 0xFF) {
                team[(short) (off + M_MOVES + n)] = mv;
                n++;
            }
        }
    }

    private void setItems(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (!hatched) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (lc != TEAM_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short seen = 0;
        for (byte i = 0; i < TEAM_SIZE; i++) {
            byte item = buf[(short) (ISO7816.OFFSET_CDATA + i)];
            if (item == (byte) 0xFF) {
                continue;
            }
            short itemBit = bit(item);
            if (item < 0 || item >= PoolData.ITEM_COUNT
                    || (itemMask & itemBit) == 0 || (seen & itemBit) != 0) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
            seen |= itemBit;
        }
        JCSystem.beginTransaction();
        for (byte i = 0; i < TEAM_SIZE; i++) {
            team[(short) (i * MON_SIZE + M_ITEM)] = buf[(short) (ISO7816.OFFSET_CDATA + i)];
        }
        JCSystem.commitTransaction();
    }

    private void applyUnlock(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (lc < 2) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        byte type = buf[ISO7816.OFFSET_CDATA];
        byte slot = buf[(short) (ISO7816.OFFSET_CDATA + 1)];
        byte avail = (type == UNLOCK_EV) ? pendingEv
                : (type == UNLOCK_MOVE) ? pendingMove
                : (type == UNLOCK_SHINY) ? pendingShiny : 0;
        if (avail == 0) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (slot < 0 || slot >= TEAM_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        short off = (short) (slot * MON_SIZE);
        byte stat = buf[(short) (ISO7816.OFFSET_CDATA + 2)];
        byte poolSlot = buf[(short) (ISO7816.OFFSET_CDATA + 3)];
        if (type == UNLOCK_EV) {
            byte evs = team[(short) (off + M_EVMASK)];
            if (lc != 3 || stat < 0 || stat >= 6
                    || (evs & bit(stat)) != 0 || (byte) (evs & (evs - 1)) != 0) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
        } else if (type == UNLOCK_MOVE) {
            boolean dup = false;
            for (byte i = 0; i < 4; i++) {
                if (team[(short) (off + M_MOVES + i)] == poolSlot) {
                    dup = true;
                }
            }
            if (lc != 4 || stat < 0 || stat >= 4 || poolSlot < 0 || poolSlot >= 8 || dup) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
        } else if (lc != 2 || (team[(short) (off + M_FLAGS)] & 1) != 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        JCSystem.beginTransaction();
        if (type == UNLOCK_EV) {
            team[(short) (off + M_EVMASK)] |= (byte) bit(stat);
            pendingEv--;
        } else if (type == UNLOCK_MOVE) {
            team[(short) (off + M_MOVES + stat)] = poolSlot;
            pendingMove--;
        } else {
            team[(short) (off + M_FLAGS)] |= 1;
            pendingShiny--;
        }
        JCSystem.commitTransaction();
    }

    private void battleStart(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (!hatched) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (lc != 4) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, oppId, (short) 0, (short) 4);
        rng.generateData(nonce, (short) 0, (short) 8);
        sessionOpen = true;
        Util.arrayCopyNonAtomic(nonce, (short) 0, buf, (short) 0, (short) 8);
        apdu.setOutgoingAndSend((short) 0, (short) 8);
    }

    private void teamPreview(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        requireBattle();
        if (lc != 24) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short teamLen = (short) (TEAM_SIZE * MON_SIZE);
        byte[] shared = sharedArray();
        Util.arrayCopyNonAtomic(team, (short) 0, shared, (short) 0, teamLen);
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, shared, teamLen, lc);
        callStrategy(apdu, LrcStrategy.KIND_TEAM_PREVIEW, (short) (teamLen + lc), shared);
    }

    private void strategyPassthrough(APDU apdu, byte kind) {
        short lc = apdu.setIncomingAndReceive();
        requireBattle();
        short expected = (kind == LrcStrategy.KIND_CHOOSE_TURN)
                ? LrcStrategy.ST_SIZE : (short) (LrcStrategy.ST_SIZE + 1);
        if (lc != expected) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        byte[] shared = sharedArray();
        Util.arrayCopyNonAtomic(apdu.getBuffer(), ISO7816.OFFSET_CDATA, shared, (short) 0, lc);
        callStrategy(apdu, kind, lc, shared);
    }

    private byte[] sharedArray() {
        return (byte[]) JCSystem.makeGlobalArray(JCSystem.ARRAY_TYPE_BYTE, (short) 133);
    }

    private void requireBattle() {
        if (!hatched || !sessionOpen) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
    }

    private void callStrategy(APDU apdu, byte kind, short inLen, byte[] shared) {
        try {
            AID aid = JCSystem.lookupAID(STRAT_AID, (short) 0, (byte) STRAT_AID.length);
            Shareable so = (aid == null) ? null
                    : JCSystem.getAppletShareableInterfaceObject(aid, (byte) 0);
            if (so == null) {
                ISOException.throwIt(SW_NO_STRATEGY);
            }
            short outLen = ((LrcStrategy) so).request(kind, shared, (short) 0, inLen, inLen);
            short expected = (short) ((kind == LrcStrategy.KIND_TEAM_PREVIEW) ? 4
                    : (kind == LrcStrategy.KIND_CHOOSE_TURN) ? 8 : 2);
            if (outLen != expected) {
                ISOException.throwIt(ISO7816.SW_UNKNOWN);
            }
            Util.arrayCopyNonAtomic(shared, inLen, apdu.getBuffer(), (short) 0, outLen);
            apdu.setOutgoingAndSend((short) 0, outLen);
        } finally {
            if (canDelete) {
                JCSystem.requestObjectDeletion();
            }
        }
    }

    private void signLoss(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (!sessionOpen || simulated) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (lc != 12) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (Util.arrayCompare(buf, ISO7816.OFFSET_CDATA, oppId, (short) 0, (short) 4) != 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, scratch, (short) 0, (short) 4);
        Util.arrayCopyNonAtomic(cardId, (short) 0, scratch, (short) 4, (short) 4);
        Util.arrayCopyNonAtomic(buf, (short) (ISO7816.OFFSET_CDATA + 4), scratch, (short) 8, (short) 8);
        macSig.init(macKey, Signature.MODE_SIGN);
        short macLen = macSig.sign(scratch, (short) 0, (short) 16, buf, (short) 0);
        sessionOpen = false;
        apdu.setOutgoingAndSend((short) 0, macLen);
    }

    private void claimWin(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive();
        if (!sessionOpen) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        if (lc != 4 && lc != 20) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (Util.arrayCompare(buf, ISO7816.OFFSET_CDATA, oppId, (short) 0, (short) 4) != 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        if (lc == 4) {
            JCSystem.beginTransaction();
            wins++;
            sessionOpen = false;
            JCSystem.commitTransaction();
            buf[0] = (byte) 0xFF;
            buf[1] = (byte) 0xFF;
            apdu.setOutgoingAndSend((short) 0, (short) 2);
            return;
        }
        Util.arrayCopyNonAtomic(cardId, (short) 0, scratch, (short) 0, (short) 4);
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, scratch, (short) 4, (short) 4);
        Util.arrayCopyNonAtomic(nonce, (short) 0, scratch, (short) 8, (short) 8);
        macSig.init(macKey, Signature.MODE_VERIFY);
        if (!macSig.verify(scratch, (short) 0, (short) 16,
                buf, (short) (ISO7816.OFFSET_CDATA + 4), (short) 16)) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        rng.generateData(scratch, (short) 0, (short) 2);
        byte type = (byte) (scratch[0] & 3);
        byte grantedItem = (byte) 0xFF;
        if (type == UNLOCK_ITEM && itemMask == (short) ((1 << PoolData.ITEM_COUNT) - 1)) {
            type = UNLOCK_EV;
        }
        JCSystem.beginTransaction();
        wins++;
        if (type == UNLOCK_ITEM) {
            byte start = (byte) mod(scratch[1], PoolData.ITEM_COUNT);
            for (byte i = 0; i < PoolData.ITEM_COUNT; i++) {
                byte idx = (byte) mod((byte) (start + i), PoolData.ITEM_COUNT);
                if ((itemMask & bit(idx)) == 0) {
                    itemMask |= bit(idx);
                    grantedItem = idx;
                    break;
                }
            }
        } else if (type == UNLOCK_EV) {
            pendingEv++;
        } else if (type == UNLOCK_MOVE) {
            pendingMove++;
        } else {
            pendingShiny++;
        }
        sessionOpen = false;
        JCSystem.commitTransaction();
        buf[0] = type;
        buf[1] = grantedItem;
        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }

    private boolean speciesTaken(byte species, byte slot) {
        for (byte j = 0; j < TEAM_SIZE; j++) {
            if (j != slot && team[(short) (j * MON_SIZE + M_SPECIES)] == species) {
                return true;
            }
        }
        return false;
    }

    private static short bit(byte i) {
        return (short) (1 << i);
    }

    private static short mod(byte v, short n) {
        return (short) ((v & 0x7F) % n);
    }
}
