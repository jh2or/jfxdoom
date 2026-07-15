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
// camera — the same inputs rSetupFrame consumes. Level changes are
// detected by the PSetup.sectors array reference (each pSetupLevel
// allocates fresh arrays); sector height changes by a hash (Phase 1
// stopgap: full mesh rebuild while a door/lift moves).
package doom.render3d;

import doom.play.PSetup;
import doom.play.Player;
import doom.render.Sector;

import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

public final class Fx3Renderer {

    // Set by the JavaFX shell at boot when the user picked the 3D
    // renderer and ConditionalFeature.SCENE3D is supported. DMain
    // branches on it.
    public static boolean active;

    private static SubScene subScene;
    private static Group levelHolder;
    private static final Translate camTranslate = new Translate();
    private static final Rotate camYaw = new Rotate(0, Rotate.Y_AXIS);

    private static Sector[] builtFor;
    private static long builtHeightsHash;

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
            // sub-scene aspect (320:200 display ≈ 4:3 → ~73.7°).
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
    }

    // The R_RenderPlayerView counterpart: make sure the level mesh is
    // current, then point the camera at the player's eye. The actual
    // rasterisation happens when JavaFX renders the pulse's frame.
    public static void renderPlayerView(Player player) {
        if (subScene == null || PSetup.sectors == null) return;

        long hash = Fx3LevelMesh.sectorHeightsHash();
        if (builtFor != PSetup.sectors || hash != builtHeightsHash) {
            levelHolder.getChildren().setAll(Fx3LevelMesh.build());
            builtFor = PSetup.sectors;
            builtHeightsHash = hash;
        }

        // Doom map (x, y, z-up) → JavaFX (x, -z, y).
        camTranslate.setX(player.mo.x / 65536.0);
        camTranslate.setY(-(player.viewz / 65536.0));
        camTranslate.setZ(player.mo.y / 65536.0);

        // BAM angle → degrees. JavaFX Rotate about Y maps the
        // camera's +Z forward to (sin θ, 0, cos θ); Doom's angle a
        // looks along (cos a, sin a) in map x/y = (cos a, 0, sin a)
        // in JavaFX X/Z ⇒ θ = 90° − a.
        double angleDeg = (player.mo.angle & 0xFFFFFFFFL)
                * (360.0 / 4294967296.0);
        camYaw.setAngle(90.0 - angleDeg);
    }
}
