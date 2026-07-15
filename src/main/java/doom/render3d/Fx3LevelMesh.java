// Level geometry → JavaFX meshes for the experimental scene-graph
// renderer. Phase 2: textured walls and flats, lit per sector.
//
// Coordinate mapping (see 3D_OVERHAUL.md): Doom map (x east, y north,
// z up) → JavaFX (X, Y, Z) = (x, -z, y). JavaFX's Y axis points down,
// so heights are negated. 1 map unit = 1 texel on walls; flats are
// world-aligned 64×64.
//
// Geometry sources:
//   - Walls come from segs (not linedefs) so each facing of a
//     two-sided line gets its own quad with its own sidedef/sector
//     attributes. One-sided segs emit a full-height mid wall;
//     two-sided segs emit an upper wall (back ceiling < front
//     ceiling) and/or a lower wall (back floor > front floor).
//     Masked midtextures on two-sided lines are Phase 4.
//   - Floors/ceilings come from Fx3SubsectorPolys' closed convex
//     polygons.
//
// UV mapping follows r_segs.c's pegging rules, expressed as
// zTexTop — the world height of the texture's row 0:
//   mid   : DONTPEGBOTTOM ? floor + texH : ceiling
//   upper : DONTPEGTOP    ? front ceiling : back ceiling + texH
//   lower : DONTPEGBOTTOM ? front ceiling : back floor
// then v(z) = zTexTop − z + rowoffset, u = seg.offset +
// textureoffset + distance along the seg.
//
// JavaFX clamps UVs at [0,1] (no wrap mode), so images are pre-tiled
// by Fx3Textures to a per-texture pitch and quads/polygons are split
// on that grid so every UV lands inside one tile. Flats use the
// fixed FLAT_PITCH grid, world-aligned (u = x, v = −y, mod 64).
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
// overdraw is harmless).
package doom.render3d;

import doom.play.PSetup;
import doom.render.Line;
import doom.render.RSky;
import doom.render.Sector;
import doom.render.Seg;
import doom.render.Side;
import doom.render.Subsector;

import javafx.scene.Group;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import java.util.HashMap;
import java.util.Map;

public final class Fx3LevelMesh {

    private Fx3LevelMesh() {
    }

    // Cached per-level: the subsector polygons only depend on map
    // x/y geometry, which never changes at runtime — moving sectors
    // only change heights. Keyed by the sectors array reference
    // (a new level allocates new arrays).
    private static Sector[] polysFor;
    private static double[][] polys;

