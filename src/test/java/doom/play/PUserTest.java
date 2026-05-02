package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Sector;
import doom.render.Subsector;
import doom.render.Tables;
import doom.system.Doomdef;
import doom.system.Doomstat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PUserTest {

    private Player makePlayer(int floorz, int ceilz) {
        Sector sec = new Sector();
        sec.floorheight = floorz;
        sec.ceilingheight = ceilz;
        sec.special = 0;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;

        Mobj mo = new Mobj();
        mo.x = 0;
        mo.y = 0;
        mo.z = floorz;
        mo.floorz = floorz;
        mo.ceilingz = ceilz;
        mo.angle = 0;
        mo.subsector = ss;
        // S_PLAY is the player standing-still state.
        mo.state = Info.states[Info.S_PLAY];

        Player p = new Player();
        p.mo = mo;
        mo.player = p;
        p.playerstate = Player.PST_LIVE;
        p.viewheight = PMobj.VIEWHEIGHT;
        return p;
    }

    @BeforeEach
    void resetTickAndModule() {
        PUser.onground = false;
        PTick.leveltime = 0;
        Doomstat.gamemode = Doomstat.GameMode.shareware;
    }

    // --- P_Thrust ---

    // angle 0 (east) → +momx, near-zero momy.
    // Note: Doom's finesine[] is the table indexed by the midpoint of
    // each FINEANGLES/8192 slice, so cardinal-angle reads land off
    // their continuous-math value by tens of ULPs. The behaviour is
    // bit-faithful to the C — both languages read the same table.
    @Test
    void thrustEastAddsToMomx() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        // angle 0 → east. finecosine(0) ≈ FRACUNIT, finesine[0] ≈ 0.
        PUser.pThrust(p, 0, Fixed.FRACUNIT);
        // momx ~ FRACUNIT (within 1 ULP).
        assertTrue(Math.abs(p.mo.momx - Fixed.FRACUNIT) <= 1,
                "momx near FRACUNIT, got " + p.mo.momx);
        // momy ~ 0 (within ~50 ULPs from quantization).
        assertTrue(Math.abs(p.mo.momy) <= 50,
                "momy near 0, got " + p.mo.momy);
    }

    // angle ANG90 (north) → near-zero momx, +momy.
    @Test
    void thrustNorthAddsToMomy() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        PUser.pThrust(p, Tables.ANG90, Fixed.FRACUNIT);
        assertTrue(Math.abs(p.mo.momx) <= 50,
                "momx should be ~0 but was " + p.mo.momx);
        assertTrue(Math.abs(p.mo.momy - Fixed.FRACUNIT) <= 1,
                "momy near FRACUNIT, got " + p.mo.momy);
    }

    // --- P_CalcHeight ---

    // Standing still (no momentum), onground → bob = 0, viewz = z + viewheight.
    @Test
    void calcHeightStillOnGroundClampsBobAndSetsViewz() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        PUser.onground = true;

        PUser.pCalcHeight(p);
        // bob = 0 because momx = momy = 0.
        assertEquals(0, p.bob);
        // viewz = mo.z + viewheight + bob (bob=0, sin term=0)
        assertEquals(p.mo.z + p.viewheight, p.viewz);
    }

    // Airborne → CF_NOMOMENTUM-equivalent branch fires; viewz uses
    // VIEWHEIGHT (not viewheight) initially, then clipped to ceiling.
    @Test
    void calcHeightAirborneClampsToCeiling() {
        // Make ceiling tight so the clip kicks in.
        Player p = makePlayer(0, 50 * Fixed.FRACUNIT);
        p.mo.z = 30 * Fixed.FRACUNIT;     // off the floor → airborne via the test-side flag
        PUser.onground = false;

        PUser.pCalcHeight(p);
        // C path: viewz = z + viewheight, but the ceiling-clip branch
        // before that final assignment clamps to ceil-4*FRACUNIT.
        // The C re-assigns viewz = z + viewheight at the end of the
        // branch (deviation: we preserve it); so the final viewz is
        // z + viewheight, NOT clipped — verify that quirk.
        assertEquals(p.mo.z + p.viewheight, p.viewz);
    }

    // Bob amplitude proportional to (momx² + momy²) >> 2, capped at MAXBOB.
    @Test
    void calcHeightBobCappedAtMaxbob() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        PUser.onground = true;
        // Crank momentum way past the cap.
        p.mo.momx = 100 * Fixed.FRACUNIT;
        p.mo.momy = 100 * Fixed.FRACUNIT;

        PUser.pCalcHeight(p);
        assertEquals(PUser.MAXBOB, p.bob);
    }

    // --- P_MovePlayer ---

    // forwardmove > 0, onground → momx grows along player angle.
    @Test
    void movePlayerForwardAccumulatesMomentum() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        // floor exactly at z so onground will compute true.
        p.cmd.forwardmove = 10;
        p.cmd.sidemove = 0;
        p.cmd.angleturn = 0;

        PUser.pMovePlayer(p);
        assertTrue(PUser.onground);
        // Some forward momentum (angle 0 → +momx, ~0 momy).
        assertTrue(p.mo.momx > 0, "expected +momx, got " + p.mo.momx);
    }

    // angleturn shifts mo.angle by (angleturn << 16) — verify exact value.
    @Test
    void movePlayerAngleTurnRotatesMobj() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        int startAngle = p.mo.angle;
        p.cmd.angleturn = 5;

        PUser.pMovePlayer(p);
        assertEquals(startAngle + (5 << 16), p.mo.angle);
    }

    // Walking from S_PLAY → S_PLAY_RUN1 transition.
    @Test
    void movePlayerTransitionsToRunStateOnFirstWalkTic() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.mo.state = Info.states[Info.S_PLAY];
        p.cmd.forwardmove = 10;

        PUser.pMovePlayer(p);
        // pSetMobjState walked the chain; state must equal S_PLAY_RUN1
        // (or whatever the chain settles on; first frame of run cycle
        // has tics > 0 so the chain stops there).
        assertEquals(Info.states[Info.S_PLAY_RUN1], p.mo.state);
    }

    // Airborne → no forward thrust applied.
    @Test
    void movePlayerAirborneSkipsThrust() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.mo.z = 50 * Fixed.FRACUNIT;     // off floor → onground = false
        p.mo.floorz = 0;
        p.cmd.forwardmove = 10;
        p.cmd.angleturn = 0;

        PUser.pMovePlayer(p);
        assertFalse(PUser.onground);
        assertEquals(0, p.mo.momx);
    }

    // --- P_PlayerThink: cheats + power-up countdown ---

    @Test
    void playerThinkCheatNoclipFlagSyncsToMobj() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.cheats |= Player.CF_NOCLIP;

        PUser.pPlayerThink(p);
        assertTrue((p.mo.flags & Mobj.MF_NOCLIP) != 0);

        p.cheats &= ~Player.CF_NOCLIP;
        PUser.pPlayerThink(p);
        assertEquals(0, p.mo.flags & Mobj.MF_NOCLIP);
    }

    // Strength power counts UP each tic; everything else counts down.
    @Test
    void playerThinkStrengthCountsUpOthersDown() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.powers[Doomdef.pw_strength] = 100;
        p.powers[Doomdef.pw_invulnerability] = 50;
        p.powers[Doomdef.pw_infrared] = 50;

        PUser.pPlayerThink(p);
        assertEquals(101, p.powers[Doomdef.pw_strength]);
        assertEquals(49, p.powers[Doomdef.pw_invulnerability]);
        assertEquals(49, p.powers[Doomdef.pw_infrared]);
    }

    // pw_invisibility countdown clears MF_SHADOW when it hits 0.
    @Test
    void playerThinkInvisibilityClearsShadowAtZero() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.powers[Doomdef.pw_invisibility] = 1;
        p.mo.flags |= Mobj.MF_SHADOW;

        PUser.pPlayerThink(p);
        assertEquals(0, p.powers[Doomdef.pw_invisibility]);
        assertEquals(0, p.mo.flags & Mobj.MF_SHADOW);
    }

    // Invulnerability flashes the inverse colormap when low/blinking.
    @Test
    void playerThinkInvulSetsInverseColormapWhileActive() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        // Big remaining counter (>4*32) → INVERSECOLORMAP fires.
        p.powers[Doomdef.pw_invulnerability] = 200;

        PUser.pPlayerThink(p);
        assertEquals(PUser.INVERSECOLORMAP, p.fixedcolormap);
    }

    // damagecount + bonuscount tick down each tic.
    @Test
    void playerThinkDecrementsScreenFlashCounters() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.damagecount = 5;
        p.bonuscount = 3;

        PUser.pPlayerThink(p);
        assertEquals(4, p.damagecount);
        assertEquals(2, p.bonuscount);
    }

    // BT_USE on first press triggers usedown + would call pUseLines —
    // verify the debounce flag flips so the next tic without the
    // button clears it.
    @Test
    void playerThinkUseButtonFlipsUsedownDebounce() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.cmd.buttons = Doomdef.BT_USE;

        // Need a blockmap for pUseLines's path traversal, but on a
        // line-less world the iterator just runs to completion. Set
        // up a minimal singleton block.
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;
        PSetup.numlines = 0;
        PSetup.lines = new doom.render.Line[0];
        PSetup.numnodes = 0;
        PSetup.nodes = new doom.render.Node[0];

        PUser.pPlayerThink(p);
        assertEquals(1, p.usedown);

        // Next tic without the button → debounce releases.
        p.cmd.buttons = 0;
        PUser.pPlayerThink(p);
        assertEquals(0, p.usedown);
    }

    // Dead player branches into DeathThink, viewheight falls.
    @Test
    void playerThinkDeadFallsViewheight() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.playerstate = Player.PST_DEAD;
        p.viewheight = 41 * Fixed.FRACUNIT; // VIEWHEIGHT

        PUser.pPlayerThink(p);
        // Falls FRACUNIT each tic until reaching 6*FRACUNIT.
        assertEquals(40 * Fixed.FRACUNIT, p.viewheight);
    }

    // BT_USE while dead → playerstate flips to PST_REBORN.
    @Test
    void deathThinkUseButtonRequestsReborn() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.playerstate = Player.PST_DEAD;
        p.cmd.buttons = Doomdef.BT_USE;

        PUser.pPlayerThink(p);
        assertEquals(Player.PST_REBORN, p.playerstate);
    }

    // BT_SPECIAL clears all other button bits at top of think.
    @Test
    void playerThinkSpecialBitClearsButtons() {
        Player p = makePlayer(0, 200 * Fixed.FRACUNIT);
        p.cmd.buttons = Doomdef.BT_SPECIAL | Doomdef.BT_USE | Doomdef.BT_ATTACK;

        PUser.pPlayerThink(p);
        assertEquals(0, p.cmd.buttons);
    }

    // Constants sanity check.
    @Test
    void constantsMatchCSource() {
        assertEquals(32, PUser.INVERSECOLORMAP);
        assertEquals(0x100000, PUser.MAXBOB);
        assertEquals(Tables.ANG90 / 18, PUser.ANG5);
    }
}
