package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MArgvTest {

    @AfterEach
    void reset() {
        MArgv.myargc = 0;
        MArgv.myargv = new String[0];
    }

    private static void setArgs(String... args) {
        MArgv.myargv = args;
        MArgv.myargc = args.length;
    }

    @Test
    void returnsZeroWhenNotPresent() {
        setArgs("doom", "-warp", "1", "1");
        assertEquals(0, MArgv.checkParm("-nomonsters"));
    }

    // M_CheckParm starts at i=1 — argv[0] (program name) is never matched.
    @Test
    void skipsArgv0() {
        setArgs("-warp", "some", "args");
        assertEquals(0, MArgv.checkParm("-warp"));
    }

    @Test
    void returnsIndexWhenPresent() {
        setArgs("doom", "-warp", "1", "1", "-nomonsters");
        assertEquals(1, MArgv.checkParm("-warp"));
        assertEquals(4, MArgv.checkParm("-nomonsters"));
    }

    // strcasecmp is case-insensitive.
    @Test
    void caseInsensitiveMatch() {
        setArgs("doom", "-WarP", "1");
        assertEquals(1, MArgv.checkParm("-warp"));
        assertEquals(1, MArgv.checkParm("-WARP"));
    }

    @Test
    void emptyArgvReturnsZero() {
        setArgs("doom");
        assertEquals(0, MArgv.checkParm("-warp"));
    }
}
