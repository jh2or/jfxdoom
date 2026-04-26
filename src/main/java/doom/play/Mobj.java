// Port of linuxdoom-1.10/p_mobj.h mobj_t — stub placeholder.
// The full mobj_t carries 30+ fields (position, orientation, sprite state,
// linked-list pointers across sector/block/thinker chains, AI state, owner
// player, etc.). This stub exists so r_defs.h types (Sector.soundtarget,
// Sector.thinglist, Seg fields indirectly) and renderer code can name the
// type; the fields are declared as they are ported alongside p_mobj.c.
package doom.play;

import doom.render.Subsector;

public class Mobj extends Thinker {

    // p_mobj.h lines 117–203 — mobjflag_t bit values.
    public static final int MF_SPECIAL      = 1;
    public static final int MF_SOLID        = 2;
    public static final int MF_SHOOTABLE    = 4;
    public static final int MF_NOSECTOR     = 8;
    public static final int MF_NOBLOCKMAP   = 16;
    public static final int MF_AMBUSH       = 32;
    public static final int MF_JUSTHIT      = 64;
    public static final int MF_JUSTATTACKED = 128;
    public static final int MF_SPAWNCEILING = 256;
    public static final int MF_NOGRAVITY    = 512;
    public static final int MF_DROPOFF      = 0x400;
    public static final int MF_PICKUP       = 0x800;
    public static final int MF_NOCLIP       = 0x1000;
    public static final int MF_SLIDE        = 0x2000;
    public static final int MF_FLOAT        = 0x4000;
    public static final int MF_TELEPORT     = 0x8000;
    public static final int MF_MISSILE      = 0x10000;
    public static final int MF_DROPPED      = 0x20000;
    public static final int MF_SHADOW       = 0x40000;
    public static final int MF_NOBLOOD      = 0x80000;
    public static final int MF_CORPSE       = 0x100000;
    public static final int MF_INFLOAT      = 0x200000;
    public static final int MF_COUNTKILL    = 0x400000;
    public static final int MF_COUNTITEM    = 0x800000;
    public static final int MF_SKULLFLY     = 0x1000000;
    public static final int MF_NOTDMATCH    = 0x2000000;
    public static final int MF_TRANSLATION  = 0xc000000;
    public static final int MF_TRANSSHIFT   = 26;

    // Position (p_mobj.h lines 213–215). Populated once p_mobj ports.
    public int x;
    public int y;
    public int z;

    // Orientation (p_mobj.h line 222).
    public int angle; // angle_t

    // p_mobj.h line 275 — back-pointer to Player when type == MT_PLAYER,
    // null otherwise. EV_VerticalDoor / EV_DoLockedDoor / many AI paths
    // gate on this to distinguish player triggers from monster triggers.
    public Player player;

    // p_mobj.h line 249 — mobjtype_t (an Info.MT_* constant). The
    // missile-class checks in P_CrossSpecialLine read this; the rest
    // of the mobj_t lifecycle (state machine, sprite, AI) lands with
    // p_mobj.c.
    public int type;

    // p_mobj.h lines 228–229 — blockmap doubly-linked list links.
    // Each blocklinks[] cell holds the head of a chain of mobjs whose
    // origin is in that block; bnext/bprev walk the chain.
    // P_BlockThingsIterator reads bnext to traverse.
    public Mobj bnext;
    public Mobj bprev;

    // p_mobj.h lines 238–239 — bounding cylinder. radius is half the
    // mobj's footprint; height is its vertical extent (z..z+height).
    // PIT_AddThingIntercepts builds a corner-to-corner crossection
    // around (x±radius, y±radius); P_TryMove reads height for vertical
    // clipping.
    public int radius; // fixed_t
    public int height; // fixed_t

    // p_mobj.h line 254 — bit flags (MF_*). Read by the position
    // linkers (skip sector or blockmap insert when MF_NOSECTOR /
    // MF_NOBLOCKMAP), by AI/render/etc.
    public int flags;

    // p_mobj.h lines 218–219, 231 — sector-list links + the BSP
    // subsector that owns the thing. P_SetThingPosition wires both;
    // P_UnsetThingPosition reverses.
    public Mobj snext;
    public Mobj sprev;
    public Subsector subsector;
}
