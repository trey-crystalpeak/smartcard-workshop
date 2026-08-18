package lrc.strat;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Shareable;
import javacard.security.RandomData;
import lrc.iface.LrcStrategy;

public class MonStrat extends Applet implements LrcStrategy {

    private final RandomData rng;
    private final byte[] r;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        MonStrat app = new MonStrat();
        if (bArray == null || bLength == 0 || bArray[bOffset] == 0) {
            app.register();
        } else {
            app.register(bArray, (short) (bOffset + 1), bArray[bOffset]);
        }
    }

    private MonStrat() {
        rng = RandomData.getInstance(RandomData.ALG_PSEUDO_RANDOM);
        r = JCSystem.makeTransientByteArray((short) 16, JCSystem.CLEAR_ON_RESET);
    }

    public Shareable getShareableInterfaceObject(AID clientAID, byte param) {
        return (param == 0) ? this : null;
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
    }

    public short request(byte kind, byte[] buf, short inOff, short inLen, short outOff) {
        switch (kind) {
            case KIND_TEAM_PREVIEW:
                return teamPreview(buf, inOff, outOff);
            case KIND_CHOOSE_TURN:
                return chooseTurn(buf, inOff, outOff);
            case KIND_FORCED_SWITCH:
                return forcedSwitch(buf, inOff, outOff);
            default:
                return 0;
        }
    }

    private short teamPreview(byte[] buf, short inOff, short outOff) {
        for (byte i = 0; i < 6; i++) {
            r[(short) (8 + i)] = i;
        }
        rng.generateData(r, (short) 0, (short) 4);
        for (byte i = 0; i < 4; i++) {
            byte j = (byte) (i + mod(r[i], (byte) (6 - i)));
            byte t = r[(short) (8 + i)];
            r[(short) (8 + i)] = r[(short) (8 + j)];
            r[(short) (8 + j)] = t;
        }
        for (byte i = 0; i < 4; i++) {
            buf[(short) (outOff + i)] = r[(short) (8 + i)];
        }
        return 4;
    }

    private short chooseTurn(byte[] buf, short inOff, short outOff) {
        for (byte slot = 0; slot < 2; slot++) {
            short act = (short) (outOff + slot * 4);
            short me = (short) (inOff + ST_ACTIVE + slot * AM_SIZE);
            buf[act] = ACT_PASS;
            buf[(short) (act + 1)] = 0;
            buf[(short) (act + 2)] = 0;
            buf[(short) (act + 3)] = 0;
            if (buf[(short) (me + AM_PRESENT)] == 0) {
                continue;
            }

            byte usable = 0;
            for (byte m = 0; m < 4; m++) {
                byte flags = buf[(short) (inOff + ST_MY_MOVES + slot * 16 + m * 4 + MM_FLAGS)];
                if ((flags & MMF_USABLE) != 0) {
                    r[(short) (8 + usable)] = m;
                    usable++;
                }
            }
            if (usable == 0) {
                byte bench = pickBench(buf, inOff, (byte) 0xFF);
                if (bench != (byte) 0xFF) {
                    buf[act] = ACT_SWITCH;
                    buf[(short) (act + 1)] = bench;
                }
                continue;
            }

            rng.generateData(r, (short) 0, (short) 4);
            byte move = r[(short) (8 + mod(r[0], usable))];
            byte flags = buf[(short) (inOff + ST_MY_MOVES + slot * 16 + move * 4 + MM_FLAGS)];
            buf[act] = ACT_MOVE;
            buf[(short) (act + 1)] = move;
            if ((flags & MMF_FOE_TARGET) != 0) {
                buf[(short) (act + 2)] = pickFoe(buf, inOff, r[1]);
            } else if ((flags & MMF_ALLY_TARGET) != 0) {
                byte other = (byte) (1 - slot);
                boolean partnerUp =
                    buf[(short) (inOff + ST_ACTIVE + other * AM_SIZE + AM_PRESENT)] != 0;
                buf[(short) (act + 2)] = (byte) (partnerUp ? -(other + 1) : -(slot + 1));
            }
            if ((buf[(short) (inOff + ST_FIELD)] & 0x02) != 0 && (r[2] & 7) == 0) {
                buf[(short) (act + 3)] = 1;
            }
        }
        return 8;
    }

    private short forcedSwitch(byte[] buf, short inOff, short outOff) {
        byte needMask = buf[inOff];
        short state = (short) (inOff + 1);
        byte first = (byte) 0xFF;
        for (byte slot = 0; slot < 2; slot++) {
            byte pick = (byte) 0xFF;
            if ((needMask & (short) (1 << slot)) != 0) {
                pick = pickBench(buf, state, first);
                if (pick != (byte) 0xFF) {
                    first = pick;
                }
            }
            buf[(short) (outOff + slot)] = pick;
        }
        return 2;
    }

    private byte pickFoe(byte[] buf, short inOff, byte rand) {
        boolean foe0 = buf[(short) (inOff + ST_ACTIVE + 2 * AM_SIZE + AM_PRESENT)] != 0;
        boolean foe1 = buf[(short) (inOff + ST_ACTIVE + 3 * AM_SIZE + AM_PRESENT)] != 0;
        if (foe0 && foe1) {
            return (byte) (((rand & 1) == 0) ? 1 : 2);
        }
        return (byte) (foe1 ? 2 : 1);
    }

    private byte pickBench(byte[] buf, short stateOff, byte taken) {
        for (byte i = 0; i < 4; i++) {
            short be = (short) (stateOff + ST_BENCH + i * 4);
            if (i != taken
                    && buf[(short) (be + BE_TEAM_SLOT)] != (byte) 0xFF
                    && buf[(short) (be + BE_CAN_SWITCH)] != 0) {
                return i;
            }
        }
        return (byte) 0xFF;
    }

    private static byte mod(byte v, byte n) {
        return (byte) ((v & 0x7F) % n);
    }
}
