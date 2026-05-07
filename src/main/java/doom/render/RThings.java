// Port of linuxdoom-1.10/r_things.c — stub.
//
// r_things.c (989 lines, "Hard" tier) handles sprite drawing: per-
// frame visible-sprite allocation (R_AddSprites for sector-bound
// mobjs + R_DrawPSprites for held-weapon overlays), back-to-front
// sort by scale, and per-column sprite-vs-wall clipping using the
// drawsegs[] silhouette ranges.
//
// For now this stub carries only `R_AddSprites` and `R_DrawMasked`,
// the two entry points r_bsp.c / r_main.c reach. They no-op until
// the full port lands.
package doom.render;

import doom.play.Mobj;

public final class RThings {

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

    // r_things.c — R_ClearSprites (stub).
    public static void rClearSprites() {
        // Intentionally empty.
    }

    private RThings() {
    }
}
