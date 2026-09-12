package doom.ai;

import doom.play.*;
import doom.system.*;
import doom.wad.WWad;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Controlled weapon fixtures exercise real vanilla animation, missile collision and blast damage. */
class RocketSafetyEngineTest {
    @TempDir Path temp;
    private static final Path WAD = Path.of("reference-wads/DOOM.WAD");
    @BeforeEach void setup() { assumeTrue(Files.isRegularFile(WAD)); PMap.changeSectorOverride = false; }
    @AfterEach void cleanup() {
        doom.ui.StStuff.stStopped = true; doom.ui.StStuff.sbar = null;
        GGame.gameaction = GGame.ga_nothing;
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.advancedemo = false; WWad.wShutdown();
    }
    private void equipLauncher(Episode e) {
        Player p = e.player();
        p.weaponowned[Doomdef.wp_missile] = true; p.ammo[Doomdef.am_misl] = 10;
        p.readyweapon = Doomdef.wp_missile; p.pendingweapon = Doomdef.wp_nochange;
        p.cmd.buttons = 0; p.attackdown = 0;
        PPspr.pSetPsprite(p, PPspr.ps_weapon, Info.S_MISSILE);
    }
    @Test void blockedLaunchIsSuppressedThroughActualWeaponSwitch() throws Exception {
        Episode e = new Episode(WAD, 3, 21000); equipLauncher(e);
        // The initial room has no clear 256-unit rocket envelope in the initial direction.
        assertFalse(RocketSafety.safeShot(Observation.read(e), Observation.read(e).angle()));
        RocketSafety guard = new RocketSafety();
        for (int i = 0; i < 40; i++) {
            var o = Observation.read(e);
            var proposal = new Explorer.Burst(new Action(0, 0, 0, Doomdef.BT_ATTACK), 4);
            var safe = guard.protect(o, proposal, e.tics());
            e.step(safe.action());
        }
        assertEquals(10, e.player().ammo[Doomdef.am_misl]);
        assertNotEquals(Doomdef.wp_missile, e.player().readyweapon);
    }
    @Test void safeRocketActuallyLaunchesAfterEightTicsWithoutNavigationTurningItIntoAWall() throws Exception {
        Episode e = new Episode(WAD, 3, 21000);
        Path demo = temp.resolve("reference.lmp");
        try (var input = getClass().getResourceAsStream("/ai/e1m1-learned.lmp")) { Files.copy(input, demo); }
        var actions = DemoInput.read(demo, 3, 21000);
        // This position is reached by normal recorded movement, rather than a teleport outside the map.
        for (int i = 0; i < 2304; i++) e.step(actions.get(i));
        equipLauncher(e);
        e.player().mo.angle = 0xc0000000;
        e.player().mo.momx = e.player().mo.momy = 0;
        var initial = Observation.read(e);
        assertTrue(RocketSafety.safeShot(initial, initial.angle()));
        RocketSafety guard = new RocketSafety();
        int accepted = -1, launched = -1, selfDamage = 0;
        for (int i = 0; i < 40; i++) {
            var o = Observation.read(e);
            int health = e.player().health, ammo = e.player().ammo[Doomdef.am_misl];
            // Navigation tries to turn and accelerate during the windup; the guard must override it.
            Action proposed = i == 0 ? new Action(0, 0, 0, Doomdef.BT_ATTACK)
                    : new Action(50, 40, 4096, i < 20 ? Doomdef.BT_ATTACK : 0);
            var safe = guard.protect(o, new Explorer.Burst(proposed, 4), e.tics());
            e.step(safe.action());
            if (accepted < 0 && Inventory.read(e.player()).rocketWindupTics() > 0) accepted = i;
            if (e.player().ammo[Doomdef.am_misl] < ammo) launched = i;
            if (e.player().health < health && e.player().attacker == e.player().mo) selfDamage += health - e.player().health;
            if (i < 9) assertEquals(0xc0000000, e.player().mo.angle);
        }
        assertEquals(0, accepted); assertEquals(8, launched);
        assertEquals(9, e.player().ammo[Doomdef.am_misl]); assertEquals(0, selfDamage);
    }
}
