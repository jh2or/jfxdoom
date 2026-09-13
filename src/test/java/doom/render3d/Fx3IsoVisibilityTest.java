package doom.render3d;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Fx3IsoVisibilityTest {
    @Test void foregroundWallIsCutButBackgroundWallIsNot() {
        assertTrue(Fx3IsoVisibility.obstructs(-100,0,0,-100, 0,128, 0,0,0,56,false));
        assertFalse(Fx3IsoVisibility.obstructs(100,0,0,100, 0,56, 0,0,0,56,false));
        assertFalse(Fx3IsoVisibility.obstructs(-500,0,-400,0, 0,128, 0,0,0,56,false));
    }

    @Test void wallBelowPlayerDoesNotObscureRaisedPlatform() {
        assertFalse(Fx3IsoVisibility.obstructs(-100,0,0,-100, 0,24, 0,0,128,56,false));
        assertTrue(Fx3IsoVisibility.obstructs(-100,0,0,-100, 128,256, 0,0,128,56,false));
    }

    @Test void hysteresisKeepsNearbyEdgeCutUntilItLeavesExpandedRegion() {
        assertFalse(Fx3IsoVisibility.obstructs(-29,-71,-27,-73, 0,128, 0,0,0,56,false));
        assertTrue(Fx3IsoVisibility.obstructs(-29,-71,-27,-73, 0,128, 0,0,0,56,true));
        assertFalse(Fx3IsoVisibility.obstructs(-29,-71,-27,-73, 0,128, 400,400,0,56,true));
    }

    @Test void closedDoorKeepsSillButRaisedUpperFragmentDoesNotFloat() {
        assertEquals(24, Fx3IsoVisibility.retainedTop(0,0,128));
        assertEquals(12, Fx3IsoVisibility.retainedTop(0,0,12));
        assertEquals(64, Fx3IsoVisibility.retainedTop(0,64,128));
        assertEquals(152, Fx3IsoVisibility.retainedTop(128,128,256));
    }
}
