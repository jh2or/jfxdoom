// ENDOOM text-screen support (deviation 42).
//
// On quit, DOS doom.exe copied the 4 000-byte ENDOOM lump straight
// into VGA text-mode memory at B800:0000 — 80×25 cells, two bytes
// per cell: a CP437 character code followed by an attribute byte
// (low nibble = foreground colour 0..15, bits 4-6 = background
// colour 0..7, bit 7 = blink). linuxdoom-1.10 never displayed it
// (no DOS text mode on X11), and neither did this port until now.
//
// The Java port paints the lump into a JavaFX Canvas when the user
// quits back to the Launchpad (see JfxDoomApp.showEndoom). This
// class holds the platform-independent half: the CP437 → Unicode
// glyph table, the VGA 16-colour text palette, and the attribute
// decoders. No JavaFX imports — the JUnit suite exercises it
// headlessly.
//
// The glyph table is embedded rather than decoded through the
// JDK's "IBM437" charset because that charset lives in the
// jdk.charsets module, which jlink-slimmed runtimes routinely
// drop. 256 chars of table beats a runtime dependency.
package doom.ui;

public final class Endoom {

    public static final int COLS = 80;
    public static final int ROWS = 25;
    public static final int SCREEN_BYTES = COLS * ROWS * 2;  // 4000

    // VGA 16-colour text palette (ARGB). Indices 0..7 are the
    // background-capable colours; 8..15 are the bright set only
    // reachable as foregrounds.
    public static final int[] PALETTE = {
            0xFF000000,  //  0 black
            0xFF0000AA,  //  1 blue
            0xFF00AA00,  //  2 green
            0xFF00AAAA,  //  3 cyan
            0xFFAA0000,  //  4 red
            0xFFAA00AA,  //  5 magenta
            0xFFAA5500,  //  6 brown
            0xFFAAAAAA,  //  7 light gray
            0xFF555555,  //  8 dark gray
            0xFF5555FF,  //  9 light blue
            0xFF55FF55,  // 10 light green
            0xFF55FFFF,  // 11 light cyan
            0xFFFF5555,  // 12 light red
            0xFFFF55FF,  // 13 light magenta
            0xFFFFFF55,  // 14 yellow
            0xFFFFFFFF,  // 15 white
    };

    // CP437 → Unicode, all 256 positions. 0x00 renders blank (the
    // VGA NUL cell); 0x01–0x1F use the DOS *display* glyphs (smiley,
    // card suits, arrows — not C0 controls); 0x20–0x7E are ASCII;
    // the high half is the classic box-drawing / accents / Greek
    // set per the Unicode Consortium CP437 mapping.
    private static final String CP437 =
            " ☺☻♥♦♣♠•"
            + "◘○◙♂♀♪♫☼"
            + "►◄↕‼¶§▬↨"
            + "↑↓→←∟↔▲▼"
            + " !\"#$%&'()*+,-./0123456789:;<=>?"
            + "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_"
            + "`abcdefghijklmnopqrstuvwxyz{|}~⌂"
            + "Çüéâäàåç"
            + "êëèïîìÄÅ"
            + "ÉæÆôöòûù"
            + "ÿÖÜ¢£¥₧ƒ"
            + "áíóúñÑªº"
            + "¿⌐¬½¼¡«»"
            + "░▒▓│┤╡╢╖"
            + "╕╣║╗╝╜╛┐"
            + "└┴┬├─┼╞╟"
            + "╚╔╩╦╠═╬╧"
            + "╨╤╥╙╘╒╓╫"
            + "╪┘┌█▄▌▐▀"
            + "αßΓπΣσµτ"
            + "ΦΘΩδ∞φε∩"
            + "≡±≥≤⌠⌡÷≈"
            + "°∙·√ⁿ²■ ";

    // Unicode glyph for a raw CP437 byte (0..255).
    public static char glyph(int b) {
        return CP437.charAt(b & 0xFF);
    }

    // Foreground colour index (0..15) from an attribute byte.
    public static int fg(int attr) {
        return attr & 0x0F;
    }

    // Background colour index (0..7) from an attribute byte. Bit 7
    // (blink) is masked off — the Canvas render is static.
    public static int bg(int attr) {
        return (attr >> 4) & 0x07;
    }

    // Blink bit. Decoded for completeness / tests; the renderer
    // ignores it (a static screen doesn't blink).
    public static boolean blink(int attr) {
        return (attr & 0x80) != 0;
    }

    private Endoom() {
    }
}