    // Build the whole level as a Group of MeshViews, one per
    // (kind, texture, light bucket) batch, using the *current*
    // sector heights. Phase 3 replaces rebuild-on-height-change with
    // in-place point updates.
    public static Group build() {
        if (polysFor != PSetup.sectors) {
            polys = Fx3SubsectorPolys.buildAll();
            polysFor = PSetup.sectors;
        }

        Map<Long, Batch> batches = new HashMap<>();

        // Floors and ceilings.
        for (int i = 0; i < PSetup.numsubsectors; i++) {
            double[] poly = polys[i];
            if (poly.length < 6) continue;
            Subsector ss = PSetup.subsectors[i];
            Sector sec = ss.sector;
            double floor = Fx3SubsectorPolys.fx(sec.floorheight);
            double ceil  = Fx3SubsectorPolys.fx(sec.ceilingheight);
            int lightnum = sec.lightlevel >> 4;
            if (sec.floorpic != RSky.skyflatnum) {
                addFlat(batch(batches, Fx3Textures.KIND_FLAT,
                                sec.floorpic, lightnum),
                        poly, floor, true);
            }
            if (sec.ceilingpic != RSky.skyflatnum) {
                addFlat(batch(batches, Fx3Textures.KIND_FLAT,
                                sec.ceilingpic, lightnum),
                        poly, ceil, false);
            }
        }

        // Walls.
        for (int i = 0; i < PSetup.numsegs; i++) {
            Seg seg = PSetup.segs[i];
            Side side = seg.sidedef;
            Line line = seg.linedef;
            Sector front = seg.frontsector;
            Sector back  = seg.backsector;
            double x1 = Fx3SubsectorPolys.fx(seg.v1.x);
            double y1 = Fx3SubsectorPolys.fx(seg.v1.y);
            double x2 = Fx3SubsectorPolys.fx(seg.v2.x);
            double y2 = Fx3SubsectorPolys.fx(seg.v2.y);
            double ffloor = Fx3SubsectorPolys.fx(front.floorheight);
            double fceil  = Fx3SubsectorPolys.fx(front.ceilingheight);

            // Vanilla fake contrast (r_segs.c) on the 16-level light
            // index: horizontal walls one step darker, vertical one
            // lighter.
            int lightnum = front.lightlevel >> 4;
            if (y1 == y2)      lightnum--;
            else if (x1 == x2) lightnum++;

            double u0 = Fx3SubsectorPolys.fx(seg.offset)
                      + Fx3SubsectorPolys.fx(side.textureoffset);
            double rowofs = Fx3SubsectorPolys.fx(side.rowoffset);
            boolean pegTop    = (line.flags & Line.ML_DONTPEGTOP) != 0;
            boolean pegBottom = (line.flags & Line.ML_DONTPEGBOTTOM) != 0;

            if (back == null) {
                int tex = side.midtexture;
                if (tex > 0) {
                    double zTexTop = pegBottom
                            ? ffloor + Fx3Textures.textureHeight(tex)
                            : fceil;
                    addWall(batch(batches, Fx3Textures.KIND_WALL, tex, lightnum),
                            tex, x1, y1, x2, y2, ffloor, fceil,
                            u0, zTexTop, rowofs);
                }
                continue;
            }

            double bfloor = Fx3SubsectorPolys.fx(back.floorheight);
            double bceil  = Fx3SubsectorPolys.fx(back.ceilingheight);

            // Upper wall — skipped between two sky ceilings
            // (r_bsp.c: the sky joins across the gap).
            if (bceil < fceil
                    && !(front.ceilingpic == RSky.skyflatnum
                         && back.ceilingpic == RSky.skyflatnum)) {
                int tex = side.toptexture;
                if (tex > 0) {
                    double zTexTop = pegTop
                            ? fceil
                            : bceil + Fx3Textures.textureHeight(tex);
                    addWall(batch(batches, Fx3Textures.KIND_WALL, tex, lightnum),
                            tex, x1, y1, x2, y2, bceil, fceil,
                            u0, zTexTop, rowofs);
                }
            }
            // Lower wall. DONTPEGBOTTOM pegs the texture to the
            // front ceiling so it stays put while doors move.
            if (bfloor > ffloor) {
                int tex = side.bottomtexture;
                if (tex > 0) {
                    double zTexTop = pegBottom ? fceil : bfloor;
                    addWall(batch(batches, Fx3Textures.KIND_WALL, tex, lightnum),
                            tex, x1, y1, x2, y2, ffloor, bfloor,
                            u0, zTexTop, rowofs);
                }
            }
        }

        Group group = new Group();
        for (Batch b : batches.values()) {
            group.getChildren().add(b.toMeshView());
        }
        return group;
    }

    // A light hash over all sector heights so the renderer can detect
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

    private static Batch batch(Map<Long, Batch> batches,
                               int kind, int num, int lightnum) {
        int ln = Math.max(0, Math.min(15, lightnum));
        long key = ((long) kind << 40) | ((long) num << 8) | ln;
        return batches.computeIfAbsent(key, k -> new Batch(kind, num, ln));
    }

    // ---- geometry emission ----

