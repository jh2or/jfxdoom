// Port of linuxdoom-1.10/r_defs.h spritedef_t (lines 444–453).
//
// One sprite's animation strip — a sequence of Spriteframes. The
// global RThings.sprites[] array is indexed by spritenum_t (Info.SPR_*),
// each entry pointing at its own Spriteframe[].
package doom.render;

public final class Spritedef {

    public int numframes;
    public Spriteframe[] spriteframes;
}
