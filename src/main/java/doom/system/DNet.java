// Port of linuxdoom-1.10/d_net.c — single-player stub.
//
// d_net.c (~600 lines) implements peer-to-peer netgame protocol:
// per-tic ticcmd_t exchange, lag compensation (BACKUPTICS), and
// console-input pumping (NetUpdate). The Java port runs in single-
// player mode for now, so D_QuitNetGame is a no-op, NetUpdate just
// pumps local input (deferred — the IVideo / m_menu layer drives
// it directly), and TryRunTics will land alongside the full game-
// loop port.
package doom.system;

public final class DNet {

    // d_net.c — D_QuitNetGame. Send-end-of-game packet; no-op in
    // single-player.
    public static void dQuitNetGame() {
        // intentionally empty
    }

    // d_net.c — NetUpdate. Per-frame netcmd pump. The C interleaves
    // calls into R_RenderPlayerView; the Java port's RMain.rRenderPlayerView
    // skips them since single-player has nothing to send.
    public static void netUpdate() {
        // intentionally empty
    }

    // d_net.c — TryRunTics. Top-level frame driver: blocks until
    // there are tics to run, then calls G_Ticker for each. Lands
    // with the full d_main / g_game port.
    public static void tryRunTics() {
        // intentionally empty
    }

    private DNet() {
    }
}
