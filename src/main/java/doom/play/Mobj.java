// Port of linuxdoom-1.10/p_mobj.h mobj_t — stub placeholder.
// The full mobj_t carries 30+ fields (position, orientation, sprite state,
// linked-list pointers across sector/block/thinker chains, AI state, owner
// player, etc.). This stub exists so r_defs.h types (Sector.soundtarget,
// Sector.thinglist, Seg fields indirectly) and renderer code can name the
// type; the fields are declared as they are ported alongside p_mobj.c.
package doom.play;

public class Mobj extends Thinker {

    // Position (p_mobj.h lines 213–215). Populated once p_mobj ports.
    public int x;
    public int y;
    public int z;

    // Orientation (p_mobj.h line 222).
    public int angle; // angle_t
}
