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
// Boot (no-args / launcher path):
//   1. JfxDoomApp.main(args) → Application.launch → start(Stage).
//   2. start() shows the StartupUi scene. The user picks a WAD
//      and a DisplayMode (windowed Nx or fullscreen) and clicks
//      Launch.
//   3. The launch callback hands control to bootGame(), which
//      builds the Canvas + Scene, registers IVideo hooks, calls
//      DMain.dDoomMain to init subsystems, then starts the
//      AnimationTimer ticking at TICRATE.
//   4. When the engine wants to quit (menu Quit / dDoomLoop's
//      iQuit), the overridden ISystem.quitHook calls
//      returnToLauncher() instead of System.exit — we stop the
//      timer, drop fullscreen, and switch the scene back to a
//      fresh StartupUi. Closing the window is a real exit (stop()
//      sets shuttingDown and the quit hook honours it).
//
// Boot (CLI-args path):
//   1. start() bypasses the launcher and calls bootGame directly
//      with launchArgs. Quitting still routes through
//      returnToLauncher so the user lands in the launcher rather
//      than losing the window.
package doom.ui;

import doom.system.DEvent;
import doom.system.DMain;
import doom.system.Doomdef;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.IntBuffer;

public final class JfxDoomApp extends Application {

    // CLI args captured from main() and replayed in start().
    private static String[] launchArgs = new String[0];

    // ARGB blit buffer is sized to the *render* framebuffer
    // (RENDER_WIDTH × RENDER_HEIGHT) so the hi-res 3D view shows
    // at native sample density; UI pixels from screens[0] are
    // nearest-neighbour upscaled by RENDER_MUL when composited
    // into argb. JavaFX then upscales argb → window by the
    // user's chosen display scale.
    //
    // RENDER_MUL is mutable (the Startup UI lets the user pick a
    // render scale per session). The argb / pixelBuffer / image
    // triple is re-created in bootGame after Doomdef.setRenderMul
    // bumps the constants — UI_W is fixed (always 320) and the
    // Doom aspect 320:200 is a literal so the fitter stays
    // resolution-agnostic.
    private static final int UI_W = Doomdef.SCREENWIDTH;
    private static final double DOOM_ASPECT = 320.0 / 200.0;
    // Default windowed scale for the CLI-driven boot path.
    private static final int DEFAULT_SCALE = 3;

    private int[] argb;
    private PixelBuffer<IntBuffer> pixelBuffer;
    private WritableImage image;

    // 256-entry ARGB palette. Indexed by VVideo.screens[0] bytes.
    private final int[] palette = new int[256];

    // Mutable session state.
    private AnimationTimer animationTimer;
    private boolean hasBootedGame;
    private boolean shuttingDown;
    private String lastIwad;
    private StartupUi.DisplayMode lastDisplay = StartupUi.DisplayMode.SCALE_3X;
    private int lastRenderMul = 2;