    // Wall quad from (x1,y1)→(x2,y2), zbot..ztop, facing the
    // frontsector (on the right of v1→v2 in map space). Split on the
    // texture's bake grid so all UVs stay in [0,1]; u0 is the texel
    // column at v1, zTexTop the world height of texture row 0.
    private static void addWall(Batch b, int texnum,
                                double x1, double y1, double x2, double y2,
                                double zbot, double ztop,
                                double u0, double zTexTop, double rowofs) {
        if (ztop <= zbot) return;
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len < 1e-6) return;

        double pitchU = Fx3Textures.wallPitchU(texnum);
        double pitchV = Fx3Textures.wallPitchV(texnum);
        double vTop = zTexTop - ztop + rowofs;
        double vBot = zTexTop - zbot + rowofs;

        int cu0 = (int) Math.floor(u0 / pitchU);
        int cu1 = (int) Math.floor((u0 + len - 1e-9) / pitchU);
        int cv0 = (int) Math.floor(vTop / pitchV);
        int cv1 = (int) Math.floor((vBot - 1e-9) / pitchV);

        for (int cu = cu0; cu <= cu1; cu++) {
            double ua = Math.max(u0, cu * pitchU);
            double ub = Math.min(u0 + len, (cu + 1) * pitchU);
            if (ub - ua < 1e-6) continue;
            double ta = (ua - u0) / len;
            double tb = (ub - u0) / len;
            double xa = x1 + ta * (x2 - x1), ya = y1 + ta * (y2 - y1);
            double xb = x1 + tb * (x2 - x1), yb = y1 + tb * (y2 - y1);
            float iua = (float) ((ua - cu * pitchU) / pitchU);
            float iub = (float) ((ub - cu * pitchU) / pitchU);

            for (int cv = cv0; cv <= cv1; cv++) {
                double va = Math.max(vTop, cv * pitchV);
                double vb = Math.min(vBot, (cv + 1) * pitchV);
                if (vb - va < 1e-6) continue;
                // v(z) = zTexTop − z + rowofs  ⇒  z = zTexTop + rowofs − v
                double zA = zTexTop + rowofs - va; // top of sub-quad
                double zB = zTexTop + rowofs - vb; // bottom
                float iva = (float) ((va - cv * pitchV) / pitchV);
                float ivb = (float) ((vb - cv * pitchV) / pitchV);

                // Corners: A=(a,top) B=(b,top) C=(b,bot) D=(a,bot);
                // (A,C,B)+(A,D,C) faces the right side of v1→v2
                // under the JavaFX axis mapping.
                int ia = b.point(xa, zA, ya, iua, iva);
                int ib = b.point(xb, zA, yb, iub, iva);
                int ic = b.point(xb, zB, yb, iub, ivb);
                int id = b.point(xa, zB, ya, iua, ivb);
                b.tri(ia, ic, ib);
                b.tri(ia, id, ic);
            }
        }
    }

    // Flat (floor/ceiling) polygon at height z, split on the
    // world-aligned FLAT_PITCH grid; u = x, v = −y in texels
    // (r_plane.c's mapping), normalized per cell.
    private static void addFlat(Batch b, double[] poly, double z, boolean floor) {
        double p = Fx3Textures.FLAT_PITCH;
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        int n = poly.length / 2;
        for (int i = 0; i < n; i++) {
            minx = Math.min(minx, poly[2 * i]);
            maxx = Math.max(maxx, poly[2 * i]);
            miny = Math.min(miny, poly[2 * i + 1]);
            maxy = Math.max(maxy, poly[2 * i + 1]);
        }
        int cx0 = (int) Math.floor(minx / p);
        int cx1 = (int) Math.floor((maxx - 1e-9) / p);
        int cy0 = (int) Math.floor(miny / p);
        int cy1 = (int) Math.floor((maxy - 1e-9) / p);

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                double bx = cx * p, by = cy * p;
                // Keep x ≥ bx, x ≤ bx+p, y ≥ by, y ≤ by+p (side
                // conventions of Fx3SubsectorPolys.clip).
                double[] cell = Fx3SubsectorPolys.clip(poly, bx, by, 0, 1, true);
                cell = Fx3SubsectorPolys.clip(cell, bx + p, by, 0, 1, false);
                cell = Fx3SubsectorPolys.clip(cell, bx, by, 1, 0, false);
                cell = Fx3SubsectorPolys.clip(cell, bx, by + p, 1, 0, true);
                int m = cell.length / 2;
                if (m < 3 || Math.abs(Fx3SubsectorPolys.signedArea(cell)) < 1e-6) {
                    continue;
                }
                int[] idx = new int[m];
                for (int i = 0; i < m; i++) {
                    double x = cell[2 * i], y = cell[2 * i + 1];
                    float u = (float) ((x - bx) / p);
                    // v = −y mod pitch: by+p−y ∈ [0,p], and the cell
                    // origin is pitch-aligned so the world 64-texel
                    // phase is preserved.
                    float v = (float) ((by + p - y) / p);
                    idx[i] = b.point(x, z, y, u, v);
                }
                for (int i = 1; i < m - 1; i++) {
                    if (floor) b.tri(idx[0], idx[i], idx[i + 1]);
                    else       b.tri(idx[0], idx[i + 1], idx[i]);
                }
            }
        }
    }

    // Growable triangle soup for one (kind, texture, light) batch.
    private static final class Batch {
        private final int kind;
        private final int num;
        private final int lightnum;

        private float[] pts = new float[256 * 3];
        private int nPts;
        private float[] uvs = new float[256 * 2];
        private int nUvs;
        private int[] faces = new int[256 * 6];
        private int nFaces;

        Batch(int kind, int num, int lightnum) {
            this.kind = kind;
            this.num = num;
            this.lightnum = lightnum;
        }

        // Map-space vertex (x, height z, y) + texcoord → index
        // (points and texcoords stay 1:1).
        int point(double x, double z, double y, float u, float v) {
            if (nPts + 3 > pts.length) {
                float[] a = new float[pts.length * 2];
                System.arraycopy(pts, 0, a, 0, nPts);
                pts = a;
            }
            if (nUvs + 2 > uvs.length) {
                float[] a = new float[uvs.length * 2];
                System.arraycopy(uvs, 0, a, 0, nUvs);
                uvs = a;
            }
            int idx = nPts / 3;
            pts[nPts++] = (float) x;
            pts[nPts++] = (float) -z;
            pts[nPts++] = (float) y;
            uvs[nUvs++] = u;
            uvs[nUvs++] = v;
            return idx;
        }

        void tri(int a, int b, int c) {
            if (nFaces + 6 > faces.length) {
                int[] f = new int[faces.length * 2];
                System.arraycopy(faces, 0, f, 0, nFaces);
                faces = f;
            }
            faces[nFaces++] = a; faces[nFaces++] = a;
            faces[nFaces++] = b; faces[nFaces++] = b;
            faces[nFaces++] = c; faces[nFaces++] = c;
        }

        MeshView toMeshView() {
            TriangleMesh mesh = new TriangleMesh();
            float[] p = new float[nPts];
            System.arraycopy(pts, 0, p, 0, nPts);
            mesh.getPoints().setAll(p);
            float[] t = new float[nUvs];
            System.arraycopy(uvs, 0, t, 0, nUvs);
            mesh.getTexCoords().setAll(t);
            int[] f = new int[nFaces];
            System.arraycopy(faces, 0, f, 0, nFaces);
            mesh.getFaces().setAll(f);

            MeshView mv = new MeshView(mesh);
            mv.setMaterial(kind == Fx3Textures.KIND_WALL
                    ? Fx3Textures.wallMaterial(num, lightnum)
                    : Fx3Textures.flatMaterial(num, lightnum));
            mv.setCullFace(kind == Fx3Textures.KIND_WALL
                    ? CullFace.BACK : CullFace.NONE);
            return mv;
        }
    }
}
