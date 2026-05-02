// Port of linuxdoom-1.10/doomdata.h mapthing_t (lines 203–210).
// One entry from the THINGS lump — the editor placement record for
// monsters / items / player starts / deathmatch starts.
//
// Five shorts on disk: x, y, angle, type, options. P_LoadThings
// reads each into a Mapthing instance, then P_SpawnMapThing routes
// it to the right spawn path (player, deathmatch, monster/item).
package doom.play;

public final class Mapthing {

    // doomdef.h line 145 — MTF_AMBUSH bit on mapthing.options.
    // Set on monsters that should ignore sound alerts (deaf monsters).
    public static final int MTF_AMBUSH = 8;

    public int x;        // short — map x in unscaled units
    public int y;        // short
    public int angle;    // short — degrees / 45 for player-likes,
                         //         BAM-encoded after byte-swap for monsters
    public int type;     // short — DoomEd thing-type number
    public int options;  // short — skill-level filter + ambush flag

    public Mapthing() {
    }

    public Mapthing(int x, int y, int angle, int type, int options) {
        this.x = x;
        this.y = y;
        this.angle = angle;
        this.type = type;
        this.options = options;
    }

    // Used by P_RemoveMobj to copy itself into the item-respawn queue
    // without aliasing.
    public Mapthing copy() {
        return new Mapthing(x, y, angle, type, options);
    }
}
