// Port of linuxdoom-1.10/i_video.c — JavaFX platform adapter.
// JfxDoomApp owns the window and registers palette/frame/tic hooks here.
// Framebuffer capture is also used by the software screen-wipe path.
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
    // JfxDoomApp creates the JavaFX window before engine boot; no separate
    // native window initialization is needed at this dispatch point.
    public static void iInitGraphics() {
        // Window lifetime is owned by the shell.
    }

    // i_video.c — I_ShutdownGraphics.
    public static void iShutdownGraphics() {
        // intentionally empty
    }

    // i_video.c — I_StartFrame. Per-frame hook called at the top
    // of D_Display (game-loop frame entry). The JavaFX shell uses
    // this to clear the screens[0] viewport area (so the two-
    // buffer composite can distinguish "UI drew here this frame"
    // from "stale renderer pixels from a previous frame").
    public static void iStartFrame() {
        if (startFrameHook != null) {
            startFrameHook.run();
        }
    }

    // i_video.c — I_StartTic. Per-tic input poll. The C posts
    // keyboard / mouse events into D_PostEvent; the JavaFX shell
    // bridges Scene.setOnKeyPressed → DEvent here via the hook.
    public static void iStartTic() {
        if (startTicHook != null) {
            startTicHook.run();
        }
    }

    // i_video.c — I_UpdateNoBlit. Mid-frame palette / wipe update,
    // doesn't push to the screen yet. No-op in the C's stripped
    // build; preserved.
    public static void iUpdateNoBlit() {
        // intentionally empty
    }

    // Hooks the JavaFX shell registers when JfxDoomApp boots, so
    // headless tests still get a no-op iFinishUpdate while the
    // running game pumps screens[0] into a JavaFX Canvas. Same
    // shape as the C function-pointer dispatch the renderer uses
    // for colfunc/spanfunc — see deviation 31.
    public static Runnable finishUpdateHook;
    public static Runnable startTicHook;
    public static Runnable startFrameHook;
    public static java.util.function.Consumer<byte[]> setPaletteHook;

    // i_video.c — I_FinishUpdate. Pushes screens[0] (the 320×200
    // palette-byte framebuffer) to the actual display. Defers to
    // the registered hook (the JavaFX shell sets it on boot);
    // headless tests leave it null.
    public static void iFinishUpdate() {
        if (finishUpdateHook != null) {
            finishUpdateHook.run();
        }
    }

    // i_video.c — I_SetPalette.
    // Uploads a 256-entry palette (PLAYPAL lump byte stream with
    // R/G/B triples). The JavaFX shell maps it to a 256-entry
    // ARGB int[] for the PixelBuffer.
    public static void iSetPalette(byte[] palette) {
        if (setPaletteHook != null) {
            setPaletteHook.accept(palette);
        }
    }

    private IVideo() {
    }
}
