// Port of linuxdoom-1.10/i_system.c — full (sans the abort + exit
// branches, replaced with throw / no-op for the embedded engine).
//
// The C process-exit / I_ZoneBase / I_AllocLow paths don't make
// sense in Java: the JVM owns its heap, and the engine doesn't
// allocate raw blocks of memory the way DOS Doom did. Where the C
// would call malloc + memset, the Java port hands back a `byte[0]`
// or skips the allocation entirely; ZZone (already ported) is the
// only remaining allocation tracker.
//
// I_Init / I_Quit dispatch into ISound / IVideo / DNet (each a
// stub class for now); when those ports land they take over.
package doom.system;

import doom.play.Ticcmd;

public final class ISystem {

    // i_system.c line 51 — heap budget hint (MB). Original DOS Doom
    // sized its single zone allocation with this. The Java port
    // doesn't reserve a fixed budget; ZZone uses the JVM heap.
    public static int mbUsed = 6;

    // i_system.c line 64 — a shared empty ticcmd handed back by
    // I_BaseTiccmd. The C reuses the same struct between callers.
    public static final Ticcmd emptycmd = new Ticcmd();

    // i_system.c — boot timestamp. Initialized lazily by iGetTime
    // so the first call returns 0 and subsequent calls return the
    // tic count since boot.
    private static long basetimeNanos = 0;

    private ISystem() {
    }

    // void I_Error(char* error, ...) — prints, exits the process.
    // In Java: throw. Callers that used to abort now get an exception.
    public static RuntimeException iError(String fmt, Object... args) {
        String msg = (args.length == 0) ? fmt : String.format(fmt, args);
        throw new DoomError(msg);
    }

    // i_system.c — I_Tactile.
    // Force-feedback hook that the original DOS build piped to
    // hardware controllers (the SDL/Linux ports were already a no-op
    // — see the empty body in i_system.c). Stub here too so
    // P_DamageMobj can call it on the console seat without a wired
    // backend.
    public static void iTactile(int on, int off, int total) {
        // Intentionally empty — matches the linuxdoom build.
    }

    // i_system.c — I_BaseTiccmd. Returns a shared scratch ticcmd
    // that callers fill before forwarding it to the playsim. The C
    // reuses one global; Java does the same.
    public static Ticcmd iBaseTiccmd() {
        return emptycmd;
    }

    // i_system.c — I_GetHeapSize. The C reports the configured zone
    // budget; the Java port has none, so report the C default.
    public static int iGetHeapSize() {
        return mbUsed * 1024 * 1024;
    }

    // i_system.c — I_ZoneBase.
    // The C allocates a contiguous byte buffer and hands it back to
    // ZZone. The Java port has no slab — ZZone uses the JVM heap
    // directly — so this returns a zero-length array. The `size`
    // out-param is reported in `iGetHeapSize`.
    public static byte[] iZoneBase() {
        return new byte[0];
    }

    // i_system.c — I_GetTime. Returns time in 1/70th-second tics
    // since the first call.
    public static int iGetTime() {
        long now = System.nanoTime();
        if (basetimeNanos == 0) {
            basetimeNanos = now;
            return 0;
        }
        long elapsed = now - basetimeNanos;
        // 1 tic = 1e9/TICRATE ns. Avoid rounding bias by integer
        // division on 64-bit nanoseconds.
        return (int) (elapsed * Doomdef.TICRATE / 1_000_000_000L);
    }

    // i_system.c — I_Init. Boots the audio + video subsystems.
    // The C calls I_InitSound; I_InitGraphics is intentionally
    // commented out (deferred to D_DoomMain). The Java port mirrors
    // the dispatch; the audio call lands in ISound's stub, video
    // initialization happens later in D_DoomMain.
    public static void iInit() {
        doom.sound.ISound.iInitSound();
        // I_InitGraphics is called separately from D_DoomMain.
    }

    // i_system.c — I_Quit. Tears down net + audio + saves
    // defaults + tears down video. The Java port doesn't call
    // System.exit — the JVM's main thread returns naturally when
    // the JavaFX shell closes. iError is the catastrophic-exit
    // path.
    public static void iQuit() {
        doom.system.DNet.dQuitNetGame();
        doom.sound.ISound.iShutdownSound();
        doom.sound.ISound.iShutdownMusic();
        MMisc.mSaveDefaults();
        doom.ui.IVideo.iShutdownGraphics();
    }

    // i_system.c — I_WaitVBL. Sleeps for `count * 1/70 s` so the
    // game loop can throttle to TICRATE.
    public static void iWaitVBL(int count) {
        try {
            Thread.sleep(count * 1000L / Doomdef.TICRATE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // i_system.c — I_BeginRead / I_EndRead. Empty in the Linux
    // build; preserved.
    public static void iBeginRead() {
        // intentionally empty
    }

    public static void iEndRead() {
        // intentionally empty
    }

    // i_system.c — I_AllocLow. The C malloc+memset's a low-memory
    // block (DOS-only). Java hands back a fresh zero-init byte[]
    // — same observable result.
    public static byte[] iAllocLow(int length) {
        return new byte[length];
    }

    // Exception class so tests can catch/assert on the I_Error path.
    public static final class DoomError extends RuntimeException {
        public DoomError(String message) {
            super(message);
        }
    }
}
