// Closed convex polygons for BSP subsectors — the geometric core of
// the experimental JavaFX scene-graph renderer (doom.render3d).
//
// Vanilla subsectors are NOT closed polygons: segs exist only along
// linedefs, so the edges a subsector gains from BSP node splits have
// no seg at all. Every hardware Doom renderer has to reconstruct
// those implicit edges. We do it the glBSP way, conceptually: start
// from the level bounding box and clip it by every partition line on
// the path from the BSP root down to the leaf (keeping the child's
// half-plane each time), then clip by the subsector's own segs. The
// result is a guaranteed-closed convex polygon per subsector, ready
// for trivial fan triangulation.
//
// Side conventions follow r_main.c's R_PointOnSide exactly:
//   side 0 (front/right)  ⇔  node.dy*(x-node.x) - (y-node.y)*node.dx > 0
// and a subsector lies on side 0 of each of its own segs.
//
// Everything here is double-precision map units (fixed_t / 65536) —
// this is view-side geometry only, the playsim never sees it. No
// JavaFX imports: this class is headless-testable.
package doom.render3d;

import doom.play.PSetup;
import doom.render.Node;
import doom.render.Seg;
import doom.render.Subsector;

public final class Fx3SubsectorPolys {

    // Clipping tolerance in map units. Vertices within EPS of a
    // partition line are kept on both sides so shared edges stay
    // watertight.
    private static final double EPS = 0.01;

    // Minimum |area| (sq map units) below which a leaf polygon is
    // considered degenerate and dropped (returned as empty).
    private static final double MIN_AREA = 1e-6;

    private Fx3SubsectorPolys() {
    }

    // Build one polygon per subsector from the currently-loaded level
    // (PSetup.nodes / subsectors / segs / vertexes). Result is packed
    // x0,y0,x1,y1,... in map units, CCW in Doom's y-up map frame.
    // Degenerate subsectors yield a zero-length array (never null).
    public static double[][] buildAll() {
        int n = PSetup.numsubsectors;
        double[][] out = new double[n][];

        // Level bounding box, expanded a little so boundary vertices
        // never sit exactly on the initial polygon's edges.
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (int i = 0; i < PSetup.numvertexes; i++) {
            double x = fx(PSetup.vertexes[i].x);
            double y = fx(PSetup.vertexes[i].y);
            if (x < minx) minx = x;
            if (x > maxx) maxx = x;
            if (y < miny) miny = y;
            if (y > maxy) maxy = y;
        }
        minx -= 8; miny -= 8; maxx += 8; maxy += 8;

        // CCW in y-up map coords.
        double[] bbox = {
                minx, miny,
                maxx, miny,
                maxx, maxy,
                minx, maxy,
        };

        if (PSetup.numnodes == 0) {
            // Single-subsector map (no BSP splits).
            for (int i = 0; i < n; i++) {
                out[i] = clipToSegs(bbox, PSetup.subsectors[i]);
            }
            return out;
        }

        descend(PSetup.numnodes - 1, bbox, out);

        // Subsectors the descent never reached (unreferenced by the
        // tree — shouldn't happen in valid maps) become empty rather
        // than null.
        for (int i = 0; i < n; i++) {
            if (out[i] == null) out[i] = new double[0];
        }
        return out;
    }

    private static void descend(int child, double[] poly, double[][] out) {
        if ((child & Node.NF_SUBSECTOR) != 0) {
            int ss = child & ~Node.NF_SUBSECTOR;
            out[ss] = clipToSegs(poly, PSetup.subsectors[ss]);
            return;
        }
        Node node = PSetup.nodes[child];
        double nx = fx(node.x);
        double ny = fx(node.y);
        double ndx = fx(node.dx);
        double ndy = fx(node.dy);
        descend(node.children[0], clip(poly, nx, ny, ndx, ndy, true), out);
        descend(node.children[1], clip(poly, nx, ny, ndx, ndy, false), out);
    }

