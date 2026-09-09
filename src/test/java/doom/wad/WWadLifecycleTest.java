package doom.wad;

import doom.system.ISystem;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WWadLifecycleTest {
    @TempDir Path directory;

    @BeforeEach void setup() {
        WWad.wShutdown();
        ZZone.zInit();
    }

    @AfterEach void teardown() { WWad.wShutdown(); }

    private Path wad(String filename, int size) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[] {'P', 'W', 'A', 'D'}).putInt(1).putInt(16);
        bytes.putInt(0x12345678);
        bytes.putInt(12).putInt(size).put(new byte[] {'E','N','D','O','O','M',0,0});
        return Files.write(directory.resolve(filename), bytes.array());
    }

    @SuppressWarnings("unchecked")
    private List<RandomAccessFile> handles() throws ReflectiveOperationException {
        var field = WWad.class.getDeclaredField("openHandles");
        field.setAccessible(true);
        return (List<RandomAccessFile>) field.get(null);
    }

    @Test void memoryLumpsPreserveFileCacheAndSurviveEvictionUntilShutdown() throws Exception {
        WWad.wInitFile(wad("base.wad", 4).toString());
        byte[] existing = WWad.wCacheLumpNum(0, ZZone.PU_CACHE);
        byte[] source = {1, 2, 3, 4};
        WWad.wAddMemoryLumps(new String[] {"RUNTIME"}, new byte[][] {source});
        source[0] = 99;
        assertSame(existing, WWad.wCacheLumpNum(0, ZZone.PU_CACHE));
        ZZone.zFree(existing);
        assertNull(WWad.lumpcache[0], "Eviction must clear a cache that grew after allocation");
        assertArrayEquals(new byte[] {0x78, 0x56, 0x34, 0x12},
                WWad.wCacheLumpNum(0, ZZone.PU_CACHE));
        assertEquals(1, WWad.wGetNumForName("RUNTIME"));
        byte[] first = WWad.wCacheLumpNum(1, ZZone.PU_CACHE);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, first);
        ZZone.zFree(first);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, WWad.wCacheLumpNum(1, ZZone.PU_CACHE));
        WWad.wShutdown();
        assertEquals(-1, WWad.wCheckNumForName("RUNTIME"));
    }

    @Test void repeatedMountClosesPreviousHandlesAndKeepsOnlyCurrentDirectory() throws Exception {
        Path first = wad("first.wad", 4);
        Path second = wad("second.wad", 4);
        for (int i = 0; i < 20; i++) {
            WWad.wInitFile(first.toString());
            RandomAccessFile previous = handles().get(0);
            WWad.wInitFile(second.toString());
            assertFalse(previous.getChannel().isOpen());
            assertEquals(1, handles().size());
            assertEquals(1, WWad.wNumLumps());
            assertEquals(0, WWad.lumpinfo[0].handle);
            assertEquals(0x78, WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE)[0]);
        }
    }

    @Test void shutdownPreservesAlreadyReadEndoomAndIsIdempotent() throws Exception {
        Path file = wad("exit.wad", 4);
        WWad.wInitFile(file.toString());
        RandomAccessFile handle = handles().get(0);
        byte[] endoom = WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE);
        WWad.wShutdown();
        WWad.wShutdown();
        assertFalse(handle.getChannel().isOpen());
        assertTrue(handles().isEmpty());
        assertEquals(-1, WWad.wCheckNumForName("ENDOOM"));
        assertArrayEquals(new byte[] {0x78, 0x56, 0x34, 0x12}, endoom);
        Files.delete(file);
    }

    @Test void failedMountDropsPartialDirectoryHandlesAndReloadState() throws Exception {
        Path valid = wad("good.wad", 4);
        Path truncated = Files.write(directory.resolve("bad.wad"), new byte[] {0});
        assertThrows(IOException.class, () -> WWad.wInitMultipleFiles(
                new String[] {valid.toString(), truncated.toString()}));
        assertTrue(handles().isEmpty());
        assertEquals(0, WWad.wNumLumps());
        assertEquals(0, WWad.lumpcache.length);
        assertThrows(ISystem.DoomError.class,
                () -> WWad.wInitFile("~" + directory.resolve("missing.wad")));
        assertNull(WWad.reloadname);
        WWad.wInitFile(valid.toString());
        assertEquals(0x78, WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE)[0]);
    }

    @Test void failedReloadableReadDoesNotCachePartialData() throws Exception {
        Path file = wad("reload.wad", 100);
        WWad.wInitFile("~" + file);
        for (int i = 0; i < 5; i++) {
            assertThrows(ISystem.DoomError.class,
                    () -> WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE));
            assertNull(WWad.lumpcache[0]);
        }
        WWad.wInitFile(wad("fresh.wad", 4).toString());
        assertNull(WWad.reloadname);
        Files.delete(file);
    }

    @Test void malformedFilesAndFailedReloadReadsReleaseOperatingSystemDescriptors() throws Exception {
        Path descriptors = Path.of("/proc/self/fd");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(descriptors),
                "Descriptor inspection requires Linux /proc");
        Path broken = Files.write(directory.resolve("bad.wad"), new byte[] {0});
        for (int i = 0; i < 10; i++) {
            assertThrows(IOException.class, () -> WWad.wInitFile(broken.toString()));
        }
        assertNoDescriptorFor(descriptors, broken);
        Path reload = wad("reload.wad", 100);
        WWad.wInitFile("~" + reload);
        for (int i = 0; i < 10; i++) {
            assertThrows(ISystem.DoomError.class,
                    () -> WWad.wReadLump(0, new byte[100]));
        }
        assertNoDescriptorFor(descriptors, reload);
    }

    private void assertNoDescriptorFor(Path directory, Path file) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path descriptor : entries.toList()) {
                try {
                    assertNotEquals(file.toAbsolutePath(), Files.readSymbolicLink(descriptor));
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // The directory stream's own descriptor may already be closed.
                }
            }
        }
    }

    @Test void purgingPreviousMountCacheCannotClearCurrentMountCache() throws Exception {
        WWad.wInitFile(wad("first.wad", 4).toString());
        byte[] old = WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE);
        WWad.wInitFile(wad("second.wad", 4).toString());
        byte[] current = WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE);
        ZZone.zFree(old);
        assertSame(current, WWad.wCacheLumpName("ENDOOM", ZZone.PU_CACHE));
    }
}
