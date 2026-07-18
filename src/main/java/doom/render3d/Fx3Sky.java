// Sky for the experimental JavaFX scene-graph renderer.
//
// Doom's sky isn't geometry: r_sky maps the view angle onto columns
// of the 256-wide sky texture, 4 repeats per full turn
// (ANGLETOSKYSHIFT: BAM angle >> 22 → 1024 columns/circle), drawn
// fullbright with texturemid = 100 — i.e. texture row 100 sits on
// the horizon and rows are 1:1 with a 320×200 view's pixels
// (vertical texel angle = atan(rows/160)).
//
// Here that becomes a camera-position-locked cylinder: 8 segments of
// 45° (= 128 sky columns each), radius well inside the far clip,
// sized so row 0 sits atan(100/160) above the horizon and row 128
// atan(28/160) below. JavaFX clamps UVs, so the sky image is baked
// 2× tiled (512 columns) and each 128-column segment starts at
// (k×128 mod 512) — always inside the image. Guard bands above and
// below stretch the edge rows so no fill color peeks out at extreme
// geometry. Quads are emitted independently (no shared vertices) so
// each can carry its own UV rectangle.
//
// Sky-flat ceilings and sky↔sky upper walls are simply not emitted
// by Fx3LevelMesh, so this cylinder shows through the holes — the
// standard hardware-renderer trick.
package doom.render3d;

import doom.render.RSky;

import javafx.scene.Group;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Translate;

public final class Fx3Sky {

    private static final int SEGMENTS = 8;      // 45° each
    private static final double RADIUS = 16384;

    // Vertical extents as multiples of RADIUS (see header) plus
    // stretched guard bands.
    private static final double TOP    = 100.0 / 160.0;
    private static final double BOTTOM = -28.0 / 160.0;
    private static final double GUARD_TOP    = 1.6;
    private static final double GUARD_BOTTOM = -1.0;

    public final Group node;
    private final Translate position = new Translate();

    public Fx3Sky() {
        node = new Group(buildCylinder());
        node.getTransforms().add(position);
    }

    // Follow the camera's position (not orientation): the sky pans
    // with view rotation but never parallax-shifts.
    public void update(double fxX, double fxY, double fxZ) {
        position.setX(fxX);
        position.setY(fxY);
        position.setZ(fxZ);
    }

    private static MeshView buildCylinder() {
        double[] ringY = { GUARD_TOP, TOP, BOTTOM, GUARD_BOTTOM };
        float[] ringV  = { 0f, 0f, 1f, 1f };

        int quads = SEGMENTS * 3;
        float[] pts = new float[quads * 4 * 3];
        float[] uvs = new float[quads * 4 * 2];
        int[] faces = new int[quads * 2 * 6];
        int p = 0, t = 0, f = 0, vi = 0;

        for (int k = 0; k < SEGMENTS; k++) {
            // BAM angles grow CCW (east → north) and so does the sky
            // column index (angle >> ANGLETOSKYSHIFT).
            double a0 = 2 * Math.PI * k / SEGMENTS;
            double a1 = 2 * Math.PI * (k + 1) / SEGMENTS;
            double x0 = RADIUS * Math.cos(a0), z0 = RADIUS * Math.sin(a0);
            double x1 = RADIUS * Math.cos(a1), z1 = RADIUS * Math.sin(a1);
            float u0 = ((k % 4) * 128) / 512f;
            float u1 = u0 + 128 / 512f;

            for (int r = 0; r < 3; r++) {
                double yT = ringY[r], yB = ringY[r + 1];
                float vT = ringV[r], vB = ringV[r + 1];
                int a = vi;
                pts[p++] = (float) x0; pts[p++] = (float) -(yT * RADIUS); pts[p++] = (float) z0;
                uvs[t++] = u0; uvs[t++] = vT;
                pts[p++] = (float) x1; pts[p++] = (float) -(yT * RADIUS); pts[p++] = (float) z1;
                uvs[t++] = u1; uvs[t++] = vT;
                pts[p++] = (float) x1; pts[p++] = (float) -(yB * RADIUS); pts[p++] = (float) z1;
                uvs[t++] = u1; uvs[t++] = vB;
                pts[p++] = (float) x0; pts[p++] = (float) -(yB * RADIUS); pts[p++] = (float) z0;
                uvs[t++] = u0; uvs[t++] = vB;
                vi += 4;

                faces[f++] = a;     faces[f++] = a;
                faces[f++] = a + 1; faces[f++] = a + 1;
                faces[f++] = a + 2; faces[f++] = a + 2;
                faces[f++] = a;     faces[f++] = a;
                faces[f++] = a + 2; faces[f++] = a + 2;
                faces[f++] = a + 3; faces[f++] = a + 3;
            }
        }

        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(pts);
        mesh.getTexCoords().setAll(uvs);
        mesh.getFaces().setAll(faces);

        MeshView mv = new MeshView(mesh);
        mv.setMaterial(Fx3Textures.skyMaterial(RSky.skytexture));
        // Viewed from inside; skip the winding question entirely.
        mv.setCullFace(CullFace.NONE);
        return mv;
    }
}
