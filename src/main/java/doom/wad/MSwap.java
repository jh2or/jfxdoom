// Port of linuxdoom-1.10/m_swap.c and m_swap.h.
// WAD files are little-endian (CLAUDE.md §3). The C source hides the swap
// behind SHORT(x)/LONG(x) macros that are no-ops on little-endian hosts and
// byte-swap on big-endian. In Java we always read via ByteBuffer.LITTLE_ENDIAN,
// so SHORT/LONG are identity — but we still expose swapSHORT/swapLONG for
// byte-level tests and for any code that really does need the byte-swap.
package doom.wad;

public final class MSwap {

    private MSwap() {
    }

    // unsigned short SwapSHORT(unsigned short x)
    //     return (x>>8) | (x<<8);
    //
    // Return as int to stay unsigned — Java has no unsigned short.
    public static int swapSHORT(int x) {
        x &= 0xFFFF;
        return ((x >>> 8) | (x << 8)) & 0xFFFF;
    }

    // unsigned long SwapLONG(unsigned long x)
    //     return (x>>24) | ((x>>8) & 0xff00) | ((x<<8) & 0xff0000) | (x<<24);
    //
    // Use int (Doom's `long` is 32-bit on Linux x86). `>>>` for unsigned shift.
    public static int swapLONG(int x) {
        return (x >>> 24)
                | ((x >>> 8) & 0xff00)
                | ((x << 8) & 0xff0000)
                | (x << 24);
    }

    // #define SHORT(x) (x)  — little-endian host pass-through.
    public static int SHORT(int x) {
        return x;
    }

    // #define LONG(x) (x)
    public static int LONG(int x) {
        return x;
    }
}
