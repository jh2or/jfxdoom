// Port of linuxdoom-1.10/m_bbox.c and m_bbox.h.
// Bounding boxes are plain int[4] arrays indexed by BOXTOP/BOXBOTTOM/BOXLEFT/BOXRIGHT,
// same as the C. Values are fixed_t (CLAUDE.md §3: fixed_t → int).
package doom.play;

public final class MBbox {

    // enum { BOXTOP, BOXBOTTOM, BOXLEFT, BOXRIGHT };
    public static final int BOXTOP = 0;
    public static final int BOXBOTTOM = 1;
    public static final int BOXLEFT = 2;
    public static final int BOXRIGHT = 3;

    private MBbox() {
    }

    // void M_ClearBox(fixed_t* box)
    //     box[BOXTOP] = box[BOXRIGHT] = MININT;
    //     box[BOXBOTTOM] = box[BOXLEFT] = MAXINT;
    public static void clearBox(int[] box) {
        box[BOXTOP] = Integer.MIN_VALUE;
        box[BOXRIGHT] = Integer.MIN_VALUE;
        box[BOXBOTTOM] = Integer.MAX_VALUE;
        box[BOXLEFT] = Integer.MAX_VALUE;
    }

    // void M_AddToBox(fixed_t* box, fixed_t x, fixed_t y)
    public static void addToBox(int[] box, int x, int y) {
        if (x < box[BOXLEFT]) {
            box[BOXLEFT] = x;
        } else if (x > box[BOXRIGHT]) {
            box[BOXRIGHT] = x;
        }
        if (y < box[BOXBOTTOM]) {
            box[BOXBOTTOM] = y;
        } else if (y > box[BOXTOP]) {
            box[BOXTOP] = y;
        }
    }
}
