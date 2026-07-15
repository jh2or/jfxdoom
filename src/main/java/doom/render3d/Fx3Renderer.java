// Facade of the experimental JavaFX scene-graph 3D renderer.
//
// This is NOT a translation of any C file — it is an opt-in
// alternative to the software renderer (R_RenderPlayerView), selected
// per session in the Launchpad or with the -fx3d CLI flag. The
// software renderer remains the default and the faithful path; see
// 3D_OVERHAUL.md for the plan and the accepted fidelity deviations.
//
// DMain.dDisplay calls renderPlayerView(player) in place of
// RMain.rRenderPlayerView when `active` is set. Everything here runs
// on the JavaFX application thread (the engine tick is driven by an
// AnimationTimer), so scene-graph mutation is safe.
//
// The renderer only READS engine state: PSetup level arrays for
// geometry and player.mo.x/y, player.viewz, player.mo.angle for the
// camera — the same inputs rSetupFrame consumes.
//
// Rebuild strategy (Phase 3): the level lives in two Groups.
//   static  — sectors whose state (heights, light, flats) still
//             matches the baseline captured at level load; built
//             once, rebuilt only when a *new* sector starts moving
//             or a switch texture flips (rare).
//   dynamic — sectors that have ever deviated (doors, lifts,
//             crushers, blinking lights) plus scroller-line segs;
//             small, rebuilt whenever its state hash changes
//             (per tic while something moves).
// Texture/flat *animation* needs no rebuild at all — materials
// re-resolve the translation tables via refreshTranslations().
package doom.render3d;

import doom.play.PSetup;
import doom.play.Player;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;

import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.List;

public final class Fx3Renderer {

    // Set by the JavaFX shell at boot when the user picked the 3D
    // renderer and ConditionalFeature.SCENE3D is supported. DMain
    // branches on it.
    public static boolean active;

    private static SubScene subScene;
    private static Group levelHolder;
    private static final Translate camTranslate = new Translate();
    private static final Rotate camYaw = new Rotate(0, Rotate.Y_AXIS);

    // Per-level rebuild bookkeeping.
    private static Sector[] builtFor;
    private static boolean[] dynamicSectors;
    private static int[] baseFloor, baseCeil, baseLight, basePicF, basePicC;
    private static List<Side> scrollerSides;
    private static long sideTexHash;
    private static long dynamicHash;
    private static Group staticGroup;
    private static Group dynamicGroup;
    private static Fx3Sky sky;

    private Fx3Renderer() {
    }

    // Lazily create the SubScene the shell layers beneath the 2D
    // canvas. Size is managed per frame by the shell (it tracks the
    // active view window).
    public static SubScene subScene() {
        if (subScene == null) {
            levelHolder = new Group();
            AmbientLight ambient = new AmbientLight(Color.WHITE);
            Group root = new Group(ambient, levelHolder);

            PerspectiveCamera camera = new PerspectiveCamera(true);
            // Doom's horizontal FOV is 90°; the vertical follows the
            // sub-scene aspect (320:200 display ≈ ~64° square-pixel).
            camera.setVerticalFieldOfView(false);
            camera.setFieldOfView(90);
            camera.setNearClip(1);
            camera.setFarClip(65536);
            camera.getTransforms().addAll(camTranslate, camYaw);

            subScene = new SubScene(root, 1, 1, true, SceneAntialiasing.DISABLED);
            subScene.setCamera(camera);
            subScene.setFill(Color.BLACK);
            subScene.setMouseTransparent(true);
        }
        return subScene;
    }

    // Drop all scene state — called when the session returns to the
    // Launchpad so the next boot starts clean.
    public static void reset() {
        active = false;
        subScene = null;
        levelHolder = null;
        builtFor = null;
        staticGroup = null;
        dynamicGroup = null;
        sky = null;
        Fx3Textures.reset();
    }

