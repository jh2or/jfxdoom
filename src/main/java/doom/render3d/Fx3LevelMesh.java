// Level geometry → JavaFX meshes for the experimental scene-graph
// renderer. Phase 1: untextured, flat-colored by sector light level.
//
// Coordinate mapping (see 3D_OVERHAUL.md): Doom map (x east, y north,
// z up) → JavaFX (X, Y, Z) = (x, -z, y). JavaFX's Y axis points down,
// so heights are negated.
//
// Geometry sources:
//   - Walls come from segs (not linedefs) so each facing of a
//     two-sided line gets its own quad with its own sidedef/sector
//     attributes — same subdivision the software renderer draws.
//     One-sided segs emit a full-height mid wall; two-sided segs emit
//     an upper wall (back ceiling < front ceiling) and/or a lower
//     wall (back floor > front floor). Masked midtextures are
//     Phase 4.
//   - Floors/ceilings come from Fx3SubsectorPolys' closed convex
//     polygons, fan-triangulated.
//
// Sky: ceilings whose ceilingpic is RSky.skyflatnum are skipped, as
// are upper walls between two sky ceilings (mirrors the r_bsp.c
// check) — the holes show the SubScene fill until Phase 3's sky
// cylinder lands.
//
// Winding: front faces are wound so wall quads face their
// frontsector, floors face up, ceilings face down. Wall MeshViews
// cull back faces (the two facings of a shared lower/upper wall are
// coplanar — culling is what prevents z-fighting between them); flats
// keep CullFace.NONE (never coplanar-duplicated, and the underside
// overdraw is harmless). FLIP_WINDING corrects the whole convention
// in one place if the first visual check shows inside-out geometry.
package doom.render3d;

import doom.play.PSetup;
import doom.render.RSky;
import doom.render.Sector;
import doom.render.Seg;
import doom.render.Subsector;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import java.util.HashMap;
import java.util.Map;

public final class Fx3LevelMesh {

    // Flip 2nd/3rd vertex of every emitted triangle if the JavaFX
    // front-face convention turns out inverted on first launch.
    private static final boolean FLIP_WINDING = false;

    // Surface classes for the material key — Phase 1 tints them
    // differently so untextured surfaces stay readable.
    private static final int CLS_WALL    = 0;
    private static final int CLS_FLOOR   = 1;
    private static final int CLS_CEILING = 2;

    private Fx3LevelMesh() {
    }

    // Cached per-level: the subsector polygons only depend on map
    // x/y geometry, which never changes at runtime — moving sectors
    // only change heights. Keyed by the sectors array reference
    // (a new level allocates new arrays).
    private static Sector[] polysFor;
    private static double[][] polys;

    // Build the whole level as a Group of MeshViews, one per
    // (surface class, light bucket) batch, using the *current*
    // sector heights. Phase 3 replaces rebuild-on-height-change with
    // in-place point updates.
    public static Group build() {
        if (polysFor != PSetup.sectors) {
            polys = Fx3SubsectorPolys.buildAll();
            polysFor = PSetup.sectors;
        }

        Map<Integer, Batch> batches = new HashMap<>();

        // Floors and ceilings.
        for (int i = 0; i < PSetup.numsubsectors; i++) {
            double[] poly = polys[i];
            if (poly.length < 6) continue;
            Subsector ss = PSetup.subsectors[i];
            Sector sec = ss.sector;
            double floor = Fx3SubsectorPolys.fx(sec.floorheight);
            double ceil  = Fx3SubsectorPolys.fx(sec.ceilingheight);
            if (sec.floorpic != RSky.skyflatnum) {
                batch(batches, CLS_FLOOR, sec.lightlevel)
                        .addFlat(poly, floor, true);
            }
            if (sec.ceilingpic != RSky.skyflatnum) {
                batch(batches, CLS_CEILING, sec.lightlevel)
                        .addFlat(poly, ceil, false);
            }
        }

        // Walls.
        for (int i = 0; i < PSetup.numsegs; i++) {
            Seg seg = PSetup.segs[i];
            Sector front = seg.frontsector;
            Sector back  = seg.backsector;
            double x1 = Fx3SubsectorPolys.fx(seg.v1.x);
            double y1 = Fx3SubsectorPolys.fx(seg.v1.y);
            double x2 = Fx3SubsectorPolys.fx(seg.v2.x);
            double y2 = Fx3SubsectorPolys.fx(seg.v2.y);
            double ffloor = Fx3SubsectorPolys.fx(front.floorheight);
            double fceil  = Fx3SubsectorPolys.fx(front.ceilingheight);

            // Vanilla fake contrast (r_segs.c): horizontal walls one
            // light step darker, vertical walls one lighter. A light
            // step in the 16-level LIGHTLEVELS table is 16 units.
            int light = front.lightlevel;
            if (y1 == y2)      light -= 16;
            else if (x1 == x2) light += 16;

            if (back == null) {
                batch(batches, CLS_WALL, light)
                        .addWall(x1, y1, x2, y2, ffloor, fceil);
                continue;
            }

            double bfloor = Fx3SubsectorPolys.fx(back.floorheight);
            double bceil  = Fx3SubsectorPolys.fx(back.ceilingheight);

            // Upper wall — skipped between two sky ceilings
            // (r_bsp.c: no upper texture is drawn when both sides'
            // ceilings are the sky flat).
            if (bceil < fceil
                    && !(front.ceilingpic == RSky.skyflatnum
                         && back.ceilingpic == RSky.skyflatnum)) {
                batch(batches, CLS_WALL, light)
                        .addWall(x1, y1, x2, y2, bceil, fceil);
            }
            // Lower wall.
            if (bfloor > ffloor) {
                batch(batches, CLS_WALL, light)
                        .addWall(x1, y1, x2, y2, ffloor, bfloor);
            }
        }

        Group group = new Group();
        for (Map.Entry<Integer, Batch> e : batches.entrySet()) {
            int key = e.getKey();
            int cls = key & 3;
            group.getChildren().add(e.getValue().toMeshView(
                    materialFor(cls, key >> 2),
                    cls == CLS_WALL ? CullFace.BACK : CullFace.NONE));
        }
        return group;
    }

