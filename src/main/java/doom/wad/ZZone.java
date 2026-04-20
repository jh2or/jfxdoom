// Port of linuxdoom-1.10/z_zone.c and z_zone.h.
// Zone Memory Allocation. The C implements a custom heap over a single byte
// buffer with coalescing/purging; the JVM already has a GC, so we model the
// zone as a tracked allocator — every Z_Malloc still returns a byte[] and the
// tag + user-backref mechanics are preserved, so the purge semantics used by
// W_CacheLumpNum / Z_ChangeTag / Z_FreeTags behave exactly as in C.
//
// Deviation from C (flagged in PORT_STATUS.md):
//   * No pre-allocated byte buffer; the JVM heap backs allocations directly.
//   * Z_FreeMemory returns the sum of sizes of purgable + free-for-purge blocks,
//     which in this tracker equals the sum of block sizes with tag >= PU_PURGELEVEL.
//   * Z_CheckHeap is a no-op: the JVM cannot have the C heap-corruption bugs
//     this function was written to detect (double-free coalescing errors, etc.).
package doom.wad;

import doom.system.ISystem;

public final class ZZone {

    // PU_ purge tags from z_zone.h.
    public static final int PU_STATIC = 1;
    public static final int PU_SOUND = 2;
    public static final int PU_MUSIC = 3;
    public static final int PU_DAVE = 4;
    public static final int PU_LEVEL = 50;
    public static final int PU_LEVSPEC = 51;
    public static final int PU_PURGELEVEL = 100;
    public static final int PU_CACHE = 101;

    // #define ZONEID 0x1d4a11 — kept for the API-contract checks below.
    public static final int ZONEID = 0x1d4a11;

    // `void** user` in C — address of the caller's cache slot. When the zone
    // frees or purges this block, `set(null)` clears the caller's reference.
    // The matching `get()` is used by Z_ChangeTag2 so we can find the block
    // from just `ptr`.
    public interface UserRef {
        byte[] get();

        void set(byte[] value);
    }

    // typedef struct memblock_s { int size; void** user; int tag; int id; ...
    public static final class MemBlock {
        public int size;       // bytes (payload size — no header in this port)
        public UserRef user;   // null for an unowned in-use block
        public int tag;
        public int id;         // ZONEID when live, 0 after free
        public byte[] data;    // the payload the caller sees
        MemBlock next;
        MemBlock prev;
    }

    // memzone_t mainzone — in the Java port, just the sentinel list head.
    // We keep a wrapper so Z_Init can re-initialise it the same way Z_ClearZone does in C.
    static MemBlock blocklist;

    // Map payload -> block for Z_Free/Z_ChangeTag2 lookup (in C this is a
    // pointer subtraction; Java needs identity-based indirection).
    private static final java.util.IdentityHashMap<byte[], MemBlock> byPayload =
            new java.util.IdentityHashMap<>();

    private ZZone() {
    }

    // void Z_Init(void)
    public static void zInit() {
        // Build the same circular sentinel list the C uses. No payload here.
        blocklist = new MemBlock();
        blocklist.tag = PU_STATIC;
        blocklist.next = blocklist;
        blocklist.prev = blocklist;
        byPayload.clear();
    }

    // void Z_Free(void* ptr)
    public static void zFree(byte[] ptr) {
        MemBlock block = byPayload.remove(ptr);
        if (block == null || block.id != ZONEID) {
            throw ISystem.iError("Z_Free: freed a pointer without ZONEID");
        }

        // C: `if (block->user > (void**)0x100) *block->user = 0;`
        // The `> 0x100` check was a sentinel hack for "marked as in-use but unowned"
        // (value 2). In this port, user is either null (free) or a real UserRef.
        if (block.user != null) {
            block.user.set(null);
        }

        // mark as free
        block.user = null;
        block.tag = 0;
        block.id = 0;

        // unlink from the list
        block.prev.next = block.next;
        block.next.prev = block.prev;
        block.next = null;
        block.prev = null;
    }

    // void* Z_Malloc(int size, int tag, void* user)
    // You can pass null for user if tag < PU_PURGELEVEL.
    public static byte[] zMalloc(int size, int tag, UserRef user) {
        // C: size = (size + 3) & ~3;  // round up to 4
        // We still round to keep the tracked sizes byte-identical to what the C
        // zone would have allocated — Z_FreeMemory spot-checks depend on this.
        int alignedSize = (size + 3) & ~3;

        if (user == null && tag >= PU_PURGELEVEL) {
            throw ISystem.iError("Z_Malloc: an owner is required for purgable blocks");
        }

        MemBlock block = new MemBlock();
        block.size = alignedSize;
        block.tag = tag;
        block.id = ZONEID;
        block.data = new byte[alignedSize];
        block.user = user;

        // insert at the tail of the list, just before sentinel
        block.prev = blocklist.prev;
        block.next = blocklist;
        blocklist.prev.next = block;
        blocklist.prev = block;

        byPayload.put(block.data, block);

        // C: *(void**)user = base-payload — populate the caller's cache slot.
        if (user != null) {
            user.set(block.data);
        }

        return block.data;
    }

    // void Z_FreeTags(int lowtag, int hightag)
    public static void zFreeTags(int lowtag, int hightag) {
        MemBlock block = blocklist.next;
        while (block != blocklist) {
            MemBlock next = block.next; // grab before freeing
            if (block.tag >= lowtag && block.tag <= hightag) {
                zFree(block.data);
            }
            block = next;
        }
    }

    // void Z_CheckHeap(void)
    // In C this walked the list and asserted pointer invariants. In Java the
    // linked list is always consistent — the GC can't corrupt it — so this is
    // a no-op. Kept for API compatibility with callers.
    public static void zCheckHeap() {
        // intentionally empty — see class comment
    }

    // void Z_ChangeTag2(void* ptr, int tag) — the real function; the macro
    // Z_ChangeTag also asserts the ZONEID first, same as here.
    public static void zChangeTag(byte[] ptr, int tag) {
        MemBlock block = byPayload.get(ptr);
        if (block == null || block.id != ZONEID) {
            throw ISystem.iError("Z_ChangeTag: freed a pointer without ZONEID");
        }
        if (tag >= PU_PURGELEVEL && block.user == null) {
            throw ISystem.iError("Z_ChangeTag: an owner is required for purgable blocks");
        }
        block.tag = tag;
    }

    // int Z_FreeMemory(void) — sum of sizes of free / purgable blocks.
    // In C `!block->user` meant "truly free block" (unowned-in-use used a sentinel
    // value `(void*)2`). In this Java tracker, zFree unlinks the block, so there
    // are no free blocks in the list — only tag >= PU_PURGELEVEL counts.
    public static int zFreeMemory() {
        int free = 0;
        for (MemBlock block = blocklist.next; block != blocklist; block = block.next) {
            if (block.tag >= PU_PURGELEVEL) {
                free += block.size;
            }
        }
        return free;
    }

    // Test-only helper: exposes an iteration-safe snapshot of live block count.
    static int liveBlockCount() {
        int n = 0;
        for (MemBlock block = blocklist.next; block != blocklist; block = block.next) {
            n++;
        }
        return n;
    }
}
