// Port of linuxdoom-1.10/m_misc.c — partial.
//
// m_misc.c is a 550-line grab-bag: file I/O, config-file load/save,
// PCX screenshotter, and M_DrawText. Most of it requires unported
// globals (key_*/mouseb*/joyb*, snd_SfxVolume, hu_font, ...) or
// the screenshot pipeline. This file carries only the two standalone
// file-I/O helpers other modules already need — M_WriteFile and
// M_ReadFile. Defaults, screenshot, and draw-text land later.
package doom.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MMisc {

    // m_misc.c lines 112–133 — M_WriteFile.
    // The C opens O_WRONLY|O_CREAT|O_TRUNC and writes the buffer in
    // one shot, returning false on open or short-write failure. Java
    // Files.write maps 1:1: create-or-truncate semantics and a single
    // call that either writes everything or throws. We translate any
    // IOException into the C's "return false" contract.
    public static boolean mWriteFile(String name, byte[] source, int length) {
        try {
            byte[] slice = (length == source.length) ? source : java.util.Arrays.copyOf(source, length);
            Files.write(Path.of(name), slice);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // m_misc.c lines 139–163 — M_ReadFile.
    // The C signature is `int M_ReadFile(char const*, byte**)`: the
    // length is the return value and the caller receives the buffer
    // through the out-parameter. In Java we collapse both onto one
    // return value — byte[] carries length via .length. Callers in
    // g_game.c used the length for bounds-checking, which the array
    // itself now provides.
    //
    // Deviation 18: Z_Malloc(PU_STATIC) is replaced with a plain
    // byte[]. The C zone-tracked the buffer so the caller could later
    // Z_Free or Z_ChangeTag it; in Java the GC collects once the
    // caller drops the reference. No existing caller re-tagged the
    // buffer (g_game uses it once and drops it), so the zone hook was
    // never load-bearing here.
    public static byte[] mReadFile(String name) {
        try {
            return Files.readAllBytes(Path.of(name));
        } catch (IOException e) {
            throw ISystem.iError("Couldn't read file %s", name);
        }
    }

    private MMisc() {
    }
}