    // A light-hash over all sector heights so the renderer can detect
    // moving sectors and rebuild (Phase 1 stopgap — Phase 3 mutates
    // points in place instead).
    public static long sectorHeightsHash() {
        long h = 1469598103934665603L;
        for (int i = 0; i < PSetup.numsectors; i++) {
            Sector s = PSetup.sectors[i];
            h = (h ^ s.floorheight) * 1099511628211L;
            h = (h ^ s.ceilingheight) * 1099511628211L;
        }
        return h;
    }

    // ---- batching ----

    private static Batch batch(Map<Integer, Batch> batches, int cls, int light) {
        if (light < 0) light = 0;
        if (light > 255) light = 255;
        // Quantize light to 32 buckets — matches COLORMAP's 32 shades
        // closely enough for the untextured phase.
        int key = ((light >> 3) << 2) | cls;
        return batches.computeIfAbsent(key, k -> new Batch());
    }

    private static PhongMaterial materialFor(int cls, int lightBucket) {
        double l = (lightBucket * 8 + 4) / 255.0;
        // Differentiate surface classes slightly so an untextured
        // room still reads as a room.
        double mul = switch (cls) {
            case CLS_FLOOR   -> 0.9;
            case CLS_CEILING -> 0.7;
            default          -> 1.0;
        };
        double v = Math.max(0.03, Math.min(1.0, l * mul));
        PhongMaterial m = new PhongMaterial(Color.gray(v));
        m.setSpecularColor(Color.BLACK);
        return m;
    }

    // Growable triangle soup for one material. TriangleMesh needs at
    // least one texcoord; Phase 1 uses a single dummy (0,0).
    private static final class Batch {
        private float[] pts = new float[256 * 3];
        private int nPts;      // floats used
        private int[] faces = new int[256 * 6];
        private int nFaces;    // ints used

        // Map-space vertex → point index (JavaFX X,Y,Z).
        private int point(double x, double z, double y) {
            if (nPts + 3 > pts.length) {
                float[] b = new float[pts.length * 2];
                System.arraycopy(pts, 0, b, 0, nPts);
                pts = b;
            }
            int idx = nPts / 3;
            pts[nPts++] = (float) x;
            pts[nPts++] = (float) -z;
            pts[nPts++] = (float) y;
            return idx;
        }

        private void tri(int a, int b, int c) {
            if (nFaces + 6 > faces.length) {
                int[] f = new int[faces.length * 2];
                System.arraycopy(faces, 0, f, 0, nFaces);
                faces = f;
            }
            if (FLIP_WINDING) {
                int t = b; b = c; c = t;
            }
            faces[nFaces++] = a; faces[nFaces++] = 0;
            faces[nFaces++] = b; faces[nFaces++] = 0;
            faces[nFaces++] = c; faces[nFaces++] = 0;
        }

        // Wall quad from (x1,y1)→(x2,y2), zbot..ztop, facing the
        // frontsector — which lies on the right of v1→v2 in map
        // space. Corners: A=(v1,top) B=(v2,top) C=(v2,bot) D=(v1,bot);
        // (A,C,B)+(A,D,C) gives an algebraic normal (dy,0,-dx) — the
        // right-side direction — under the JavaFX axis mapping.
        void addWall(double x1, double y1, double x2, double y2,
                     double zbot, double ztop) {
            if (ztop <= zbot) return;
            int a = point(x1, ztop, y1);
            int b = point(x2, ztop, y2);
            int c = point(x2, zbot, y2);
            int d = point(x1, zbot, y1);
            tri(a, c, b);
            tri(a, d, c);
        }

        // Fan-triangulate a convex CCW (map y-up) polygon at height z.
        // floor=true faces up (visible from above), false faces down.
        void addFlat(double[] poly, double z, boolean floor) {
            int n = poly.length / 2;
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = point(poly[2 * i], z, poly[2 * i + 1]);
            }
            for (int i = 1; i < n - 1; i++) {
                if (floor) tri(idx[0], idx[i], idx[i + 1]);
                else       tri(idx[0], idx[i + 1], idx[i]);
            }
        }

        MeshView toMeshView(PhongMaterial material, CullFace cull) {
            TriangleMesh mesh = new TriangleMesh();
            float[] p = new float[nPts];
            System.arraycopy(pts, 0, p, 0, nPts);
            mesh.getPoints().setAll(p);
            mesh.getTexCoords().setAll(0f, 0f);
            int[] f = new int[nFaces];
            System.arraycopy(faces, 0, f, 0, nFaces);
            mesh.getFaces().setAll(f);
            MeshView mv = new MeshView(mesh);
            mv.setMaterial(material);
            mv.setCullFace(cull);
            return mv;
        }
    }
}
