// Port of linuxdoom-1.10/m_fixed.c and m_fixed.h.
// Original comment header:
//   DESCRIPTION: Fixed point implementation.
//
// 32-bit integer as 16.16. All fixed-point arithmetic in the port routes through
// the static methods on this class (CLAUDE.md §4). Class is non-instantiable.
package doom.fixed;

public final class Fixed {

    // #define FRACBITS  16
    public static final int FRACBITS = 16;

    // #define FRACUNIT  (1<<FRACBITS)
    public static final int FRACUNIT = 1 << FRACBITS;

    private Fixed() {
    }

    // fixed_t FixedMul(fixed_t a, fixed_t b)
    //     return ((long long) a * (long long) b) >> FRACBITS;
    public static int mul(int a, int b) {
        return (int) (((long) a * (long) b) >> FRACBITS);
    }

    // fixed_t FixedDiv(fixed_t a, fixed_t b)
    //     if ((abs(a)>>14) >= abs(b)) return (a^b)<0 ? MININT : MAXINT;
    //     return FixedDiv2(a,b);
    public static int div(int a, int b) {
        if ((Math.abs(a) >> 14) >= Math.abs(b)) {
            return ((a ^ b) < 0) ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return div2(a, b);
    }

    // fixed_t FixedDiv2(fixed_t a, fixed_t b)
    //     double c = ((double)a) / ((double)b) * FRACUNIT;
    //     if (c >= 2147483648.0 || c < -2147483648.0) I_Error("FixedDiv: divide by zero");
    //     return (fixed_t) c;
    public static int div2(int a, int b) {
        double c = ((double) a) / ((double) b) * FRACUNIT;
        if (c >= 2147483648.0 || c < -2147483648.0) {
            // C calls I_Error and aborts. We throw — same effect, testable.
            throw new ArithmeticException("FixedDiv: divide by zero");
        }
        return (int) c;
    }

    // Convenience wrappers — not in C, but specified by CLAUDE.md §4.
    public static int toInt(int a) {
        return a >> FRACBITS;
    }

    public static int fromInt(int i) {
        return i << FRACBITS;
    }
}
