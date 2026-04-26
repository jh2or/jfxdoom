// Port of linuxdoom-1.10/r_main.c — partial.
//
// Carries the BSP-descent functions (R_PointOnSide, R_PointOnSegSide,
// R_PointInSubsector) that p_maputl/p_mobj/p_telept all need, plus
// the global counter (validcount) and the renderer-side scalars
// (centery, centerx, extralight) read by r_draw.
//
// The remaining ~870 lines (R_PointToAngle, R_PointToDist,
// R_InitTables, R_InitTextureMapping, R_InitLightTables,
// R_ExecuteSetViewSize, R_Init, R_SetupFrame, R_RenderPlayerView)
// land alongside the renderer cluster (r_bsp/r_segs/r_things).
package doom.render;

import doom.fixed.Fixed;

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

    // r_main.c line 55 — frame-and-trace counter. Bumped before each
    // BSP traversal (R_RenderPlayerView) and before each PathTraverse
    // (PMaputl). Lines/sectors stamped with the current value have
    // already been visited in this pass. Initialized to 1 in the C so
    // the first walk always sees fresh state (validcount == 0).
    public static int validcount = 1;

    //
    // R_PointOnSide
    // Traverse BSP (sub) tree, check point against partition plane.
    // Returns side 0 (front) or 1 (back).
    //
    // r_main.c lines 161–211. Identical structure to
    // PMaputl.pPointOnLineSide but operates on a node_t (whose
    // partition is (x,y) + (dx,dy)) instead of a line_t.
    public static int rPointOnSide(int x, int y, Node node) {
        int dx;
        int dy;
        int left;
        int right;

        if (node.dx == 0) {
            if (x <= node.x) {
                return node.dy > 0 ? 1 : 0;
            }
            return node.dy < 0 ? 1 : 0;
        }
        if (node.dy == 0) {
            if (y <= node.y) {
                return node.dx < 0 ? 1 : 0;
            }
            return node.dx > 0 ? 1 : 0;
        }

        dx = (x - node.x);
        dy = (y - node.y);

        // Try to quickly decide by looking at sign bits.
        if (((node.dy ^ node.dx ^ dx ^ dy) & 0x80000000) != 0) {
            if (((node.dy ^ dx) & 0x80000000) != 0) {
                // (left is negative)
                return 1;
            }
            return 0;
        }

        left = Fixed.mul(node.dy >> Fixed.FRACBITS, dx);
        right = Fixed.mul(dy, node.dx >> Fixed.FRACBITS);

        if (right < left) {
            // front side
            return 0;
        }
        // back side
        return 1;
    }

    //
    // R_PointOnSegSide
    // Same shape as R_PointOnSide but operates on a seg.
    //
    // r_main.c lines 214–274.
    public static int rPointOnSegSide(int x, int y, Seg line) {
        int lx;
        int ly;
        int ldx;
        int ldy;
        int dx;
        int dy;
        int left;
        int right;

        lx = line.v1.x;
        ly = line.v1.y;

        ldx = line.v2.x - lx;
        ldy = line.v2.y - ly;

        if (ldx == 0) {
            if (x <= lx) {
                return ldy > 0 ? 1 : 0;
            }
            return ldy < 0 ? 1 : 0;
        }
        if (ldy == 0) {
            if (y <= ly) {
                return ldx < 0 ? 1 : 0;
            }
            return ldx > 0 ? 1 : 0;
        }

        dx = (x - lx);
        dy = (y - ly);

        // Try to quickly decide by looking at sign bits.
        if (((ldy ^ ldx ^ dx ^ dy) & 0x80000000) != 0) {
            if (((ldy ^ dx) & 0x80000000) != 0) {
                // (left is negative)
                return 1;
            }
            return 0;
        }

        left = Fixed.mul(ldy >> Fixed.FRACBITS, dx);
        right = Fixed.mul(dy, ldx >> Fixed.FRACBITS);

        if (right < left) {
            // front side
            return 0;
        }
        // back side
        return 1;
    }

    //
    // R_PointInSubsector
    // Descends the BSP from the root (always at numnodes-1) until it
    // hits a leaf (NF_SUBSECTOR bit set) and returns the subsector.
    //
    // r_main.c lines 800–823.
    public static Subsector rPointInSubsector(int x, int y) {
        Node node;
        int side;
        int nodenum;

        // single subsector is a special case
        if (doom.play.PSetup.numnodes == 0) {
            return doom.play.PSetup.subsectors[0];
        }

        nodenum = doom.play.PSetup.numnodes - 1;

        while ((nodenum & Node.NF_SUBSECTOR) == 0) {
            node = doom.play.PSetup.nodes[nodenum];
            side = rPointOnSide(x, y, node);
            nodenum = node.children[side];
        }

        return doom.play.PSetup.subsectors[nodenum & ~Node.NF_SUBSECTOR];
    }

    private RMain() {
    }
}
