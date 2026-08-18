package hello;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

// The smallest useful Java Card applet, and a smoke test for your toolchain:
// build it with `make hello`, load it, then send CLA=0x80 INS=0x00 and read
// back "Hello, world!". This is the whole shape of an applet - install()
// registers it, process() answers APDUs - with none of the lrcmon machinery.
public class HelloWorld extends Applet {
    private static final byte[] GREETING = {
        'H', 'e', 'l', 'l', 'o', ',', ' ', 'w', 'o', 'r', 'l', 'd', '!'
    };

    public static void install(byte[] params, short offset, byte length) {
        new HelloWorld().register();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        byte[] buf = apdu.getBuffer();
        if (buf[ISO7816.OFFSET_CLA] != (byte) 0x80) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
        if (buf[ISO7816.OFFSET_INS] != (byte) 0x00) {
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
        Util.arrayCopyNonAtomic(GREETING, (short) 0, buf, (short) 0, (short) GREETING.length);
        apdu.setOutgoingAndSend((short) 0, (short) GREETING.length);
    }
}
