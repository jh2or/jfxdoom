package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;
import doom.render.Patch;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HuStuffTest {

    // Minimal 1×12 patch — gives HuLib something to read height from
    // without dragging a WAD load into the test.
    private static Patch stubPatch() {
        return Patch.fromLump(new byte[] {
                1, 0,         // width 1
                12, 0,        // height 12
                0, 0,         // leftoffset
                0, 0,         // topoffset
                0, 0, 0, 0,   // columnofs[0]
        });
    }

    @BeforeEach
    void resetState() {
        for (int i = 0; i < HuStuff.HU_FONTSIZE; i++) {
            HuStuff.huFont[i] = stubPatch();
        }
        GGame.players[0] = new Player();
        GGame.consoleplayer = 0;
        HuStuff.headsupactive = false;
        HuStuff.messageCounter = 0;
        HuStuff.messageOn[0] = false;
        HuStuff.messageNottobefuckedwith = false;
        HuStuff.messageDontfuckwithme = false;
        HuStuff.showMessages = true;
        HuStuff.plr = null;
    }

    // huStart flips headsupactive on and points plr at the console
    // player so huTicker has a Player to read player.message from.
    @Test
    void huStartActivatesAndBindsPlayer() {
        HuStuff.huStart();
        assertTrue(HuStuff.headsupactive);
        assertEquals(GGame.players[0], HuStuff.plr);
    }

    // huStop drops headsupactive so subsequent draws/erases no-op.
    @Test
    void huStopDeactivates() {
        HuStuff.huStart();
        HuStuff.huStop();
        assertFalse(HuStuff.headsupactive);
    }

    // huStart called twice still ends in the active state (the first
    // call's huStop fires from inside huStart; the second arms again).
    @Test
    void huStartReentrantStaysActive() {
        HuStuff.huStart();
        HuStuff.huStart();
        assertTrue(HuStuff.headsupactive);
    }

    // huStart with no font loaded (huInit never ran) bails before the
    // SText init, so headsupactive stays false but the call doesn't
    // throw. Matches the test-only guard in huStart.
    @Test
    void huStartWithoutFontIsNoOp() {
        for (int i = 0; i < HuStuff.HU_FONTSIZE; i++) {
            HuStuff.huFont[i] = null;
        }
        HuStuff.huStart();
        assertFalse(HuStuff.headsupactive);
    }

    // huTicker pulls a pending player.message into the SText widget,
    // clears the message slot, lights messageOn, and arms the timeout.
    @Test
    void huTickerConsumesPlayerMessage() {
        HuStuff.huStart();
        HuStuff.plr.message = "Picked up the blue keycard.";

        HuStuff.huTicker();

        assertNull(HuStuff.plr.message);
        assertTrue(HuStuff.messageOn[0]);
        assertEquals(HuStuff.HU_MSGTIMEOUT, HuStuff.messageCounter);
    }

    // After HU_MSGTIMEOUT ticks the message turns itself off.
    @Test
    void huTickerCountsDownAndDisablesMessage() {
        HuStuff.huStart();
        HuStuff.plr.message = "hi";
        HuStuff.huTicker(); // arms counter, sets messageOn=true
        assertTrue(HuStuff.messageOn[0]);

        for (int i = 0; i < HuStuff.HU_MSGTIMEOUT; i++) {
            HuStuff.huTicker();
        }

        assertFalse(HuStuff.messageOn[0]);
        assertEquals(0, HuStuff.messageCounter);
    }

    // showMessages=false suppresses the pull from player.message
    // (unless messageDontfuckwithme overrides).
    @Test
    void huTickerSkipsWhenMessagesDisabled() {
        HuStuff.huStart();
        HuStuff.showMessages = false;
        HuStuff.plr.message = "ignored";

        HuStuff.huTicker();

        // Message stays in the player slot; widget never armed.
        assertEquals("ignored", HuStuff.plr.message);
        assertFalse(HuStuff.messageOn[0]);
    }

    // messageDontfuckwithme forces the message through even when
    // showMessages is off (used for fatal-style notices).
    @Test
    void huTickerForcedMessageBypassesShowMessagesGate() {
        HuStuff.huStart();
        HuStuff.showMessages = false;
        HuStuff.messageDontfuckwithme = true;
        HuStuff.plr.message = "force me";

        HuStuff.huTicker();

        assertNull(HuStuff.plr.message);
        assertTrue(HuStuff.messageOn[0]);
        // The override latches into messageNottobefuckedwith and the
        // bool itself is consumed.
        assertTrue(HuStuff.messageNottobefuckedwith);
        assertFalse(HuStuff.messageDontfuckwithme);
    }

    // huTicker / huDrawer / huErase are no-ops when headsupactive is
    // false — verified by the absence of state mutation / exceptions.
    @Test
    void tickerDrawerEraserInactiveAreNoOps() {
        HuStuff.headsupactive = false;
        HuStuff.plr = GGame.players[0];
        HuStuff.plr.message = "queued";

        HuStuff.huTicker();
        HuStuff.huDrawer();
        HuStuff.huErase();

        // huTicker bailed early → message wasn't consumed.
        assertEquals("queued", HuStuff.plr.message);
        assertFalse(HuStuff.messageOn[0]);
    }
}
