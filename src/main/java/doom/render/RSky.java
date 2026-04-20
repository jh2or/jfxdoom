// Port of linuxdoom-1.10/r_sky.c and r_sky.h.
// Sky rendering globals. The Doom sky is a 256-wide wall texture wrapping
// horizontally over 360° — a full 1024-column sky repeats the 256 texels
// four times, indexed via ANGLETOSKYSHIFT.
//
// Translation notes:
//   * Three scalar globals and one init function — mechanical mapping.
//   * SKYFLATNAME and ANGLETOSKYSHIFT are compile-time constants in the C
//     and stay `public static final` here.
//   * `skyflatnum` is assigned by R_InitFlats in the full engine — the
//     call site in r_sky.c is commented out in the shipped source. The
//     global is still declared for r_plane.c / r_segs.c to reference.
package doom.render;

import doom.fixed.Fixed;

public final class RSky {

    // r_sky.h line 32 — the sky flat's 8-char lump name.
    public static final String SKYFLATNAME = "F_SKY1";

    // r_sky.h line 35 — the sky map is 256*128*4 maps (4 repeats × 256 cols).
    public static final int ANGLETOSKYSHIFT = 22;

    // r_sky.c line 47–49.
    public static int skyflatnum;
    public static int skytexture;
    public static int skytexturemid;

    private RSky() {
    }

    // void R_InitSkyMap(void) — r_sky.c line 57.
    // Called whenever the view size changes.
    public static void rInitSkyMap() {
        // skyflatnum = R_FlatNumForName(SKYFLATNAME);  // commented out in C
        skytexturemid = 100 * Fixed.FRACUNIT;
    }

    // Test-only: zero module state.
    static void resetForTests() {
        skyflatnum = 0;
        skytexture = 0;
        skytexturemid = 0;
    }
}
