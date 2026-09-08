package doom.system;

import doom.render.RData;
import doom.render.RMain;
import doom.render.RSky;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Dump renderer state right after boot so we can spot a
 * mis-initialised lookup table.
 *
 * Armed via `-De1m1.probe=1`.
 */
class E1M1RenderProbe {

    private static final Path WAD_PATH = doom.testing.TestWads.find();

    @Test
    void dumpRendererState() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getProperty("e1m1.probe")),
                "Diagnostic disabled; enable with -De1m1.probe=1");
        doom.testing.TestWads.require(WAD_PATH);

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        // Viewport.
        System.err.printf(
                "viewwidth=%d viewheight=%d centerx=%d centery=%d detailshift=%d%n",
                RMain.viewwidth, doom.render.RDraw.viewheight,
                RMain.centerx, RMain.centery, RMain.detailshift);

        // Sample xtoviewangle across the screen — first 10, middle 5, last 10.
        StringBuilder sb = new StringBuilder("xtoviewangle: ");
        int[] x = { 0, 1, 2, 3, 4, 5, 8, 16, 80, 160, 240, 308, 316, 318, 319, 320 };
        for (int i : x) {
            sb.append(String.format("[%d]=%08x ", i, RMain.xtoviewangle[i]));
        }
        System.err.println(sb);

        // Sample texture column tables for a known wall texture
        // (STARTAN3) to verify width / lump / ofs are sensible.
        int tex = RData.rCheckTextureNumForName("STARTAN3");
        if (tex < 0) tex = RData.rCheckTextureNumForName("STARTAN1");
        if (tex >= 0) {
            System.err.printf("STARTAN# tex=%d height=%d widthmask=%08x%n",
                    tex,
                    RData.textureheight[tex] >> 16,
                    RData.texturewidthmask[tex]);
            sb.setLength(0);
            sb.append("STARTAN cols: ");
            for (int c : new int[] { 0, 1, 2, 16, 32, 63 }) {
                sb.append(String.format("[%d]=lump:%d ofs:%d ", c,
                        RData.texturecolumnlump[tex][c],
                        RData.texturecolumnofs[tex][c]));
            }
            System.err.println(sb);
        } else {
            System.err.println("STARTAN# not found");
        }

        // Skytexture sanity.
        System.err.printf("skytexture=%d skyflatnum=%d%n",
                RSky.skytexture, RSky.skyflatnum);

        // Wider column sweep — full 128-wide STARTAN3.
        if (tex >= 0) {
            StringBuilder sb2 = new StringBuilder("STARTAN cols (wide): ");
            for (int c : new int[] { 0, 32, 60, 63, 64, 65, 80, 100, 127 }) {
                sb2.append(String.format("[%d]=lump:%d ofs:%d ",
                        c, RData.texturecolumnlump[tex][c],
                        RData.texturecolumnofs[tex][c]));
            }
            System.err.println(sb2);

            // Render the actual texture composite to a PNG so we
            // can eyeball it.
            int w = 128;
            int h = RData.textureheight[tex] >> 16;
            byte[] palette;
            try {
                palette = doom.wad.WWad.wCacheLumpName("PLAYPAL",
                        doom.wad.ZZone.PU_CACHE);
            } catch (IOException e) { throw new AssertionError("Probe image input failed", e); }
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int c = 0; c < w; c++) {
                try {
                    RData.ColumnRef col = RData.rGetColumn(tex, c);
                    for (int y = 0; y < h; y++) {
                        int idx = col.data[col.offset + (y & 127)] & 0xff;
                        int r = palette[idx * 3]     & 0xff;
                        int g = palette[idx * 3 + 1] & 0xff;
                        int b = palette[idx * 3 + 2] & 0xff;
                        img.setRGB(c, y, 0xff000000 | (r << 16) | (g << 8) | b);
                    }
                } catch (IOException e) { throw new AssertionError("Probe texture read failed", e); }
            }
            java.nio.file.Path outDir = java.nio.file.Path.of("build/probe");
            java.nio.file.Files.createDirectories(outDir);
            javax.imageio.ImageIO.write(img, "png",
                    outDir.resolve("texture_startan.png").toFile());
            System.err.println("wrote build/probe/texture_startan.png");
        }

        // viewz + viewheight at spawn (before any pTicker pass) and
        // after one full tic, to verify pCalcHeight populates it.
        doom.play.Player p = doom.system.GGame.players[0];
        System.err.printf("BEFORE tic: viewz=%d viewheight=%d mo.z=%d%n",
                p.viewz >> 16, p.viewheight >> 16, p.mo.z >> 16);
        DMain.dDoomLoop();
        System.err.printf("AFTER  tic: viewz=%d viewheight=%d mo.z=%d centery=%d%n",
                p.viewz >> 16, p.viewheight >> 16, p.mo.z >> 16,
                RMain.centery);
    }
}
