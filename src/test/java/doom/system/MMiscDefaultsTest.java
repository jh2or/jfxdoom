package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.sound.SSound;
import doom.ui.HuStuff;
import doom.ui.MMenu;
import doom.ui.VVideo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MMiscDefaultsTest {

    @BeforeEach
    void resetState() {
        MArgv.myargc = 1;
        MArgv.myargv = new String[] { "doom" };
        MMenu.mouseSensitivity = 5;
        MMenu.screenblocks = 10;
        MMenu.detailLevel = 0;
        SSound.snd_SfxVolume = 8;
        SSound.snd_MusicVolume = 8;
        HuStuff.showMessages = true;
        VVideo.usegamma = 0;
    }

    // ---- mLoadDefaults ----

    // No config file present → every key gets its baked-in default.
    @Test
    void loadDefaultsSeedsBakedInValues(@TempDir Path tmp) {
        MMisc.defaultfile = tmp.resolve("nope.cfg").toString();
        MMenu.mouseSensitivity = 999;
        SSound.snd_SfxVolume = 999;
        HuStuff.showMessages = false;
        MMisc.mLoadDefaults();
        // mouseSensitivity defaults to 5, sfx_volume to 8,
        // show_messages to 1 (true).
        assertEquals(5, MMenu.mouseSensitivity);
        assertEquals(8, SSound.snd_SfxVolume);
        assertTrue(HuStuff.showMessages);
    }

    // A hand-written config overrides the baked-in defaults.
    @Test
    void loadDefaultsOverridesFromFile(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("default.cfg");
        Files.writeString(cfg, """
                mouse_sensitivity\t\t9
                sfx_volume\t\t12
                music_volume\t\t3
                show_messages\t\t0
                screenblocks\t\t7
                usegamma\t\t2
                """, StandardCharsets.UTF_8);
        MMisc.defaultfile = cfg.toString();
        MMisc.mLoadDefaults();
        assertEquals(9,  MMenu.mouseSensitivity);
        assertEquals(12, SSound.snd_SfxVolume);
        assertEquals(3,  SSound.snd_MusicVolume);
        assertFalse(HuStuff.showMessages);
        assertEquals(7,  MMenu.screenblocks);
        assertEquals(2,  VVideo.usegamma);
    }

    // Hex values (0x prefix) are decoded.
    @Test
    void loadDefaultsParsesHexValues(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("default.cfg");
        Files.writeString(cfg, "usegamma\t\t0x3\n", StandardCharsets.UTF_8);
        MMisc.defaultfile = cfg.toString();
        MMisc.mLoadDefaults();
        assertEquals(3, VVideo.usegamma);
    }

    // Unknown keys are silently skipped (forward-compat for new
    // defaults added later).
    @Test
    void loadDefaultsSkipsUnknownKeys(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("default.cfg");
        Files.writeString(cfg,
                "future_setting\t\t42\nmouse_sensitivity\t\t6\n",
                StandardCharsets.UTF_8);
        MMisc.defaultfile = cfg.toString();
        MMisc.mLoadDefaults();
        assertEquals(6, MMenu.mouseSensitivity);
    }

    // -config <path> overrides the default file location.
    @Test
    void loadDefaultsHonorsConfigCmdline(@TempDir Path tmp) throws IOException {
        Path custom = tmp.resolve("alt.cfg");
        Files.writeString(custom, "sfx_volume\t\t11\n", StandardCharsets.UTF_8);
        MArgv.myargc = 3;
        MArgv.myargv = new String[] { "doom", "-config", custom.toString() };
        MMisc.mLoadDefaults();
        assertEquals(11, SSound.snd_SfxVolume);
        assertEquals(custom.toString(), MMisc.defaultfile);
    }

    // Quoted-string values (none of the wired keys use them) are
    // silently skipped without breaking the parse.
    @Test
    void loadDefaultsSkipsStringValues(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("default.cfg");
        Files.writeString(cfg,
                "chatmacro0\t\t\"howdy\"\nmouse_sensitivity\t\t4\n",
                StandardCharsets.UTF_8);
        MMisc.defaultfile = cfg.toString();
        MMisc.mLoadDefaults();
        assertEquals(4, MMenu.mouseSensitivity);
    }

    // ---- mSaveDefaults ----

    // Round-trip — write, mutate, read — restores the saved values.
    @Test
    void saveDefaultsRoundTrips(@TempDir Path tmp) {
        MMisc.defaultfile = tmp.resolve("default.cfg").toString();
        MMenu.mouseSensitivity = 7;
        SSound.snd_SfxVolume = 11;
        SSound.snd_MusicVolume = 4;
        HuStuff.showMessages = false;
        MMenu.screenblocks = 8;
        VVideo.usegamma = 3;
        MMisc.mSaveDefaults();

        // Clobber and reload — values should come back.
        MMenu.mouseSensitivity = 0;
        SSound.snd_SfxVolume = 0;
        SSound.snd_MusicVolume = 0;
        HuStuff.showMessages = true;
        MMenu.screenblocks = 0;
        VVideo.usegamma = 0;
        MMisc.mLoadDefaults();
        assertEquals(7,  MMenu.mouseSensitivity);
        assertEquals(11, SSound.snd_SfxVolume);
        assertEquals(4,  SSound.snd_MusicVolume);
        assertFalse(HuStuff.showMessages);
        assertEquals(8,  MMenu.screenblocks);
        assertEquals(3,  VVideo.usegamma);
    }

    // The on-disk format is "name<tab><tab>value" per line.
    @Test
    void saveDefaultsEmitsTabSeparatedKeyValueLines(@TempDir Path tmp)
            throws IOException {
        MMisc.defaultfile = tmp.resolve("default.cfg").toString();
        MMenu.mouseSensitivity = 5;
        MMisc.mSaveDefaults();
        String body = Files.readString(Path.of(MMisc.defaultfile),
                StandardCharsets.UTF_8);
        assertTrue(body.contains("mouse_sensitivity\t\t5"),
                "body: " + body);
    }
}
