// Port of linuxdoom-1.10/p_mobj.h mobj_t.
// Runtime object state shared by simulation, sector/block/thinker chains,
// sprite rendering, AI and player ownership.
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

    // p_pspr.h lines 50–51 — frame-bit flags packed into mobj.frame /
    // pspdef.state.frame.
    public static final int FF_FULLBRIGHT = 0x8000;
    public static final int FF_FRAMEMASK  = 0x7fff;

    // p_mobj.h lines 223–224 — current sprite + frame for the
    // renderer. P_SetMobjState writes these from the state row.
    public int sprite; // spritenum_t
    public int frame;  // | optional FF_FULLBRIGHT bit (32768)

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

    // p_mobj.h lines 234–235 — the closest interval over all
    // sectors the mobj is currently touching. P_TryMove writes both
    // when a move is committed; P_ZMovement reads them for vertical
    // clipping and gravity.
    public int floorz;   // fixed_t
    public int ceilingz; // fixed_t

    // p_mobj.h lines 242–244 — momentum, used to update position.
    // Updated each tic by P_XYMovement / P_ZMovement (p_mobj.c) and
    // by P_SlideMove (p_map.c) when a player slide along a wall.
    public int momx; // fixed_t
    public int momy; // fixed_t
    public int momz; // fixed_t

    // p_mobj.h line 250 — &mobjinfo[type]. Cached on spawn for cheap
    // access; reads spawnstate, painchance, deathsound, speed, etc.
    public MobjInfo info;

    // p_mobj.h lines 252–253 — current animation state + tic counter.
    // P_MobjThinker decrements `tics`; on zero, P_SetMobjState
    // advances to `state.nextstate`.
    public int tics;     // tics until next state change; -1 = static
    public State state;

    // p_mobj.h line 255 — current hit points.
    public int health;

    // p_mobj.h line 247 — `validcount` snapshot for the per-frame
    // already-checked stamp; written by callers, not by P_MobjThinker.
    public int validcount;

    // p_mobj.h lines 258–259 — AI direction state. P_NewChaseDir /
    // P_Move (p_enemy.c) read/write these.
    public int movedir;   // 0..7
    public int movecount;

    // p_mobj.h line 263 — AI target / missile owner.
    public Mobj target;

    // p_mobj.h line 267 — countdown before AI may attack again.
    public int reactiontime;

    // p_mobj.h line 271 — chase commitment: while >0, AI sticks with
    // current target regardless of damage.
    public int threshold;

    // p_mobj.h line 278 — round-robin start index for player-search.
    public int lastlook;

    // p_mobj.h line 284 — AI tracer (revenant homing missile target).
    public Mobj tracer;

    // p_mobj.h line 281 — spawn record for nightmare respawn /
    // monster-respawn queue. Copied from the THINGS lump entry by
    // P_SpawnMapThing; preserved across the mobj's lifecycle so
    // P_NightmareRespawn / P_RespawnSpecials can recreate the mobj
    // at its original placement.
    public Mapthing spawnpoint;
}
