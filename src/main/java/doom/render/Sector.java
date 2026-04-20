// Port of linuxdoom-1.10/r_defs.h sector_t (lines 101–135).
// Runtime record for a map sector, populated by p_setup.
package doom.render;

import doom.play.Mobj;

public final class Sector {

    public int floorheight;   // fixed_t
    public int ceilingheight; // fixed_t

    public int floorpic;      // short
    public int ceilingpic;    // short
    public int lightlevel;    // short
    public int special;       // short
    public int tag;           // short

    // 0 = untraversed, 1,2 = sndlines-1.
    public int soundtraversed;

    // Thing that made a sound (or null).
    public Mobj soundtarget;

    // Mapblock bounding box for height changes.
    public int[] blockbox = new int[4];

    // Origin for any sounds played by the sector.
    public Degenmobj soundorg = new Degenmobj();

    // If == validcount, already checked.
    public int validcount;

    // List of mobjs in sector.
    public Mobj thinglist;

    // thinker_t for reversable actions (anonymous type in C).
    public Object specialdata;

    public int linecount;
    public Line[] lines; // [linecount] size
}