    // Clip the node-derived polygon by the subsector's own segs
    // (interior is on side 0 — the front/right — of each seg), then
    // sanity-check the result.
    //
    // Precision detail: a seg that is a *split fragment* of a
    // linedef has builder-rounded integer endpoints, so the line
    // through them tilts slightly off the true linedef line and can
    // shave legitimate corners off the polygon (observed on E1M1
    // subsector 96). Clip against the parent linedef's exact line
    // instead — oriented to match the seg's facing — like GL-node
    // builders do.
    private static double[] clipToSegs(double[] poly, Subsector ss) {
        for (int i = 0; i < ss.numlines; i++) {
            Seg seg = PSetup.segs[ss.firstline + i];
            double x1, y1, x2, y2;
            if (seg.linedef != null && seg.linedef.sidenum[0] >= 0
                    && seg.linedef.sidenum[0] < PSetup.numsides) {
                boolean onSide0 =
                        seg.sidedef == PSetup.sides[seg.linedef.sidenum[0]];
                if (onSide0) {
                    x1 = fx(seg.linedef.v1.x); y1 = fx(seg.linedef.v1.y);
                    x2 = fx(seg.linedef.v2.x); y2 = fx(seg.linedef.v2.y);
                } else {
                    x1 = fx(seg.linedef.v2.x); y1 = fx(seg.linedef.v2.y);
                    x2 = fx(seg.linedef.v1.x); y2 = fx(seg.linedef.v1.y);
                }
            } else {
                x1 = fx(seg.v1.x); y1 = fx(seg.v1.y);
                x2 = fx(seg.v2.x); y2 = fx(seg.v2.y);
            }
            poly = clip(poly, x1, y1, x2 - x1, y2 - y1, true);
        }
        poly = dedupe(poly);

        // Vanilla NODES store partition lines as rounded 16-bit
        // (x,y,dx,dy) of the chosen split seg — DoomBSP worked in
        // floating point and rounded on write, so a stored partition
        // can sit several map units off the true dividing line and
        // clip corners off the leaf, seg vertices included (E1M1
        // subsectors 156/159/160, up to ~5 units). Subsector regions
        // are convex by construction, so the faithful repair is the
        // convex hull of the clipped polygon plus the subsector's own
        // seg endpoints: sloppily-cut corners come back, correctly-
        // clipped edges are unchanged.
        int extra = ss.numlines * 4;
        double[] cloud = new double[poly.length + extra];
        System.arraycopy(poly, 0, cloud, 0, poly.length);
        int m = poly.length;
        for (int i = 0; i < ss.numlines; i++) {
            Seg seg = PSetup.segs[ss.firstline + i];
            cloud[m++] = fx(seg.v1.x);
            cloud[m++] = fx(seg.v1.y);
            cloud[m++] = fx(seg.v2.x);
            cloud[m++] = fx(seg.v2.y);
        }
        poly = convexHull(cloud);

        if (poly.length < 6 || Math.abs(signedArea(poly)) < MIN_AREA) {
            return new double[0];
        }
        if (signedArea(poly) < 0) {
            poly = reverse(poly);
        }
        return poly;
    }

