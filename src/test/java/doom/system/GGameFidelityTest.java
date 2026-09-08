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

    private void assertSpeeds(int baron, int caco, int imp) {
        assertEquals(baron * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_BRUISERSHOT].speed);
        assertEquals(caco * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_HEADSHOT].speed);
        assertEquals(imp * Fixed.FRACUNIT, Info.mobjinfo[Info.MT_TROOPSHOT].speed);
    }
}
