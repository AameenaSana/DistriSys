package dev.distrisys.erasure;

/**
 * Arithmetic in the finite field GF(2^8), using the primitive polynomial
 * x^8 + x^4 + x^3 + x^2 + 1 (0x11D) and generator 2.
 *
 * <p>Elements are ints in [0, 255]. Inputs outside that range are not checked: these methods
 * sit on the hot path of every encode and decode, and callers always pass {@code b & 0xFF}.
 */
public final class GF256 {

    public static final int POLYNOMIAL = 0x11D;
    public static final int ORDER = 256;

    /** Cycle length of the generator: the number of non-zero elements. */
    private static final int CYCLE = ORDER - 1; // 255

    /**
     * EXP[i] = 2^i. Holds two full cycles (510 entries) so that LOG[a] + LOG[b], which is at most
     * 254 + 254 = 508, can index it directly without a "% 255" on every multiplication.
     */
    private static final int[] EXP = new int[2 * CYCLE];

    /**
     * LOG[a] = the k with 2^k == a. LOG[0] is meaningless (0 is not a power of 2) and stays 0;
     * every method checks for 0 before reading this table.
     */
    private static final int[] LOG = new int[ORDER];

    static {
        int x = 1;
        for (int i = 0; i < CYCLE; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x = mulSlow(x, 2);
        }
        // Because 0x11D is primitive, 2^255 wraps back to 1. If it didn't, the tables would be wrong.
        if (x != 1) {
            throw new AssertionError("0x11D is not primitive?! 2^255 = " + x);
        }
        for (int i = CYCLE; i < EXP.length; i++) {
            EXP[i] = EXP[i - CYCLE];
        }
    }

    private GF256() {
    }

    /** Polynomial addition: coefficients are bits, added mod 2, with no carries. */
    public static int add(int a, int b) {
        return a ^ b;
    }

    /** In characteristic 2, every element is its own negative, so subtracting is adding. */
    public static int sub(int a, int b) {
        return a ^ b;
    }

    /** a * b = 2^(log a + log b). */
    public static int mul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    /** a / b = 2^(log a - log b). The +255 keeps the index non-negative. */
    public static int div(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("division by zero in GF(256)");
        }
        if (a == 0) {
            return 0;
        }
        return EXP[LOG[a] - LOG[b] + CYCLE];
    }

    /** 1/a = 2^(-log a) = 2^(255 - log a), since 2^255 = 1. */
    public static int inv(int a) {
        if (a == 0) {
            throw new ArithmeticException("zero has no inverse in GF(256)");
        }
        return EXP[CYCLE - LOG[a]];
    }

    /** a^n = 2^(n * log a), with exponents taken mod 255. */
    public static int pow(int a, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative exponent: " + n);
        }
        if (n == 0) {
            return 1; // including 0^0, by convention
        }
        if (a == 0) {
            return 0;
        }
        // Reduce n first: LOG[a] * n could overflow an int for large n; 254 * 254 cannot.
        return EXP[(LOG[a] * (n % CYCLE)) % CYCLE];
    }

    /** 2^i. */
    public static int exp(int i) {
        if (i < 0) {
            throw new IllegalArgumentException("negative exponent: " + i);
        }
        return EXP[i % CYCLE];
    }

    /** The k in [0, 254] with 2^k == a. */
    public static int log(int a) {
        if (a == 0) {
            throw new ArithmeticException("log(0) is undefined in GF(256)");
        }
        return LOG[a];
    }

    /**
     * Reference multiplication by shift-and-XOR ("Russian peasant"), without tables.
     * Used to build the tables, and as an independent oracle in tests.
     */
    static int mulSlow(int a, int b) {
        int result = 0;
        while (b != 0) {
            if ((b & 1) != 0) {
                result ^= a;          // this bit of b is set: add the current multiple of a
            }
            a <<= 1;                  // a = a * x
            if ((a & 0x100) != 0) {
                a ^= POLYNOMIAL;      // degree hit 8: reduce mod 0x11D (subtract = XOR)
            }
            b >>>= 1;                 // move on to b's next bit
        }
        return result;
    }
}
