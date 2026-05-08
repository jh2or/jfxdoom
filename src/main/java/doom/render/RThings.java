// Port of linuxdoom-1.10/r_things.c — stub.
//
// r_things.c (989 lines, "Hard" tier) handles sprite drawing: per-
// frame visible-sprite allocation (R_AddSprites for sector-bound
// mobjs + R_DrawPSprites for held-weapon overlays), back-to-front
// sort by scale, and per-column sprite-vs-wall clipping using the
// drawsegs[] silhouette ranges.
//
// For now this stub carries `R_AddSprites` / `R_DrawMasked` /
// `R_ClearSprites` (entry points r_bsp.c / r_main.c reach) plus
// the `negonearray` / `mfloorclip` / `mceilingclip` / `spryscale`
// / `sprtopscreen` globals that r_segs reads / writes during the
// masked-mid-texture path. They no-op or carry sentinel values
// until the full port lands.
package doom.render;

import doom.play.Mobj;
import doom.system.Doomdef;

public final class RThings {

    // r_things.c line 81 — negonearray[SCREENWIDTH] filled with -1
    // sentinels, used by r_segs as a default sprtopclip / negative
    // baseline.
    public static final short[] negonearray = new short[Doomdef.SCREENWIDTH];

    static {
        for (int i = 0; i < negonearray.length; i++) {
            negonearray[i] = -1;
        }
    }

    // r_things.c — current sprite y-scale + screen-row top, set by
    // R_DrawSprite / R_RenderMaskedSegRange before each column.
    public static int spryscale;     // fixed_t
    public static int sprtopscreen;  // fixed_t

    // r_things.c — current sprite-clip rows (short* + offset). The
    // C aliases these into the openings[] buffer with an x1-offset;
    // r_segs writes to them when populating masked midtextures.
    public static short[] mfloorclip;
    public static int     mfloorclipOffset;
    public static short[] mceilingclip;
    public static int     mceilingclipOffset;

    // r_things.c — R_AddSprites (stub).
    // Per-subsector hook called from R_Subsector — walks
    // sector.thinglist and allocates a vissprite_t for each visible
    // mobj. The stub no-ops; sprites simply don't render.
    public static void rAddSprites(Sector sec) {
        // Intentionally empty until the full port lands. Walks
        // sec.thinglist via the snext chain when implemented.
        if (sec == null) {
            return;
        }
        Mobj t = sec.thinglist;
        while (t != null) {
            // R_ProjectSprite(t) — deferred.
            t = t.snext;
        }
    }

    // r_things.c — R_DrawMasked (stub).
    public static void rDrawMasked() {
        // Intentionally empty.
    }

    // r_things.c — R_DrawMaskedColumn (stub).
    // Called by r_segs's R_RenderMaskedSegRange + r_things's
    // R_DrawSprite once that ports. Walks the column's posts (each
    // a (topdelta, length, pixels) triple) and dispatches each
    // visible run through RDraw.rDrawColumn / rDrawFuzzColumn /
    // rDrawTranslatedColumn after clipping against mfloorclip /
    // mceilingclip and the per-row sprtopscreen. The full body
    // lands when r_things ports.
    public static void rDrawMaskedColumn(RData.ColumnRef column) {
        // Intentionally empty until r_things ports.
    }

    // r_things.c — R_ClearSprites (stub).
    public static void rClearSprites() {
        // Intentionally empty.
    }

    private RThings() {
    }
}
