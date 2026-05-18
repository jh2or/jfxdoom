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

    // d_englsh.h lines 357–434 — Doom 1 end-of-episode finale text
    // (E1TEXT..E4TEXT). Consumed by F_StartFinale.
    public static final String E1TEXT =
            "Once you beat the big badasses and\n" +
            "clean out the moon base you're supposed\n" +
            "to win, aren't you? Aren't you? Where's\n" +
            "your fat reward and ticket home? What\n" +
            "the hell is this? It's not supposed to\n" +
            "end this way!\n" +
            "\n" +
            "It stinks like rotten meat, but looks\n" +
            "like the lost Deimos base.  Looks like\n" +
            "you're stuck on The Shores of Hell.\n" +
            "The only way out is through.\n" +
            "\n" +
            "To continue the DOOM experience, play\n" +
            "The Shores of Hell and its amazing\n" +
            "sequel, Inferno!\n";

    public static final String E2TEXT =
            "You've done it! The hideous cyber-\n" +
            "demon lord that ruled the lost Deimos\n" +
            "moon base has been slain and you\n" +
            "are triumphant! But ... where are\n" +
            "you? You clamber to the edge of the\n" +
            "moon and look down to see the awful\n" +
            "truth.\n" +
            "\n" +
            "Deimos floats above Hell itself!\n" +
            "You've never heard of anyone escaping\n" +
            "from Hell, but you'll make the bastards\n" +
            "sorry they ever heard of you! Quickly,\n" +
            "you rappel down to  the surface of\n" +
            "Hell.\n" +
            "\n" +
            "Now, it's on to the final chapter of\n" +
            "DOOM! -- Inferno.";

    public static final String E3TEXT =
            "The loathsome spiderdemon that\n" +
            "masterminded the invasion of the moon\n" +
            "bases and caused so much death has had\n" +
            "its ass kicked for all time.\n" +
            "\n" +
            "A hidden doorway opens and you enter.\n" +
            "You've proven too tough for Hell to\n" +
            "contain, and now Hell at last plays\n" +
            "fair -- for you emerge from the door\n" +
            "to see the green fields of Earth!\n" +
            "Home at last.\n" +
            "\n" +
            "You wonder what's been happening on\n" +
            "Earth while you were battling evil\n" +
            "unleashed. It's good that no Hell-\n" +
            "spawn could have come through that\n" +
            "door with you ...";

    public static final String E4TEXT =
            "the spider mastermind must have sent forth\n" +
            "its legions of hellspawn before your\n" +
            "final confrontation with that terrible\n" +
            "beast from hell.  but you stepped forward\n" +
            "and brought forth eternal damnation and\n" +
            "suffering upon the horde as a true hero\n" +
            "would in the face of something so evil.\n" +
            "\n" +
            "besides, someone was gonna pay for what\n" +
            "happened to daisy, your pet rabbit.\n" +
            "\n" +
            "but now, you see spread before you more\n" +
            "potential pain and gibbitude as a nation\n" +
            "of demons run amok among our cities.\n" +
            "\n" +
            "next stop, hell on earth!";

    // d_englsh.h lines 436–525 — Doom 2 inter-mission text screens
    // (C1TEXT..C6TEXT), fired between specific map boundaries.
    public static final String C1TEXT =
            "YOU HAVE ENTERED DEEPLY INTO THE INFESTED\n" +
            "STARPORT. BUT SOMETHING IS WRONG. THE\n" +
            "MONSTERS HAVE BROUGHT THEIR OWN REALITY\n" +
            "WITH THEM, AND THE STARPORT'S TECHNOLOGY\n" +
            "IS BEING SUBVERTED BY THEIR PRESENCE.\n" +
            "\n" +
            "AHEAD, YOU SEE AN OUTPOST OF HELL, A\n" +
            "FORTIFIED ZONE. IF YOU CAN GET PAST IT,\n" +
            "YOU CAN PENETRATE INTO THE HAUNTED HEART\n" +
            "OF THE STARBASE AND FIND THE CONTROLLING\n" +
            "SWITCH WHICH HOLDS EARTH'S POPULATION\n" +
            "HOSTAGE.";

    public static final String C2TEXT =
            "YOU HAVE WON! YOUR VICTORY HAS ENABLED\n" +
            "HUMANKIND TO EVACUATE EARTH AND ESCAPE\n" +
            "THE NIGHTMARE.  NOW YOU ARE THE ONLY\n" +
            "HUMAN LEFT ON THE FACE OF THE PLANET.\n" +
            "CANNIBAL MUTATIONS, CARNIVOROUS ALIENS,\n" +
            "AND EVIL SPIRITS ARE YOUR ONLY NEIGHBORS.\n" +
            "YOU SIT BACK AND WAIT FOR DEATH, CONTENT\n" +
            "THAT YOU HAVE SAVED YOUR SPECIES.\n" +
            "\n" +
            "BUT THEN, EARTH CONTROL BEAMS DOWN A\n" +
            "MESSAGE FROM SPACE: \"SENSORS HAVE LOCATED\n" +
            "THE SOURCE OF THE ALIEN INVASION. IF YOU\n" +
            "GO THERE, YOU MAY BE ABLE TO BLOCK THEIR\n" +
            "ENTRY.  THE ALIEN BASE IS IN THE HEART OF\n" +
            "YOUR OWN HOME CITY, NOT FAR FROM THE\n" +
            "STARPORT.\" SLOWLY AND PAINFULLY YOU GET\n" +
            "UP AND RETURN TO THE FRAY.";

    public static final String C3TEXT =
            "YOU ARE AT THE CORRUPT HEART OF THE CITY,\n" +
            "SURROUNDED BY THE CORPSES OF YOUR ENEMIES.\n" +
            "YOU SEE NO WAY TO DESTROY THE CREATURES'\n" +
            "ENTRYWAY ON THIS SIDE, SO YOU CLENCH YOUR\n" +
            "TEETH AND PLUNGE THROUGH IT.\n" +
            "\n" +
            "THERE MUST BE A WAY TO CLOSE IT ON THE\n" +
            "OTHER SIDE. WHAT DO YOU CARE IF YOU'VE\n" +
            "GOT TO GO THROUGH HELL TO GET TO IT?";

    public static final String C4TEXT =
            "THE HORRENDOUS VISAGE OF THE BIGGEST\n" +
            "DEMON YOU'VE EVER SEEN CRUMBLES BEFORE\n" +
            "YOU, AFTER YOU PUMP YOUR ROCKETS INTO\n" +
            "HIS EXPOSED BRAIN. THE MONSTER SHRIVELS\n" +
            "UP AND DIES, ITS THRASHING LIMBS\n" +
            "DEVASTATING UNTOLD MILES OF HELL'S\n" +
            "SURFACE.\n" +
            "\n" +
            "YOU'VE DONE IT. THE INVASION IS OVER.\n" +
            "EARTH IS SAVED. HELL IS A WRECK. YOU\n" +
            "WONDER WHERE BAD FOLKS WILL GO WHEN THEY\n" +
            "DIE, NOW. WIPING THE SWEAT FROM YOUR\n" +
            "FOREHEAD YOU BEGIN THE LONG TREK BACK\n" +
            "HOME. REBUILDING EARTH OUGHT TO BE A\n" +
            "LOT MORE FUN THAN RUINING IT WAS.\n";

    public static final String C5TEXT =
            "CONGRATULATIONS, YOU'VE FOUND THE SECRET\n" +
            "LEVEL! LOOKS LIKE IT'S BEEN BUILT BY\n" +
            "HUMANS, RATHER THAN DEMONS. YOU WONDER\n" +
            "WHO THE INMATES OF THIS CORNER OF HELL\n" +
            "WILL BE.";

    public static final String C6TEXT =
            "CONGRATULATIONS, YOU'VE FOUND THE\n" +
            "SUPER SECRET LEVEL!  YOU'D BETTER\n" +
            "BLAZE THROUGH THIS ONE!\n";

    // d_englsh.h lines 676–693 — character cast names (Doom 2 finale).
    public static final String CC_ZOMBIE  = "ZOMBIEMAN";
    public static final String CC_SHOTGUN = "SHOTGUN GUY";
    public static final String CC_HEAVY   = "HEAVY WEAPON DUDE";
    public static final String CC_IMP     = "IMP";
    public static final String CC_DEMON   = "DEMON";
    public static final String CC_LOST    = "LOST SOUL";
    public static final String CC_CACO    = "CACODEMON";
    public static final String CC_HELL    = "HELL KNIGHT";
    public static final String CC_BARON   = "BARON OF HELL";
    public static final String CC_ARACH   = "ARACHNOTRON";
    public static final String CC_PAIN    = "PAIN ELEMENTAL";
    public static final String CC_REVEN   = "REVENANT";
    public static final String CC_MANCU   = "MANCUBUS";
    public static final String CC_ARCH    = "ARCH-VILE";
    public static final String CC_SPIDER  = "THE SPIDER MASTERMIND";
    public static final String CC_CYBER   = "THE CYBERDEMON";
    public static final String CC_HERO    = "OUR HERO";

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
