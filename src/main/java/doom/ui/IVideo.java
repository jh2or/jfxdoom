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

    // i_video.c — I_InitGraphics.
    // Opens the X11 / SDL window in the C; the Java port defers to
    // the JavaFX shell (JfxDoomApp), which constructs the
    // PixelBuffer<IntBuffer> + Canvas independently. This stub is
    // the dispatch point D_DoomMain calls; the JavaFX shell
    // overrides the implementation by registering a back-end
    // hook (or by replacing this class outright).
    public static void iInitGraphics() {
        // intentionally empty until the JavaFX shell port wires up
    }

    // i_video.c — I_ShutdownGraphics.
    public static void iShutdownGraphics() {
        // intentionally empty
    }

    // i_video.c — I_StartFrame. Per-frame hook called at the top
    // of D_Display (game-loop frame entry).
    public static void iStartFrame() {
        // intentionally empty
    }

    // i_video.c — I_StartTic. Per-tic input poll. The C posts
    // keyboard / mouse events into D_PostEvent; the JavaFX shell
    // bridges Scene.setOnKeyPressed → DEvent here.
    public static void iStartTic() {
        // intentionally empty
    }

    // i_video.c — I_UpdateNoBlit. Mid-frame palette / wipe update,
    // doesn't push to the screen yet. No-op in the C's stripped
    // build; preserved.
    public static void iUpdateNoBlit() {
        // intentionally empty
    }

    // i_video.c — I_FinishUpdate. Pushes screens[0] (the 320×200
    // palette-byte framebuffer) to the actual display. The JavaFX
    // shell overrides; the stub is a no-op so headless tests can
    // still drive the game loop.
    public static void iFinishUpdate() {
        // intentionally empty
    }

    // i_video.c — I_SetPalette.
    // Uploads a 256-entry palette (PLAYPAL lump byte stream with
    // R/G/B triples). The JavaFX shell maps it to a 256-entry
    // ARGB int[] for the PixelBuffer.
    public static void iSetPalette(byte[] palette) {
        // intentionally empty
    }

    private IVideo() {
    }
}
