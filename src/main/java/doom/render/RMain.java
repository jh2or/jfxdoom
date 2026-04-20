// Port skeleton of linuxdoom-1.10/r_main.c — only the globals that already-
// ported modules (r_draw.c) need to read. The full body lands in a later
// iteration once the BSP traversal deps (p_setup) are in place.
package doom.render;

public final class RMain {

    // r_main.c line 103 — vertical centre of the screen (viewheight / 2).
    // Set by R_ExecuteSetViewSize; read by R_DrawColumn/R_DrawFuzzColumn.
    public static int centery;

    // r_main.c line 104 — horizontal centre. Referenced by segs/bsp (not r_draw).
    public static int centerx;

    // Whether the current player is underwater / in an invuln sphere, etc.
    // Used by r_draw (nope, by r_bsp/segs/things via walllights). Kept here
    // so later renderer ports have a home.
    public static int extralight;

    private RMain() {
    }
}
