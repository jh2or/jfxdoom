package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import doom.fixed.Fixed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RSkyTest {

    @BeforeEach
    void resetState() {
        RSky.resetForTests();
    }

    @Test
    void constantsMatchCSource() {
        assertEquals("F_SKY1", RSky.SKYFLATNAME);
        assertEquals(22, RSky.ANGLETOSKYSHIFT);
    }

    @Test
    void initSkyMapSetsTextureMidTo100FracUnits() {
        RSky.rInitSkyMap();
        assertEquals(100 * Fixed.FRACUNIT, RSky.skytexturemid);
        assertEquals(Fixed.fromInt(100), RSky.skytexturemid);
    }

    @Test
    void initSkyMapLeavesSkytextureAndFlatnumUntouched() {
        // The shipped C leaves the R_FlatNumForName assignment commented out;
        // the caller (R_InitData/game logic) must set skyflatnum and
        // skytexture. InitSkyMap must not zero or overwrite them.
        RSky.skytexture = 42;
        RSky.skyflatnum = 7;
        RSky.rInitSkyMap();
        assertEquals(42, RSky.skytexture);
        assertEquals(7, RSky.skyflatnum);
    }
}
