// Port of linuxdoom-1.10/d_player.h wbstartstruct_t + wbplayerstruct_t
// (lines 173–211).
//
// Snapshot of stats passed from G_DoCompleted to WI_Start so the
// intermission screen can render the level summary independent of
// the live player state (which gets reset by G_PlayerFinishLevel
// before the intermission animates).
package doom.ui;

import doom.system.Doomdef;

public final class Wbstartstruct {

    // d_player.h lines 173–185 — wbplayerstruct_t.
    public static final class WbPlayer {
        public boolean in;     // playeringame[]
        public int     skills; // killcount
        public int     sitems; // itemcount
        public int     ssecret; // secretcount
        public int     stime;  // leveltime at exit (in tics)
        public final int[] frags = new int[Doomdef.MAXPLAYERS];
        public int     score;  // unused in vanilla but the field is real
    }

    // d_player.h lines 187–211 — wbstartstruct_t.
    public int  epsd;
    public boolean didsecret;
    public int  last;       // 0-based previous map
    public int  next;       // 0-based next map

    public int  maxkills;
    public int  maxitems;
    public int  maxsecret;
    public int  maxfrags;

    public int  partime;    // tics

    public int  pnum;       // intermission's "this player"

    public final WbPlayer[] plyr = new WbPlayer[Doomdef.MAXPLAYERS];

    public Wbstartstruct() {
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            plyr[i] = new WbPlayer();
        }
    }
}
