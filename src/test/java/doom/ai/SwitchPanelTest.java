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

    @Test void recognizesOnlyVisibleUnpressedPanelsWithinLocalRange() {
        assertSame(panel, new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0));
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(64.0));
        assertNull(new MotionProbe(MotionProbeTest.at(128, 0)).switchPanel(192.0), "No seeing through the back of a wall");
        Line blocker = MotionProbeTest.line(48, -100, 48, 100); blocker.special = 11;
        PSetup.lines = new Line[] {panel, blocker};
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0), "An occluded panel and a bare special wall are not visible buttons");
        PSetup.lines = new Line[] {panel}; PSetup.sides[0].midtexture = 2;
        assertNull(new MotionProbe(MotionProbeTest.at(0, 0)).switchPanel(192.0));
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