    public static void main(String[] args) {
        launchArgs = args;
        Application.launch(JfxDoomApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("jfxdoom");

        // Route engine quit requests back to the launcher instead
        // of System.exit. shuttingDown is set by stop() so a real
        // window close still terminates the JVM.
        ISystem.quitHook = () -> {
            if (shuttingDown) return;
            Platform.runLater(() -> returnToLauncher(stage));
        };

        if (launchArgs.length > 0) {
            // CLI-driven boot — skip the Startup UI entirely.
            captureLastFromArgs(launchArgs);
            bootGame(stage, launchArgs, StartupUi.DisplayMode.SCALE_3X, lastRenderMul);
        } else {
            showLauncher(stage);
        }
    }

    private void showLauncher(Stage stage) {
        StartupUi ui = new StartupUi().prefill(lastIwad, lastDisplay, lastRenderMul);
        Scene startupScene = ui.build(stage, (args, display, renderMul) -> {
            captureLastFromArgs(args);
            lastDisplay = display;
            lastRenderMul = renderMul;
            bootGame(stage, args, display, renderMul);
        });
        stage.setFullScreen(false);
        stage.setScene(startupScene);
        stage.setResizable(true);
        // The launcher window has a different size than the game
        // canvas — re-centre once the new scene is in place.
        stage.sizeToScene();
        stage.centerOnScreen();
        if (!stage.isShowing()) stage.show();
    }

    // Build the game canvas + key listeners, hand control to the
    // engine, then drive it at TICRATE. Called from start() (CLI
    // path) or from the Startup UI's Launch callback.
    private void bootGame(Stage stage, String[] args,
                          StartupUi.DisplayMode display, int renderMul) {
        // Apply the user's render-scale choice before any renderer
        // class allocates RENDER_*-sized scratch arrays. setRenderMul
        // updates the Doomdef constants; the resizeForRender calls
        // swap module-level static arrays (RMain, RPlane, RThings,
        // RDraw) to match. VVideo.viewBuffer re-allocates inside
        // vInit, which dDoomMain calls below.
        Doomdef.setRenderMul(renderMul);
        doom.render.RMain.resizeForRender();
        doom.render.RPlane.resizeForRender();
        doom.render.RThings.resizeForRender();
        doom.render.RDraw.resizeForRender();

        // (Re-)allocate the ARGB blit buffer + PixelBuffer +
        // WritableImage at the new RENDER_* size. Each is wrapped
        // around the previous one, so we re-create the whole chain.
        int w = Doomdef.RENDER_WIDTH;
        int h = Doomdef.RENDER_HEIGHT;
        if (argb == null || argb.length != w * h) {
            argb = new int[w * h];
            pixelBuffer = new PixelBuffer<>(w, h, IntBuffer.wrap(argb),
                    PixelFormat.getIntArgbPreInstance());
            image = new WritableImage(pixelBuffer);
        }

        // Engine state needs a fresh start on re-launch: clear
        // the quitting guard and drop the previous WAD list so
        // dDoomMain doesn't double-load.
        if (hasBootedGame) {
            ISystem.iResetForRelaunch();
            DMain.dResetWadFiles();
        }

        Canvas canvas = new Canvas();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        // Pixel-perfect upscale.
        gc.setImageSmoothing(false);

        StackPane root = new StackPane(canvas);
        // Black bars when the canvas doesn't fill the stage
        // (fullscreen aspect mismatch).
        root.setStyle("-fx-background-color: black;");

        // Canvas / scene sizing uses the *UI* logical grid (320x200) ×
        // user-picked display scale. The renderer's RENDER_MUL only
        // affects sample density inside that same window — JavaFX
        // scales the hi-res argb image to fit.
        int uiH = Doomdef.SCREENHEIGHT;
        Scene scene;
        if (display != null && display.fullscreen) {
            scene = new Scene(root);
            installAspectFitter(canvas, scene);
        } else {
            int scale = (display != null) ? display.scale : DEFAULT_SCALE;
            if (scale <= 0) scale = DEFAULT_SCALE;
            canvas.setWidth(UI_W * scale);
            canvas.setHeight(uiH * scale);
            scene = new Scene(root, UI_W * scale, uiH * scale);
        }

        scene.setOnKeyPressed(e -> {
            int code = mapKey(e.getCode(), e.getText());
            if (code < 0) return;
            DMain.dPostEvent(new DEvent(DEvent.EV_KEYDOWN, code));
        });
        scene.setOnKeyReleased(e -> {
            int code = mapKey(e.getCode(), e.getText());
            if (code < 0) return;
            DMain.dPostEvent(new DEvent(DEvent.EV_KEYUP, code));
        });
        // Layout-shifted characters: KeyPressed only reports the
        // physical key's unshifted character (Shift+0 → "0" on
        // Windows), so chars like '=' / '+' on a German keyboard
        // never reach the menu's screen-size handler from KeyPressed
        // alone. KeyTyped delivers the post-layout, post-modifier
        // character — narrowly forward the ones the menu cares
        // about so normal letter/digit keys aren't double-fired.
        scene.setOnKeyTyped(e -> {
            String chars = e.getCharacter();
            if (chars == null || chars.isEmpty()) return;
            char c = chars.charAt(0);
            if (c == '=' || c == '+') {
                DMain.dPostEvent(new DEvent(DEvent.EV_KEYDOWN, c));
                DMain.dPostEvent(new DEvent(DEvent.EV_KEYUP,   c));
            }
        });

        stage.setScene(scene);
        if (display != null && display.fullscreen) {
            stage.setResizable(true);
            // Suppress JavaFX's default Esc-exits-fullscreen so the
            // Doom menu's Esc keeps working.
            stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
            stage.setFullScreenExitHint("");
            stage.setFullScreen(true);
        } else {
            stage.setFullScreen(false);
            stage.setResizable(false);
            stage.sizeToScene();
            stage.centerOnScreen();
        }
        if (!stage.isShowing()) stage.show();

        // Register IVideo hooks. The drawImage size uses the
        // live canvas dimensions so fullscreen resizes (e.g. on
        // monitor change) are picked up without re-registration.
        //
        // The argb blit composites two palette buffers each frame:
        //   - VVideo.screens[0]  (UI, sized UI_W × UI_H)
        //   - VVideo.viewBuffer  (3D view, sized W × H)
        // For pixels inside the active viewport rect the view
        // buffer wins; outside, the UI buffer is nearest-neighbour
        // upscaled by RENDER_MUL. The viewport is "active" only
        // when the engine is in GS_LEVEL and the automap is off —
        // otherwise the UI buffer carries the full frame (title
        // screen, intermission, finale, automap).
        IVideo.finishUpdateHook = () -> {
            byte[] ui = VVideo.screens[0];
            byte[] view = VVideo.viewBuffer;
            if (ui == null) return;

            int vx = doom.render.RDraw.viewwindowx;
            int vy = doom.render.RDraw.viewwindowy;
            int vw = doom.render.RDraw.scaledviewwidth;
            int vh = doom.render.RDraw.viewheight;
            boolean viewActive =
                    doom.system.Doomstat.gamestate == doom.system.Doomstat.GS_LEVEL
                    && !doom.ui.AmMap.automapactive
                    && vw > 0 && vh > 0;

            int vxEnd = vx + vw;
            int vyEnd = vy + vh;

            // Read render dims dynamically (RENDER_MUL is a per-
            // session value the Startup UI sets).
            int renderW = Doomdef.RENDER_WIDTH;
            int renderH = Doomdef.RENDER_HEIGHT;
            int rm      = Doomdef.RENDER_MUL;

            int i = 0;
            for (int y = 0; y < renderH; y++) {
                int uiRowOff = (y / rm) * UI_W;
                boolean rowInView = viewActive && y >= vy && y < vyEnd;
                int viewRowOff = y * renderW;
                for (int x = 0; x < renderW; x++, i++) {
                    int pal;
                    if (rowInView && x >= vx && x < vxEnd) {
                        // viewport zone: prefer the UI byte if non-
                        // zero (a menu/HUD overlay painted there
                        // this frame, post-startFrameHook clear),
                        // otherwise the 3D view's pixel.
                        int uiPal = ui[uiRowOff + (x / rm)] & 0xff;
                        pal = (uiPal != 0) ? uiPal : (view[viewRowOff + x] & 0xff);
                    } else {
                        pal = ui[uiRowOff + (x / rm)] & 0xff;
                    }
                    argb[i] = palette[pal];
                }
            }

            pixelBuffer.updateBuffer(b -> null);
            gc.drawImage(image, 0, 0, canvas.getWidth(), canvas.getHeight());
        };
        IVideo.setPaletteHook = bytes -> uploadPalette(bytes);
        IVideo.startTicHook = () -> {
            // Drain queued key events into the Doom event queue
            // when D_PostEvent is wired (deferred).
        };
        // Each frame, before the engine paints anything, clear
        // the *viewport* slice of screens[0] to palette index 0.
        // The composite below treats any non-zero pixel in the
        // viewport zone as "UI drew here" (menu / HUD overlay /
        // pause text / message banner) and prefers it over
        // viewBuffer. Anything outside the viewport (status bar,
        // brick border, automap) is untouched.
        IVideo.startFrameHook = () -> {
            byte[] ui = VVideo.screens[0];
            if (ui == null) return;
            int rm = Doomdef.RENDER_MUL;
            int vxUi = doom.render.RDraw.viewwindowx / rm;
            int vyUi = doom.render.RDraw.viewwindowy / rm;
            int vwUi = doom.render.RDraw.scaledviewwidth / rm;
            int vhUi = doom.render.RDraw.viewheight / rm;
            if (vwUi <= 0 || vhUi <= 0) return;
            for (int y = vyUi; y < vyUi + vhUi; y++) {
                int rowOff = y * UI_W + vxUi;
                java.util.Arrays.fill(ui, rowOff, rowOff + vwUi, (byte) 0);
            }
        };

        // Boot the engine.
        try {
            DMain.dDoomMain(args);
            // Load PLAYPAL and set the initial palette so the
            // colormap bytes resolve to actual ARGB.
            loadInitialPalette();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        hasBootedGame = true;
        animationTimer = new AnimationTimer() {
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
        };
        animationTimer.start();
    }

    // Called from the overridden ISystem.quitHook. The engine has
    // already torn down audio and saved defaults by the time we
    // get here — we just stop the tic loop and swap back to a
    // fresh StartupUi.
    private void returnToLauncher(Stage stage) {
        if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
        }
        // Drop hooks so any straggling engine callbacks no-op
        // until bootGame re-installs them.
        IVideo.finishUpdateHook = null;
        IVideo.setPaletteHook = null;
        IVideo.startTicHook = null;
        showLauncher(stage);
    }

    // Resize the canvas inside the StackPane so 320:200 is
    // preserved when the scene is larger or a different aspect
    // ratio than the engine's native ratio. Black bars appear via
    // the StackPane's background.
    private static void installAspectFitter(Canvas canvas, Scene scene) {
        InvalidationListener l = obs -> {
            double sw = scene.getWidth();
            double sh = scene.getHeight();
            if (sw <= 0 || sh <= 0) return;
            double sa = sw / sh;
            double cw, ch;
            if (sa > DOOM_ASPECT) {
                // Wider than Doom — pillarbox (limit width).
                ch = sh;
                cw = sh * DOOM_ASPECT;
            } else {
                // Taller than Doom — letterbox (limit height).
                cw = sw;
                ch = sw / DOOM_ASPECT;
            }
            canvas.setWidth(cw);
            canvas.setHeight(ch);
        };
        scene.widthProperty().addListener(l);
        scene.heightProperty().addListener(l);
        l.invalidated(null);
    }

    private void captureLastFromArgs(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("-iwad".equals(args[i])) {
                lastIwad = args[i + 1];
                return;
            }
        }
    }

