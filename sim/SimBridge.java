import com.licel.jcardsim.smartcardio.CardSimulator;
import com.licel.jcardsim.utils.AIDUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.smartcardio.CommandAPDU;

public class SimBridge {

    static final byte[] SIM_PARAMS = {0x00, 0x00, 0x01, 0x01};

    public static void main(String[] args) throws Exception {
        CardSimulator sim = new CardSimulator();
        sim.installApplet(AIDUtil.create("F04C5243020101"), lrc.strat.MonStrat.class);
        sim.installApplet(AIDUtil.create("F04C5243010101"), lrc.core.MonCore.class,
                SIM_PARAMS, (short) 0, (byte) SIM_PARAMS.length);
        sim.selectApplet(AIDUtil.create("F04C5243010101"));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                System.out.println(hex(sim.transmitCommand(
                        new CommandAPDU(bytes(line.trim()))).getBytes()));
            } catch (Exception e) {
                System.out.println("ERR " + e);
            }
            System.out.flush();
        }
    }

    static byte[] bytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
