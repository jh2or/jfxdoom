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
//   2. start() shows the Launchpad scene. The user picks a WAD
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
//      fresh Launchpad. Closing the window is a real exit (stop()
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
    // RENDER_MUL is mutable (the Launchpad lets the user pick a
    // render scale per session). The argb / pixelBuffer / image
    // triple is re-created in bootGame after Doomdef.setRenderMul
    // bumps the constants — UI_W is fixed (always 320) and the
    // Doom aspect 320:200 is a literal so the fitter stays
    // resolution-agnostic.
    private static final int UI_W = Doomdef.SCREENWIDTH;
    private static final double DOOM_ASPECT = 320.0 / 200.0;
    // Default windowed scale for the CLI-driven boot path.
    private static final int DEFAULT_SCALE = 4;

    // 256-entry ARGB palette. Indexed by VVideo.screens[0] bytes.
    private final int[] palette = new int[256];

    // Mutable session state.
    private AnimationTimer animationTimer;
    private boolean hasBootedGame;
    private boolean shuttingDown;
    private String lastIwad;
    private Launchpad.DisplayMode lastDisplay = Launchpad.DisplayMode.SCALE_4X;
    private int lastRenderMul = 1;
    private Launchpad.RendererMode lastRenderer = Launchpad.RendererMode.SOFTWARE;

    // One blit target: canvas + ARGB buffer + PixelBuffer-backed
    // image. Software mode uses one, pure 3D one, side-by-side two
    // (one per pane, composited differently).
    private static final class BlitSurface {
        final Canvas canvas = new Canvas();
        final GraphicsContext gc = canvas.getGraphicsContext2D();
        final int[] argb;
        final PixelBuffer<IntBuffer> pixelBuffer;
        final WritableImage image;

        BlitSurface(int w, int h) {
            gc.setImageSmoothing(false); // pixel-perfect upscale
            argb = new int[w * h];
            pixelBuffer = new PixelBuffer<>(w, h, IntBuffer.wrap(argb),
                    PixelFormat.getIntArgbPreInstance());
            image = new WritableImage(pixelBuffer);
        }

        // clearFirst: the fx3 composite writes transparent holes,
        // which don't overwrite last frame's pixels on their own.
        void blit(boolean clearFirst) {
            pixelBuffer.updateBuffer(b -> null);
            if (clearFirst) {
                gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            }
            gc.drawImage(image, 0, 0, canvas.getWidth(), canvas.getHeight());
        }
    }

    // 3D-mode palette-flash tint (sub-palette entry 0) and the last
    // applied fixedcolormap effect, updated from the render hook.
    private javafx.scene.paint.Color fx3TintColor = javafx.scene.paint.Color.BLACK;
    private int fx3LastFixedColormap;

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
            // CLI-driven boot — skip the Launchpad entirely. The
            // -fx3d / -sbs / -sbsv flags select the experimental
            // scene-graph renderer resp. the side-by-side modes
            // (3D_OVERHAUL.md, 3D_SIDEBYSIDE_PLAN.md); the engine's
            // arg parser ignores flags it doesn't know.
            captureLastFromArgs(launchArgs);
            Launchpad.RendererMode mode = Launchpad.RendererMode.SOFTWARE;
            for (String a : launchArgs) {
                if ("-fx3d".equals(a)) mode = Launchpad.RendererMode.FX3D;
                else if ("-sbs".equals(a))  mode = Launchpad.RendererMode.SBS_H;
                else if ("-sbsv".equals(a)) mode = Launchpad.RendererMode.SBS_V;
            }
            lastRenderer = mode;
            bootGame(stage, launchArgs, Launchpad.DisplayMode.SCALE_4X,
                    lastRenderMul, mode);
        } else {
            showLauncher(stage);
        }
    }

    private void showLauncher(Stage stage) {
        Launchpad ui = new Launchpad()
                .prefill(lastIwad, lastDisplay, lastRenderMul, lastRenderer);
        Scene startupScene = ui.build(stage, (args, display, renderMul, renderer) -> {
            captureLastFromArgs(args);
            lastDisplay = display;
            lastRenderMul = renderMul;
            lastRenderer = renderer;
            bootGame(stage, args, display, renderMul, renderer);
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

    // Build the game pane(s) + key listeners, hand control to the
    // engine, then drive it at TICRATE. Called from start() (CLI
    // path) or from the Launchpad's Launch callback.
    private void bootGame(Stage stage, String[] args,
                          Launchpad.DisplayMode display, int renderMul,
                          Launchpad.RendererMode modeIn) {
        Launchpad.RendererMode mode =
                (modeIn != null) ? modeIn : Launchpad.RendererMode.SOFTWARE;
        if (mode.usesFx3() && !javafx.application.Platform.isSupported(
                javafx.application.ConditionalFeature.SCENE3D)) {
            // Same degrade-gracefully policy as audio (Windows
            // Sandbox: no D3D pipeline → no SCENE3D).
            System.err.println(
                    "jfxdoom: SCENE3D not supported by this JavaFX "
                    + "pipeline — falling back to the software renderer.");
            mode = Launchpad.RendererMode.SOFTWARE;
        }
        // In pure 3D mode the world draws at display resolution and
        // the render buffer only carries the weapon overlay — pinned
        // to 2× so the (greyed-out) Launchpad scale truly has no
        // effect. Side-by-side honours the user's choice: it applies
        // to the software pane (and, incidentally, the 3D pane's
        // weapon overlay).
        if (mode == Launchpad.RendererMode.FX3D) {
            renderMul = 2;
        }
        boolean fx3 = mode.usesFx3();
        boolean sbs = mode.sideBySide();
        boolean sbsHorizontal = mode == Launchpad.RendererMode.SBS_H;

        // Apply the render-scale choice before any renderer class
        // allocates RENDER_*-sized scratch arrays. setRenderMul
        // updates the Doomdef constants; the resizeForRender calls
        // swap module-level static arrays (RMain, RPlane, RThings,
        // RDraw) to match. VVideo.viewBuffer re-allocates inside
        // vInit, which dDoomMain calls below.
        Doomdef.setRenderMul(renderMul);
        doom.render.RMain.resizeForRender();
        doom.render.RPlane.resizeForRender();
        doom.render.RThings.resizeForRender();
        doom.render.RDraw.resizeForRender();

        int w = Doomdef.RENDER_WIDTH;
        int h = Doomdef.RENDER_HEIGHT;

        // One surface per pane: the software composite needs one in
        // software + side-by-side modes, the fx3 cutout composite in
        // fx3 + side-by-side modes.
        final BlitSurface swSurface =
                (mode != Launchpad.RendererMode.FX3D) ? new BlitSurface(w, h) : null;
        final BlitSurface fxSurface = fx3 ? new BlitSurface(w, h) : null;

        // Engine state needs a fresh start on re-launch: clear
        // the quitting guard and drop the previous WAD list so
        // dDoomMain doesn't double-load.
        if (hasBootedGame) {
            ISystem.iResetForRelaunch();
            DMain.dResetWadFiles();
        }

        // Experimental scene-graph 3D renderer (3D_OVERHAUL.md): a
        // SubScene layered *behind* its pane's canvas renders the
        // world view on the GPU; the canvas keeps all 2D (status
        // bar, border, HUD, menu, automap, wipe) and gets
        // transparent pixels punched out of the active view window
        // so the SubScene shows through. Unmanaged: positioned per
        // frame in finishUpdateHook to track the view window.
        javafx.scene.SubScene fx3Sub = null;
        javafx.scene.shape.Rectangle fx3TintR = null;
        doom.render3d.Fx3Renderer.reset();
        if (fx3) {
            fx3Sub = doom.render3d.Fx3Renderer.subScene();
            fx3Sub.setManaged(false);
            fx3Sub.setVisible(false);
            // Palette-flash approximation: damage/bonus/radsuit
            // tints live in PLAYPAL sub-palettes, which only the
            // 2D canvas obeys. Additively blending the tinted
            // palette's "black" (entry 0 ≈ the pure tint
            // component) over the SubScene reproduces the flash
            // on the 3D view. Sits between SubScene and canvas.
            fx3TintR = new javafx.scene.shape.Rectangle();
            fx3TintR.setManaged(false);
            fx3TintR.setVisible(false);
            fx3TintR.setBlendMode(javafx.scene.effect.BlendMode.ADD);
            fx3TintR.setMouseTransparent(true);
            doom.render3d.Fx3Renderer.active = true;
            doom.render3d.Fx3Renderer.sideBySide = sbs;
        }
        final javafx.scene.SubScene fx3SubScene = fx3Sub;
        final javafx.scene.shape.Rectangle fx3Tint = fx3TintR;

        // Pane assembly. Each pane is a black StackPane holding its
        // canvas (and, for the 3D pane, the SubScene + tint rect
        // beneath it). Side-by-side puts both panes — always the
        // same size — into an HBox (software left) or VBox
        // (software top).
        StackPane swPane = null;
        if (swSurface != null) {
            swPane = new StackPane(swSurface.canvas);
            swPane.setStyle("-fx-background-color: black;");
        }
        StackPane fxPane = null;
        if (fxSurface != null) {
            fxPane = new StackPane();
            fxPane.getChildren().addAll(fx3Sub, fx3TintR, fxSurface.canvas);
            fxPane.setStyle("-fx-background-color: black;");
        }
        javafx.scene.Parent root;
        if (sbs) {
            javafx.scene.layout.Pane pair;
            if (sbsHorizontal) {
                javafx.scene.layout.HBox hb =
                        new javafx.scene.layout.HBox(swPane, fxPane);
                hb.setAlignment(javafx.geometry.Pos.CENTER);
                pair = hb;
            } else {
                javafx.scene.layout.VBox vb =
                        new javafx.scene.layout.VBox(swPane, fxPane);
                vb.setAlignment(javafx.geometry.Pos.CENTER);
                pair = vb;
            }
            pair.setStyle("-fx-background-color: black;");
            root = pair;
        } else {
            root = (fxPane != null) ? fxPane : swPane;
        }

        // Canvas / scene sizing uses the *UI* logical grid (320x200) ×
        // user-picked display scale per pane. The renderer's
        // RENDER_MUL only affects sample density inside that window —
        // JavaFX scales the hi-res argb image to fit.
        int uiH = Doomdef.SCREENHEIGHT;
        Canvas[] canvases = (sbs)
                ? new Canvas[] { swSurface.canvas, fxSurface.canvas }
                : new Canvas[] { (fxSurface != null ? fxSurface : swSurface).canvas };
        Scene scene;
        if (display != null && display.fullscreen) {
            scene = new Scene(root);
            if (sbs) {
                installAspectFitterDual(swSurface.canvas, fxSurface.canvas,
                        scene, sbsHorizontal);
            } else {
                installAspectFitter(canvases[0], scene);
            }
        } else {
            int scale = (display != null) ? display.scale : DEFAULT_SCALE;
            if (scale <= 0) scale = DEFAULT_SCALE;
            for (Canvas c : canvases) {
                c.setWidth(UI_W * scale);
                c.setHeight(uiH * scale);
            }
            int sceneW = UI_W * scale * ((sbs && sbsHorizontal) ? 2 : 1);
            int sceneH = uiH * scale * ((sbs && !sbsHorizontal) ? 2 : 1);
            scene = new Scene(root, sceneW, sceneH);
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

            // Read render dims dynamically (RENDER_MUL is a per-
            // session value the Launchpad sets).
            int renderW = Doomdef.RENDER_WIDTH;
            int renderH = Doomdef.RENDER_HEIGHT;
            int rm      = Doomdef.RENDER_MUL;

            // Software pane: UI over viewBuffer (the classic
            // composite).
            if (swSurface != null) {
                compositeSoftware(swSurface.argb, ui, view, viewActive,
                        vx, vy, vw, vh, renderW, renderH, rm);
                swSurface.blit(false);
            }

            // 3D pane: UI opaque, weapon psprite from the two-pass
            // mask, transparent holes over the SubScene.
            if (fxSurface != null) {
                compositeFx3(fxSurface.argb, ui, viewActive,
                        vx, vy, vw, vh, renderW, renderH, rm);
            }

            // Track the view window with the SubScene: same rect,
            // scaled from render pixels to canvas pixels, offset by
            // the canvas position inside its pane (fullscreen
            // letterboxing centres it).
            if (fx3SubScene != null) {
                if (viewActive) {
                    javafx.geometry.Bounds cb =
                            fxSurface.canvas.getBoundsInParent();
                    double sx = cb.getWidth() / renderW;
                    double sy = cb.getHeight() / renderH;
                    double lx = cb.getMinX() + vx * sx;
                    double ly = cb.getMinY() + vy * sy;
                    double lw = vw * sx;
                    double lh = vh * sy;
                    fx3SubScene.setLayoutX(lx);
                    fx3SubScene.setLayoutY(ly);
                    fx3SubScene.setWidth(lw);
                    fx3SubScene.setHeight(lh);
                    fx3SubScene.setVisible(true);

                    // Palette flash: additive tint from the current
                    // sub-palette's entry 0 (pure tint component).
                    javafx.scene.paint.Color tint = fx3TintColor;
                    boolean tintOn = tint != null
                            && !tint.equals(javafx.scene.paint.Color.BLACK);
                    fx3Tint.setLayoutX(lx);
                    fx3Tint.setLayoutY(ly);
                    fx3Tint.setWidth(lw);
                    fx3Tint.setHeight(lh);
                    fx3Tint.setFill(tintOn ? tint : javafx.scene.paint.Color.BLACK);
                    fx3Tint.setVisible(tintOn);

                    // Invulnerability / light-amp approximations via
                    // a color-adjust pass over the SubScene.
                    doom.play.Player p =
                            doom.system.GGame.players[doom.system.GGame.consoleplayer];
                    int fcm = (p != null) ? p.fixedcolormap : 0;
                    if (fcm != fx3LastFixedColormap) {
                        fx3LastFixedColormap = fcm;
                        if (fcm == 32) {
                            // INVERSECOLORMAP: desaturate + invert
                            // (white DIFFERENCE happens via effect
                            // chain: ColorAdjust can't invert, so
                            // approximate with max brightness swing).
                            javafx.scene.effect.ColorAdjust ca =
                                    new javafx.scene.effect.ColorAdjust();
                            ca.setSaturation(-1);
                            ca.setBrightness(0.7);
                            ca.setContrast(-0.6);
                            fx3SubScene.setEffect(ca);
                        } else if (fcm > 0) {
                            // Light amplification visor: fullbright.
                            javafx.scene.effect.ColorAdjust ca =
                                    new javafx.scene.effect.ColorAdjust();
                            ca.setBrightness(0.55);
                            fx3SubScene.setEffect(ca);
                        } else {
                            fx3SubScene.setEffect(null);
                        }
                    }
                } else {
                    fx3SubScene.setVisible(false);
                    fx3Tint.setVisible(false);
                }
            }

            if (fxSurface != null) {
                // Transparent holes don't overwrite last frame's
                // pixels — blit clears first.
                fxSurface.blit(true);
            }
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
    // fresh Launchpad.
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
        // Drop the 3D scene so the next boot starts clean.
        doom.render3d.Fx3Renderer.reset();
        showLauncher(stage);
    }

    // Software composite: for pixels inside the active view window
    // the view buffer wins unless a UI overlay painted there this
    // frame (post-startFrameHook clear); outside, the UI buffer is
    // nearest-neighbour upscaled by RENDER_MUL.
    private void compositeSoftware(int[] out, byte[] ui, byte[] view,
                                   boolean viewActive, int vx, int vy,
                                   int vw, int vh,
                                   int renderW, int renderH, int rm) {
        int vxEnd = vx + vw;
        int vyEnd = vy + vh;
        int i = 0;
        for (int y = 0; y < renderH; y++) {
            int uiRowOff = (y / rm) * UI_W;
            boolean rowInView = viewActive && y >= vy && y < vyEnd;
            int viewRowOff = y * renderW;
            for (int x = 0; x < renderW; x++, i++) {
                int pal;
                if (rowInView && x >= vx && x < vxEnd) {
                    int uiPal = ui[uiRowOff + (x / rm)] & 0xff;
                    pal = (uiPal != 0) ? uiPal : (view[viewRowOff + x] & 0xff);
                } else {
                    pal = ui[uiRowOff + (x / rm)] & 0xff;
                }
                out[i] = palette[pal];
            }
        }
    }

    // Fx3 composite: UI overlays stay opaque, the weapon psprite
    // comes from Fx3Renderer's two-pass mask (drawn pixels agree
    // across the passes; background is 0 in pass 1 and 1 in pass 2),
    // everything else in the view window becomes a transparent hole
    // over the SubScene.
    private void compositeFx3(int[] out, byte[] ui,
                              boolean viewActive, int vx, int vy,
                              int vw, int vh,
                              int renderW, int renderH, int rm) {
        byte[] pass1 = doom.render3d.Fx3Renderer.weaponPass1;
        byte[] pass2 = doom.render3d.Fx3Renderer.weaponPass2;
        int vxEnd = vx + vw;
        int vyEnd = vy + vh;
        int i = 0;
        for (int y = 0; y < renderH; y++) {
            int uiRowOff = (y / rm) * UI_W;
            boolean rowInView = viewActive && y >= vy && y < vyEnd;
            int viewRowOff = y * renderW;
            for (int x = 0; x < renderW; x++, i++) {
                if (rowInView && x >= vx && x < vxEnd) {
                    int uiPal = ui[uiRowOff + (x / rm)] & 0xff;
                    if (uiPal != 0) {
                        out[i] = palette[uiPal];
                    } else {
                        int vi = viewRowOff + x;
                        boolean background = pass1 == null || pass2 == null
                                || (pass1[vi] == 0 && pass2[vi] == 1);
                        out[i] = background ? 0 : palette[pass2[vi] & 0xff];
                    }
                } else {
                    out[i] = palette[ui[uiRowOff + (x / rm)] & 0xff];
                }
            }
        }
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

    // Fullscreen fitter for the side-by-side pair: each pane gets
    // half the scene (width for horizontal, height for stacked) and
    // fits 320:200 inside its half; both canvases always end up the
    // same size. Black bars appear via the container's background.
    private static void installAspectFitterDual(Canvas a, Canvas b,
                                                Scene scene,
                                                boolean horizontal) {
        InvalidationListener l = obs -> {
            double sw = scene.getWidth();
            double sh = scene.getHeight();
            if (sw <= 0 || sh <= 0) return;
            double boxW = horizontal ? sw / 2 : sw;
            double boxH = horizontal ? sh : sh / 2;
            double ba = boxW / boxH;
            double cw, ch;
            if (ba > DOOM_ASPECT) {
                ch = boxH;
                cw = boxH * DOOM_ASPECT;
            } else {
                cw = boxW;
                ch = boxW / DOOM_ASPECT;
            }
            a.setWidth(cw);
            a.setHeight(ch);
            b.setWidth(cw);
            b.setHeight(ch);
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
        // Entry 0 is black in the base palette; damage/bonus/radsuit
        // sub-palettes lift it to the pure tint component, which the
        // 3D view reproduces as an additive overlay.
        fx3TintColor = javafx.scene.paint.Color.rgb(
                bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff);
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
