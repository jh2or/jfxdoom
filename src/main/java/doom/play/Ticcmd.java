// Port of linuxdoom-1.10/d_ticcmd.h ticcmd_t (lines 36–44).
//
// Per-tick input sample for one player (single-player) or transmitted
// to peers (multiplayer). G_BuildTiccmd assembles each tic; the
// playsim consumes via Player.cmd.
//
// Field widths in the C are sub-int (char/short/byte) and fit on the
// wire — the Java port keeps them as int per CLAUDE.md §3 and trusts
// the source-side packing. Values still respect their original
// signedness: forwardmove / sidemove are signed-byte semantics
// (-128..127), angleturn is signed-short semantics, chatchar / buttons
// are unsigned-byte semantics.
package doom.play;

public final class Ticcmd {

    public int forwardmove;  // signed: *2048 to scale into momentum
    public int sidemove;     // signed: *2048 to scale into momentum
    public int angleturn;    // signed: <<16 for angle delta
    public int consistancy;  // checks for net game (unused in single-player)
    public int chatchar;     // unsigned byte
    public int buttons;      // unsigned byte — BT_* bits

    public Ticcmd() {
    }
}
