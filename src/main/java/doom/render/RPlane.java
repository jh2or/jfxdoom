// Port of linuxdoom-1.10/r_plane.c — stub.
//
// r_plane.c (453 lines, "Hard" tier) handles floor and ceiling
// rendering: per-frame "visplane" allocation (R_FindPlane), per-
// column visplane checking + extension (R_CheckPlane), and the
// span-fill driver (R_MakeSpans / R_DrawPlanes) that turns each
// visplane into horizontal flat-fill spans.
//
// For now this stub carries only `R_FindPlane` and `R_DrawPlanes` —
// the entry points r_bsp.c needs and r_main.c calls at end of frame.
// They no-op until the full port lands.
package doom.render;

public final class RPlane {

    // r_plane.c — Visplane (stub-allocated). Fields land with the
    // full port.
    public static final class Visplane {
        public int height;     // fixed_t
        public int picnum;
        public int lightlevel;
        public int minx;
        public int maxx;
    }

    // r_plane.c — R_FindPlane.
    // Finds (or creates) a visplane matching height/picnum/lightlevel.
    // Stub returns a fresh Visplane so r_bsp can store the reference
    // in floorplane / ceilingplane without NPEing.
    public static Visplane rFindPlane(int height, int picnum, int lightlevel) {
        Visplane vp = new Visplane();
        vp.height = height;
        vp.picnum = picnum;
        vp.lightlevel = lightlevel;
        vp.minx = Integer.MAX_VALUE;
        vp.maxx = Integer.MIN_VALUE;
        return vp;
    }

    // r_plane.c — R_DrawPlanes (stub).
    public static void rDrawPlanes() {
        // Intentionally empty until the full port lands.
    }

    // r_plane.c — R_ClearPlanes (stub).
    public static void rClearPlanes() {
        // Intentionally empty until the full port lands.
    }

    private RPlane() {
    }
}
