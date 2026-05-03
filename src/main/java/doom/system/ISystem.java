// Port of linuxdoom-1.10/i_system.c and i_system.h, ONLY the I_Error hook for now.
// The full file (timer, video-mode glue, quitting, etc.) is ported later as the
// JavaFX shell comes online (PORT_STATUS layer 7).
package doom.system;

public final class ISystem {

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

    // Exception class so tests can catch/assert on the I_Error path.
    public static final class DoomError extends RuntimeException {
        public DoomError(String message) {
            super(message);
        }
    }
}
