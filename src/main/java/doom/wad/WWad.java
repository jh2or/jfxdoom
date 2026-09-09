// Port of linuxdoom-1.10/w_wad.c and w_wad.h.
// WAD file header, directory, lump I/O.
//
// Faithful translation notes:
//   * The C stores `numlumps` and `lumpinfo[]` as globals; we do the same as
//     `public static` fields (CLAUDE.md §2).
//   * C opens WAD files once and seeks on the same handle for every ReadLump.
//     We mirror that with a list of RandomAccessFile handles kept open for the
//     lifetime of the mounted WAD set — `handle` on lumpinfo_t is an index into that list.
//   * The W_Profile debug routine is skipped (CLAUDE.md §2: "faithful first" —
//     but W_Profile is a dev-only heap dump never reached in release builds,
//     and its struct-pointer arithmetic is meaningless in the Java model).
package doom.wad;

import doom.system.ISystem;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WWad {

    // typedef struct { char identification[4]; int numlumps; int infotableofs; } wadinfo_t;
    public static final class WadInfo {
        public String identification; // "IWAD" or "PWAD"
        public int numlumps;
        public int infotableofs;
    }

    // typedef struct { int filepos; int size; char name[8]; } filelump_t;
    public static final class FileLump {
        public int filepos;
        public int size;
        public String name; // 8-char, null-padded; stored trimmed (CLAUDE.md §3)
    }

    // typedef struct { char name[8]; int handle; int position; int size; } lumpinfo_t;
    public static final class LumpInfo {
        public String name; // uppercase, null-stripped
        public int handle;  // index into openHandles; -1 for reloadable
        public int position;
        public int size;
        private byte[] memoryData; // Port extension: generated lumps owned by this mount.
    }

    // Globals (w_wad.c line 61–64).
    public static LumpInfo[] lumpinfo = new LumpInfo[0];
    public static int numlumps = 0;
    public static byte[][] lumpcache = new byte[0][];

    // Reload-indicator (w_wad.c line 137–138).
    static int reloadlump;
    static String reloadname;

    // Open file handles. `handle` on LumpInfo is an index into this list.
    private static final List<RandomAccessFile> openHandles = new ArrayList<>();

    private WWad() {
    }

    // void ExtractFileBase(char* path, char* dest) — extract 8-char base name.
    static String extractFileBase(String path) {
        // back up until a \ or / or start
        int lastSep = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        String base = path.substring(lastSep + 1);
        // drop extension
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        if (base.length() > 8) {
            throw ISystem.iError("Filename base of %s >8 chars", path);
        }
        return base.toUpperCase();
    }

    // void W_AddFile(char* filename)
    public static void wAddFile(String filename) throws IOException {
        // Reload indicator: filename starts with '~'.
        if (!filename.isEmpty() && filename.charAt(0) == '~') {
            filename = filename.substring(1);
            reloadname = filename;
            reloadlump = numlumps;
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(filename, "r");
        } catch (IOException e) {
            System.out.println(" couldn't open " + filename);
            return;
        }

        boolean retained = false;
        try {
            System.out.println(" adding " + filename);
            int startlump = numlumps;

            List<FileLump> fileinfo;

            if (!filename.toLowerCase().endsWith(".wad")) {
                // single lump file
                FileLump single = new FileLump();
                single.filepos = 0;
                single.size = (int) raf.length();
                single.name = extractFileBase(filename);
                fileinfo = List.of(single);
            } else {
                // WAD file — read header.
                byte[] headerBytes = new byte[12];
                raf.readFully(headerBytes);
                WadInfo header = parseHeader(headerBytes);
                if (!"IWAD".equals(header.identification) && !"PWAD".equals(header.identification)) {
                    throw ISystem.iError("Wad file %s doesn't have IWAD or PWAD id", filename);
                }
                int length = header.numlumps * 16; // sizeof(filelump_t) = 4+4+8 = 16
                byte[] dirBytes = new byte[length];
                raf.seek(header.infotableofs);
                raf.readFully(dirBytes);
                fileinfo = parseDirectory(dirBytes, header.numlumps);
            }

            // Fill in lumpinfo — realloc equivalent.
            LumpInfo[] newLumps = Arrays.copyOf(lumpinfo, startlump + fileinfo.size());

            // Register the handle. Reloadable WADs store -1 and open on demand in W_ReadLump.
            int storehandle;
            if (reloadname != null && filename.equals(reloadname)) {
                storehandle = -1;
                // The finally block closes reloadable WADs after directory parsing.
            } else {
                storehandle = openHandles.size();
            }

            for (int i = 0; i < fileinfo.size(); i++) {
                FileLump fl = fileinfo.get(i);
                LumpInfo li = new LumpInfo();
                li.handle = storehandle;
                li.position = fl.filepos;
                li.size = fl.size;
                li.name = fl.name;
                newLumps[startlump + i] = li;
            }
            if (storehandle != -1) {
                openHandles.add(raf);
                retained = true;
            }
            lumpinfo = newLumps;
            numlumps = newLumps.length;
        } finally {
            if (!retained) raf.close();
        }
    }

    // void W_InitMultipleFiles(char** filenames)
    public static void wInitMultipleFiles(String[] filenames) throws IOException {
        wShutdown();
        boolean initialized = false;
        try {
            for (String filename : filenames) {
                if (filename == null) break; // null-terminated list in C
                wAddFile(filename);
            }
            if (numlumps == 0) {
                throw ISystem.iError("W_InitFiles: no files found");
            }
            // set up caching — null-initialised (matches C memset(lumpcache, 0, size)).
            lumpcache = new byte[numlumps][];
            initialized = true;
        } finally {
            if (!initialized) wShutdown();
        }
    }

    // void W_InitFile(char* filename) — convenience around W_InitMultipleFiles.
    public static void wInitFile(String filename) throws IOException {
        wInitMultipleFiles(new String[] { filename, null });
    }

    // int W_NumLumps(void)
    public static int wNumLumps() {
        return numlumps;
    }

    /** Append runtime-generated data without changing any WAD on disk. */
    public static void wAddMemoryLumps(String[] names, byte[][] data) {
        if (names.length != data.length) throw new IllegalArgumentException("Lump count mismatch");
        LumpInfo[] added = new LumpInfo[names.length];
        for (int i = 0; i < names.length; i++) {
            if (!names[i].matches("[A-Z0-9_]{1,8}"))
                throw new IllegalArgumentException("Invalid lump name: " + names[i]);
            LumpInfo lump = new LumpInfo();
            lump.name = names[i];
            lump.handle = -2;
            lump.memoryData = data[i].clone();
            lump.size = data[i].length;
            added[i] = lump;
        }
        lumpinfo = Arrays.copyOf(lumpinfo, numlumps + added.length);
        System.arraycopy(added, 0, lumpinfo, numlumps, added.length);
        numlumps = lumpinfo.length;
        lumpcache = Arrays.copyOf(lumpcache, numlumps);
    }

    /** Replace a generated map after save preflight, without growing the directory. */
    public static void wReplaceMemoryLumps(int first, byte[][] data) {
        if (first < 0 || first + data.length > numlumps)
            throw new IllegalArgumentException("Memory lump range outside directory");
        byte[][] owned = new byte[data.length][];
        for (int i = 0; i < data.length; i++) {
            if (lumpinfo[first + i].memoryData == null)
                throw new IllegalArgumentException("Cannot replace a file-backed lump");
            owned[i] = data[i].clone();
        }
        for (int i = 0; i < data.length; i++) {
            int lump = first + i;
            if (lumpcache[lump] != null) ZZone.zFree(lumpcache[lump]);
            lumpinfo[lump].memoryData = owned[i];
            lumpinfo[lump].size = owned[i].length;
        }
    }

    // int W_CheckNumForName(char* name) — -1 if not found.
    //
    // The C scans backwards so later PWAD lumps override earlier ones, and uses
    // two int compares (the lump name is 8 bytes = two 4-byte ints). We just
    // compare strings case-insensitively — identical observable result.
    public static int wCheckNumForName(String name) {
        String upper = name.length() > 8 ? name.substring(0, 8).toUpperCase() : name.toUpperCase();
        for (int i = numlumps - 1; i >= 0; i--) {
            if (lumpinfo[i].name.equals(upper)) {
                return i;
            }
        }
        return -1;
    }

    // int W_GetNumForName(char* name) — I_Errors if not found.
    public static int wGetNumForName(String name) {
        int i = wCheckNumForName(name);
        if (i == -1) {
            throw ISystem.iError("W_GetNumForName: %s not found!", name);
        }
        return i;
    }

    // int W_LumpLength(int lump)
    public static int wLumpLength(int lump) {
        if (lump >= numlumps) {
            throw ISystem.iError("W_LumpLength: %d >= numlumps", lump);
        }
        return lumpinfo[lump].size;
    }

    // void W_ReadLump(int lump, void* dest)
    public static void wReadLump(int lump, byte[] dest) throws IOException {
        if (lump >= numlumps) {
            throw ISystem.iError("W_ReadLump: %d >= numlumps", lump);
        }
        LumpInfo l = lumpinfo[lump];
        if (l.memoryData != null) {
            System.arraycopy(l.memoryData, 0, dest, 0, l.size);
            return;
        }
        RandomAccessFile raf;
        boolean closeAfter = false;
        if (l.handle == -1) {
            // reloadable — open on demand
            if (reloadname == null) {
                throw ISystem.iError("W_ReadLump: reloadname is null");
            }
            raf = new RandomAccessFile(reloadname, "r");
            closeAfter = true;
        } else {
            raf = openHandles.get(l.handle);
        }
        try {
            raf.seek(l.position);
            int read = 0;
            while (read < l.size) {
                int n = raf.read(dest, read, l.size - read);
                if (n == -1) break;
                read += n;
            }
            if (read < l.size) {
                throw ISystem.iError("W_ReadLump: only read %d of %d on lump %d",
                        read, l.size, lump);
            }
        } finally {
            if (closeAfter) raf.close();
        }
    }

    // void* W_CacheLumpNum(int lump, int tag)
    public static byte[] wCacheLumpNum(int lump, int tag) throws IOException {
        if (lump < 0 || lump >= numlumps) {
            throw ISystem.iError("W_CacheLumpNum: %d >= numlumps", lump);
        }
        if (lumpcache[lump] == null) {
            // read the lump in
            final int fLump = lump;
            final byte[][] cache = lumpcache;
            final LumpInfo owner = lumpinfo[lump];
            ZZone.UserRef slot = new ZZone.UserRef() {
                public byte[] get() { return cache[fLump]; }
                public void set(byte[] v) {
                    cache[fLump] = v;
                    // Appending memory lumps grows the cache array within a mount.
                    // An old mount's eviction must still never clear a new mount.
                    if (fLump < numlumps && lumpinfo[fLump] == owner)
                        lumpcache[fLump] = v;
                }
            };
            ZZone.zMalloc(wLumpLength(lump), tag, slot);
            try {
                wReadLump(lump, lumpcache[lump]);
            } catch (IOException | RuntimeException ex) {
                ZZone.zFree(lumpcache[lump]);
                throw ex;
            }
        } else {
            ZZone.zChangeTag(lumpcache[lump], tag);
        }
        return lumpcache[lump];
    }

    // void* W_CacheLumpName(char* name, int tag)
    public static byte[] wCacheLumpName(String name, int tag) throws IOException {
        return wCacheLumpNum(wGetNumForName(name), tag);
    }

    // --- Parser helpers ---

    static WadInfo parseHeader(byte[] bytes) {
        if (bytes.length < 12) {
            throw ISystem.iError("WAD header truncated (%d bytes)", bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        WadInfo info = new WadInfo();
        byte[] id = new byte[4];
        buf.get(id);
        info.identification = new String(id, StandardCharsets.US_ASCII);
        info.numlumps = buf.getInt();
        info.infotableofs = buf.getInt();
        return info;
    }

    static List<FileLump> parseDirectory(byte[] bytes, int count) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<FileLump> out = new ArrayList<>(count);
        byte[] nameBuf = new byte[8];
        for (int i = 0; i < count; i++) {
            FileLump lump = new FileLump();
            lump.filepos = buf.getInt();
            lump.size = buf.getInt();
            buf.get(nameBuf);
            lump.name = trimLumpName(nameBuf);
            out.add(lump);
        }
        return out;
    }

    // 8-char null-padded lump name → trimmed uppercase Java String (CLAUDE.md §3).
    public static String trimLumpName(byte[] raw) {
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII).toUpperCase();
    }

    // Java launcher lifecycle: release files after ENDOOM has been read.
    public static void wShutdown() {
        for (RandomAccessFile raf : openHandles) {
            try { raf.close(); } catch (IOException ignored) { /* */ }
        }
        openHandles.clear();
        lumpinfo = new LumpInfo[0];
        lumpcache = new byte[0][];
        numlumps = 0;
        reloadname = null;
        reloadlump = 0;
    }

    // Test-only compatibility helper; production uses the same lifecycle.
    public static void resetForTests() throws IOException {
        wShutdown();
    }
}