    // The R_RenderPlayerView counterpart: make sure the level meshes
    // are current, then point the camera at the player's eye. The
    // actual rasterisation happens when JavaFX renders the pulse's
    // frame.
    public static void renderPlayerView(Player player) {
        if (subScene == null || PSetup.sectors == null) return;

        boolean full = false;
        if (builtFor != PSetup.sectors) {
            captureLevelBaseline();
            full = true;
        }

        // Switches write side texture indices directly
        // (P_ChangeSwitchTexture) — detect via a hash over all sides.
        long sth = computeSideTexHash();
        if (sth != sideTexHash) {
            sideTexHash = sth;
            full = true;
        }

        // Promote sectors that deviate from their load-time state.
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (dynamicSectors[i]) continue;
            Sector s = PSetup.sectors[i];
            if (s.floorheight != baseFloor[i]
                    || s.ceilingheight != baseCeil[i]
                    || s.lightlevel != baseLight[i]
                    || s.floorpic != basePicF[i]
                    || s.ceilingpic != basePicC[i]) {
                dynamicSectors[i] = true;
                full = true;
            }
        }

        if (full) {
            staticGroup = Fx3LevelMesh.build(dynamicSectors, false);
            dynamicGroup = Fx3LevelMesh.build(dynamicSectors, true);
            levelHolder.getChildren().setAll(sky.node, staticGroup, dynamicGroup);
            dynamicHash = computeDynamicHash();
        } else {
            long dh = computeDynamicHash();
            if (dh != dynamicHash) {
                dynamicHash = dh;
                dynamicGroup = Fx3LevelMesh.build(dynamicSectors, true);
                levelHolder.getChildren().set(2, dynamicGroup);
            }
        }

        // Animated flats/textures: re-resolve the translation tables
        // and swap diffuse maps that moved.
        Fx3Textures.refreshTranslations();

        // Doom map (x, y, z-up) → JavaFX (x, -z, y).
        double fxX = player.mo.x / 65536.0;
        double fxY = -(player.viewz / 65536.0);
        double fxZ = player.mo.y / 65536.0;
        camTranslate.setX(fxX);
        camTranslate.setY(fxY);
        camTranslate.setZ(fxZ);
        sky.update(fxX, fxY, fxZ);

        // BAM angle → degrees. JavaFX Rotate about Y maps the
        // camera's +Z forward to (sin θ, 0, cos θ); Doom's angle a
        // looks along (cos a, sin a) in map x/y = (cos a, 0, sin a)
        // in JavaFX X/Z ⇒ θ = 90° − a.
        double angleDeg = (player.mo.angle & 0xFFFFFFFFL)
                * (360.0 / 4294967296.0);
        camYaw.setAngle(90.0 - angleDeg);
    }

    private static void captureLevelBaseline() {
        int n = PSetup.numsectors;
        dynamicSectors = new boolean[n];
        baseFloor = new int[n];
        baseCeil = new int[n];
        baseLight = new int[n];
        basePicF = new int[n];
        basePicC = new int[n];
        for (int i = 0; i < n; i++) {
            Sector s = PSetup.sectors[i];
            baseFloor[i] = s.floorheight;
            baseCeil[i] = s.ceilingheight;
            baseLight[i] = s.lightlevel;
            basePicF[i] = s.floorpic;
            basePicC[i] = s.ceilingpic;
        }
        scrollerSides = new ArrayList<>();
        for (int i = 0; i < PSetup.numlines; i++) {
            Line line = PSetup.lines[i];
            if (Fx3LevelMesh.isScroller(line) && line.sidenum[0] >= 0) {
                scrollerSides.add(PSetup.sides[line.sidenum[0]]);
            }
        }
        sky = new Fx3Sky();
        sideTexHash = computeSideTexHash();
        builtFor = PSetup.sectors;
    }

    private static long computeSideTexHash() {
        long h = 1469598103934665603L;
        for (int i = 0; i < PSetup.numsides; i++) {
            Side s = PSetup.sides[i];
            h = (h ^ s.toptexture) * 1099511628211L;
            h = (h ^ s.midtexture) * 1099511628211L;
            h = (h ^ s.bottomtexture) * 1099511628211L;
        }
        return h;
    }

    private static long computeDynamicHash() {
        long h = 1469598103934665603L;
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (!dynamicSectors[i]) continue;
            Sector s = PSetup.sectors[i];
            h = (h ^ s.floorheight) * 1099511628211L;
            h = (h ^ s.ceilingheight) * 1099511628211L;
            h = (h ^ s.lightlevel) * 1099511628211L;
            h = (h ^ s.floorpic) * 1099511628211L;
            h = (h ^ s.ceilingpic) * 1099511628211L;
        }
        for (Side s : scrollerSides) {
            h = (h ^ s.textureoffset) * 1099511628211L;
        }
        return h;
    }
}
