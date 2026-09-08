package doom.ui;

import doom.system.Doomdef;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JfxDoomAppKeyTest {
    @Test
    void functionKeysMapToDoomScanCodesWithoutPrintableText() {
        KeyCode[] keys = {KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4,
                KeyCode.F5, KeyCode.F6, KeyCode.F7, KeyCode.F8,
                KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12};
        int[] expected = {Doomdef.KEY_F1, Doomdef.KEY_F2, Doomdef.KEY_F3, Doomdef.KEY_F4,
                Doomdef.KEY_F5, Doomdef.KEY_F6, Doomdef.KEY_F7, Doomdef.KEY_F8,
                Doomdef.KEY_F9, Doomdef.KEY_F10, Doomdef.KEY_F11, Doomdef.KEY_F12};
        for (int i = 0; i < keys.length; i++) {
            assertEquals(expected[i], JfxDoomApp.mapKey(keys[i], ""), keys[i].name());
            assertEquals(expected[i], JfxDoomApp.mapKey(keys[i], null), keys[i].name());
        }
    }
}