    // Andrew's monotone chain — returns the CCW convex hull of the
    // packed point cloud. Collinear points are dropped.
    static double[] convexHull(double[] pts) {
        int n = pts.length / 2;
        if (n < 3) return new double[0];

        // Sort lexicographically by (x, y).
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int c = Double.compare(pts[2 * a], pts[2 * b]);
            return (c != 0) ? c : Double.compare(pts[2 * a + 1], pts[2 * b + 1]);
        });

        int[] hull = new int[2 * n];
        int k = 0;
        // Lower hull.
        for (int ii = 0; ii < n; ii++) {
            int i = order[ii];
            while (k >= 2 && cross(pts, hull[k - 2], hull[k - 1], i) <= 0) k--;
            hull[k++] = i;
        }
        // Upper hull.
        int lower = k + 1;
        for (int ii = n - 2; ii >= 0; ii--) {
            int i = order[ii];
            while (k >= lower && cross(pts, hull[k - 2], hull[k - 1], i) <= 0) k--;
            hull[k++] = i;
        }
        k--; // last point equals the first

        double[] out = new double[2 * k];
        for (int i = 0; i < k; i++) {
            out[2 * i] = pts[2 * hull[i]];
            out[2 * i + 1] = pts[2 * hull[i] + 1];
        }
        return dedupe(out);
    }

    private static double cross(double[] pts, int o, int a, int b) {
        return (pts[2 * a] - pts[2 * o]) * (pts[2 * b + 1] - pts[2 * o + 1])
             - (pts[2 * a + 1] - pts[2 * o + 1]) * (pts[2 * b] - pts[2 * o]);
    }

    // Sutherland–Hodgman clip of a convex polygon against one side of
    // the directed line (ax,ay)+(dx,dy). side0=true keeps the
    // front/right half-plane (R_PointOnSide's "0" side), side0=false
    // keeps the back/left one. Points within EPS of the line are kept
    // on both sides.
    static double[] clip(double[] poly, double ax, double ay,
                         double dx, double dy, boolean side0) {
        int n = poly.length / 2;
        if (n == 0) return poly;
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return poly;
        double ux = dx / len, uy = dy / len;

        double[] out = new double[2 * (n + 4)];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double px = poly[2 * i], py = poly[2 * i + 1];
            double qx = poly[2 * j], qy = poly[2 * j + 1];
            // Signed distance: positive on side 0 (front/right).
            double fp = uy * (px - ax) - ux * (py - ay);
            double fq = uy * (qx - ax) - ux * (qy - ay);
            if (!side0) {
                fp = -fp;
                fq = -fq;
            }
            boolean inP = fp >= -EPS;
            boolean inQ = fq >= -EPS;
            if (inP) {
                if (m + 4 > out.length) out = grow(out, m);
                out[m++] = px;
                out[m++] = py;
            }
            if (inP != inQ) {
                double t = fp / (fp - fq);
                if (m + 4 > out.length) out = grow(out, m);
                out[m++] = px + t * (qx - px);
                out[m++] = py + t * (qy - py);
            }
        }
        double[] res = new double[m];
        System.arraycopy(out, 0, res, 0, m);
        return res;
    }

    private static double[] grow(double[] a, int used) {
        double[] b = new double[a.length * 2];
        System.arraycopy(a, 0, b, 0, used);
        return b;
    }

    // Drop consecutive vertices closer than EPS (clipping against
    // near-coincident lines produces them).
    private static double[] dedupe(double[] poly) {
        int n = poly.length / 2;
        if (n == 0) return poly;
        double[] out = new double[poly.length];
        int m = 0;
        for (int i = 0; i < n; i++) {
            double x = poly[2 * i], y = poly[2 * i + 1];
            if (m > 0) {
                double lx = out[m - 2], ly = out[m - 1];
                if (Math.abs(x - lx) < EPS && Math.abs(y - ly) < EPS) continue;
            }
            out[m++] = x;
            out[m++] = y;
        }
        // Close the loop: first vs last.
        if (m >= 4
                && Math.abs(out[0] - out[m - 2]) < EPS
                && Math.abs(out[1] - out[m - 1]) < EPS) {
            m -= 2;
        }
        double[] res = new double[m];
        System.arraycopy(out, 0, res, 0, m);
        return res;
    }

    // Shoelace area — positive for CCW in the y-up map frame.
    static double signedArea(double[] poly) {
        int n = poly.length / 2;
        double a = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += poly[2 * i] * poly[2 * j + 1] - poly[2 * j] * poly[2 * i + 1];
        }
        return a / 2;
    }

    private static double[] reverse(double[] poly) {
        int n = poly.length / 2;
        double[] out = new double[poly.length];
        for (int i = 0; i < n; i++) {
            out[2 * i] = poly[2 * (n - 1 - i)];
            out[2 * i + 1] = poly[2 * (n - 1 - i) + 1];
        }
        return out;
    }

    static double fx(int fixed) {
        return fixed / 65536.0;
    }
}
