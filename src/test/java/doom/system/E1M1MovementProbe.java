package doom.system;

import doom.play.Mobj;
import doom.play.Player;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Walk the player forward for ~70 tics (2 s) and dump a frame
 * every few tics. Reports any exception that fires during the
 * run.
 *
 * Armed via `-De1m1.probe=1`.
 */
class E1M1MovementProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void walkForwardAndCapture() throws IOException {
        if (!"1".equals(System.getProperty("e1m1.probe"))) return;

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        Player p = GGame.players[0];
        Path outDir = Path.of("build/probe");
        Files.createDirectories(outDir);
        // Pretend the user is holding "up arrow" the whole time.
        GGame.gamekeydown[Doomdef.KEY_UPARROW] = true;
        for (int tic = 0; tic < 350; tic++) {
            // After tic 80 also hold right-turn so the player
            // moves AND rotates through every facing.
            if (tic == 80) GGame.gamekeydown[Doomdef.KEY_RIGHTARROW] = true;
            try {
                DMain.dDoomLoop();
            } catch (Throwable t) {
                Mobj mo = p.mo;
                System.err.printf("[tic %d] EXCEPTION x=%d y=%d z=%d angle=%08x: %s%n",
                        tic,
                        mo != null ? mo.x >> 16 : 0,
                        mo != null ? mo.y >> 16 : 0,
                        mo != null ? mo.z >> 16 : 0,
                        mo != null ? mo.angle : 0,
                        t.toString());
                t.printStackTrace(System.err);
                break;
            }
            if (tic % 10 == 0) {
                dump(VVideo.screens[0], outDir.resolve(
                        "e1m1_walk_" + tic + ".png").toString());
                Mobj mo = p.mo;
                System.err.printf("[tic %d] x=%d y=%d z=%d momx=%d momy=%d%n",
                        tic,
                        mo != null ? mo.x >> 16 : 0,
                        mo != null ? mo.y >> 16 : 0,
                        mo != null ? mo.z >> 16 : 0,
                        mo != null ? mo.momx >> 16 : 0,
                        mo != null ? mo.momy >> 16 : 0);
            }
        }
    }

    private static void dump(byte[] linear, String outPath) throws IOException {
        byte[] palette;
        try {
            palette = WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE);
        } catch (IOException e) { return; }
        int w = Doomdef.SCREENWIDTH;
        int h = Doomdef.SCREENHEIGHT;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int idx = linear[i] & 0xff;
            int r = palette[idx * 3]     & 0xff;
            int g = palette[idx * 3 + 1] & 0xff;
            int b = palette[idx * 3 + 2] & 0xff;
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", Path.of(outPath).toFile());
    }
}
