package doom.ai;

import doom.play.*;
import doom.render.*;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CombatDistanceTest {
    private Line[] lines;
    private Thinker next, previous;
    @BeforeEach void setup() {
        lines = PSetup.lines; PSetup.lines = new Line[0];
        next = PTick.thinkercap.next; previous = PTick.thinkercap.prev;
        PTick.thinkercap.next = PTick.thinkercap.prev = PTick.thinkercap;
    }
    @AfterEach void restore() {
        PSetup.lines = lines; PTick.thinkercap.next = next; PTick.thinkercap.prev = previous;
    }
    private Mobj enemy(int type, int x, int y) {
        Mobj m = new Mobj(); m.type = type; m.x = x * 65536; m.y = y * 65536;
        m.health = 200; m.height = 56 * 65536; m.radius = 20 * 65536; m.flags = Mobj.MF_SHOOTABLE | Mobj.MF_SOLID;
        return m;
    }
    private Observation at(Mobj enemy, int ready, int pending, int windup, boolean rocketReady) {
        Inventory inventory = new Inventory(0, 0, 50, 10, 0, ready, pending, 10, windup, rocketReady);
        return new Observation(0, 0, 0, 100, 90, 7 | (1 << Doomdef.wp_missile), 8, enemy,
                0, 56, 16, 0, 0, Doomdef.wp_shotgun, 0, inventory, List.of());
    }
    private Observation rocket(Mobj enemy) { return at(enemy, Doomdef.wp_missile, Doomdef.wp_nochange, 0, true).withPreferredWeapon(Doomdef.wp_missile); }
    private Explorer.Burst fire() { return new Explorer.Burst(new Action(50, 40, 0, Doomdef.BT_ATTACK), 4); }

    @Test void shotgunnersAndRocketsGetLargerBandsAndRetreatHasHysteresis() {
        Mobj gunner = enemy(Info.MT_SHOTGUY, 250, 0), imp = enemy(Info.MT_TROOP, 250, 0);
        var o = at(gunner, Doomdef.wp_shotgun, Doomdef.wp_nochange, 0, false);
        assertEquals(288, CombatSpacing.band(o, gunner).minimum());
        assertTrue(CombatSpacing.band(o, gunner).minimum() > CombatSpacing.band(o, imp).minimum());
        assertEquals(384, CombatSpacing.band(rocket(gunner), gunner).minimum());
        SurvivalCombat c = new SurvivalCombat();
        assertTrue(c.choose(o, new MotionProbe(o), 0).forward() < 0);
        gunner.x = 300 * 65536;
        assertTrue(c.choose(o, new MotionProbe(o), 1).forward() < 0);
        gunner.x = 340 * 65536;
        assertEquals(0, c.choose(o, new MotionProbe(o), 2).forward());
        gunner.x = 290 * 65536;
        assertEquals(0, c.choose(o, new MotionProbe(o), 3).forward());
    }
    @Test void navigationDoesNotResumeClosingAfterFightTimesOut() {
        var o = at(enemy(Info.MT_SHOTGUY, 220, 0), Doomdef.wp_shotgun, Doomdef.wp_nochange, 0, false);
        LocalController c = new LocalController(true, 2048, LearningStrategy.SURVIVAL);
        c.towards(o, new MotionProbe(o), 400, 0, 0, false);
        Action action = c.towards(o, new MotionProbe(o), 400, 0, 175, false).action();
        assertTrue(action.forward() < 0, "Navigation must not walk back into a close shotgunner during combat cooldown");
    }
    @Test void retreatUsesWorldDirectionAndDoesNotBackThroughWalls() {
        Mobj gunner = enemy(Info.MT_SHOTGUY, 220, 0);
        var base = at(gunner, Doomdef.wp_shotgun, Doomdef.wp_nochange, 0, false);
        var backwards = new Observation(0, 0, Math.PI, 100, 90, base.weapons(), 8, gunner,
                0, 56, 16, 0, 0, Doomdef.wp_shotgun, 0, base.inventory(), List.of());
        var result = CombatSpacing.protect(backwards, new Explorer.Burst(new Action(-25, 0, 0, 0), 2));
        assertTrue(result.action().forward() > 0, "Facing away requires forward movement to retreat");
        PSetup.lines = new Line[]{MotionProbeTest.line(-24, -100, -24, 100)};
        result = CombatSpacing.protect(base, new Explorer.Burst(new Action(25, 0, 0, 0), 2));
        assertTrue(LocalController.safeMotion(base, new MotionProbe(base, true), result.action(), 2));
    }
    @Test void distantTargetIsInsufficientWhenAnEnemyWallOrBarrelCanIntercept() {
        Mobj target = enemy(Info.MT_BRUISER, 480, 0); var o = rocket(target);
        assertTrue(RocketSafety.safeShot(o, 0, List.of(target), new Line[0]));
        Mobj close = enemy(Info.MT_SHOTGUY, 160, 0);
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target, close), new Line[0]));
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target), new Line[]{MotionProbeTest.line(100, -100, 100, 100)}));
        Mobj barrel = enemy(Info.MT_BARREL, -100, 100);
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target, barrel), new Line[0]));
    }
    @Test void checksMissileWidthAutoAimDoorsVerticalAimingAndDiagonalBlastDistance() {
        Mobj target = enemy(Info.MT_BRUISER, 480, 0); var o = rocket(target);
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target), new Line[]{MotionProbeTest.line(100, 20, 240, 20)}));
        Line door = MotionProbeTest.line(120, -100, 120, 100); door.backsector = new Sector();
        door.frontsector.ceilingheight = door.backsector.ceilingheight = 128 * 65536;
        assertTrue(RocketSafety.safeShot(o, 0, List.of(target), new Line[]{door}));
        door.backsector.specialdata = new Object();
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target), new Line[]{door}), "Moving doors can close during the launch delay");
        door.backsector.specialdata = null;
        door.backsector.ceilingheight = 36 * 65536;
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target), new Line[]{door}));
        target.z = 128 * 65536;
        assertFalse(RocketSafety.safeShot(o, 0, List.of(target), new Line[0]), "High auto-aim could hit the nearby ceiling");
        target.z = 0;
        Line diagonal = MotionProbeTest.line(220, 180, 220, 260);
        assertFalse(RocketSafety.safeShot(o, Math.PI / 4, List.of(), new Line[]{diagonal}));
    }
    @Test void guardSuppressesUnsafeFireEvenWhileSwitchingAwayAndCatchesAutomaticPickup() {
        var close = enemy(Info.MT_SHOTGUY, 120, 0);
        var o = rocket(close);
        var result = new RocketSafety().protect(o, fire(), 0);
        assertEquals(0, result.action().buttons() & Doomdef.BT_ATTACK);
        assertEquals(Doomdef.wp_shotgun, (result.action().buttons() & Doomdef.BT_WEAPONMASK) >> Doomdef.BT_WEAPONSHIFT);
        assertEquals(1, result.duration());
        o = at(close, Doomdef.wp_pistol, Doomdef.wp_missile, 0, false);
        result = new RocketSafety().protect(o, fire(), 0);
        assertEquals(0, result.action().buttons() & Doomdef.BT_ATTACK);
        assertEquals(Doomdef.wp_shotgun, (result.action().buttons() & Doomdef.BT_WEAPONMASK) >> Doomdef.BT_WEAPONSHIFT);
    }
    @Test void allowedShotBrakesAndLocksAimThroughLaunchAndEarlyFlightIncludingInheritedWindup() {
        var o = rocket(enemy(Info.MT_BRUISER, 480, 0)); RocketSafety guard = new RocketSafety();
        var result = guard.protect(o, fire(), 10);
        assertNotEquals(0, result.action().buttons() & Doomdef.BT_ATTACK);
        assertEquals(0, result.action().forward()); assertEquals(0, result.action().side());
        var navigation = new Explorer.Burst(new Action(50, 40, 4096, Doomdef.BT_ATTACK), 4);
        for (int tic = 11; tic < 35; tic++) {
            result = guard.protect(o, navigation, tic);
            assertEquals(0, result.action().turn()); assertEquals(0, result.action().forward());
            assertEquals(0, result.action().buttons() & Doomdef.BT_ATTACK); assertEquals(1, result.duration());
        }
        var inherited = at(o.enemy(), Doomdef.wp_missile, Doomdef.wp_nochange, 4, false);
        result = new RocketSafety().protect(inherited, navigation, 100);
        assertEquals(0, result.action().turn()); assertEquals(0, result.action().buttons() & Doomdef.BT_ATTACK);
    }
    @Test void movingPlayerMustSettleAndNewlyRaisingLauncherCannotStartAFiringBurst() {
        var o = rocket(enemy(Info.MT_BRUISER, 480, 0));
        var moving = new Observation(0, 0, 0, 100, 90, o.weapons(), 8, o.enemy(), 0, 56, 16, 4, 0,
                Doomdef.wp_missile, 0, o.inventory(), List.of());
        var result = new RocketSafety().protect(moving, fire(), 0);
        assertTrue(result.action().forward() < 0); assertEquals(0, result.action().buttons() & Doomdef.BT_ATTACK);
        var raising = at(o.enemy(), Doomdef.wp_missile, Doomdef.wp_nochange, 0, false);
        assertEquals(0, new RocketSafety().protect(raising, fire(), 0).action().buttons() & Doomdef.BT_ATTACK);
    }
    @Test void launcherIsSelectedOnlyForADistantUsefulAndClearShot() {
        var target = enemy(Info.MT_BRUISER, 480, 0); var o = at(target, Doomdef.wp_shotgun, Doomdef.wp_nochange, 0, false);
        assertEquals(Doomdef.wp_missile, RocketSafety.preferredWeapon(o));
        target.x = 200 * 65536; assertEquals(Doomdef.wp_shotgun, RocketSafety.preferredWeapon(o));
        target.x = 480 * 65536; PSetup.lines = new Line[]{MotionProbeTest.line(80, -100, 80, 100)};
        assertEquals(Doomdef.wp_shotgun, RocketSafety.preferredWeapon(o));
    }
}
