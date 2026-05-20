package doom.system;

import doom.play.Mobj;
import doom.play.Player;
import doom.play.PSetup;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic probe: boot E1M1 and print the player's spawn
 * position + angle + sector to stderr so we can compare against
 * the known-correct values from the WAD's THINGS lump.
 *
 * Armed via `-De1m1.probe=1` (same gate as the screenshot
 * probe).
 */
class E1M1SpawnProbe {

    private static final Path WAD_PATH = Path.of("reference-wads/DOOM.WAD");

    @Test
    void dumpSpawnState() throws IOException {
        if (!"1".equals(System.getProperty("e1m1.probe"))) return;

        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString(),
                "-warp", "1", "1",
                "-nomonsters" });

        Player p = GGame.players[0];
        Mobj mo = (p != null) ? p.mo : null;
        if (mo == null) {
            System.err.println("E1M1SpawnProbe: no player mobj");
            return;
        }
        // Doom map coords are stored as 16.16 fixed; print the
        // integer part for readability.
        int xi = mo.x >> 16;
        int yi = mo.y >> 16;
        int zi = mo.z >> 16;
        // angle_t is unsigned 32-bit. Convert to degrees 0..359.
        long ang = mo.angle & 0xffffffffL;
        double degrees = (ang / (double) (1L << 32)) * 360.0;
        System.err.printf(
                "E1M1 spawn: x=%d y=%d z=%d angle=0x%08x (~%.1f deg) sector=%d%n",
                xi, yi, zi, (int) mo.angle, degrees,
                indexOf(mo.subsector != null ? mo.subsector.sector : null));
        System.err.printf(
                "  floorz=%d ceilingz=%d%n",
                mo.floorz >> 16, mo.ceilingz >> 16);
    }

    private static int indexOf(doom.render.Sector s) {
        if (s == null || PSetup.sectors == null) return -1;
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i] == s) return i;
        }
        return -1;
    }
}
