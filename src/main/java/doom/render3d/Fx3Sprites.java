// Mobj billboards for the experimental JavaFX scene-graph renderer.
//
// Mirrors R_ProjectSprite's frame selection: sprdef/sprframe from
// mobj.sprite and (frame & FF_FRAMEMASK); for rotating frames the
// patch is picked from the view angle relative to the mobj's facing
// ((ang − thing.angle + ANG45/2·9) >>> 29), with the flip bit baked
// into the cached image so quads keep constant UVs.
//
// Each live mobj owns one MeshView quad, kept in an identity map and
// mark-and-swept per frame (mobjs enter via Sector.thinglist, which
// is exactly the set P_SetThingPosition links — MF_NOSECTOR things
// never appear, same as the software renderer). Quads are vertical
// (Doom sprites never pitch), yawed to the camera's angle so they
// face the view plane, positioned at the mobj with vanilla offsets:
// left edge at −spriteoffset, top at z + spritetopoffset.
//
// Occlusion: JavaFX renders in scene-graph order with the depth
// buffer on, so sprites drawn after the opaque world are correctly
// hidden by walls. Transparent texels still write depth (no alpha
// test in JavaFX), which is why the renderer distance-sorts sprites
// and masked quads back-to-front each frame — a nearer quad drawn
// first would blank out a farther one behind it.
//
// MF_SHADOW (spectres) approximates the fuzz effect with node
// opacity — a documented fidelity deviation.
package doom.render3d;

import doom.play.Mobj;
import doom.play.PSetup;
import doom.play.Player;
import doom.render.RData;
import doom.render.RThings;
import doom.render.Spritedef;
import doom.render.Spriteframe;
import doom.render.Tables;

import javafx.scene.Node;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Fx3Sprites {

    private final Map<Mobj, SpriteNode> pool = new IdentityHashMap<>();

    // One entry per visible sprite this frame, with its map-space
    // position for the renderer's back-to-front sort.
    public record Entry(Node node, double mx, double my) {}

    // Sync the pool to the live mobj set and return this frame's
    // sprite entries. camYawDeg is the camera's Rotate angle so the
    // quads face the view plane.
    public List<Entry> update(Player viewPlayer,
                              double camX, double camY, double camYawDeg) {
        List<Entry> out = new ArrayList<>();
        int stamp = ++frameStamp;

        for (int i = 0; i < PSetup.numsectors; i++) {
            for (Mobj m = PSetup.sectors[i].thinglist; m != null; m = m.snext) {
                if (viewPlayer != null && m == viewPlayer.mo) continue;
                if (m.sprite < 0 || m.sprite >= RThings.numsprites) continue;
                Spritedef sprdef = RThings.sprites[m.sprite];
                int frame = m.frame & Mobj.FF_FRAMEMASK;
                if (sprdef == null || sprdef.spriteframes == null
                        || frame >= sprdef.numframes) continue;
                Spriteframe sprframe = sprdef.spriteframes[frame];
                if (sprframe == null) continue;

                double mx = m.x / 65536.0;
                double my = m.y / 65536.0;

                int lump;
                boolean flip;
                if (sprframe.rotate) {
                    // R_PointToAngle from the camera, in doubles.
                    double deg = Math.toDegrees(
                            Math.atan2(my - camY, mx - camX));
                    int ang = (int) ((deg / 360.0) * 4294967296.0);
                    int rot = (ang - m.angle + (Tables.ANG45 / 2) * 9) >>> 29;
                    lump = sprframe.lump[rot];
                    flip = sprframe.flip[rot] != 0;
                } else {
                    lump = sprframe.lump[0];
                    flip = sprframe.flip[0] != 0;
                }

                int lightnum;
                if ((m.frame & Mobj.FF_FULLBRIGHT) != 0) {
                    lightnum = 15;
                } else {
                    lightnum = (m.subsector != null && m.subsector.sector != null)
                            ? m.subsector.sector.lightlevel >> 4 : 8;
                }

                SpriteNode sn = pool.computeIfAbsent(m, k -> new SpriteNode());
                sn.stamp = stamp;
                sn.shape(lump, flip, lightnum);
                // Sprite-clip adjustment (standard hardware-port
                // tweak): lots of sprite art extends below the feet
                // (height > topoffset). The software renderer paints
                // sprites over the already-drawn floor so it never
                // shows, but the depth buffer would sink them into
                // the ground — raise grounded things so the quad's
                // bottom rests on the floor. Airborne things
                // (missiles, ceiling hangers, falling) keep their
                // true position.
                double raise = (m.z <= m.floorz)
                        ? Math.max(0, sn.sinkBelowFeet) : 0;
                sn.place(mx, -(m.z / 65536.0) - raise, my, camYawDeg);
                sn.view.setOpacity(
                        (m.flags & Mobj.MF_SHADOW) != 0 ? 0.35 : 1.0);
                out.add(new Entry(sn.view, mx, my));
            }
        }

        // Sweep mobjs that despawned.
        Iterator<SpriteNode> it = pool.values().iterator();
        while (it.hasNext()) {
            if (it.next().stamp != stamp) it.remove();
        }
        return out;
    }

    public void clear() {
        pool.clear();
    }

    private int frameStamp;

    private static final class SpriteNode {
        final MeshView view;
        final TriangleMesh mesh;
        final Translate pos = new Translate();
        final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
        int stamp;
        int shownLump = -1;
        boolean shownFlip;
        int shownLight = -1;
        // How far (map units) the art hangs below the feet; > 0
        // means a grounded thing needs raising (see update()).
        double sinkBelowFeet;

        SpriteNode() {
            mesh = new TriangleMesh();
            mesh.getTexCoords().setAll(0, 0, 1, 0, 1, 1, 0, 1);
            mesh.getFaces().setAll(
                    0, 0, 1, 1, 2, 2,
                    0, 0, 2, 2, 3, 3);
            view = new MeshView(mesh);
            view.setCullFace(CullFace.NONE);
            view.getTransforms().addAll(pos, yaw);
        }

        // Update geometry/material when the animation frame moved.
        void shape(int lump, boolean flip, int lightnum) {
            if (lump == shownLump && flip == shownFlip
                    && lightnum == shownLight) {
                return;
            }
            shownLump = lump;
            shownFlip = flip;
            shownLight = lightnum;

            // Vanilla sprite extents: left edge at −spriteoffset,
            // top at spritetopoffset above the mobj's z.
            float xl = (float) -(RData.spriteoffset[lump] / 65536.0);
            float xr = xl + (float) (RData.spritewidth[lump] / 65536.0);
            float yTop = (float) -(RData.spritetopoffset[lump] / 65536.0);
            float yBot = yTop + Fx3Textures.spriteHeight(lump);
            sinkBelowFeet = yBot;
            mesh.getPoints().setAll(
                    xl, yTop, 0,
                    xr, yTop, 0,
                    xr, yBot, 0,
                    xl, yBot, 0);
            view.setMaterial(Fx3Textures.spriteMaterial(lump, lightnum, flip));
        }

        void place(double fxX, double fxY, double fxZ, double camYawDeg) {
            pos.setX(fxX);
            pos.setY(fxY);
            pos.setZ(fxZ);
            yaw.setAngle(camYawDeg);
        }
    }
}
