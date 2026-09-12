package doom.system;

import doom.fixed.Fixed;
import doom.play.Info;
import doom.play.MRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GGameFidelityTest {
    @AfterEach
    void restoreNormal() {
        Doomstat.fastparm = false;
        Doomstat.respawnparm = false;
        GGame.demorecording = false;
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        GGame.gameaction = GGame.ga_nothing;
    }

    @Test
    void newGameResetsBothRandomStreams() {
        MRandom.prndindex = 147;
        MRandom.rndindex = 35;
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        assertEquals(8, MRandom.pRandom());
        assertEquals(8, MRandom.mRandom());
    }

    @Test
    void respawnRequestSurvivesInitializationAndNightmareDoesNotBecomeRequest() {
        Doomstat.respawnparm = true;
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        assertTrue(Doomstat.respawnmonsters);
        Doomstat.respawnparm = false;
        GGame.gInitNew(Doomstat.sk_nightmare, 1, 1);
        assertTrue(Doomstat.respawnmonsters);
        GGame.gInitNew(Doomstat.sk_medium, 1, 1);
        assertFalse(Doomstat.respawnmonsters);
    }

    @Test
    void fastTablesRemainStableAndRestoreExactlyAcrossLaunches() {
        restoreNormal();
        int[] normal = new int[Info.S_SARG_PAIN2 - Info.S_SARG_RUN1 + 1];
        for (int i = 0; i < normal.length; i++) normal[i] = Info.states[Info.S_SARG_RUN1 + i].tics;
        for (int cycle = 0; cycle < 3; cycle++) {
            Doomstat.fastparm = true;
            for (int repeat = 0; repeat < 3; repeat++) {
                GGame.gInitNew(Doomstat.sk_medium, 1, 1);
                for (int i = 0; i < normal.length; i++) assertEquals(normal[i] >> 1, Info.states[Info.S_SARG_RUN1 + i].tics);
                assertSpeeds(20, 20, 20);
            }
            Doomstat.fastparm = false;
            GGame.gInitNew(Doomstat.sk_nightmare, 1, 1);
            assertSpeeds(20, 20, 20);
            GGame.gInitNew(Doomstat.sk_medium, 1, 1);
            for (int i = 0; i < normal.length; i++) assertEquals(normal[i], Info.states[Info.S_SARG_RUN1 + i].tics);
            assertSpeeds(15, 10, 10);
        }
    }

    @Test
    void finishLevelClearsTemporaryEffectsButKeepsEarnedEquipment() {
        doom.play.Player previous = GGame.players[0];
        doom.play.Player p = new doom.play.Player();
        GGame.players[0] = p;
        try {
            p.mo = new doom.play.Mobj(); p.mo.flags = doom.play.Mobj.MF_SHADOW | doom.play.Mobj.MF_SOLID;
            java.util.Arrays.fill(p.cards, true); java.util.Arrays.fill(p.powers, 99);
            p.extralight = 2; p.fixedcolormap = 32; p.damagecount = 4; p.bonuscount = 6;
            p.health = 73; p.armorpoints = 91; p.armortype = 2; p.backpack = true;
            p.weaponowned[Doomdef.wp_shotgun] = true; p.readyweapon = Doomdef.wp_shotgun;
            p.ammo[Doomdef.am_shell] = 42; p.maxammo[Doomdef.am_shell] = 100;
            GGame.gPlayerFinishLevel(0);
            assertArrayEquals(new boolean[Doomdef.NUMCARDS], p.cards);
            assertArrayEquals(new int[Doomdef.NUMPOWERS], p.powers);
            assertEquals(doom.play.Mobj.MF_SOLID, p.mo.flags);
            assertEquals(0, p.extralight | p.fixedcolormap | p.damagecount | p.bonuscount);
            assertEquals(73, p.health); assertEquals(91, p.armorpoints); assertEquals(2, p.armortype);
            assertTrue(p.backpack); assertTrue(p.weaponowned[Doomdef.wp_shotgun]);
            assertEquals(Doomdef.wp_shotgun, p.readyweapon);
            assertEquals(42, p.ammo[Doomdef.am_shell]); assertEquals(100, p.maxammo[Doomdef.am_shell]);
        } finally { GGame.players[0] = previous; }
    }

    private void assertSpeeds(int baron, int caco, int imp) {
        assertEquals(baron * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_BRUISERSHOT].speed);
        assertEquals(caco * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_HEADSHOT].speed);
        assertEquals(imp * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_TROOPSHOT].speed);
    }
}
