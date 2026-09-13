package doom.render3d;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Fx3IsoCameraTest {
    @Test void axesHaveEqualForeshorteningAndExpectedSigns() {
        double x = Math.hypot(Fx3IsoCamera.right(1,0), Fx3IsoCamera.down(1,0,0));
        double y = Math.hypot(Fx3IsoCamera.right(0,1), Fx3IsoCamera.down(0,1,0));
        double z = Math.abs(Fx3IsoCamera.down(0,0,1));
        assertEquals(Math.sqrt(2.0/3), x, 1e-12);
        assertEquals(x, y, 1e-12);
        assertEquals(x, z, 1e-12);
        assertTrue(Fx3IsoCamera.right(1,0) > 0);
        assertTrue(Fx3IsoCamera.right(0,1) < 0);
        assertTrue(Fx3IsoCamera.down(0,0,1) < 0);
    }

    @Test void translationAlongViewingRayChangesOnlyDepth() {
        assertEquals(Fx3IsoCamera.right(10,20), Fx3IsoCamera.right(110,120), 1e-12);
        assertEquals(Fx3IsoCamera.down(10,20,30), Fx3IsoCamera.down(110,120,-70), 1e-12);
        assertTrue(Fx3IsoCamera.depth(110,120,-70) > Fx3IsoCamera.depth(10,20,30));
        assertTrue(Fx3IsoCamera.depth(10,20,80) < Fx3IsoCamera.depth(10,20,30));
        assertEquals(2 * Fx3IsoCamera.scale(640), Fx3IsoCamera.scale(1280));
    }

    @Test void directionalFramesMatchDoomConventionAcrossWraparound() {
        int[] expected = {5,4,3,2,1,0,7,6};
        for (int i=0; i<8; i++) assertEquals(expected[i], Fx3IsoCamera.rotation(i*0x20000000));
        assertEquals(5, Fx3IsoCamera.rotation(0x0fffffff));
        assertEquals(5, Fx3IsoCamera.rotation(0x10000000));
        assertEquals(4, Fx3IsoCamera.rotation(0x10000001));
    }
}
