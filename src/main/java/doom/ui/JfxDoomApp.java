// JavaFX shell for the Doom engine.
//
// Replaces linuxdoom-1.10/i_main.c + the X11/SDL backend bits of
// i_video.c / i_sound.c. Wires:
//   - The 320×200 palette-byte framebuffer (VVideo.screens[0]) to a
//     Canvas via WritableImage + IntBuffer (one ARGB int per pixel).
//   - The 35Hz tic schedule to a JavaFX AnimationTimer driving
//     DMain.dDoomLoop.
//   - JavaFX Scene key events to a small DEvent queue read by
//     DMain.dProcessEvents (deferred until the responder chain
//     ports — events are queued but unused for now).
//
// Boot:
//   1. JfxDoomApp.main(args) (the application entry) calls
//      Application.launch which invokes start(Stage).
//   2. start() builds the Canvas + Scene + key listeners, then
//      runs DMain.dDoomMain(args) inline (boots V/Z/W/R/P/I/S
//      subsystems and warps to the requested level).
//   3. AnimationTimer ticks at 35Hz (using TICRATE), each call
//      runs DMain.dDoomLoop() and repaints the canvas.
//
// Run with:
//   gradle run --args="-iwad reference-wads/DOOM.WAD -warp 1 1"
package doom.ui;

import doom.system.DEvent;
import doom.system.DMain;
import doom.system.Doomdef;
import doom.wad.WWad;
import doom.wad.ZZone;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.IntBuffer;

public final class JfxDoomApp extends Application {

    // CLI args captured from main() and replayed in start().
    private static String[] launchArgs = new String[0];

    private static final int W = Doomdef.SCREENWIDTH;
    private static final int H = Doomdef.SCREENHEIGHT;
    // Default integer scale so the 320×200 frame has presence on
    // modern displays.
    private static final int SCALE = 3;

    // The JavaFX-side framebuffer: one ARGB int per pixel,
    // wrapped in a PixelBuffer<IntBuffer> backing a WritableImage.
    private final int[] argb = new int[W * H];
    private final IntBuffer argbBuf = IntBuffer.wrap(argb);
    private final PixelBuffer<IntBuffer> pixelBuffer =
            new PixelBuffer<>(W, H, argbBuf, PixelFormat.getIntArgbPreInstance());
    private final WritableImage image = new WritableImage(pixelBuffer);

    // 256-entry ARGB palette. Indexed by VVideo.screens[0] bytes.
    private final int[] palette = new int[256];

    public static void main(String[] args) {
        launchArgs = args;
        Application.launch(JfxDoomApp.class, args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        Canvas canvas = new Canvas(W * SCALE, H * SCALE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        // Pixel-perfect upscale.
        gc.setImageSmoothing(false);

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, W * SCALE, H * SCALE);

        // Key events queued for the engine. M_Responder /
        // G_Responder dispatch lands when m_menu / g_game port;
        // for now, ESC closes the window.
        scene.setOnKeyPressed(e -> {
            int code = mapKey(e.getCode());
            if (code < 0) return;
            DEvent ev = new DEvent(DEvent.EV_KEYDOWN, code);
            DMain.dPostEvent(ev);
        });
        scene.setOnKeyReleased(e -> {
            int code = mapKey(e.getCode());
            if (code < 0) return;
            DEvent ev = new DEvent(DEvent.EV_KEYUP, code);
            DMain.dPostEvent(ev);
        });

        stage.setTitle("jfxdoom");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // Register IVideo hooks.
        IVideo.finishUpdateHook = () -> {
            // Convert palette-byte framebuffer → ARGB ints.
            byte[] src = VVideo.screens[0];
            if (src == null) {
                return;
            }
            for (int i = 0; i < W * H; i++) {
                argb[i] = palette[src[i] & 0xff];
            }
            // Rebind the buffer (no-op on contents — signals the
            // PixelBuffer that the underlying ints changed).
            pixelBuffer.updateBuffer(b -> null);
            // Repaint Canvas.
            gc.drawImage(image, 0, 0, W * SCALE, H * SCALE);
        };
        IVideo.setPaletteHook = bytes -> uploadPalette(bytes);
        IVideo.startTicHook = () -> {
            // Drain queued key events into the Doom event queue
            // when D_PostEvent is wired (deferred).
        };

        // Boot the engine.
        DMain.dDoomMain(launchArgs);

        // Load PLAYPAL and set the initial palette so the colormap
        // bytes resolve to actual ARGB.
        loadInitialPalette();

        // Drive the engine at TICRATE Hz.
        new AnimationTimer() {
            long lastNs = 0;
            final long ticNs = 1_000_000_000L / Doomdef.TICRATE;

            @Override
            public void handle(long now) {
                if (lastNs == 0) {
                    lastNs = now;
                }
                while (now - lastNs >= ticNs) {
                    DMain.dDoomLoop();
                    lastNs += ticNs;
                }
            }
        }.start();
    }

    @Override
    public void stop() {
        try {
            doom.system.ISystem.iQuit();
        } catch (Throwable t) {
            // best-effort shutdown
        }
    }

    // PLAYPAL is a 256-RGB-triple palette table. Doom shipped 14
    // palettes (different damage tints, pickup gold), but R/G/B is
    // identical across the first one and the initial render uses
    // it.
    private void loadInitialPalette() throws IOException {
        int lump = WWad.wCheckNumForName("PLAYPAL");
        if (lump < 0) {
            // No palette → leave argb at all-black. Caller logs.
            return;
        }
        byte[] bytes = WWad.wCacheLumpNum(lump, ZZone.PU_CACHE);
        uploadPalette(bytes);
    }

    private void uploadPalette(byte[] bytes) {
        for (int i = 0; i < 256; i++) {
            int r = bytes[i * 3]     & 0xff;
            int g = bytes[i * 3 + 1] & 0xff;
            int b = bytes[i * 3 + 2] & 0xff;
            palette[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }

    // Translate a JavaFX KeyCode to the Doom keycode space
    // (Doomdef.KEY_* + raw ASCII for letter / digit keys).
    // Returns -1 for unmapped keys (modifiers, etc.).
    private static int mapKey(KeyCode k) {
        switch (k) {
            case LEFT:        return Doomdef.KEY_LEFTARROW;
            case RIGHT:       return Doomdef.KEY_RIGHTARROW;
            case UP:          return Doomdef.KEY_UPARROW;
            case DOWN:        return Doomdef.KEY_DOWNARROW;
            case ESCAPE:      return Doomdef.KEY_ESCAPE;
            case ENTER:       return Doomdef.KEY_ENTER;
            case BACK_SPACE:  return Doomdef.KEY_BACKSPACE;
            case PAUSE:       return Doomdef.KEY_PAUSE;
            case SHIFT:       return Doomdef.KEY_RSHIFT;
            case CONTROL:     return Doomdef.KEY_RCTRL;
            case ALT:         return Doomdef.KEY_RALT;
            case SPACE:       return Doomdef.KEY_SPACE;
            case COMMA:       return Doomdef.KEY_COMMA;
            case PERIOD:      return Doomdef.KEY_PERIOD;
            default:
                // Plain ASCII pass-through for letter / digit keys
                // — Doom's responder reads gamekeydown[ch] for the
                // weapon-change shortcuts ('1'..'7').
                String s = k.getName();
                if (s.length() == 1) {
                    char c = Character.toLowerCase(s.charAt(0));
                    if (c < Doomdef.NUMKEYS) {
                        return c;
                    }
                }
                return -1;
        }
    }
}
