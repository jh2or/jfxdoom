// Port of linuxdoom-1.10/m_misc.c — partial.
//
// File I/O + defaults-file load / save. PCX screenshot writer +
// M_DrawText still deferred (no use-site driving them).
//
// The C uses a `default_t` array of (name, int* location, default,
// scantranslate, untranslated) tuples and writes a single-line-per-
// key text file. The Java port keeps the same shape but the
// `int*` becomes a per-key accessor pair (IntSupplier getter +
// IntConsumer setter) so cross-module fields can be reached
// without making them all `public static int` — deviation 38.
package doom.system;

import doom.sound.SSound;
import doom.ui.HuStuff;
import doom.ui.MMenu;
import doom.ui.VVideo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

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

    //
    // Defaults table — m_misc.c lines 234–299.
    //
    // Each entry pairs a config-file key with a getter / setter
    // and a default value. The original C indexes the same struct
    // via `int*`; the Java port uses IntSupplier / IntConsumer
    // method references (deviation 38) so the underlying fields
    // can live on their owning module classes (HuStuff /
    // MMenu / SSound / VVideo) instead of being pulled onto
    // MMisc as `public static int`.
    //
    public static final class Default {
        public final String       name;
        public final IntSupplier  getter;
        public final IntConsumer  setter;
        public final int          defaultValue;

        public Default(String name, IntSupplier getter,
                       IntConsumer setter, int defaultValue) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
        }
    }

    // The subset of defaults the Java port actually wires up. Key-
    // binding defaults are skipped — G_Responder pins them — and
    // mouse / joystick / chat defaults stay out until those input
    // paths land.
    public static final Default[] defaults = {
            new Default("mouse_sensitivity",
                    () -> MMenu.mouseSensitivity,
                    v -> MMenu.mouseSensitivity = v,    5),
            new Default("sfx_volume",
                    () -> SSound.snd_SfxVolume,
                    SSound::sSetSfxVolume,             8),
            new Default("music_volume",
                    () -> SSound.snd_MusicVolume,
                    SSound::sSetMusicVolume,           8),
            new Default("show_messages",
                    () -> HuStuff.showMessages ? 1 : 0,
                    v -> HuStuff.showMessages = (v != 0),
                    1),
            new Default("screenblocks",
                    () -> MMenu.screenblocks,
                    v -> MMenu.screenblocks = v,       10),
            new Default("detaillevel",
                    () -> MMenu.detailLevel,
                    v -> MMenu.detailLevel = v,        0),
            new Default("usegamma",
                    () -> VVideo.usegamma,
                    v -> VVideo.usegamma = v,          0),
    };

    public static String defaultfile = "default.cfg";

    //
    // M_SaveDefaults — m_misc.c lines 308–332.
    //
    // The C uses fprintf to a single-line-per-key text file. The
    // Java port writes the same shape with a BufferedWriter so a
    // hand-edited config remains diff-friendly.
    public static void mSaveDefaults() {
        try (BufferedWriter w = Files.newBufferedWriter(
                Path.of(defaultfile), StandardCharsets.UTF_8)) {
            for (Default d : defaults) {
                w.write(d.name);
                w.write("\t\t");
                w.write(Integer.toString(d.getter.getAsInt()));
                w.newLine();
            }
        } catch (IOException e) {
            // "can't write the file, but don't complain" — matches
            // the C's silent fopen-failure path.
        }
    }

    //
    // M_LoadDefaults — m_misc.c lines 340–403.
    //
    // Seed every key with its baked-in default, then overlay the
    // contents of the config file (if present). -config <path>
    // overrides the file location.
    public static void mLoadDefaults() {
        for (Default d : defaults) {
            d.setter.accept(d.defaultValue);
        }
        int p = MArgv.checkParm("-config");
        if (p > 0 && p < MArgv.myargc - 1) {
            defaultfile = MArgv.myargv[p + 1];
        }
        Path path = Path.of(defaultfile);
        if (!Files.isRegularFile(path)) return;
        try (BufferedReader r = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = -1;
                for (int i = 0; i < line.length(); i++) {
                    if (Character.isWhitespace(line.charAt(i))) {
                        sp = i; break;
                    }
                }
                if (sp < 0) continue;
                String key = line.substring(0, sp);
                String val = line.substring(sp).trim();
                int parsed;
                try {
                    if (val.startsWith("0x") || val.startsWith("0X")) {
                        parsed = Integer.parseInt(val.substring(2), 16);
                    } else if (val.startsWith("\"") && val.endsWith("\"")) {
                        // Quoted string default — none of the wired
                        // keys take strings yet, so skip silently.
                        continue;
                    } else {
                        parsed = Integer.parseInt(val);
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
                for (Default d : defaults) {
                    if (d.name.equals(key)) {
                        d.setter.accept(parsed);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // Best-effort — leave the baked-in defaults in place.
        }
    }

    private MMisc() {
    }
}
