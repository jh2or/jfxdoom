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

    // Exception class so tests can catch/assert on the I_Error path.
    public static final class DoomError extends RuntimeException {
        public DoomError(String message) {
            super(message);
        }
    }
}
