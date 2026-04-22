// Port of linuxdoom-1.10/i_video.c — partial stub.
//
// i_video.c is the DOS/X11 video backend; its full body (palette upload,
// keyboard polling, framebuffer blit) maps onto the JavaFX shell and is
// deferred per CLAUDE.md §11. The few functions other ported modules
// call directly land here as they are needed.
package doom.ui;

import static doom.system.Doomdef.SCREENHEIGHT;
import static doom.system.Doomdef.SCREENWIDTH;

public final class IVideo {

    // i_video.c line 527 — void I_ReadScreen (byte* scr):
    //     memcpy (scr, screens[0], SCREENWIDTH*SCREENHEIGHT);
    public static void iReadScreen(byte[] scr) {
        System.arraycopy(VVideo.screens[0], 0, scr, 0, SCREENWIDTH * SCREENHEIGHT);
    }

    private IVideo() {
    }
}
