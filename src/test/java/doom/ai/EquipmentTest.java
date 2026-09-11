package doom.ai;

import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentTest {
    private Line[] original;
    @BeforeEach void keepWorld() { original = PSetup.lines; PSetup.lines = new Line[0]; }
    @AfterEach void restoreWorld() { PSetup.lines = original; }
    private Mobj item(int sprite, int x, int y) {
        Mobj m = new Mobj(); m.sprite = sprite; m.x = x * 65536; m.y = y * 65536;
        m.radius = 20 * 65536; m.flags = Mobj.MF_SPECIAL;
        return m;
    }
    private Observation at(Inventory inv, int weapons, Mobj... items) {
        return new Observation(0, 0, 0, 100, inv.bullets() + 4 * inv.shells(), weapons, 8, null,
                0, 56, 16, 0, 0, inv.preferredWeapon(weapons), 0, inv, List.of(items));
    }

    @Test void collectsUsefulNearbyArmorButIgnoresFullArmorAndBlueArmorDowngrade() {
        Mobj green = item(Info.SPR_ARM1, 64, 24), blue = item(Info.SPR_ARM2, 80, 0);
        Observation bare = at(Inventory.PISTOL_START, 3, green);
        assertSame(green, new PickupController().choose(bare, new MotionProbe(bare), 200, 0, 0));
        Observation full = at(new Inventory(200, 2, 50, 0, 0, 1, 9), 3, green, blue);
        assertNull(new PickupController().choose(full, new MotionProbe(full), 200, 0, 0));
        Observation protectedPlayer = at(new Inventory(80, 2, 50, 0, 0, 1, 9), 3, green, blue);
        assertEquals(0, PickupController.value(protectedPlayer, green));
        assertTrue(PickupController.value(protectedPlayer, blue) > 0);
    }

    @Test void ignoresOccludedUnreachableAndDistantItemsAndBoundsFailedDetours() {
        Mobj shotgun = item(Info.SPR_SHOT, 100, 0);
        Observation o = at(Inventory.PISTOL_START, 3, shotgun);
        PSetup.lines = new Line[] {MotionProbeTest.line(50, -100, 50, 100)};
        PickupController controller = new PickupController();
        assertNull(controller.choose(o, new MotionProbe(o), 200, 0, 0));
        PSetup.lines = new Line[0]; shotgun.z = 64 * 65536;
        assertNull(controller.choose(o, new MotionProbe(o), 200, 0, 1));
        shotgun.z = 0; shotgun.x = 220 * 65536;
        assertNull(controller.choose(o, new MotionProbe(o), 200, 0, 2));
        shotgun.x = 100 * 65536;
        assertSame(shotgun, controller.choose(o, new MotionProbe(o), 200, 0, 3));
        assertNull(controller.choose(o, new MotionProbe(o), 200, 0, 108));
        assertNull(controller.choose(o, new MotionProbe(o), 200, 0, 109));
        assertSame(shotgun, controller.choose(o, new MotionProbe(o), 200, 0, 318));
    }

    @Test void recognizesDroppedShotgunAndOnlySeeksShellsWhenUseful() {
        Mobj shotgun = item(Info.SPR_SHOT, 80, 0); shotgun.flags |= Mobj.MF_DROPPED;
        Mobj shells = item(Info.SPR_SHEL, 50, 0);
        Observation bare = at(Inventory.PISTOL_START, 3, shotgun, shells);
        assertSame(shotgun, new PickupController().choose(bare, new MotionProbe(bare), 200, 0, 0));
        assertEquals(0, PickupController.value(bare, shells));
        Observation armed = at(new Inventory(0, 0, 50, 2, 0, 2, 9), 7, shells);
        assertTrue(PickupController.value(armed, shells) > 0);
        Observation supplied = at(new Inventory(0, 0, 50, 50, 0, 2, 9), 7, shells);
        assertEquals(0, PickupController.value(supplied, shells));
    }

    @Test void equipmentAwareShortcutsRetainAnObservedWeaponAcquisition() {
        var path = List.of(new RunTrace.Sample(0, 0, 0, 0, 0, 100, 0),
                new RunTrace.Sample(1, 64, 0, 0, 0, 100, 0, -1, Inventory.PISTOL_START, 7),
                new RunTrace.Sample(2, 0, 0, 0, 0, 100, 0, -1, Inventory.PISTOL_START, 7));
        assertEquals(1, RouteOptimizer.equipmentFrontier(path, 0, 0, 3, java.util.Set.of()));
        assertEquals(2, RouteOptimizer.equipmentFrontier(path, 0, 0, 7, java.util.Set.of()));
    }

    @Test void seeksUnownedKeysAndUsefulHealthButIgnoresRedundantPickups() {
        Mobj key = item(Info.SPR_BKEY, 64, 0), medkit = item(Info.SPR_MEDI, 64, 0);
        Observation healthy = at(Inventory.PISTOL_START, 3, key, medkit);
        assertTrue(PickupController.value(healthy, key) > 0);
        assertEquals(0, PickupController.value(healthy, medkit));
        Observation hurt = new Observation(0, 0, 0, 20, 50, 3, 8, null, 0, 56, 16, 0, 0, 1,
                1 << Doomdef.it_bluecard, Inventory.PISTOL_START, List.of(key, medkit));
        assertEquals(0, PickupController.value(hurt, key));
        assertSame(medkit, new PickupController().choose(hurt, new MotionProbe(hurt), 200, 0, 0));
    }

    @Test void approachesVisibleUnownedKeysAcrossARoomWithALocalProbeAndBoundedDeadline() {
        Mobj key = item(Info.SPR_BKEY, 400, 0);
        Observation o = at(Inventory.PISTOL_START, 3, key);
        PickupController controller = new PickupController();
        assertSame(key, controller.choose(o, new MotionProbe(o), -100, 0, 0));
        assertNull(controller.choose(o, new MotionProbe(o), -100, 0, 105));
        controller.reset();
        PSetup.lines = new Line[] {MotionProbeTest.line(300, -100, 300, 100)};
        assertNull(controller.choose(o, new MotionProbe(o), -100, 0, 106), "Far wall still occludes the key");
        PSetup.lines = new Line[0]; key.x = 513 * 65536;
        assertNull(controller.choose(o, new MotionProbe(o), -100, 0, 107));
    }

    @Test void selectsShotgunWithoutAnEnemyAndFallsBackWhenShellsRunOut() {
        Inventory loaded = new Inventory(0, 0, 50, 2, 0, Doomdef.wp_pistol, Doomdef.wp_nochange);
        assertEquals(Doomdef.wp_shotgun, loaded.preferredWeapon(7));
        Observation o = at(loaded, 7);
        Action change = new LocalController().towards(o, new MotionProbe(o), 200, 0, 0, false).action();
        assertNotEquals(0, change.buttons() & Doomdef.BT_CHANGE);
        assertEquals(Doomdef.wp_shotgun, (change.buttons() & Doomdef.BT_WEAPONMASK) >> Doomdef.BT_WEAPONSHIFT);
        Inventory empty = new Inventory(0, 0, 50, 0, 0, Doomdef.wp_shotgun, Doomdef.wp_nochange);
        assertEquals(Doomdef.wp_pistol, empty.preferredWeapon(7));
        int chaingun = 7 | (1 << Doomdef.wp_chaingun);
        assertEquals(Doomdef.wp_chaingun, empty.preferredWeapon(chaingun));
        Inventory pending = new Inventory(0, 0, 50, 2, 0, Doomdef.wp_pistol, Doomdef.wp_shotgun);
        o = at(pending, 7);
        assertEquals(0, new LocalController().towards(o, new MotionProbe(o), 200, 0, 0, false).action().buttons() & Doomdef.BT_CHANGE);
    }
}
