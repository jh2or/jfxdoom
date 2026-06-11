package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EndoomTest {

    // ---- dimensions ----

    @Test
    void screenDimensionsMatchVgaTextMode() {
        assertEquals(80, Endoom.COLS);
        assertEquals(25, Endoom.ROWS);
        assertEquals(4000, Endoom.SCREEN_BYTES);
    }

    // ---- glyph table ----

    // ASCII range passes through unchanged.
    @Test
    void glyphAsciiPassThrough() {
        assertEquals('A', Endoom.glyph(0x41));
        assertEquals(' ', Endoom.glyph(0x20));
        assertEquals('~', Endoom.glyph(0x7E));
        assertEquals('0', Endoom.glyph(0x30));
    }

    // Classic CP437 box-drawing / shade glyphs — the ones ENDOOM
    // screens are mostly built from.
    @Test
    void glyphBoxDrawingAndShades() {
        assertEquals('░', Endoom.glyph(0xB0)); // ░ light shade
        assertEquals('▒', Endoom.glyph(0xB1)); // ▒ medium shade
        assertEquals('▓', Endoom.glyph(0xB2)); // ▓ dark shade
        assertEquals('█', Endoom.glyph(0xDB)); // █ full block
        assertEquals('│', Endoom.glyph(0xB3)); // │ vertical
        assertEquals('═', Endoom.glyph(0xCD)); // ═ double horizontal
        assertEquals('╔', Endoom.glyph(0xC9)); // ╔ double down-right
        assertEquals('╝', Endoom.glyph(0xBC)); // ╝ double up-left
    }

    // Low range uses the DOS display glyphs, not C0 controls.
    @Test
    void glyphLowRangeUsesDosDisplayGlyphs() {
        assertEquals(' ',      Endoom.glyph(0x00)); // NUL renders blank
        assertEquals('☺', Endoom.glyph(0x01)); // ☺
        assertEquals('♥', Endoom.glyph(0x03)); // ♥
        assertEquals('↑', Endoom.glyph(0x18)); // ↑
        assertEquals('⌂', Endoom.glyph(0x7F)); // ⌂
    }

    // High-half accents / Greek spot checks.
    @Test
    void glyphHighHalfSpotChecks() {
        assertEquals('Ç', Endoom.glyph(0x80)); // Ç
        assertEquals('ü', Endoom.glyph(0x81)); // ü
        assertEquals('α', Endoom.glyph(0xE0)); // α
        assertEquals('°', Endoom.glyph(0xF8)); // °
        assertEquals('■', Endoom.glyph(0xFE)); // ■
        assertEquals(' ', Endoom.glyph(0xFF)); // NBSP
    }

    // Byte values past 255 are masked, mirroring unsigned reads.
    @Test
    void glyphMasksToByte() {
        assertEquals(Endoom.glyph(0x41), Endoom.glyph(0x141));
    }

    // ---- attribute decoding ----

    // attr 0x1E = yellow on blue: fg 14, bg 1, no blink.
    @Test
    void attributeYellowOnBlue() {
        assertEquals(14, Endoom.fg(0x1E));
        assertEquals(1, Endoom.bg(0x1E));
        assertFalse(Endoom.blink(0x1E));
    }

    // Bit 7 sets blink and is excluded from the bg index.
    @Test
    void attributeBlinkBitMaskedFromBg() {
        assertEquals(1, Endoom.bg(0x9E));
        assertEquals(14, Endoom.fg(0x9E));
        assertTrue(Endoom.blink(0x9E));
    }

    // White-on-black default text attribute.
    @Test
    void attributeDefaultTextWhiteOnBlack() {
        assertEquals(7, Endoom.fg(0x07));
        assertEquals(0, Endoom.bg(0x07));
        assertFalse(Endoom.blink(0x07));
    }

    // ---- palette ----

    @Test
    void paletteIsVgaSixteen() {
        assertEquals(16, Endoom.PALETTE.length);
        assertEquals(0xFF000000, Endoom.PALETTE[0]);   // black
        assertEquals(0xFF0000AA, Endoom.PALETTE[1]);   // blue
        assertEquals(0xFFAA5500, Endoom.PALETTE[6]);   // brown
        assertEquals(0xFFAAAAAA, Endoom.PALETTE[7]);   // light gray
        assertEquals(0xFF555555, Endoom.PALETTE[8]);   // dark gray
        assertEquals(0xFFFFFF55, Endoom.PALETTE[14]);  // yellow
        assertEquals(0xFFFFFFFF, Endoom.PALETTE[15]);  // white
    }
}
