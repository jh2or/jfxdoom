package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.sound.Sounds;

import org.junit.jupiter.api.Test;

class InfoDataTest {

    // ----- sprnames[] -----

    @Test
    void sprnamesLengthMatchesNumSprites() {
        assertEquals(Info.NUMSPRITES, Info.sprnames.length);
    }

    @Test
    void sprnamesEdgeEntriesMatchCSource() {
        assertEquals("TROO", Info.sprnames[Info.SPR_TROO]);
        assertEquals("TLP2", Info.sprnames[Info.SPR_TLP2]);
        assertEquals("PLAY", Info.sprnames[Info.SPR_PLAY]);
        assertEquals("BFGG", Info.sprnames[Info.SPR_BFGG]);
    }

    // ----- states[] -----

    @Test
    void statesLengthMatchesNumStates() {
        assertEquals(Info.NUMSTATES, Info.states.length);
    }

    // S_NULL: SPR_TROO frame 0 tics -1 — matches info.c line 136.
    @Test
    void stateZeroIsSNullMatchingCSource() {
        State s = Info.states[Info.S_NULL];
        assertEquals(Info.SPR_TROO, s.sprite);
        assertEquals(0, s.frame);
        assertEquals(-1, s.tics);
        assertNull(s.action); // every action is null until p_pspr/p_enemy port
        assertEquals(Info.S_NULL, s.nextstate);
    }

    // S_PISTOLFLASH: SPR_PISF, frame 32768 (FF_FULLBRIGHT bit), tics 7,
    // nextstate S_LIGHTDONE.
    @Test
    void statePistolFlashMatchesCSource() {
        State s = Info.states[Info.S_PISTOLFLASH];
        assertEquals(Info.SPR_PISF, s.sprite);
        assertEquals(32768, s.frame); // bit 15 = FF_FULLBRIGHT
        assertEquals(7, s.tics);
        assertEquals(Info.S_LIGHTDONE, s.nextstate);
    }

    // S_PUNCH: SPR_PUNG frame 0 tics 1 nextstate S_PUNCH (loops).
    @Test
    void statePunchMatchesCSource() {
        State s = Info.states[Info.S_PUNCH];
        assertEquals(Info.SPR_PUNG, s.sprite);
        assertEquals(1, s.tics);
        assertEquals(Info.S_PUNCH, s.nextstate); // self-loop
    }

    // ----- mobjinfo[] -----

    @Test
    void mobjinfoLengthMatchesNumMobjTypes() {
        assertEquals(Info.NUMMOBJTYPES, Info.mobjinfo.length);
    }

    // MT_PLAYER: doomednum -1, spawnstate S_PLAY, spawnhealth 100,
    // radius 16, height 56, mass 100. From info.c lines 1108–1132.
    @Test
    void mobjinfoPlayerMatchesCSource() {
        MobjInfo p = Info.mobjinfo[Info.MT_PLAYER];
        assertEquals(-1, p.doomednum);
        assertEquals(Info.S_PLAY, p.spawnstate);
        assertEquals(100, p.spawnhealth);
        assertEquals(Info.S_PLAY_RUN1, p.seestate);
        assertEquals(Info.S_PLAY_PAIN, p.painstate);
        assertEquals(255, p.painchance);
        assertEquals(Sounds.sfx_plpain, p.painsound);
        assertEquals(Info.S_PLAY_DIE1, p.deathstate);
        assertEquals(Info.S_PLAY_XDIE1, p.xdeathstate);
        assertEquals(Sounds.sfx_pldeth, p.deathsound);
        assertEquals(0, p.speed);
        assertEquals(16 * Fixed.FRACUNIT, p.radius);
        assertEquals(56 * Fixed.FRACUNIT, p.height);
        assertEquals(100, p.mass);
        // flags: MF_SOLID | MF_SHOOTABLE | MF_DROPOFF | MF_PICKUP | MF_NOTDMATCH
        int expectedFlags = Mobj.MF_SOLID | Mobj.MF_SHOOTABLE | Mobj.MF_DROPOFF
                | Mobj.MF_PICKUP | Mobj.MF_NOTDMATCH;
        assertEquals(expectedFlags, p.flags);
    }

    // MT_POSSESSED: zombieman, doomednum 3004, 20 HP. From info.c lines 1134–1158.
    @Test
    void mobjinfoPossessedMatchesCSource() {
        MobjInfo z = Info.mobjinfo[Info.MT_POSSESSED];
        assertEquals(3004, z.doomednum);
        assertEquals(20, z.spawnhealth);
        assertEquals(Sounds.sfx_posit1, z.seesound);
        assertEquals(Sounds.sfx_podth1, z.deathsound);
        assertEquals(20 * Fixed.FRACUNIT, z.radius);
        assertEquals(56 * Fixed.FRACUNIT, z.height);
        assertEquals(8, z.speed);
        assertEquals(Mobj.MF_SOLID | Mobj.MF_SHOOTABLE | Mobj.MF_COUNTKILL,
                z.flags);
    }

    // MT_TFOG: teleport fog, MF_NOBLOCKMAP|MF_NOGRAVITY.
    @Test
    void mobjinfoTeleportFogIsNonInteractive() {
        MobjInfo fog = Info.mobjinfo[Info.MT_TFOG];
        assertEquals(-1, fog.doomednum);
        int blockMask = Mobj.MF_NOBLOCKMAP | Mobj.MF_NOGRAVITY;
        assertEquals(blockMask, fog.flags & blockMask);
    }

    // ----- Cross-table consistency -----

    @Test
    void everyStateNextstateIsInRange() {
        for (int i = 0; i < Info.states.length; i++) {
            State s = Info.states[i];
            assertNotNull(s, "states[" + i + "] is null");
            int n = s.nextstate;
            assertTrue(n >= 0 && n < Info.NUMSTATES,
                    "states[" + i + "].nextstate=" + n + " out of range");
        }
    }

    @Test
    void everyMobjinfoStateRefIsInRange() {
        for (int i = 0; i < Info.mobjinfo.length; i++) {
            MobjInfo m = Info.mobjinfo[i];
            assertNotNull(m);
            assertStateIdValid("spawnstate", i, m.spawnstate);
            assertStateIdValid("seestate", i, m.seestate);
            assertStateIdValid("painstate", i, m.painstate);
            assertStateIdValid("meleestate", i, m.meleestate);
            assertStateIdValid("missilestate", i, m.missilestate);
            assertStateIdValid("deathstate", i, m.deathstate);
            assertStateIdValid("xdeathstate", i, m.xdeathstate);
            assertStateIdValid("raisestate", i, m.raisestate);
        }
    }

    private static void assertStateIdValid(String label, int mobjIdx, int n) {
        assertTrue(n >= 0 && n < Info.NUMSTATES,
                "mobjinfo[" + mobjIdx + "]." + label + "=" + n + " out of range");
    }
}
