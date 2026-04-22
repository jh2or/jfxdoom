package doom.system;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MMiscTest {

    @Test
    void writeFileCreatesAndWritesExactBytes(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.bin");
        byte[] data = { 1, 2, 3, 4, 5, 6, 7, 8 };

        assertTrue(MMisc.mWriteFile(target.toString(), data, data.length));
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    // Only `length` bytes are written even if `source` is longer — the
    // C wrote exactly `length` bytes regardless of buffer size.
    @Test
    void writeFileHonoursLengthParameter(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("partial.bin");
        byte[] data = { 10, 20, 30, 40, 50 };

        assertTrue(MMisc.mWriteFile(target.toString(), data, 3));
        assertArrayEquals(new byte[] { 10, 20, 30 }, Files.readAllBytes(target));
    }

    // Overwrite semantics: O_TRUNC in the C open(); Java Files.write
    // defaults to CREATE+TRUNCATE_EXISTING.
    @Test
    void writeFileTruncatesExistingFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("existing.bin");
        Files.write(target, new byte[] { 99, 99, 99, 99, 99, 99 });

        assertTrue(MMisc.mWriteFile(target.toString(), new byte[] { 1, 2 }, 2));
        assertArrayEquals(new byte[] { 1, 2 }, Files.readAllBytes(target));
    }

    // open() failure → return false, matching the C's handle == -1 branch.
    @Test
    void writeFileReturnsFalseOnOpenFailure(@TempDir Path dir) {
        Path bad = dir.resolve("missing-subdir").resolve("nested").resolve("out.bin");
        assertFalse(MMisc.mWriteFile(bad.toString(), new byte[] { 1 }, 1));
    }

    @Test
    void readFileReturnsEntireFileContents(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("in.bin");
        byte[] data = { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0 };
        Files.write(src, data);

        byte[] got = MMisc.mReadFile(src.toString());
        assertArrayEquals(data, got);
        assertEquals(data.length, got.length);
    }

    @Test
    void readFileRoundTripsWithWriteFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("round.bin");
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        assertTrue(MMisc.mWriteFile(target.toString(), payload, payload.length));
        assertArrayEquals(payload, MMisc.mReadFile(target.toString()));
    }

    // C: I_Error ("Couldn't read file %s", name). We translate to
    // DoomError with the same message substring.
    @Test
    void readFileRaisesIErrorOnMissingFile(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.bin");
        ISystem.DoomError err = assertThrows(ISystem.DoomError.class,
                () -> MMisc.mReadFile(missing.toString()));
        assertTrue(err.getMessage().contains("Couldn't read file"));
    }
}
