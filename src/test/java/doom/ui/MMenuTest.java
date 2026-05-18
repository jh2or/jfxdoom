package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;
import doom.sound.SSound;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.Dstrings;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MMenuTest {

    @BeforeEach
    void resetState() {
        MMenu.menuactive = false;
        MMenu.messageToPrint = 0;
        MMenu.messageString = null;
        MMenu.messageRoutine = null;
        MMenu.messageNeedsInput = false;
        MMenu.saveStringEnter = 0;
        MMenu.itemOn = 0;
        MMenu.skullAnimCounter = 8;
        MMenu.whichSkull = 0;
        MMenu.currentMenu = MMenu.MainDef;
        MMenu.currentMenu.lastOn = 0;
        MMenu.quickSaveSlot = -1;
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        Doomstat.netgame = false;
        Doomstat.usergame = true;
        Doomstat.gamestate = Doomstat.GS_LEVEL;
        Doomstat.demoplayback = false;
        GGame.players[0] = new Player();
        GGame.consoleplayer = 0;
        GGame.gameaction = GGame.ga_nothing;
        SSound.snd_SfxVolume = 8;
        SSound.snd_MusicVolume = 8;
        HuStuff.showMessages = true;
        HuStuff.messageDontfuckwithme = false;
        MMenu.mouseSensitivity = 5;
        MMenu.detailLevel = 0;
        MMenu.screenSize = 7;
        MMenu.screenblocks = 10;
    }

    // ---- mStartControlPanel ----

    // First call pops the main menu open at lastOn.
    @Test
    void startControlPanelOpensMainMenu() {
        MMenu.menuactive = false;
        MMenu.mStartControlPanel();
        assertTrue(MMenu.menuactive);
        assertSame(MMenu.MainDef, MMenu.currentMenu);
    }

    // Reentrant call when menu is already active is a no-op.
    @Test
    void startControlPanelReentrantIsNoOp() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.OptionsDef;
        MMenu.mStartControlPanel();
        assertSame(MMenu.OptionsDef, MMenu.currentMenu);
    }

    // ---- mResponder ESC ----

    // ESC with menu closed pops the menu.
    @Test
    void responderEscOpensMenu() {
        MMenu.menuactive = false;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_ESCAPE;
        boolean ate = MMenu.mResponder(ev);
        assertTrue(ate);
        assertTrue(MMenu.menuactive);
    }

    // ESC with menu open closes it.
    @Test
    void responderEscClosesMenu() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.MainDef;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_ESCAPE;
        MMenu.mResponder(ev);
        assertFalse(MMenu.menuactive);
    }

    // ---- mResponder arrows ----

    // Down arrow advances itemOn (skipping -1 status items).
    @Test
    void responderDownArrowAdvancesItemOn() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.MainDef;
        MMenu.itemOn = 0;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_DOWNARROW;
        MMenu.mResponder(ev);
        assertEquals(1, MMenu.itemOn);
    }

    // Down arrow from last item wraps to 0.
    @Test
    void responderDownArrowWrapsToFirst() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.MainDef;
        MMenu.itemOn = MMenu.MainDef.numitems - 1;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_DOWNARROW;
        MMenu.mResponder(ev);
        assertEquals(0, MMenu.itemOn);
    }

    // Up arrow from 0 wraps to last item.
    @Test
    void responderUpArrowWrapsToLast() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.MainDef;
        MMenu.itemOn = 0;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_UPARROW;
        MMenu.mResponder(ev);
        assertEquals(MMenu.MainDef.numitems - 1, MMenu.itemOn);
    }

    // Down arrow skips status==-1 separator rows in the Options menu.
    @Test
    void responderDownArrowSkipsSeparators() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.OptionsDef;
        MMenu.itemOn = MMenu.scrnsize_idx;   // index 3, next is empty -1
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_DOWNARROW;
        MMenu.mResponder(ev);
        assertEquals(MMenu.mousesens_idx, MMenu.itemOn);  // skipped option_empty1
    }

    // ---- mResponder hotkey alphakey ----

    // Pressing 'o' from MainDef jumps to the Options item.
    @Test
    void responderAlphaKeyJumpsToItem() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.MainDef;
        MMenu.itemOn = 0;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'o';
        MMenu.mResponder(ev);
        assertEquals(MMenu.options_idx, MMenu.itemOn);
    }

    // ---- mResponder backspace ----

    // Backspace pops back to the previous menu.
    @Test
    void responderBackspacePopsToPreviousMenu() {
        MMenu.menuactive = true;
        MMenu.currentMenu = MMenu.OptionsDef;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_BACKSPACE;
        MMenu.mResponder(ev);
        assertSame(MMenu.MainDef, MMenu.currentMenu);
    }

    // ---- F-keys ----

    // F8 toggles HuStuff.showMessages.
    @Test
    void responderF8TogglesMessages() {
        MMenu.menuactive = false;
        HuStuff.showMessages = true;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_F8;
        MMenu.mResponder(ev);
        assertFalse(HuStuff.showMessages);
        assertEquals(Dstrings.MSGOFF, GGame.players[0].message);
    }

    // F11 cycles gamma 0..4.
    @Test
    void responderF11CyclesGamma() {
        MMenu.menuactive = false;
        VVideo.usegamma = 4;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = Doomdef.KEY_F11;
        MMenu.mResponder(ev);
        assertEquals(0, VVideo.usegamma);
        assertEquals(Dstrings.GAMMALVL0, GGame.players[0].message);
    }

    // ---- mStartMessage / mResponder message popup ----

    // A non-input message dispatches its routine on any keypress.
    @Test
    void messageNonInputDispatchesOnAnyKey() {
        final int[] called = { -1 };
        MMenu.mStartMessage("hello", ch -> called[0] = ch, false);
        assertEquals(1, MMenu.messageToPrint);
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'x';
        MMenu.mResponder(ev);
        assertEquals('x', called[0]);
        assertEquals(0, MMenu.messageToPrint);
    }

    // An input-required message ignores keys other than y/n/space/ESC.
    @Test
    void messageInputRequiredIgnoresInvalidKeys() {
        final int[] called = { -1 };
        MMenu.mStartMessage("really?", ch -> called[0] = ch, true);
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'q';
        boolean ate = MMenu.mResponder(ev);
        assertFalse(ate);
        assertEquals(-1, called[0]);
        assertEquals(1, MMenu.messageToPrint);
    }

    // An input-required message accepts 'y'.
    @Test
    void messageInputRequiredAcceptsYes() {
        final int[] called = { -1 };
        MMenu.mStartMessage("really?", ch -> called[0] = ch, true);
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'y';
        MMenu.mResponder(ev);
        assertEquals('y', called[0]);
        assertEquals(0, MMenu.messageToPrint);
    }

    // ---- mTicker ----

    // Skull cursor flips every 8 ticks.
    @Test
    void tickerFlipsSkullEveryEightTics() {
        MMenu.whichSkull = 0;
        MMenu.skullAnimCounter = 1;
        MMenu.mTicker();   // counter→0, flips
        assertEquals(1, MMenu.whichSkull);
        assertEquals(8, MMenu.skullAnimCounter);
        for (int i = 0; i < 8; i++) MMenu.mTicker();
        assertEquals(0, MMenu.whichSkull);
    }

    // ---- mChooseSkill / mEpisode ----

    // Picking skill on Doom 1 defers a new game via gDeferedInitNew.
    @Test
    void chooseSkillDefersNewGame() {
        MMenu.mEpisode(0);                     // sets epi=0 + NewDef
        MMenu.mChooseSkill(MMenu.hurtme);
        assertEquals(GGame.ga_newgame, GGame.gameaction);
        assertEquals(MMenu.hurtme, GGame.dSkill);
        assertEquals(1, GGame.dEpisode);
        assertEquals(1, GGame.dMap);
    }

    // Nightmare skill triggers the confirmation prompt before
    // defering.
    @Test
    void chooseSkillNightmareOpensConfirmation() {
        MMenu.mChooseSkill(MMenu.nightmare);
        assertEquals(1, MMenu.messageToPrint);
        assertNotNull(MMenu.messageString);
    }

    // ---- mSfxVol / mMusicVol clamps ----

    @Test
    void sfxVolDownClampsAtZero() {
        SSound.snd_SfxVolume = 0;
        MMenu.mSfxVol(0);
        assertEquals(0, SSound.snd_SfxVolume);
    }

    @Test
    void sfxVolUpClampsAtFifteen() {
        SSound.snd_SfxVolume = 15;
        MMenu.mSfxVol(1);
        assertEquals(15, SSound.snd_SfxVolume);
    }

    @Test
    void musicVolDownDecrementsToZero() {
        SSound.snd_MusicVolume = 5;
        MMenu.mMusicVol(0);
        assertEquals(4, SSound.snd_MusicVolume);
    }

    // ---- mChangeSensitivity clamps ----

    @Test
    void changeSensitivityClampsBetweenZeroAndNine() {
        MMenu.mouseSensitivity = 0;
        MMenu.mChangeSensitivity(0);
        assertEquals(0, MMenu.mouseSensitivity);
        MMenu.mouseSensitivity = 9;
        MMenu.mChangeSensitivity(1);
        assertEquals(9, MMenu.mouseSensitivity);
    }

    // ---- mSizeDisplay clamps ----

    @Test
    void sizeDisplayClampsScreenSize() {
        MMenu.screenSize = 0;
        MMenu.screenblocks = 3;
        MMenu.mSizeDisplay(0);
        assertEquals(0, MMenu.screenSize);
        MMenu.screenSize = 8;
        MMenu.screenblocks = 11;
        MMenu.mSizeDisplay(1);
        assertEquals(8, MMenu.screenSize);
    }

    // ---- mStringWidth ----

    // Width-of-empty-string is zero.
    @Test
    void stringWidthEmptyIsZero() {
        // huFont[0] may be null in test context — mStringWidth
        // still handles it by adding 4 per char.
        assertEquals(0, MMenu.mStringWidth(""));
    }

    // ---- mSaveGame ----

    // Save game refuses without an active level.
    @Test
    void saveGameRefusesWithoutUsergame() {
        Doomstat.usergame = false;
        MMenu.mSaveGame(0);
        assertEquals(1, MMenu.messageToPrint);
        assertEquals(Dstrings.SAVEDEAD, MMenu.messageString);
    }

    // ---- mEpisode shareware gate ----

    // Shareware episode > 0 prompts for upgrade.
    @Test
    void episodeSharewarePromptsForUpgrade() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        MMenu.mEpisode(1);
        assertEquals(1, MMenu.messageToPrint);
        assertSame(MMenu.ReadDef1, MMenu.currentMenu);
    }
}
