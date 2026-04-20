package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MBboxTest {

    @Test
    void clearBoxInitialisesToExtremes() {
        int[] box = new int[4];
        MBbox.clearBox(box);
        assertEquals(Integer.MIN_VALUE, box[MBbox.BOXTOP]);
        assertEquals(Integer.MIN_VALUE, box[MBbox.BOXRIGHT]);
        assertEquals(Integer.MAX_VALUE, box[MBbox.BOXBOTTOM]);
        assertEquals(Integer.MAX_VALUE, box[MBbox.BOXLEFT]);
    }

    @Test
    void addToBoxExpandsLeftAndRight() {
        int[] box = new int[4];
        MBbox.clearBox(box);
        MBbox.addToBox(box, 10, 20);
        // After the first point, left == right == x and bottom == top == y.
        assertEquals(10, box[MBbox.BOXLEFT]);
        // BOXRIGHT only updates via `else if x > right` — so after the first
        // addition only BOXLEFT changes (since x < MAXINT). This matches the C.
        assertEquals(Integer.MIN_VALUE, box[MBbox.BOXRIGHT]);
        assertEquals(20, box[MBbox.BOXBOTTOM]);
        assertEquals(Integer.MIN_VALUE, box[MBbox.BOXTOP]);

        // Second point exceeds both current extents on the right/top side.
        MBbox.addToBox(box, 100, 200);
        assertEquals(10, box[MBbox.BOXLEFT]);
        assertEquals(100, box[MBbox.BOXRIGHT]);
        assertEquals(20, box[MBbox.BOXBOTTOM]);
        assertEquals(200, box[MBbox.BOXTOP]);
    }

    @Test
    void addToBoxDoesNotShrink() {
        int[] box = { 500, -500, -500, 500 }; // top, bottom, left, right
        // Point inside the box must not change any extent.
        MBbox.addToBox(box, 0, 0);
        assertEquals(500, box[MBbox.BOXTOP]);
        assertEquals(-500, box[MBbox.BOXBOTTOM]);
        assertEquals(-500, box[MBbox.BOXLEFT]);
        assertEquals(500, box[MBbox.BOXRIGHT]);
    }

    @Test
    void addToBoxXLessThanLeftOnlyUpdatesLeft() {
        // C uses `else if` — x<left shortcuts, right is never compared.
        int[] box = { 0, 0, 100, 200 };
        MBbox.addToBox(box, 50, 0);
        assertEquals(50, box[MBbox.BOXLEFT]);
        assertEquals(200, box[MBbox.BOXRIGHT]);
    }
}
