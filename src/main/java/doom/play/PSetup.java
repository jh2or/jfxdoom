// Port of linuxdoom-1.10/p_setup.c — stub.
//
// p_setup.c owns level-load: it parses all the lump types (THINGS,
// LINEDEFS, SIDEDEFS, VERTEXES, SEGS, SSECTORS, NODES, SECTORS,
// REJECT, BLOCKMAP) and populates the run-time world arrays. That
// full body is ahead of us; this stub carries only the world-array
// globals so modules that index `sectors[]` / `lines[]` can compile.
//
// The arrays themselves are populated by tests (for unit tests of
// sector/linedef walkers) or by `P_SetupLevel` once that lands.
package doom.play;

import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;

public final class PSetup {

    // r_state.h / p_setup.c — arrays and their counts.
    public static int numsectors;
    public static Sector[] sectors;

    public static int numlines;
    public static Line[] lines;

    public static int numsides;
    public static Side[] sides;

    private PSetup() {
    }
}
