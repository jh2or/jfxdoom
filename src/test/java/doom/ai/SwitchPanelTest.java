package doom.ai;

import doom.play.PSetup;
import doom.render.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SwitchPanelTest {
    private Line[] lines;
    private Side[] sides;
    private RData.Texture[] textures;
    private Line panel;
    @BeforeEach void setup() {
        lines = PSetup.lines; sides = PSetup.sides; textures = RData.textures;
        RData.textures = new RData.Texture[3];
        String[] names = {"STONE", "SW1BRCOM", "SW2BRCOM"};
        for (int i = 0; i < 3; i++) { RData.textures[i] = new RData.Texture(); RData.textures[i].name = names[i]; }
        panel = MotionProbeTest.line(96, 64, 96, -64); panel.sidenum[0] = 0;
        Side face = new Side(); face.midtexture = 1;
        PSetup.sides = new Side[] {face}; PSetup.lines = new Line[] {panel};
    }
    @AfterEach void restore() { PSetup.lines = lines; PSetup.sides = sides; RData.textures = textures; }

    @Test void recognizesBothSwitchStatesButSkipsObservedActivations() {
        assertSame(panel, new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0));
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(64.0));
        assertNull(new MotionProbe(MotionProbeTest.at(128, 0)).switchPanel(192.0), "No seeing through the back of a wall");
        Line blocker = MotionProbeTest.line(48, -100, 48, 100); blocker.special = 11;
        PSetup.lines = new Line[] {panel, blocker};
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0), "An occluded panel and a bare special wall are not visible buttons");
        PSetup.lines = new Line[] {panel}; PSetup.sides[0].midtexture = 2;
        assertSame(panel, new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0), "Some maps start switches in SW2 state");
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0, java.util.Set.of(panel)));
    }

    @Test void seesDistantLocalPanelOutsideTheShortCollisionCache() {
        panel.v1.x = panel.v2.x = 400 * 65536;
        MotionProbe probe = new MotionProbe(MotionProbeTest.at(0, 0));
        assertNull(probe.switchPanel(192.0));
        assertSame(panel, probe.switchPanel(512.0));
        PSetup.lines = new Line[] {panel, MotionProbeTest.line(300, -100, 300, 100)};
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(512.0));
    }

    @Test void facesAnIntendedSwitchInsteadOfSteeringAwayFromItsWall() {
        var o = MotionProbeTest.at(64, 0);
        MotionProbe probe = new MotionProbe(o);
        LocalController controller = new LocalController();
        assertEquals(0, controller.towards(o, probe, 96, 0, 0, false).action().turn());
        var press = controller.towards(o, probe, 96, 0, 1, false).action();
        assertEquals(0, press.turn());
        assertNotEquals(0, press.buttons() & doom.system.Doomdef.BT_USE);
    }

    @Test void canUseAPanelOnATraversableTwoSidedSurface() {
        panel.backsector = new Sector(); panel.backsector.ceilingheight = 128 * 65536;
        MotionProbe probe = new MotionProbe(MotionProbeTest.at(48, 0));
        assertFalse(probe.blocks(panel));
        UseController use = new UseController();
        assertFalse(use.press(probe, 0, 0));
        assertTrue(use.press(probe, 0, 1));
    }
}
