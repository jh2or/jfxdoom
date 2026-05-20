package doom.system;

import doom.fixed.Fixed;
import doom.play.Mobj;
import doom.play.Player;
import doom.render.Tables;
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
 * Teleport the player to known-good positions deeper in E1M1
 * and dump screenshots. Lets us check whether the spawn-point
 * banding is just "wall right next to player" or a real bug.
 *
 * Armed via `-De1m1.probe=1`.
 */
class E1M1TeleportProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void teleportAndCapture() throws IOException {
        if (!"1".equals(System.getProperty("e1m1.probe"))) return;

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        Player p = GGame.players[0];
        Mobj mo = p.mo;

        Path outDir = Path.of("build/probe");
        Files.createDirectories(outDir);

        // E1M1 starting alcove → main hall coords (from the WAD).
        // The reference screenshot looks roughly like player at
        // (1056, -3360), facing north.
        teleportAndDump(mo, p, 1056, -3520, 0,                outDir, "deep_north_1");
        teleportAndDump(mo, p, 1056, -3360, Tables.ANG90,     outDir, "deep_north_2");
        teleportAndDump(mo, p, 1056, -3200, Tables.ANG90,     outDir, "deep_north_3");
        teleportAndDump(mo, p, 1056, -3000, Tables.ANG90,     outDir, "deep_north_4");
        // Look in 90-degree turns from one position.
        teleportAndDump(mo, p, 1056, -3360, 0,                outDir, "look_east");
        teleportAndDump(mo, p, 1056, -3360, Tables.ANG180,    outDir, "look_south");
        teleportAndDump(mo, p, 1056, -3360, Tables.ANG90 * 3, outDir, "look_west");
    }

    private static void teleportAndDump(Mobj mo, Player p,
                                        int x, int y, int angle,
                                        Path outDir, String label) throws IOException {
        // Clamp velocity.
        mo.momx = mo.momy = mo.momz = 0;
        mo.x = x * Fixed.FRACUNIT;
        mo.y = y * Fixed.FRACUNIT;
        mo.angle = angle;
        // Recompute the player's sector + floorz by linking the
        // mobj back into the blockmap/sector graph.
        doom.play.PMaputl.pUnsetThingPosition(mo);
        doom.play.PMaputl.pSetThingPosition(mo);
        mo.z = mo.floorz;
        p.viewz = mo.z + doom.play.PMobj.VIEWHEIGHT;
        // Render one frame, dump.
        DMain.dDoomLoop();
        dump(VVideo.screens[0], outDir.resolve("tp_" + label + ".png").toString());
        System.err.printf("%s: sector floor=%d ceiling=%d%n",
                label,
                mo.floorz >> 16,
                mo.ceilingz >> 16);
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
