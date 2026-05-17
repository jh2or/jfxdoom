package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.MRandom;
import doom.play.Mobj;
import doom.play.Player;
import doom.render.Patch;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.Dstrings;
import doom.system.GGame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StStuffTest {

    // Tiny stub patch so stLoadGraphics-equivalent code paths don't NPE
    // when the test seeds StStuff state without a WAD.
    private static Patch stubPatch() {
        return Patch.fromLump(new byte[] {
                1, 0,           // width 1
                12, 0,          // height 12
                0, 0,           // leftoffset
                0, 0,           // topoffset
                0, 0, 0, 0,     // columnofs[0]
        });
    }

    @BeforeEach
    void resetState() {
        Player p = new Player();
        p.health      = 100;
        p.armorpoints = 0;
        p.armortype   = 0;
        p.readyweapon = Doomdef.wp_pistol;
        p.message     = null;
        GGame.players[0] = p;
        GGame.consoleplayer = 0;
        GGame.playeringame[0] = true;
        Doomstat.netgame = false;
        Doomstat.deathmatch = 0;
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        Doomstat.gameepisode = 1;
        Doomstat.gamemap = 1;

        // Re-seed StStuff statics so tests don't bleed between cases.
        StStuff.plyr = p;
        StStuff.stOldHealth   = -1;
        StStuff.stPalette     = -1;
        StStuff.stMsgCounter  = 1;       // so the --counter check in stUpdateWidgets doesn't go negative
        StStuff.stFaceIndex   = 0;
        StStuff.stFaceCount   = 0;
        StStuff.stFragsCount  = 0;
        StStuff.stRandomNumber = 0;
        StStuff.stStatusbarOn[0]  = true;
        StStuff.stNotDeathmatch[0] = false;
        StStuff.stArmsOn[0]   = false;
        StStuff.stFragsOn[0]  = false;
        StStuff.stStopped     = true;
        StStuff.veryFirstTime = true;
        StStuff.facePriority  = 0;
        StStuff.faceLastAttackDown = -1;
        StStuff.painOldHealth = -1;
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            StStuff.oldWeaponsOwned[i] = false;
        }
        // Stub the patch caches so guards see "graphics loaded".
        for (int i = 0; i < 10; i++) {
            StStuff.tallnum[i]  = stubPatch();
            StStuff.shortnum[i] = stubPatch();
        }
        for (int i = 0; i < StStuff.faces.length; i++) {
            StStuff.faces[i] = stubPatch();
        }
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            StStuff.keys[i] = stubPatch();
        }
        for (int i = 0; i < 6; i++) {
            StStuff.arms[i][0] = stubPatch();
            StStuff.arms[i][1] = stubPatch();
        }
        StStuff.tallpercent = stubPatch();
        StStuff.sbar        = stubPatch();
        StStuff.armsbg      = stubPatch();
        StStuff.faceback    = stubPatch();
        StLib.sttminus      = stubPatch();
    }

    // Tear down the patch-cache stubs so cross-class tests
    // (PMobjTest's pSpawnPlayer → stStart) still hit the
    // sbar==null guard.
    @AfterEach
    void clearPatches() {
        StStuff.sbar = null;
        StStuff.armsbg = null;
        StStuff.faceback = null;
        StStuff.tallpercent = null;
        for (int i = 0; i < 10; i++) {
            StStuff.tallnum[i]  = null;
            StStuff.shortnum[i] = null;
        }
        for (int i = 0; i < StStuff.faces.length; i++) {
            StStuff.faces[i] = null;
        }
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            StStuff.keys[i] = null;
        }
        for (int i = 0; i < 6; i++) {
            StStuff.arms[i][0] = null;
            StStuff.arms[i][1] = null;
        }
        StLib.sttminus = null;
    }

    // ---- stCalcPainOffset ----

    // Full health → no pain offset (face strip starts at index 0).
    @Test
    void calcPainOffsetFullHealthIsZero() {
        StStuff.plyr.health = 100;
        assertEquals(0, StStuff.stCalcPainOffset());
    }

    // Health 0 → max pain offset (last pain face).
    @Test
    void calcPainOffsetZeroHealthIsMaxStride() {
        StStuff.plyr.health = 0;
        // Formula: ST_FACESTRIDE * ((100 - 0) * NUMPAINFACES / 101) =
        // 7 * (500 / 101) = 7 * 4 = 28.
        assertEquals(StStuff.ST_FACESTRIDE * 4, StStuff.stCalcPainOffset());
    }

    // Health > 100 (soul-sphere etc.) is clamped to 100 → offset 0.
    @Test
    void calcPainOffsetClampsAtHundred() {
        StStuff.plyr.health = 200;
        assertEquals(0, StStuff.stCalcPainOffset());
    }

    // ---- stDoPaletteStuff ----

    // No damage / bonus → palette 0 (base).
    @Test
    void doPaletteStuffNoDamageNoBonusGivesBasePalette() {
        StStuff.plyr.damagecount = 0;
        StStuff.plyr.bonuscount  = 0;
        StStuff.plyr.powers[Doomdef.pw_strength] = 0;
        StStuff.plyr.powers[Doomdef.pw_ironfeet] = 0;
        StStuff.stPalette = -1;  // force re-upload check
        StStuff.luPalette = 0;   // not actually read because palette==0 stays 0... wait yes it is.
        // The palette upload reads the WAD, which the test doesn't
        // load. Sidestep by pre-loading stPalette to 0 too so the
        // "if (palette != stPalette)" branch doesn't fire.
        StStuff.stPalette = 0;
        StStuff.stDoPaletteStuff();
        assertEquals(0, StStuff.stPalette);
    }

    // damagecount > 0 → palette in red-shift band.
    @Test
    void doPaletteStuffPicksRedShiftSlot() {
        StStuff.plyr.damagecount = 16;
        StStuff.stPalette = -2;  // distinct from the value we'll compute
        // The test only checks the computed slot, not the upload, so
        // we pre-equalise to avoid the wCacheLumpNum call:
        // for damagecount=16, (16+7)>>3 = 2; +STARTREDPALS(1) = 3.
        int expected = ((16 + 7) >> 3) + StStuff.STARTREDPALS;
        StStuff.stPalette = expected;
        StStuff.stDoPaletteStuff();
        assertEquals(expected, StStuff.stPalette);
    }

    // Radiation suit (ironfeet) gives RADIATIONPAL slot.
    @Test
    void doPaletteStuffRadiationSuitGivesRadiationPalette() {
        StStuff.plyr.damagecount = 0;
        StStuff.plyr.bonuscount = 0;
        StStuff.plyr.powers[Doomdef.pw_strength] = 0;
        StStuff.plyr.powers[Doomdef.pw_ironfeet] = 4 * 32 + 1; // > 4*32 branch
        StStuff.stPalette = StStuff.RADIATIONPAL;
        StStuff.stDoPaletteStuff();
        assertEquals(StStuff.RADIATIONPAL, StStuff.stPalette);
    }

    // ---- stUpdateFaceWidget ----

    // Dead player → ST_DEADFACE.
    @Test
    void updateFaceWidgetDeadPlayerPicksDeadFace() {
        StStuff.plyr.health = 0;
        StStuff.stOldHealth = 0;
        StStuff.stUpdateFaceWidget();
        assertEquals(StStuff.ST_DEADFACE, StStuff.stFaceIndex);
    }

    // CF_GODMODE → ST_GODFACE.
    @Test
    void updateFaceWidgetGodModeShowsGodFace() {
        StStuff.plyr.health = 100;
        StStuff.plyr.cheats = Player.CF_GODMODE;
        StStuff.stOldHealth = 100;
        // Face priority resets each call once stFaceCount reaches 0.
        StStuff.stUpdateFaceWidget();
        assertEquals(StStuff.ST_GODFACE, StStuff.stFaceIndex);
    }

    // Picking up a new weapon → evil grin face (offset within current
    // pain band).
    @Test
    void updateFaceWidgetEvilGrinOnWeaponPickup() {
        StStuff.plyr.health = 100;
        StStuff.plyr.bonuscount = 4;
        StStuff.plyr.weaponowned[Doomdef.wp_shotgun] = true;
        StStuff.oldWeaponsOwned[Doomdef.wp_shotgun]  = false;
        StStuff.stOldHealth = 100;
        StStuff.stUpdateFaceWidget();
        assertEquals(StStuff.ST_EVILGRINOFFSET, StStuff.stFaceIndex);
    }

    // ---- stUpdateWidgets ----

    // Frags count in deathmatch nets other players' kills minus the
    // player's own deaths. self-frag entry subtracts.
    @Test
    void updateWidgetsComputesFragsCount() {
        Doomstat.deathmatch = 1;
        StStuff.plyr.frags[0] = 3;  // self
        StStuff.plyr.frags[1] = 5;
        StStuff.plyr.frags[2] = 2;
        StStuff.plyr.frags[3] = 1;
        StStuff.stMsgCounter = 1;   // avoid the chat-popup branch
        StStuff.stUpdateWidgets();
        // 5 + 2 + 1 - 3 = 5
        assertEquals(5, StStuff.stFragsCount);
    }

    // Health / armor / ammo holders are refreshed from the player.
    @Test
    void updateWidgetsRefreshesViews() {
        StStuff.plyr.health      = 87;
        StStuff.plyr.armorpoints = 42;
        StStuff.plyr.ammo[2]     = 25;
        StStuff.plyr.maxammo[2]  = 50;
        StStuff.stMsgCounter = 1;
        StStuff.stUpdateWidgets();
        assertEquals(87, StStuff.healthView[0]);
        assertEquals(42, StStuff.armorView[0]);
        assertEquals(25, StStuff.ammoView[2][0]);
        assertEquals(50, StStuff.maxAmmoView[2][0]);
    }

    // Keycard slots: card present → slot index, absent → -1, skull key
    // overrides keycard.
    @Test
    void updateWidgetsKeyboxSlotsTrackCardsAndSkulls() {
        StStuff.plyr.cards[0] = true;   // blue card
        StStuff.plyr.cards[1] = false;
        StStuff.plyr.cards[2] = true;   // red card
        StStuff.plyr.cards[3 + 1] = true; // yellow skull overrides yellow slot
        StStuff.stMsgCounter = 1;
        StStuff.stUpdateWidgets();
        assertEquals(0, StStuff.keyboxes[0]);
        assertEquals(4, StStuff.keyboxes[1]);  // skull idx (1+3)
        assertEquals(2, StStuff.keyboxes[2]);
    }

    // Weapon-owned mirror tracks the boolean[] every tic.
    @Test
    void updateWidgetsMirrorsWeaponOwnership() {
        StStuff.plyr.weaponowned[2] = true;  // wp_shotgun
        StStuff.plyr.weaponowned[5] = true;  // wp_missile
        StStuff.stMsgCounter = 1;
        StStuff.stUpdateWidgets();
        assertEquals(1, StStuff.armsOwnedView[1][0]); // index 1 → weapon 2
        assertEquals(1, StStuff.armsOwnedView[4][0]); // index 4 → weapon 5
        assertEquals(0, StStuff.armsOwnedView[0][0]); // wp_pistol slot empty
    }

    // ---- stTicker ----

    // stTicker bumps stClock, samples M_Random, and snapshots health.
    @Test
    void tickerAdvancesClockAndSnapshotsHealth() {
        StStuff.plyr.health = 64;
        int before = StStuff.stClock;
        int rndBefore = MRandom.rndindex;
        StStuff.stMsgCounter = 1;
        StStuff.stTicker();
        assertEquals(before + 1, StStuff.stClock);
        assertEquals((rndBefore + 1) & 0xFF, MRandom.rndindex);
        assertEquals(64, StStuff.stOldHealth);
    }

    // ---- stStart guard ----

    // Without graphics loaded (sbar==null), stStart is a no-op and
    // leaves stStopped true. Mirrors HuStuff's huFont guard.
    @Test
    void startWithoutGraphicsIsNoOp() {
        StStuff.sbar = null;
        StStuff.stStopped = true;
        StStuff.stStart();
        assertTrue(StStuff.stStopped);
    }



    // ---- stResponder cheats ----

    private static void typeCheat(String s) {
        for (int i = 0; i < s.length(); i++) {
            DEvent ev = new DEvent();
            ev.type  = DEvent.EV_KEYDOWN;
            ev.data1 = s.charAt(i);
            StStuff.stResponder(ev);
        }
    }

    // Type "iddqd" → CF_GODMODE flips on + health pinned to 100.
    @Test
    void responderIddqdEnablesGodMode() {
        StStuff.plyr.cheats = 0;
        StStuff.plyr.health = 30;
        StStuff.plyr.mo = new Mobj();
        StStuff.plyr.mo.health = 30;

        typeCheat("iddqd");

        assertTrue((StStuff.plyr.cheats & Player.CF_GODMODE) != 0);
        assertEquals(100, StStuff.plyr.health);
        assertEquals(100, StStuff.plyr.mo.health);
        assertEquals(Dstrings.STSTR_DQDON, StStuff.plyr.message);
    }

    // Type "idfa" → max ammo + all weapons + armor 200/2.
    @Test
    void responderIdfaFillsArmsAndAmmo() {
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            StStuff.plyr.ammo[i]    = 0;
            StStuff.plyr.maxammo[i] = 200;
        }
        typeCheat("idfa");
        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            assertTrue(StStuff.plyr.weaponowned[i], "weapon " + i);
        }
        for (int i = 0; i < Doomdef.NUMAMMO; i++) {
            assertEquals(StStuff.plyr.maxammo[i], StStuff.plyr.ammo[i]);
        }
        assertEquals(200, StStuff.plyr.armorpoints);
        assertEquals(2, StStuff.plyr.armortype);
        assertEquals(Dstrings.STSTR_FAADDED, StStuff.plyr.message);
    }

    // Type "idkfa" → idfa effects + all keycards.
    @Test
    void responderIdkfaAlsoGivesKeys() {
        typeCheat("idkfa");
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            assertTrue(StStuff.plyr.cards[i], "card " + i);
        }
        assertEquals(Dstrings.STSTR_KFAADDED, StStuff.plyr.message);
    }

    // "idclip" toggles CF_NOCLIP (Doom 2 cheat — accepted by both
    // gamemodes via the chtCheckCheat fallthrough).
    @Test
    void responderIdclipTogglesNoclip() {
        StStuff.plyr.cheats = 0;
        typeCheat("idclip");
        assertTrue((StStuff.plyr.cheats & Player.CF_NOCLIP) != 0);
        assertEquals(Dstrings.STSTR_NCON, StStuff.plyr.message);
    }

    // "idchoppers" gives the chainsaw + invulnerability power.
    @Test
    void responderIdchoppersGivesChainsawAndInvuln() {
        StStuff.plyr.weaponowned[Doomdef.wp_chainsaw] = false;
        StStuff.plyr.powers[Doomdef.pw_invulnerability] = 0;
        typeCheat("idchoppers");
        assertTrue(StStuff.plyr.weaponowned[Doomdef.wp_chainsaw]);
        assertTrue(StStuff.plyr.powers[Doomdef.pw_invulnerability] != 0);
        assertEquals(Dstrings.STSTR_CHOPPERS, StStuff.plyr.message);
    }

    // "idclev <epsd><map>" defers a new game with those parameters.
    @Test
    void responderIdclevDefersNewGame() {
        GGame.gameaction = GGame.ga_nothing;
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        typeCheat("idclev12");
        assertEquals(GGame.ga_newgame, GGame.gameaction);
        assertEquals(1, GGame.dEpisode);
        assertEquals(2, GGame.dMap);
    }

    // The automap mode-flip notify event swaps stGameState.
    @Test
    void responderAutomapEnterSetsAutomapState() {
        StStuff.stGameState = StStuff.FirstPersonState;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYUP;
        ev.data1 = AmMap.AM_MSGENTERED;
        StStuff.stResponder(ev);
        assertEquals(StStuff.AutomapState, StStuff.stGameState);
        assertTrue(StStuff.stFirstTime);
    }
}
