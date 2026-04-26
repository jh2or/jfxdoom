// Port of linuxdoom-1.10/r_defs.h node_t (lines 265–279).
// A BSP partition node. The renderer walks the tree from the root
// (always at `numnodes-1`) down to leaf subsectors.
//
// `children[i]` is the child index for side i; if the high bit
// `NF_SUBSECTOR` (0x8000) is set, the remaining bits index into
// `subsectors[]` instead of `nodes[]`.
package doom.render;

public final class Node {

    // doomdata.h line 178 — leaf-marker bit on `children[]`.
    public static final int NF_SUBSECTOR = 0x8000;

    // Partition line from (x,y) to (x+dx, y+dy).
    public int x;  // fixed_t
    public int y;  // fixed_t
    public int dx; // fixed_t
    public int dy; // fixed_t

    // Bounding box for each child, used to clip against view frustum.
    // Indexed [child][BOXTOP/BOXBOTTOM/BOXLEFT/BOXRIGHT].
    public int[][] bbox = new int[2][4]; // fixed_t

    // unsigned short in C — masked to 16 bits when read.
    public int[] children = new int[2];
}
