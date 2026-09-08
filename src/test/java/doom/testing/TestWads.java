package doom.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Local test data only; no WAD download or redistribution. */
public final class TestWads {
    private TestWads() { }

    public static Path find() {
        String configured = System.getProperty("doom.wad");
        if (configured != null) return Path.of(configured).toAbsolutePath();
        for (String filename : new String[] {"DOOM.WAD", "doom.wad", "doom1.wad", "DOOM1.WAD"}) {
            Path candidate = Path.of("reference-wads", filename);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath();
        }
        return Path.of("reference-wads/DOOM.WAD").toAbsolutePath();
    }

    public static void require(Path path) {
        assumeTrue(Files.isRegularFile(path), "WAD unavailable: " + path + "; set -Ddoom.wad=/path/to/doom.wad");
    }
}
