package doom.ai;

import doom.play.Ticcmd;
import doom.system.Doomdef;

/** One legal player input, represented exactly as a vanilla four-byte demo command. */
public record Action(int forward, int side, int turn, int buttons) {
    public static final Action IDLE = new Action(0, 0, 0, 0);

    public Action {
        if (forward < -50 || forward > 50 || side < -40 || side > 40 || turn < -32768 || turn > 32767
                || (turn & 255) != 0 || buttons < 0 || buttons >= Doomdef.BT_SPECIAL) {
            throw new IllegalArgumentException("Input must be legal and demo-quantized");
        }
    }

    public Ticcmd command() {
        Ticcmd cmd = new Ticcmd();
        cmd.forwardmove = forward;
        cmd.sidemove = side;
        cmd.angleturn = turn;
        cmd.buttons = buttons;
        return cmd;
    }

    public int packed() {
        return (forward & 255) | ((side & 255) << 8) | (((turn >> 8) & 255) << 16) | (buttons << 24);
    }

    public static Action unpack(int packed) {
        return new Action((byte) packed, (byte) (packed >> 8), ((byte) (packed >> 16)) << 8,
                (packed >>> 24) & 255);
    }
}
