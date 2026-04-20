package doom.wad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.system.ISystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZZoneTest {

    // Simple mutable UserRef — analogous to the C caller's `&lumpcache[n]`.
    static final class Slot implements ZZone.UserRef {
        byte[] ptr;
        public byte[] get() { return ptr; }
        public void set(byte[] v) { ptr = v; }
    }

    @BeforeEach
    void init() {
        ZZone.zInit();
    }

    @Test
    void initGivesEmptyZone() {
        assertEquals(0, ZZone.liveBlockCount());
        assertEquals(0, ZZone.zFreeMemory());
    }

    @Test
    void mallocReturnsBufferOfAlignedSize() {
        byte[] a = ZZone.zMalloc(100, ZZone.PU_STATIC, null);
        // C: size = (size+3) & ~3 → 100 stays 100.
        assertEquals(100, a.length);

        byte[] b = ZZone.zMalloc(101, ZZone.PU_STATIC, null);
        // 101 rounded up to 104.
        assertEquals(104, b.length);
    }

    @Test
    void mallocSetsUserSlot() {
        Slot slot = new Slot();
        byte[] data = ZZone.zMalloc(32, ZZone.PU_CACHE, slot);
        // Zone writes the new pointer into the slot, per the C
        //   `*(void**)user = (void*)((byte*)base + sizeof(memblock_t))`.
        assertNotNull(data);
        assertEquals(data, slot.ptr);
    }

    @Test
    void purgableBlockRequiresOwner() {
        assertThrows(ISystem.DoomError.class,
                () -> ZZone.zMalloc(64, ZZone.PU_CACHE, null));
        assertThrows(ISystem.DoomError.class,
                () -> ZZone.zMalloc(64, ZZone.PU_PURGELEVEL, null));
    }

    @Test
    void freeClearsUserSlot() {
        Slot slot = new Slot();
        byte[] data = ZZone.zMalloc(16, ZZone.PU_CACHE, slot);
        ZZone.zFree(data);
        // Matches the C: `*block->user = 0;`
        assertNull(slot.ptr);
        assertEquals(0, ZZone.liveBlockCount());
    }

    @Test
    void freeWithoutZoneIdThrows() {
        byte[] foreign = new byte[16];
        assertThrows(ISystem.DoomError.class, () -> ZZone.zFree(foreign));
    }

    @Test
    void changeTagUpdatesTag() {
        Slot slot = new Slot();
        byte[] data = ZZone.zMalloc(32, ZZone.PU_STATIC, slot);
        ZZone.zChangeTag(data, ZZone.PU_CACHE);
        // Promoting to PU_CACHE requires an owner — we provided one.
        ZZone.zChangeTag(data, ZZone.PU_STATIC);
    }

    @Test
    void changeTagPromotingToPurgableWithoutOwnerThrows() {
        byte[] data = ZZone.zMalloc(32, ZZone.PU_STATIC, null);
        assertThrows(ISystem.DoomError.class,
                () -> ZZone.zChangeTag(data, ZZone.PU_CACHE));
    }

    @Test
    void changeTagForeignPointerThrows() {
        byte[] foreign = new byte[16];
        assertThrows(ISystem.DoomError.class,
                () -> ZZone.zChangeTag(foreign, ZZone.PU_STATIC));
    }

    @Test
    void freeTagsRemovesMatchingBlocks() {
        ZZone.zMalloc(100, ZZone.PU_STATIC, null);   // tag 1
        ZZone.zMalloc(200, ZZone.PU_LEVEL, null);    // tag 50
        Slot cacheSlot = new Slot();
        byte[] cached = ZZone.zMalloc(300, ZZone.PU_CACHE, cacheSlot); // tag 101
        assertTrue(ZZone.liveBlockCount() == 3);

        // Free everything tagged as PU_LEVEL (50..51).
        ZZone.zFreeTags(ZZone.PU_LEVEL, ZZone.PU_LEVSPEC);
        assertEquals(2, ZZone.liveBlockCount());

        // The cached block's slot is untouched because its tag is PU_CACHE (101).
        assertEquals(cached, cacheSlot.ptr);

        // Free every purgable block.
        ZZone.zFreeTags(ZZone.PU_PURGELEVEL, Integer.MAX_VALUE);
        assertEquals(1, ZZone.liveBlockCount());
        assertNull(cacheSlot.ptr);
    }

    @Test
    void freeMemoryCountsPurgable() {
        ZZone.zMalloc(100, ZZone.PU_STATIC, null);              // not counted
        Slot s = new Slot();
        ZZone.zMalloc(300, ZZone.PU_CACHE, s);                  // counted (300)
        assertEquals(300, ZZone.zFreeMemory());
    }
}