    @Override
    public void stop() {
        // Window close → real exit. shuttingDown short-circuits
        // the quit hook so it doesn't try to re-show the launcher.
        shuttingDown = true;
        try {
            ISystem.iQuit();
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

    // Translate a JavaFX KeyCode (+ typed text) to the Doom
    // keycode space (Doomdef.KEY_* + raw ASCII for letter / digit
    // keys). Returns -1 for unmapped keys (modifiers, etc.).
    //
    // `text` is the KeyEvent.getText() result; it respects the
    // user's keyboard layout (e.g. on a German layout the "-"
    // character arrives on a different physical KeyCode than US,
    // so KeyCode.MINUS never matches — but getText() returns "-"
    // either way). For printable ASCII the typed character takes
    // precedence over the KeyCode mapping; modifier and special
    // keys fall back to the KeyCode switch.
    private static int mapKey(KeyCode k, String text) {
        // Layout-aware shortcut for printable ASCII (esp. the
        // screen-size keys '-' / '=' / ',' / '.').
        if (text != null && text.length() == 1) {
            char c = text.charAt(0);
            if (c > 0 && c < Doomdef.NUMKEYS) {
                return Character.toLowerCase(c);
            }
        }
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
            case MINUS:       return Doomdef.KEY_MINUS;
            case EQUALS:      return Doomdef.KEY_EQUALS;
            default:
                // Plain ASCII pass-through for letter / digit keys
                // — Doom's responder reads gamekeydown[ch] for the
                // weapon-change shortcuts ('1'..'7'). Used as a
                // last resort if getText() didn't deliver a char.
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
