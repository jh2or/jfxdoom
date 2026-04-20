// Port of linuxdoom-1.10/m_argv.c and m_argv.h.
// Command-line argument handling.
package doom.system;

public final class MArgv {

    // int myargc;  /  char** myargv;
    //
    // Java's String[] is the direct analogue of char**. myargc is redundant
    // (use myargv.length) but we keep it to match the C globals 1:1.
    public static int myargc;
    public static String[] myargv = new String[0];

    private MArgv() {
    }

    // int M_CheckParm(char* check)
    //     for (i=1; i<myargc; i++) if (!strcasecmp(check, myargv[i])) return i;
    //     return 0;
    public static int checkParm(String check) {
        for (int i = 1; i < myargc; i++) {
            if (check.equalsIgnoreCase(myargv[i])) {
                return i;
            }
        }
        return 0;
    }
}
