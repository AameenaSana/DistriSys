package dev.distrisys.erasure;

import static dev.distrisys.erasure.GF256.add;
import static dev.distrisys.erasure.GF256.div;
import static dev.distrisys.erasure.GF256.exp;
import static dev.distrisys.erasure.GF256.inv;
import static dev.distrisys.erasure.GF256.log;
import static dev.distrisys.erasure.GF256.mul;
import static dev.distrisys.erasure.GF256.mulSlow;
import static dev.distrisys.erasure.GF256.pow;
import static dev.distrisys.erasure.GF256.sub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The field is tiny (256 elements), so almost every property is checked exhaustively
 * rather than by sampling: 65,536 pairs, and 16.7 million triples for associativity.
 * If these pass, the field is correct for every possible input.
 */
class GF256Test {

    private static final int N = GF256.ORDER;

    @Nested
    class Addition {

        @Test
        void isXor() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    assertEquals(a ^ b, add(a, b));
                }
            }
        }

        @Test
        void everyElementIsItsOwnNegative() {
            for (int a = 0; a < N; a++) {
                assertEquals(0, add(a, a), "a + a must be 0");
            }
        }

        @Test
        void subtractionEqualsAddition() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    assertEquals(add(a, b), sub(a, b));
                }
            }
        }
    }

    @Nested
    class SlowMultiplication {

        /** Hand-checkable values; see the worked examples in the milestone notes. */
        @Test
        void knownValues() {
            assertEquals(0x04, mulSlow(0x02, 0x02));
            assertEquals(0x09, mulSlow(0x03, 0x07), "carry-less: (x+1)(x^2+x+1) = x^3+1, not 21");
            assertEquals(0x1D, mulSlow(0x80, 0x02), "x^8 reduces to x^4+x^3+x^2+1");
            assertEquals(0x01, mulSlow(0x8E, 0x02), "0x8E is the inverse of 2");
            assertEquals(0x8F, mulSlow(0x53, 0xCA));
            assertEquals(0x0F, mulSlow(0x12, 0x34));
            assertEquals(0xE2, mulSlow(0xFF, 0xFF));
        }

        @Test
        void zeroAndOne() {
            for (int a = 0; a < N; a++) {
                assertEquals(0, mulSlow(a, 0));
                assertEquals(0, mulSlow(0, a));
                assertEquals(a, mulSlow(a, 1));
                assertEquals(a, mulSlow(1, a));
            }
        }
    }

    @Nested
    class Multiplication {

        @Test
        void tablesAgreeWithSlowMultiplicationForEveryPair() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    int expected = mulSlow(a, b);
                    int actual = mul(a, b);
                    if (expected != actual) {
                        fail(String.format("mul(0x%02X, 0x%02X): table gives 0x%02X, slow gives 0x%02X",
                                a, b, actual, expected));
                    }
                }
            }
        }

        @Test
        void resultIsAlwaysAByte() {
            // Catches sign-extension bugs from Java's signed byte type.
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    int p = mul(a, b);
                    assertTrue(p >= 0 && p < N, () -> "out of range: " + p);
                }
            }
        }

        @Test
        void isCommutative() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    assertEquals(mul(a, b), mul(b, a));
                }
            }
        }

        @Test
        void isAssociative() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    int ab = mul(a, b);
                    for (int c = 0; c < N; c++) {
                        if (mul(ab, c) != mul(a, mul(b, c))) {
                            fail(String.format("(a*b)*c != a*(b*c) for a=%d b=%d c=%d", a, b, c));
                        }
                    }
                }
            }
        }

        @Test
        void distributesOverAddition() {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    for (int c = 0; c < N; c++) {
                        if (mul(a, add(b, c)) != add(mul(a, b), mul(a, c))) {
                            fail(String.format("a*(b+c) != a*b + a*c for a=%d b=%d c=%d", a, b, c));
                        }
                    }
                }
            }
        }

        @Test
        void hasNoZeroDivisors() {
            // The property that integers mod 256 lack (2 * 128 = 0). This is what makes it a field.
            for (int a = 1; a < N; a++) {
                for (int b = 1; b < N; b++) {
                    if (mul(a, b) == 0) {
                        fail("a*b == 0 for non-zero a=" + a + ", b=" + b);
                    }
                }
            }
        }
    }

    @Nested
    class ExpAndLog {

        @Test
        void twoGeneratesEveryNonZeroElementExactlyOnce() {
            boolean[] seen = new boolean[N];
            for (int i = 0; i < 255; i++) {
                int e = exp(i);
                assertTrue(e != 0, "2^i is never 0");
                assertTrue(!seen[e], () -> "2^i repeats a value before i = 255: " + e);
                seen[e] = true;
            }
            assertEquals(1, exp(255), "2^255 == 2^0 == 1: the cycle length is 255");
        }

        @Test
        void knownPowers() {
            assertEquals(0x01, exp(0));
            assertEquals(0x80, exp(7));
            assertEquals(0x1D, exp(8));
            assertEquals(0x8E, exp(254));
        }

        @Test
        void expWrapsAround() {
            for (int i = 0; i < 600; i++) {
                assertEquals(exp(i % 255), exp(i));
            }
        }

        @Test
        void logAndExpAreInverses() {
            for (int i = 0; i < 255; i++) {
                assertEquals(i, log(exp(i)));
            }
            for (int a = 1; a < N; a++) {
                assertEquals(a, exp(log(a)));
            }
        }

        @Test
        void logOfZeroIsUndefined() {
            assertThrows(ArithmeticException.class, () -> log(0));
        }
    }

    @Nested
    class InverseAndDivision {

        @Test
        void everyNonZeroElementHasExactlyOneInverse() {
            for (int a = 1; a < N; a++) {
                int count = 0;
                for (int b = 1; b < N; b++) {
                    if (mul(a, b) == 1) {
                        count++;
                    }
                }
                assertEquals(1, count, "number of inverses of " + a);
                assertEquals(1, mul(a, inv(a)));
                assertEquals(a, inv(inv(a)));
            }
        }

        @Test
        void knownInverses() {
            assertEquals(0x01, inv(0x01));
            assertEquals(0x8E, inv(0x02));
            assertEquals(0xF4, inv(0x03));
            assertEquals(0x8C, inv(0x53));
        }

        @Test
        void divisionUndoesMultiplication() {
            for (int a = 0; a < N; a++) {
                for (int b = 1; b < N; b++) {
                    assertEquals(a, div(mul(a, b), b));
                    assertEquals(mul(a, inv(b)), div(a, b));
                }
            }
        }

        @Test
        void zeroHasNoInverse() {
            assertThrows(ArithmeticException.class, () -> inv(0));
            assertThrows(ArithmeticException.class, () -> div(5, 0));
            assertThrows(ArithmeticException.class, () -> div(0, 0));
        }

        @Test
        void zeroDividedByAnythingIsZero() {
            for (int b = 1; b < N; b++) {
                assertEquals(0, div(0, b));
            }
        }
    }

    @Nested
    class Power {

        @Test
        void matchesRepeatedMultiplication() {
            for (int a = 0; a < N; a++) {
                int expected = 1;
                for (int n = 0; n < 600; n++) {
                    assertEquals(expected, pow(a, n), "pow(" + a + ", " + n + ")");
                    expected = mul(expected, a);
                }
            }
        }

        @Test
        void fermatsLittleTheorem() {
            // a^255 = 1 for every non-zero a, because the non-zero elements form a cycle of length 255.
            for (int a = 1; a < N; a++) {
                assertEquals(1, pow(a, 255));
            }
        }

        @Test
        void zeroEdgeCases() {
            assertEquals(1, pow(0, 0), "convention: x^0 = 1 for every x");
            assertEquals(0, pow(0, 1));
            assertEquals(0, pow(0, 255));
        }
    }
}
