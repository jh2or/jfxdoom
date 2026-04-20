package doom.fixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FixedTest {

    @Test
    void constants() {
        assertEquals(16, Fixed.FRACBITS);
        assertEquals(65536, Fixed.FRACUNIT);
    }

    @Test
    void fromIntAndBack() {
        assertEquals(1 << 16, Fixed.fromInt(1));
        assertEquals(42 << 16, Fixed.fromInt(42));
        assertEquals(-5 << 16, Fixed.fromInt(-5));
        assertEquals(1, Fixed.toInt(Fixed.fromInt(1)));
        assertEquals(-5, Fixed.toInt(Fixed.fromInt(-5)));
    }

    // C: FixedMul(a,b) == ((long long)a * (long long)b) >> 16.
    // 2.0 * 3.0 in 16.16 = 0x20000 * 0x30000 >> 16 = 0x60000 (= 6.0).
    @Test
    void mulBasic() {
        assertEquals(Fixed.fromInt(6), Fixed.mul(Fixed.fromInt(2), Fixed.fromInt(3)));
        assertEquals(Fixed.fromInt(-6), Fixed.mul(Fixed.fromInt(-2), Fixed.fromInt(3)));
        assertEquals(Fixed.fromInt(0), Fixed.mul(0, Fixed.fromInt(12345)));
    }

    // 0.5 * 0.5 = 0.25 in 16.16: 0x8000 * 0x8000 >> 16 = 0x4000.
    @Test
    void mulFractional() {
        int half = Fixed.FRACUNIT / 2;
        int quarter = Fixed.FRACUNIT / 4;
        assertEquals(quarter, Fixed.mul(half, half));
    }

    // Large operands: no overflow via the long-multiply path.
    @Test
    void mulLargeOperands() {
        // 30000.0 * 30000.0 in fixed would overflow 32-bit, but the long mul is fine.
        int a = Fixed.fromInt(30000);
        int b = Fixed.fromInt(2);
        assertEquals(Fixed.fromInt(60000), Fixed.mul(a, b));
    }

    // FixedDiv: saturates when |a|>>14 >= |b|.
    // Use raw fixed_t values rather than fromInt — Fixed.fromInt(100000) overflows
    // the 32-bit int (100000 << 16 wraps), which the C avoids by construction
    // (Doom never feeds fromInt numbers that large), but the saturation path
    // needs a clean positive/negative `a` to assert the sign result.
    @Test
    void divSaturatesOnOverflowGuard() {
        int a = Fixed.fromInt(30000); // 30000<<16 fits in int, positive
        int b = 1;                    // tiny denominator — guard triggers
        assertEquals(Integer.MAX_VALUE, Fixed.div(a, b));
        assertEquals(Integer.MIN_VALUE, Fixed.div(-a, b));
    }

    // Normal FixedDiv: 6 / 2 = 3 in fixed.
    @Test
    void divBasic() {
        assertEquals(Fixed.fromInt(3), Fixed.div(Fixed.fromInt(6), Fixed.fromInt(2)));
        assertEquals(Fixed.fromInt(-3), Fixed.div(Fixed.fromInt(-6), Fixed.fromInt(2)));
    }

    // 1.0 / 2.0 = 0.5.
    @Test
    void divFractional() {
        int half = Fixed.FRACUNIT / 2;
        assertEquals(half, Fixed.div(Fixed.FRACUNIT, Fixed.fromInt(2)));
    }

    // div2 without the guard is what you get when the guard doesn't trigger;
    // also exposed for direct testing.
    @Test
    void div2Basic() {
        assertEquals(Fixed.fromInt(3), Fixed.div2(Fixed.fromInt(6), Fixed.fromInt(2)));
    }

    @Test
    void div2ThrowsOnOverflow() {
        // b == 0 → c is +/-infinity → must throw.
        assertThrows(ArithmeticException.class, () -> Fixed.div2(Fixed.fromInt(1), 0));
    }
}
