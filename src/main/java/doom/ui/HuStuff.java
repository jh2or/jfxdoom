// Port of linuxdoom-1.10/hu_stuff.c — partial.
//
// Pickup messages + (deferred) chat / level-banner widgets. Built
// on hu_lib (TextLine / SText / IText). Sits between the playsim
// (which writes into player.message) and the renderer (which calls
// HuStuff.huDrawer / huErase from D_Display).
//
// Ported: font load, message ticker (advances the SText widget when
// player.message is non-null), message drawer/eraser, huStart /
// huStop. Deferred (waits on full menu / multiplayer chat / level-
// title localisation): level-name banner, chat widget, chat-char
// queue, HU_Responder, foreign-key shiftxform tables.
package doom.ui;

import doom.play.Player;
import doom.render.Patch;
import doom.system.Doomdef;
import doom.system.GGame;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class HuStuff {

    // hu_stuff.h lines 30–34 — font lump range.
    public static final char HU_FONTSTART = '!';
    public static final char HU_FONTEND   = '_';
    public static final int  HU_FONTSIZE  =
            (HU_FONTEND - HU_FONTSTART + 1);

    // hu_stuff.h lines 39–44 — message-widget geometry + timeout.
    public static final int HU_MSGX = 0;
    public static final int HU_MSGY = 0;
    public static final int HU_MSGWIDTH = 64;
    public static final int HU_MSGHEIGHT = 1;
    public static final int HU_MSGTIMEOUT = 4 * Doomdef.TICRATE;

    // The font patches, indexed by (char - HU_FONTSTART).
    public static Patch[] huFont = new Patch[HU_FONTSIZE];

    // Per-frame state.
    public static Player plr;
    public static final HuLib.SText wMessage = new HuLib.SText();
    public static final boolean[] messageOn = new boolean[1];
    public static boolean messageNottobefuckedwith;
    public static boolean messageDontfuckwithme;
    public static int messageCounter;
    public static boolean headsupactive;

    // hu_stuff.c — show-messages preference (m_misc default,
    // toggled via the in-game menu). Default true.
    public static boolean showMessages = true;

    private HuStuff() {
    }

    //
    // HU_Init — load STCFN033..STCFN095.
    //
    // hu_stuff.c lines 392–412.
    public static void huInit() throws IOException {
        int j = HU_FONTSTART;
        for (int i = 0; i < HU_FONTSIZE; i++) {
            String name = String.format("STCFN%03d", j++);
            int lump = WWad.wCheckNumForName(name);
            if (lump < 0) {
                huFont[i] = null;
                continue;
            }
            byte[] data = WWad.wCacheLumpNum(lump, ZZone.PU_STATIC);
            huFont[i] = Patch.fromLump(data);
        }
    }

    //
    // HU_Stop / HU_Start
    //
    // hu_stuff.c lines 414–484.
    public static void huStop() {
        headsupactive = false;
    }

    public static void huStart() {
        if (headsupactive) {
            huStop();
        }

        plr = GGame.players[GGame.consoleplayer];
        messageOn[0] = false;
        messageNottobefuckedwith = false;
        messageDontfuckwithme = false;

        // Java-only guard: in the C, HU_Init runs unconditionally during
        // D_DoomMain before any HU_Start fires, so font[0] is always
        // valid. Several playsim tests call pSpawnPlayer (→ huStart)
        // without booting the full engine and therefore never load the
        // WAD font — skip the widget init in that case so the test can
        // still exercise the spawn path.
        if (huFont[0] == null) {
            return;
        }

        // Message widget.
        HuLib.huLibInitSText(wMessage, HU_MSGX, HU_MSGY, HU_MSGHEIGHT,
                huFont, HU_FONTSTART, messageOn);

        // Chat widget / level-title widget / player input buffers
        // — deferred (waits on m_menu / multiplayer chat port).

        headsupactive = true;
    }

    //
    // HU_Drawer / HU_Erase
    //
    // hu_stuff.c lines 486–503.
    public static void huDrawer() {
        if (!headsupactive) {
            return;
        }
        HuLib.huLibDrawSText(wMessage);
        // chat-widget / level-title-widget draws deferred.
    }

    public static void huErase() {
        if (!headsupactive) {
            return;
        }
        HuLib.huLibEraseSText(wMessage);
    }

    //
    // HU_Ticker
    //
    // hu_stuff.c lines 505–578. Just the message branch — the
    // netgame chat-character pump is deferred.
    public static void huTicker() {
        if (!headsupactive) {
            return;
        }

        // tick down message counter if message is up
        if (messageCounter != 0 && --messageCounter == 0) {
            messageOn[0] = false;
            messageNottobefuckedwith = false;
        }

        if (showMessages || messageDontfuckwithme) {
            // display message if necessary
            if (plr.message != null && !messageNottobefuckedwith
                    || plr.message != null && messageDontfuckwithme) {
                HuLib.huLibAddMessageToSText(wMessage, null, plr.message);
                plr.message = null;
                messageOn[0] = true;
                messageCounter = HU_MSGTIMEOUT;
                messageNottobefuckedwith = messageDontfuckwithme;
                messageDontfuckwithme = false;
            }
        }
    }
}
