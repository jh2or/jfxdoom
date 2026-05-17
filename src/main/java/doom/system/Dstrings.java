// Port of linuxdoom-1.10/dstrings.c and dstrings.h.
// Globally defined strings plus the quit-message pool used by M_Menu.
//
// The C header chain pulls in d_englsh.h (or d_french.h) which defines
// several hundred UI strings as #defines. Those are only referenced at
// call sites via the preprocessor, so translating them all up-front
// would be dead code — they land in their callers' classes (MMenu,
// HuStuff, PInter, FFinale, etc.) as those modules are ported.
//
// QUITMSG is the one d_englsh.h constant that endmsg[] references
// directly, so it lives here.
package doom.system;

public final class Dstrings {

    // dstrings.h line 41 — default savegame base name.
    public static final String SAVEGAMENAME = "doomsav";

    // dstrings.h line 49.
    public static final String DEVMAPS = "devmaps";

    // dstrings.h line 50.
    public static final String DEVDATA = "devdata";

    // dstrings.h line 56 — NUMBER of quit messages. The `endmsg[]`
    // array is sized `NUM_QUITMESSAGES + 1`, matching C.
    public static final int NUM_QUITMESSAGES = 22;

    // d_englsh.h line 41 — referenced by endmsg[0].
    public static final String QUITMSG = "are you sure you want to\nquit this great game?";

    // d_englsh.h lines 125–130 — locked-door denial messages
    // (consumed by EV_VerticalDoor / EV_DoLockedDoor in p_doors.c).
    public static final String PD_BLUEO   = "You need a blue key to activate this object";
    public static final String PD_REDO    = "You need a red key to activate this object";
    public static final String PD_YELLOWO = "You need a yellow key to activate this object";
    public static final String PD_BLUEK   = "You need a blue key to open this door";
    public static final String PD_REDK    = "You need a red key to open this door";
    public static final String PD_YELLOWK = "You need a yellow key to open this door";

    // d_englsh.h lines 339–354 — status-bar cheat acknowledgements.
    // Consumed by ST_Responder.
    public static final String STSTR_MUS      = "Music Change";
    public static final String STSTR_NOMUS    = "IMPOSSIBLE SELECTION";
    public static final String STSTR_DQDON    = "Degreelessness Mode On";
    public static final String STSTR_DQDOFF   = "Degreelessness Mode Off";
    public static final String STSTR_KFAADDED = "Very Happy Ammo Added";
    public static final String STSTR_FAADDED  = "Ammo (no keys) Added";
    public static final String STSTR_NCON     = "No Clipping Mode ON";
    public static final String STSTR_NCOFF    = "No Clipping Mode OFF";
    public static final String STSTR_BEHOLD   = "inVuln, Str, Inviso, Rad, Allmap, or Lite-amp";
    public static final String STSTR_BEHOLDX  = "Power-up Toggled";
    public static final String STSTR_CHOPPERS = "... doesn't suck - GM";
    public static final String STSTR_CLEV     = "Changing Level...";

    // dstrings.c lines 35–67 — endmsg[NUM_QUITMESSAGES+1].
    //
    // The C source is missing two commas in the initializer. In C,
    // adjacent string literals concatenate at the lexical level, so:
    //   line 45 "...see if i care."   (no trailing comma)
    //   line 48 "you want to quit?..."
    // become a single array entry `"...see if i care.you want to quit?..."`.
    // Same happens between lines 54 and 57. We reproduce the bug
    // verbatim (CLAUDE.md §2: "preserve ... bugs of the original").
    //
    // That leaves 21 real entries in a size-23 array; slots 21 and 22
    // are null (zero-initialized in C, null here).
    public static final String[] endmsg = new String[NUM_QUITMESSAGES + 1];

    static {
        // DOOM1
        endmsg[0] = QUITMSG;
        endmsg[1] = "please don't leave, there's more\ndemons to toast!";
        endmsg[2] = "let's beat it -- this is turning\ninto a bloodbath!";
        endmsg[3] = "i wouldn't leave if i were you.\ndos is much worse.";
        endmsg[4] = "you're trying to say you like dos\nbetter than me, right?";
        endmsg[5] = "don't leave yet -- there's a\ndemon around that corner!";
        endmsg[6] = "ya know, next time you come in here\ni'm gonna toast ya.";
        // Lines 45 and 48 concatenate (missing comma in C).
        endmsg[7] = "go ahead and leave. see if i care."
                + "you want to quit?\nthen, thou hast lost an eighth!";

        // QuitDOOM II messages
        endmsg[8]  = "don't go now, there's a \ndimensional shambler waiting\nat the dos prompt!";
        endmsg[9]  = "get outta here and go back\nto your boring programs.";
        endmsg[10] = "if i were your boss, i'd \n deathmatch ya in a minute!";
        endmsg[11] = "look, bud. you leave now\nand you forfeit your body count!";
        endmsg[12] = "just leave. when you come\nback, i'll be waiting with a bat.";
        // Lines 54 and 57 concatenate (missing comma in C).
        endmsg[13] = "you're lucky i don't smack\nyou for thinking about leaving."
                + "fuck you, pussy!\nget the fuck out!";

        // FinalDOOM?
        endmsg[14] = "you quit and i'll jizz\nin your cystholes!";
        endmsg[15] = "if you leave, i'll make\nthe lord drink my jizz.";
        endmsg[16] = "hey, ron! can we say\n'fuck' in the game?";
        endmsg[17] = "i'd leave: this is just\nmore monsters and levels.\nwhat a load.";
        endmsg[18] = "suck it down, asshole!\nyou're a fucking wimp!";
        endmsg[19] = "don't quit now! we're \nstill spending your money!";

        // Internal debug. Different style, too.
        endmsg[20] = "THIS IS NO MESSAGE!\nPage intentionally left blank.";

        // Slots 21 and 22 left null to mirror C's tail-of-array zero init.
    }

    private Dstrings() {
    }
}
